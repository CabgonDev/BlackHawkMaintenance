package com.cabgon.blackhawk.data.user

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.UTFDataFormatException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CorruptBiometricCredentialsException(cause: Throwable? = null) :
    Exception("Las credenciales biometricas guardadas no son validas", cause)

class BiometricCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("biometric_login", Context.MODE_PRIVATE)

    fun hasCredentials(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    fun prepareEncryption(): Cipher = cipher(Cipher.ENCRYPT_MODE)

    fun prepareDecryption(): Cipher {
        val iv = decodeStoredBytes(KEY_IV)
        if (iv.size != 12) throw CorruptBiometricCredentialsException()
        return cipher(Cipher.DECRYPT_MODE, iv)
    }

    fun save(cipher: Cipher, email: String, password: String) {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeUTF(email)
            it.writeUTF(password)
        }
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(output.toByteArray())
        check(prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()) { "No se pudieron persistir las credenciales biométricas" }
    }

    fun decrypt(cipher: Cipher): Pair<String, String> {
        val encrypted = decodeStoredBytes(KEY_CIPHERTEXT)
        cipher.updateAAD(AAD)
        val plain = cipher.doFinal(encrypted)
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { it.readUTF() to it.readUTF() }
        } catch (error: EOFException) {
            throw CorruptBiometricCredentialsException(error)
        } catch (error: UTFDataFormatException) {
            throw CorruptBiometricCredentialsException(error)
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun decodeStoredBytes(key: String): ByteArray {
        val encoded = prefs.getString(key, null) ?: throw CorruptBiometricCredentialsException()
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw CorruptBiometricCredentialsException(error)
        }
    }

    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
        val key = if (mode == Cipher.ENCRYPT_MODE) getOrCreateKey() else {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw UnrecoverableKeyException("La clave biometrica ya no existe")
        }
        return Cipher.getInstance(TRANSFORMATION).apply {
            if (mode == Cipher.ENCRYPT_MODE) {
                init(mode, key)
            } else {
                init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            init(builder.build())
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "blackhawk_biometric_login_v2"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private val AAD = "blackhawk-biometric-login-v1".toByteArray(Charsets.UTF_8)
    }
}
