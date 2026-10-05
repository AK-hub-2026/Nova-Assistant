package com.example.nova.memory

import android.content.Context
import com.example.nova.core.AiProviderType
import com.example.nova.core.NovaProviderModelConfig
import com.example.nova.security.ProviderSecretVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NovaRuntimeSettings(
    val primaryProvider: AiProviderType = AiProviderType.GEMINI,
    val enableCloudFallback: Boolean = true,
    val geminiModel: String = NovaProviderModelConfig.DEFAULT_GEMINI_MODEL_ID,
    val groqModel: String = NovaProviderModelConfig.DEFAULT_GROQ_MODEL_ID,
    val openAiModel: String = NovaProviderModelConfig.DEFAULT_OPENAI_MODEL_ID,
    val openAiBaseUrl: String = "https://api.openai.com/v1/",
    val spokenResponsesEnabled: Boolean = true,
    val lowRamModeOverride: Boolean? = null, // null = auto-detect from ActivityManager
    val requireConfirmationForSensitiveActions: Boolean = true,
    val showAccessibilityFloatingHud: Boolean = true,
    val memoryContextInjectionEnabled: Boolean = true,
    val assistantIdentity: com.example.nova.core.AssistantIdentityConfig = com.example.nova.core.AssistantIdentityConfig()
)

class NovaPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "nova_preferences",
        Context.MODE_PRIVATE
    )

    init {
        ProviderSecretVault.auditAndMigrateLegacyPlaintextSecrets(appContext, prefs)
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<NovaRuntimeSettings> = _settings.asStateFlow()

    private fun loadSettings(): NovaRuntimeSettings {
        val providerName = prefs.getString("primary_provider", AiProviderType.GEMINI.name)
            ?: AiProviderType.GEMINI.name
        val provider = runCatching { AiProviderType.valueOf(providerName) }
            .getOrDefault(AiProviderType.GEMINI)

        val lowRamRaw = prefs.getInt("low_ram_override", -1)
        val lowRamOverride = when (lowRamRaw) {
            1 -> true
            0 -> false
            else -> null
        }

        val rawGeminiModel = prefs.getString(
            "gemini_model",
            NovaProviderModelConfig.DEFAULT_GEMINI_MODEL_ID
        )
        val resolvedGeminiModel = NovaProviderModelConfig.resolveGeminiModelId(rawGeminiModel)

        val customName = prefs.getString("custom_assistant_name", null)?.takeIf { it.isNotBlank() }
        val customDisplay = prefs.getString("custom_assistant_display_name", null)?.takeIf { it.isNotBlank() }
        val identity = com.example.nova.core.AssistantIdentityConfig(
            assistantName = com.example.nova.core.AssistantIdentityConfig.DEFAULT_NAME,
            displayName = customDisplay ?: com.example.nova.core.AssistantIdentityConfig.DEFAULT_DISPLAY_NAME,
            customName = customName
        )

        return NovaRuntimeSettings(
            primaryProvider = provider,
            enableCloudFallback = prefs.getBoolean("enable_cloud_fallback", true),
            geminiModel = resolvedGeminiModel,
            groqModel = prefs.getString("groq_model", NovaProviderModelConfig.DEFAULT_GROQ_MODEL_ID)
                ?: NovaProviderModelConfig.DEFAULT_GROQ_MODEL_ID,
            openAiModel = prefs.getString("openai_model", NovaProviderModelConfig.DEFAULT_OPENAI_MODEL_ID)
                ?: NovaProviderModelConfig.DEFAULT_OPENAI_MODEL_ID,
            openAiBaseUrl = prefs.getString("openai_base_url", "https://api.openai.com/v1/")
                ?: "https://api.openai.com/v1/",
            spokenResponsesEnabled = prefs.getBoolean("spoken_responses_enabled", true),
            lowRamModeOverride = lowRamOverride,
            requireConfirmationForSensitiveActions = prefs.getBoolean(
                "require_confirmation_sensitive",
                true
            ),
            showAccessibilityFloatingHud = prefs.getBoolean("show_accessibility_hud", true),
            memoryContextInjectionEnabled = prefs.getBoolean("memory_context_enabled", true),
            assistantIdentity = identity
        )
    }

    fun updatePrimaryProvider(provider: AiProviderType) {
        prefs.edit().putString("primary_provider", provider.name).apply()
        _settings.value = loadSettings()
    }

    fun updateCloudFallback(enabled: Boolean) {
        prefs.edit().putBoolean("enable_cloud_fallback", enabled).apply()
        _settings.value = loadSettings()
    }

    fun updateSpokenResponses(enabled: Boolean) {
        prefs.edit().putBoolean("spoken_responses_enabled", enabled).apply()
        _settings.value = loadSettings()
    }

    fun updateLowRamOverride(override: Boolean?) {
        val code = when (override) {
            true -> 1
            false -> 0
            null -> -1
        }
        prefs.edit().putInt("low_ram_override", code).apply()
        _settings.value = loadSettings()
    }

    fun updateRequireConfirmation(required: Boolean) {
        prefs.edit().putBoolean("require_confirmation_sensitive", required).apply()
        _settings.value = loadSettings()
    }

    fun updateShowAccessibilityHud(show: Boolean) {
        prefs.edit().putBoolean("show_accessibility_hud", show).apply()
        _settings.value = loadSettings()
    }

    fun updateMemoryContextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("memory_context_enabled", enabled).apply()
        _settings.value = loadSettings()
    }

    fun updateGeminiModel(modelId: String) {
        val resolved = NovaProviderModelConfig.resolveGeminiModelId(modelId)
        prefs.edit().putString("gemini_model", resolved).apply()
        _settings.value = loadSettings()
    }

    fun updateGroqModel(modelId: String) {
        val cleaned = modelId.trim().ifBlank { NovaProviderModelConfig.DEFAULT_GROQ_MODEL_ID }
        prefs.edit().putString("groq_model", cleaned).apply()
        _settings.value = loadSettings()
    }

    fun updateOpenAiModel(modelId: String) {
        val cleaned = modelId.trim().ifBlank { NovaProviderModelConfig.DEFAULT_OPENAI_MODEL_ID }
        prefs.edit().putString("openai_model", cleaned).apply()
        _settings.value = loadSettings()
    }

    fun markPermissionRequested(permissionKey: String) {
        val current = prefs.getStringSet("requested_permissions", emptySet()).orEmpty().toMutableSet()
        current.add(permissionKey)
        prefs.edit().putStringSet("requested_permissions", current).apply()
    }

    fun hasRequestedPermission(permissionKey: String): Boolean {
        val current = prefs.getStringSet("requested_permissions", emptySet()).orEmpty()
        return permissionKey in current
    }
}
