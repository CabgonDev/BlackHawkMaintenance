#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "LLAMA", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LLAMA", __VA_ARGS__)

#include "llama.h"

static JavaVM* g_jvm = nullptr;

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;

static int g_nCtx     = 1024;
static int g_nThreads = 4;

static std::atomic_bool g_cancel(false);

static const int kSeqMax = 1;
static const llama_seq_id kSeqId = 0;

static const llama_vocab* vocab() { return llama_model_get_vocab(g_model); }
static llama_token tok_eos() { return llama_vocab_eos(vocab()); }

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static bool abort_cb(void* data) {
    auto* flag = reinterpret_cast<std::atomic_bool*>(data);
    return flag && flag->load();
}

static std::vector<llama_token> tokenize(const std::string& text, bool add_special) {
    std::vector<llama_token> tokens(text.size() + 16);

    int n = llama_tokenize(
            vocab(),
            text.c_str(),
            (int) text.size(),
            tokens.data(),
            (int) tokens.size(),
            add_special,
            true
    );

    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab(), text.c_str(), (int)text.size(),
                           tokens.data(), (int)tokens.size(),
                           add_special, true);
    }

    if (n < 0) n = 0;
    tokens.resize(n);
    return tokens;
}

static std::string token_to_piece(llama_token tok) {
    std::vector<char> buf(256);
    int n = llama_token_to_piece(vocab(), tok, buf.data(), (int)buf.size(), 0, false);
    if (n < 0) {
        buf.resize(-n);
        n = llama_token_to_piece(vocab(), tok, buf.data(), (int)buf.size(), 0, false);
    }
    if (n <= 0) return {};
    return std::string(buf.data(), (size_t)n);
}

static llama_batch make_batch_seq(const std::vector<llama_token>& tokens, int n_past, bool logits_last) {
    llama_batch b = llama_batch_init((int)tokens.size(), 0, kSeqMax);
    for (int i = 0; i < (int)tokens.size(); i++) {
        b.token[i]     = tokens[i];
        b.pos[i]       = n_past + i;
        b.n_seq_id[i]  = 1;
        b.seq_id[i][0] = kSeqId;
        b.logits[i]    = logits_last && (i == (int)tokens.size() - 1);
    }
    b.n_tokens = (int)tokens.size();
    return b;
}

static llama_batch make_batch_one(llama_token tok, int pos) {
    llama_batch b = llama_batch_init(1, 0, kSeqMax);
    b.token[0]     = tok;
    b.pos[0]       = pos;
    b.n_seq_id[0]  = 1;
    b.seq_id[0][0] = kSeqId;
    b.logits[0]    = true;
    b.n_tokens     = 1;
    return b;
}

static void free_all() {
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}

static bool ensure_context() {
    if (!g_model) return false;
    if (g_ctx) return true;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) g_nCtx;
    cparams.n_threads       = g_nThreads;
    cparams.n_threads_batch = g_nThreads;
    cparams.n_seq_max       = kSeqMax;

    cparams.n_batch  = 1024;
    cparams.n_ubatch = 1024;

    cparams.abort_callback      = abort_cb;
    cparams.abort_callback_data = &g_cancel;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        return false;
    }
    return true;
}

static void reset_sequence_memory(llama_context* ctx, llama_seq_id seq_id) {
    llama_memory_t mem = llama_get_memory(ctx);
    const bool ok = llama_memory_seq_rm(mem, seq_id, (llama_pos)-1, (llama_pos)-1);
    if (!ok) {
        LOGI("memory_seq_rm returned false; fallback to memory_clear(data=true)");
        llama_memory_clear(mem, true);
    }
}

static llama_sampler* build_sampler(float temperature, float topP) {
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* chain = llama_sampler_chain_init(sparams);
    if (!chain) return llama_sampler_init_greedy();

    const float tp = (topP > 0.0f && topP <= 1.0f) ? topP : 0.90f;
    const float t  = (temperature > 0.0f) ? temperature : 0.25f;

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(tp, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(64, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(t));

    const uint32_t seed = (uint32_t) std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));

    return chain;
}

// ---- JNI helpers for callback ----

static void cb_onToken(JNIEnv* env, jobject cb, jmethodID mid, const std::string& s) {
    if (!cb || !mid || s.empty()) return;
    jstring js = env->NewStringUTF(s.c_str());
    env->CallVoidMethod(cb, mid, js);
    env->DeleteLocalRef(js);
}

static void cb_onVoid(JNIEnv* env, jobject cb, jmethodID mid) {
    if (!cb || !mid) return;
    env->CallVoidMethod(cb, mid);
}

