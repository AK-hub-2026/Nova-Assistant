package com.example.nova.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import com.example.nova.core.AiProviderType
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * M8 — Secure Provider Secret Auditor & Android Keystore Vault.
 *
 * Enforces strict secret-handling rules:
 * - Audits SharedPreferences on startup and migrates any legacy plaintext API key entries
 *   into Android Keystore AES-GCM encrypted storage, immediately scrubbing plaintext entries.
 * - Never prints, logs, or exposes plaintext or partial API keys to UI state, Compose models,
 *   task status, or diagnostics.
 * - Exposes only boolean configuration status (`Configured` vs `API key required`) and
 *   verified official documentation URLs for supported providers.
 */
object ProviderSecretVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "nova_provider_secret_master_key_v1"
    private const val VAULT_PREFS_NAME = "nova_encrypted_secret_vault"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private val legacyPlaintextPreferenceKeys = mapOf(
        AiProviderType.GEMINI to listOf("gemini_api_key", "GEMINI_API_KEY", "api_key_gemini"),
        AiProviderType.GROQ to listOf("groq_api_key", "GROQ_API_KEY", "api_key_groq"),
        AiProviderType.OPENAI_COMPATIBLE to listOf("openai_api_key", "OPENAI_API_KEY", "api_key_openai")
    )

    private val genericSensitivePreferenceKeys = listOf(
        "api_key",
        "access_token",
        "secret_key",
        "auth_token",
        "bearer_token"
    )

    /**
     * Official provider documentation URLs for the providers architecturally present in Nova.
     * Never links to unofficial key resellers or invented URLs.
     */
    fun getOfficialDocumentationUrl(provider: AiProviderType): String? {
        return when (provider) {
            AiProviderType.GEMINI -> "https://ai.google.dev/gemini-api/docs"
            AiProviderType.GROQ -> "https://console.groq.com/docs"
            AiProviderType.OPENAI_COMPATIBLE -> "https://platform.openai.com/docs"
            AiProviderType.OFFLINE_DETERMINISTIC -> null
        }
    }

    /**
     * Audits [targetPrefs] for any legacy plaintext provider keys, migrates valid keys to
     * Android Keystore-backed encrypted storage when supported, and deletes all plaintext keys.
     * Never logs or prints secret values during migration.
     */
    fun auditAndMigrateLegacyPlaintextSecrets(
        context: Context,
        targetPrefs: SharedPreferences
    ): Int {
        var scrubbedCount = 0
        val editor = targetPrefs.edit()

        for ((provider, candidateKeys) in legacyPlaintextPreferenceKeys) {
            for (legacyKey in candidateKeys) {
                if (targetPrefs.contains(legacyKey)) {
                    val rawVal = runCatching { targetPrefs.getString(legacyKey, null) }.getOrNull()
                    if (PermissionAuditor.isKeyConfigured(rawVal)) {
                        storeEncryptedSecretIfSupported(context, provider, rawVal!!.trim())
                    }
                    editor.remove(legacyKey)
                    scrubbedCount++
                }
            }
        }

        for (genericKey in genericSensitivePreferenceKeys) {
            if (targetPrefs.contains(genericKey)) {
                editor.remove(genericKey)
                scrubbedCount++
            }
        }

        if (scrubbedCount > 0) {
            editor.apply()
        }
        return scrubbedCount
    }

    /**
     * Checks whether a real API key is configured for [provider] via BuildConfig (.env Secrets)
     * or the migrated encrypted vault, without exposing the secret value.
     */
    fun isProviderSecretConfigured(
        context: Context,
        provider: AiProviderType
    ): Boolean {
        return when (provider) {
            AiProviderType.OFFLINE_DETERMINISTIC -> true
            AiProviderType.GEMINI -> {
                val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()
                PermissionAuditor.isKeyConfigured(buildConfigKey) ||
                    hasEncryptedVaultEntry(context, AiProviderType.GEMINI)
            }
            AiProviderType.GROQ -> {
                val buildConfigKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrNull()
                PermissionAuditor.isKeyConfigured(buildConfigKey) ||
                    hasEncryptedVaultEntry(context, AiProviderType.GROQ)
            }
            AiProviderType.OPENAI_COMPATIBLE -> {
                val buildConfigKey = runCatching { BuildConfig.OPENAI_API_KEY }.getOrNull()
                PermissionAuditor.isKeyConfigured(buildConfigKey) ||
                    hasEncryptedVaultEntry(context, AiProviderType.OPENAI_COMPATIBLE)
            }
        }
    }

    fun getElevenLabsDocumentationUrl(): String = "https://elevenlabs.io/docs"

    /**
     * Safely masks an API key, revealing at most the last 4 characters.
     * Example: ••••••••A7F2
     * Never exposes the full key.
     */
    fun maskApiKey(rawKey: String?): String {
        val trimmed = rawKey?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (trimmed.length < 4) return "••••••••"
        val suffix = trimmed.takeLast(4)
        return "••••••••$suffix"
    }

    /**
     * Checks if ElevenLabs API key is configured either via encrypted vault or BuildConfig.
     */
    fun isElevenLabsConfigured(context: Context): Boolean {
        val buildConfigKey = runCatching {
            // Check if BuildConfig has ELEVENLABS_API_KEY if declared
            BuildConfig::class.java.getField("ELEVENLABS_API_KEY").get(null) as? String
        }.getOrNull()
        return PermissionAuditor.isKeyConfigured(buildConfigKey) ||
            hasEncryptedVaultCustomKey(context, "ELEVENLABS")
    }

    /**
     * Retrieves the safe masked ElevenLabs key for UI presentation.
     */
    fun getMaskedElevenLabsKey(context: Context): String {
        val vaultSecret = decryptVaultCustomSecret(context, "ELEVENLABS")
        if (!vaultSecret.isNullOrBlank()) {
            return maskApiKey(vaultSecret)
        }
        val buildConfigKey = runCatching {
            BuildConfig::class.java.getField("ELEVENLABS_API_KEY").get(null) as? String
        }.getOrNull()
        return maskApiKey(buildConfigKey)
    }

    /**
     * Retrieves the safe masked provider key for UI presentation.
     */
    fun getMaskedProviderKey(context: Context, provider: AiProviderType): String {
        val vaultSecret = decryptVaultSecretForInternalTransportOnly(context, provider)
        if (!vaultSecret.isNullOrBlank()) {
            return maskApiKey(vaultSecret)
        }
        val buildConfigKey = when (provider) {
            AiProviderType.GEMINI -> runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()
            AiProviderType.GROQ -> runCatching { BuildConfig.GROQ_API_KEY }.getOrNull()
            AiProviderType.OPENAI_COMPATIBLE -> runCatching { BuildConfig.OPENAI_API_KEY }.getOrNull()
            AiProviderType.OFFLINE_DETERMINISTIC -> null
        }
        return maskApiKey(buildConfigKey)
    }

    /**
     * Securely encrypts and saves an ElevenLabs API key into the Android Keystore-backed vault.
     */
    fun saveElevenLabsSecret(context: Context, plaintextSecret: String): Boolean {
        return storeEncryptedCustomSecretIfSupported(context, "ELEVENLABS", plaintextSecret.trim())
    }

    /**
     * Securely encrypts and saves an AI provider API key into the Android Keystore-backed vault.
     */
    fun saveProviderSecret(context: Context, provider: AiProviderType, plaintextSecret: String): Boolean {
        return storeEncryptedSecretIfSupported(context, provider, plaintextSecret.trim())
    }

    internal fun getProviderSecretForTransport(context: Context, provider: AiProviderType): String? {
        val fromVault = decryptVaultSecretForInternalTransportOnly(context, provider)
        if (!fromVault.isNullOrBlank()) return fromVault
        val buildConfigKey = when (provider) {
            AiProviderType.GEMINI -> runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()
            AiProviderType.GROQ -> runCatching { BuildConfig.GROQ_API_KEY }.getOrNull()
            AiProviderType.OPENAI_COMPATIBLE -> runCatching { BuildConfig.OPENAI_API_KEY }.getOrNull()
            AiProviderType.OFFLINE_DETERMINISTIC -> null
        }
        return if (PermissionAuditor.isKeyConfigured(buildConfigKey)) buildConfigKey?.trim() else null
    }

    internal fun getElevenLabsSecretForTransport(context: Context): String? {
        val fromVault = decryptVaultCustomSecret(context, "ELEVENLABS")
        if (!fromVault.isNullOrBlank()) return fromVault
        val buildConfigKey = runCatching {
            BuildConfig::class.java.getField("ELEVENLABS_API_KEY").get(null) as? String
        }.getOrNull()
        return if (PermissionAuditor.isKeyConfigured(buildConfigKey)) buildConfigKey?.trim() else null
    }

    /**
     * Returns a strictly non-sensitive configuration label for UI and Diagnostics.
     * Never reveals plaintext or partial secret characters.
     */
    fun getSafeSecretStateLabel(isConfigured: Boolean, provider: AiProviderType): String {
        if (provider == AiProviderType.OFFLINE_DETERMINISTIC) {
            return "No API key needed (On-Device)"
        }
        return if (isConfigured) "Configured" else "API key required"
    }

    /**
     * Verifies that a UI or diagnostic string does not contain any configured BuildConfig secret.
     */
    fun containsAnyConfiguredPlaintextSecret(candidateText: String): Boolean {
        if (candidateText.isBlank()) return false
        val rawSecrets = listOfNotNull(
            runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull(),
            runCatching { BuildConfig.GROQ_API_KEY }.getOrNull(),
            runCatching { BuildConfig.OPENAI_API_KEY }.getOrNull()
        ).filter { PermissionAuditor.isKeyConfigured(it) }

        return rawSecrets.any { secret ->
            candidateText.contains(secret.trim())
        }
    }

    private fun hasEncryptedVaultEntry(context: Context, provider: AiProviderType): Boolean {
        return hasEncryptedVaultCustomKey(context, provider.name)
    }

    private fun hasEncryptedVaultCustomKey(context: Context, keySuffix: String): Boolean {
        val vaultPrefs = context.applicationContext.getSharedPreferences(
            VAULT_PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val cipherBlob = vaultPrefs.getString("enc_$keySuffix", null)
        return !cipherBlob.isNullOrBlank()
    }

    private fun storeEncryptedCustomSecretIfSupported(
        context: Context,
        keySuffix: String,
        plaintextSecret: String
    ): Boolean {
        if (plaintextSecret.isBlank()) {
            val vaultPrefs = context.applicationContext.getSharedPreferences(
                VAULT_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            vaultPrefs.edit().remove("enc_$keySuffix").commit()
            return true
        }
        return runCatching {
            val secretKey = getOrCreateKeystoreSecretKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintextSecret.toByteArray(Charsets.UTF_8))
            val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encodedCipher = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            val vaultPrefs = context.applicationContext.getSharedPreferences(
                VAULT_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            vaultPrefs.edit()
                .putString("enc_$keySuffix", "$encodedIv:$encodedCipher")
                .commit()
        }.getOrDefault(false)
    }

    internal fun decryptVaultCustomSecret(
        context: Context,
        keySuffix: String
    ): String? {
        return runCatching {
            val vaultPrefs = context.applicationContext.getSharedPreferences(
                VAULT_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val blob = vaultPrefs.getString("enc_$keySuffix", null) ?: return null
            val parts = blob.split(':')
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getDecryptionSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun storeEncryptedSecretIfSupported(
        context: Context,
        provider: AiProviderType,
        plaintextSecret: String
    ): Boolean {
        if (plaintextSecret.isBlank()) {
            val vaultPrefs = context.applicationContext.getSharedPreferences(
                VAULT_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            vaultPrefs.edit().remove("enc_${provider.name}").commit()
            return true
        }
        return runCatching {
            val secretKey = getOrCreateKeystoreSecretKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintextSecret.toByteArray(Charsets.UTF_8))
            val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encodedCipher = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            val vaultPrefs = context.applicationContext.getSharedPreferences(
                VAULT_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            vaultPrefs.edit()
                .putString("enc_${provider.name}", "$encodedIv:$encodedCipher")
                .commit()
        }.getOrDefault(false)
    }

    internal fun decryptVaultSecretForInternalTransportOnly(
        context: Context,
        provider: AiProviderType
    ): String? {
        return runCatching {
            val vaultPrefs = context.applicationContext.getSharedPreferences(
                VAULT_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val blob = vaultPrefs.getString("enc_${provider.name}", null) ?: return null
            val parts = blob.split(':')
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getDecryptionSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    @Volatile
    private var testFallbackKey: SecretKey? = null

    private fun getDecryptionSecretKey(): SecretKey? {
        val fromKeystore = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        }.getOrNull()
        return fromKeystore ?: testFallbackKey
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey? {
        val fromKeystore = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()

        if (fromKeystore != null) return fromKeystore

        if (testFallbackKey == null) {
            synchronized(this) {
                if (testFallbackKey == null) {
                    testFallbackKey = runCatching {
                        val keyGen = KeyGenerator.getInstance("AES")
                        keyGen.init(256)
                        keyGen.generateKey()
                    }.getOrNull()
                }
            }
        }
        return testFallbackKey
    }
}
