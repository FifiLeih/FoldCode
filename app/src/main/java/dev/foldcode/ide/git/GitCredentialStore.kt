package dev.foldcode.ide

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitHubCredentials(val username: String = "", val token: String = "")

/** Stores the GitHub token encrypted by a non-exportable Android Keystore key. */
class GitCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("github_credentials", Context.MODE_PRIVATE)
    private val alias = "foldcode_github_credentials"

    fun load(): GitHubCredentials {
        val username = preferences.getString("username", "").orEmpty()
        val encrypted = preferences.getString("token", null) ?: return GitHubCredentials(username)
        return runCatching { GitHubCredentials(username, decrypt(encrypted)) }
            .getOrElse {
                clear()
                GitHubCredentials(username)
            }
    }

    fun save(username: String, token: String) {
        preferences.edit {
            putString("username", username.trim())
            putString("token", if (token.isBlank()) null else encrypt(token))
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12) { "Invalid credential payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