static void cb_onError(JNIEnv* env, jobject cb, jmethodID mid, const std::string& msg) {
    if (!cb || !mid) return;
    jstring js = env->NewStringUTF(msg.c_str());
    env->CallVoidMethod(cb, mid, js);
    env->DeleteLocalRef(js);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_cabgon_blackhawk_ai_llm_LlamaBridge_init(
        JNIEnv* env, jobject,
        jstring modelPath, jint nCtx, jint nThreads
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("init: begin model=%s nCtx=%d nThreads=%d", path ? path : "(null)", (int)nCtx, (int)nThreads);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap  = true;
    mparams.use_mlock = false;

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("init: Failed to load model");
        llama_backend_free();
        return JNI_FALSE;
    }

    g_nCtx     = (int)nCtx;
    g_nThreads = (int)nThreads;

    if (!ensure_context()) {
        free_all();
        return JNI_FALSE;
    }

    LOGI("init: model loaded + context created ok");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cabgon_blackhawk_ai_llm_LlamaBridge_cancel(JNIEnv*, jobject) {
    g_cancel.store(true);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cabgon_blackhawk_ai_llm_LlamaBridge_release(JNIEnv*, jobject) {
    free_all();
}

// ---- Shared eval function ----
static bool eval_prompt(JNIEnv* env, const std::string& promptStr, int& n_past_out, std::string& err) {
    if (!g_model || !g_ctx) {
        err = "model not initialized";
        return false;
    }

    reset_sequence_memory(g_ctx, kSeqId);

    auto tokens = tokenize(promptStr, /*add_special*/ false);
    if (tokens.empty()) {
        err = "tokenization produced 0 tokens";
        return false;
    }

    const int chunkSize = 64;
    int n_past = 0;

    for (int i = 0; i < (int)tokens.size(); i += chunkSize) {
        if (g_cancel.load()) {
            err = "cancelled during eval";
            return false;
        }

        const int end = std::min(i + chunkSize, (int)tokens.size());
        std::vector<llama_token> chunk(tokens.begin() + i, tokens.begin() + end);
        const bool logits_last = (end == (int)tokens.size());

        llama_batch b = make_batch_seq(chunk, n_past, logits_last);
        const int rc = llama_decode(g_ctx, b);
        llama_batch_free(b);

        if (rc != 0) {
            err = "decode prompt failed";
            return false;
        }

        n_past += (int)chunk.size();
    }

    n_past_out = n_past;
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_cabgon_blackhawk_ai_llm_LlamaBridge_generate(
        JNIEnv* env, jobject,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP
) {
    if (!g_model || !g_ctx) return env->NewStringUTF("ERROR: model not initialized");

    g_cancel.store(false);

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(p ? p : "");
    env->ReleaseStringUTFChars(prompt, p);

    LOGI("gen: begin seq=%d promptChars=%d maxTokens=%d temp=%.3f topP=%.3f",
         (int)kSeqId, (int)promptStr.size(), (int)maxTokens, (double)temperature, (double)topP);

    int n_past = 0;
    std::string err;
    if (!eval_prompt(env, promptStr, n_past, err)) {
        std::string msg = std::string("ERROR: ") + err;
        return env->NewStringUTF(msg.c_str());
    }

    llama_sampler* smpl = build_sampler((float)temperature, (float)topP);
    if (!smpl) return env->NewStringUTF("ERROR: sampler init failed");

    for (auto t : tokenize(promptStr, false)) llama_sampler_accept(smpl, t);

    std::string out;
    out.reserve(1024);
    int pos = n_past;

    for (int i = 0; i < (int)maxTokens; i++) {
        if (g_cancel.load()) {
            out += "\n[ERROR: cancelled]\n";
            break;
        }

        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (tok == tok_eos()) break;

        llama_sampler_accept(smpl, tok);
        out += token_to_piece(tok);

        llama_batch b2 = make_batch_one(tok, pos++);
        const int rc = llama_decode(g_ctx, b2);
        llama_batch_free(b2);

        if (rc != 0) {
            LOGE("gen: decode next failed rc=%d", rc);
            out += "\n[ERROR: decode next failed]\n";
            break;
        }
    }

    llama_sampler_free(smpl);

    LOGI("gen: done seq=%d outChars=%d", (int)out.size(), (int)out.size());
    if (out.empty()) return env->NewStringUTF("ERROR: empty output");
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cabgon_blackhawk_ai_llm_LlamaBridge_generateStream(
        JNIEnv* env, jobject,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jobject callback
) {
    if (!g_model || !g_ctx) {
        if (callback) {
            jclass cls = env->GetObjectClass(callback);
            jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
            cb_onError(env, callback, onError, "model not initialized");
        }
        return;
    }

    if (!callback) return;

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
    jmethodID midOnDone  = env->GetMethodID(cbCls, "onDone", "()V");
    jmethodID midOnError = env->GetMethodID(cbCls, "onError", "(Ljava/lang/String;)V");

    g_cancel.store(false);

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(p ? p : "");
    env->ReleaseStringUTFChars(prompt, p);

    LOGI("genS: begin seq=%d promptChars=%d maxTokens=%d temp=%.3f topP=%.3f",
         (int)kSeqId, (int)promptStr.size(), (int)maxTokens, (double)temperature, (double)topP);

    int n_past = 0;
    std::string err;
    if (!eval_prompt(env, promptStr, n_past, err)) {
        cb_onError(env, callback, midOnError, err);
        return;
    }

    llama_sampler* smpl = build_sampler((float)temperature, (float)topP);
    if (!smpl) {
        cb_onError(env, callback, midOnError, "sampler init failed");
        return;
    }

    for (auto t : tokenize(promptStr, false)) llama_sampler_accept(smpl, t);

    int pos = n_past;
    std::string buffer;
    buffer.reserve(128);

    auto flush = [&]() {
        if (!buffer.empty()) {
            cb_onToken(env, callback, midOnToken, buffer);
            buffer.clear();
        }
    };

    for (int i = 0; i < (int)maxTokens; i++) {
        if (g_cancel.load()) {
            cb_onError(env, callback, midOnError, "cancelled");
            break;
        }

        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (tok == tok_eos()) break;

        llama_sampler_accept(smpl, tok);
        std::string piece = token_to_piece(tok);
        buffer += piece;

        // Flush heuristics to reduce JNI overhead
        if (buffer.size() >= 48 || piece.find('\n') != std::string::npos) {
            flush();
        }

        llama_batch b2 = make_batch_one(tok, pos++);
        const int rc = llama_decode(g_ctx, b2);
        llama_batch_free(b2);

        if (rc != 0) {
            LOGE("genS: decode next failed rc=%d", rc);
            cb_onError(env, callback, midOnError, "decode next failed");
            break;
        }
    }

    flush();
    llama_sampler_free(smpl);
    cb_onVoid(env, callback, midOnDone);

    LOGI("genS: done seq=%d", (int)kSeqId);
}
