package com.cabgon.blackhawk.data.user

import android.util.Base64
import android.content.Context
import androidx.core.content.edit
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Non-exportable per-installation key used to prove the device to Firebase Functions. */
class DeviceBindingStore(context: Context? = null) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun publicKeyBase64(): String = Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)

    fun keyId(): String = sha256Hex(keyPair().public.encoded)

    fun sign(message: String): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(keyPair().private)
            update(message.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun hasKey(): Boolean = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.containsAlias(KEY_ALIAS)
    }.getOrDefault(false)

    fun markBound(uid: String) {
        prefs?.edit {
            putStringSet(KEY_BOUND_UIDS, boundUids() + uid)
        }
    }

    fun isBoundTo(uid: String): Boolean = hasKey() && uid in boundUids()

    fun hasAnyBinding(): Boolean = hasKey() && boundUids().isNotEmpty()

    /** Removes the local device key and every local account binding after a remote release succeeds. */
    fun clearBinding() {
        prefs?.edit { clear() }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun boundUids(): Set<String> = prefs?.getStringSet(KEY_BOUND_UIDS, emptySet()).orEmpty()

    private fun keyPair() = run {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return@run java.security.KeyPair(existing.certificate.publicKey, existing.privateKey)

        KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEY_STORE).apply {
            initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKeyPair()
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "blackhawk_device_binding_v1"
        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val PREFS = "device_binding"
        private const val KEY_BOUND_UIDS = "bound_uids"
    }
}
