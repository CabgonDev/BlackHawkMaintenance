package com.cabgon.blackhawk.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cabgon.blackhawk.ai.Translator
import com.cabgon.blackhawk.ai.chat.AdvancedRagEngineImpl
import com.cabgon.blackhawk.ai.chat.LocalRetrieverAdapter
import com.cabgon.blackhawk.ai.chat.OfflineRagEngine
import com.cabgon.blackhawk.ai.chat.OfflineRagEngineImpl
import com.cabgon.blackhawk.ai.llm.DeviceProfiles
import com.cabgon.blackhawk.ai.llm.LocalLlmClient
import com.cabgon.blackhawk.data.chat.ChatDao
import com.cabgon.blackhawk.data.chat.ChatRepository
import java.io.File

class ChatViewModelFactory(
    private val appContext: Context,
    private val chatDao: ChatDao,
    private val packageIdProvider: () -> String
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val retriever = LocalRetrieverAdapter(appContext)
        val engine: OfflineRagEngine = buildEngine(appContext, retriever)
        val repo = ChatRepository(chatDao, engine)
        return ChatViewModel(repo, packageIdProvider) as T
    }

    private fun buildEngine(
        context: Context,
        retriever: LocalRetrieverAdapter
    ): OfflineRagEngine {

        val modelFile = File(context.filesDir, "models/model.gguf")
        val hasModel = modelFile.exists() && modelFile.length() > 10_000_000L

        Log.d("CHAT_ENGINE", "hasModel=$hasModel model=${modelFile.absolutePath} size=${modelFile.length()}")

        return if (hasModel) {
            val profile = DeviceProfiles.pickProfile(context)
            Log.d("CHAT_ENGINE", "ENGINE=ADVANCED profile=$profile")

            val llmConfig = DeviceProfiles.buildLlmConfig(
                context = context,
                modelPath = modelFile.absolutePath
            )
            val llm = LocalLlmClient(llmConfig)
            val translator = Translator(context.applicationContext)

            AdvancedRagEngineImpl(
                retriever = retriever,
                llm = llm,
                translator = translator,
                appContext = context.applicationContext
            )
        } else {
            Log.d("CHAT_ENGINE", "ENGINE=LIGHT")
            OfflineRagEngineImpl(context, retriever)
        }
    }
}
