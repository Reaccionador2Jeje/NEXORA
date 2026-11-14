package dev.atdev.nexora

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Supabase OAuth PKCE integration for the NEXORA Android client.
 * Only the publishable key is embedded here; provider client secrets stay in Supabase.
 */
class SupabaseAuth(private val context: Context) {
    companion object {
        private const val SUPABASE_URL = "https://opxhywwivpzrzpswbotx.supabase.co"
        private const val SUPABASE_KEY = "sb_publishable_aAycgGr8E6vCHWjceHNcHg_b3kPn0iG"
        const val REDIRECT_URI = "dev.atdev.nexora://auth/callback"
        private const val PREFS = "nexora_auth"
        private const val KEY_ALIAS = "nexora_auth_storage_key"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun accountLabel(): String? = prefs.getString("account_email", null)

    fun startOAuth(activity: Activity, provider: String) {
        require(provider == "google" || provider == "github") { "Unsupported sign-in provider." }
        val verifier = base64Url(randomBytes(32))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        saveSecret("pending_verifier", verifier)

        val authorizeUrl = Uri.parse("$SUPABASE_URL/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", provider)
            .appendQueryParameter("redirect_to", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        activity.startActivity(Intent(Intent.ACTION_VIEW, authorizeUrl))
    }

    fun handleDeepLink(uri: Uri?, callback: (Result<String>) -> Unit): Boolean {
        if (uri == null || uri.scheme != "dev.atdev.nexora" || uri.host != "auth" || uri.path != "/callback") {
            return false
        }

        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            prefs.edit().remove("pending_verifier").apply()
            callback(Result.failure(IllegalStateException(error)))
            return true
        }

        val code = uri.getQueryParameter("code")
        val verifier = readSecret("pending_verifier")
        prefs.edit().remove("pending_verifier").apply()
        if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
            callback(Result.failure(IllegalStateException("The sign-in callback is incomplete. Start again.")))
            return true
        }

        Thread {
            try {
                val body = JSONObject()
                    .put("auth_code", code)
                    .put("code_verifier", verifier)
                val session = requestJson("/auth/v1/token?grant_type=pkce", body, null)
                storeSession(session)
                val email = session.optJSONObject("user")?.optString("email").orEmpty()
                val label = email.ifBlank { "Signed in to NEXORA" }
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(Result.success(label)) }
            } catch (error: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(Result.failure(error)) }
            }
        }.start()
        return true
    }

    fun refreshIfNeeded(callback: (Result<String?>) -> Unit) {
        val refreshToken = readSecret("refresh_token")
        if (refreshToken.isNullOrBlank()) {
            callback(Result.success(null))
            return
        }
        val expiresAt = prefs.getLong("expires_at", 0L)
        if (expiresAt > System.currentTimeMillis() / 1000L + 90L) {
            callback(Result.success(accountLabel()))
            return
        }
        Thread {
            try {
                val session = requestJson(
                    "/auth/v1/token?grant_type=refresh_token",
                    JSONObject().put("refresh_token", refreshToken),
                    null
                )
                storeSession(session)
                val email = session.optJSONObject("user")?.optString("email").orEmpty()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(Result.success(email.ifBlank { "Signed in to NEXORA" }))
                }
            } catch (error: Exception) {
                clearSession()
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(Result.failure(error)) }
            }
        }.start()
    }

    fun signOut(callback: (Result<Unit>) -> Unit) {
        val accessToken = readSecret("access_token")
        Thread {
            try {
                if (!accessToken.isNullOrBlank()) {
                    requestJson("/auth/v1/logout", JSONObject(), accessToken)
                }
                clearSession()
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(Result.success(Unit)) }
            } catch (error: Exception) {
                // Clear local credentials even if the remote session has already expired.
                clearSession()
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(Result.failure(error)) }
            }
        }.start()
    }

    private fun storeSession(session: JSONObject) {
        val access = session.optString("access_token")
        val refresh = session.optString("refresh_token")
        require(access.isNotBlank() && refresh.isNotBlank()) { "Supabase did not return a valid session." }
        saveSecret("access_token", access)
        saveSecret("refresh_token", refresh)
        val expiresIn = session.optLong("expires_in", 3600L)
        prefs.edit()
            .putLong("expires_at", System.currentTimeMillis() / 1000L + expiresIn)
            .putString("account_email", session.optJSONObject("user")?.optString("email").orEmpty())
            .apply()
    }

    private fun clearSession() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("expires_at")
            .remove("account_email")
            .apply()
    }

    private fun requestJson(path: String, body: JSONObject, bearer: String?): JSONObject {
        val connection = (URL("$SUPABASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optString("msg").ifBlank {
                        JSONObject(text).optString("message").ifBlank { text }
                    }
                } catch (_: Exception) { text }
                throw IllegalStateException("Supabase Auth ($code): ${message.take(240)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun saveSecret(name: String, value: String) {
        prefs.edit().putString(name, encrypt(value)).apply()
    }

    private fun readSecret(name: String): String? =
        prefs.getString(name, null)?.let { decrypt(it) }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12) { "Invalid encrypted authentication data." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
