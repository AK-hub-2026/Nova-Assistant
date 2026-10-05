package com.example.nova.ai

import com.example.BuildConfig
import com.example.nova.core.AiProviderType
import com.example.nova.core.ExecutionChannel
import com.example.nova.core.LatencyTracker
import com.example.nova.core.PlannedToolCall
import com.example.nova.core.RiskLevel
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.memory.ConversationTurnEntity
import com.example.nova.memory.MemoryFactEntity
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.security.PermissionAuditor
import com.example.nova.security.SensitiveDataRedactor
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class ProviderPlanOutcome {
    data class ToolSelected(
        val call: PlannedToolCall,
        val providerLabel: String
    ) : ProviderPlanOutcome()

    data class MultiToolSelected(
        val calls: List<PlannedToolCall>,
        val providerLabel: String
    ) : ProviderPlanOutcome()

    data class DirectAnswer(
        val answerText: String,
        val providerLabel: String
    ) : ProviderPlanOutcome()

    data class ConfigurationOrNetworkRequired(
        val statusCode: String,
        val message: String
    ) : ProviderPlanOutcome()
}

/**
 * Multi-provider AI routing engine supporting:
 * 1. Google Gemini REST & Streaming API (Primary, default model `gemini-3.5-flash`)
 * 2. Groq Cloud OpenAI-Compatible API (`llama-3.3-70b-versatile`)
 * 3. Generic OpenAI-Compatible REST API (`gpt-4o-mini` or custom endpoint)
 * 4. Local Deterministic Intent Parser (Offline fast-path <1ms)
 *
 * M11 Latency Optimizations:
 * - Optimized socket timeouts (8s connect, 18s read) eliminate 60-90s hang conditions.
 * - HTTP connection reuse via single shared OkHttpClient.
 * - Monotonic latency instrumentation tracking time-to-first-token.
 */
class NovaAiProviderRouter {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val mapAdapter by lazy {
        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )
        moshi.adapter<Map<String, Any?>>(type)
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _providerRuntimeErrors = MutableStateFlow<Map<AiProviderType, String>>(emptyMap())
    val providerRuntimeErrors: StateFlow<Map<AiProviderType, String>> = _providerRuntimeErrors.asStateFlow()

    fun recordProviderRuntimeError(provider: AiProviderType, errorMessage: String) {
        val redacted = SensitiveDataRedactor.redactText(errorMessage.trim()).redactedText
        _providerRuntimeErrors.value = _providerRuntimeErrors.value.toMutableMap().apply {
            put(provider, redacted.ifBlank { "${provider.displayName} runtime request failed." })
        }
    }

    fun clearProviderRuntimeError(provider: AiProviderType) {
        if (_providerRuntimeErrors.value.containsKey(provider)) {
            _providerRuntimeErrors.value = _providerRuntimeErrors.value.toMutableMap().apply {
                remove(provider)
            }
        }
    }

    suspend fun planUserRequest(
        userUtterance: String,
        settings: NovaRuntimeSettings,
        isNetworkOnline: Boolean,
        screenSnapshot: SemanticScreenSnapshot?,
        recentTurns: List<ConversationTurnEntity>,
        memoryFacts: List<MemoryFactEntity>
    ): ProviderPlanOutcome = withContext(Dispatchers.IO) {
        LatencyTracker.onRoutingStart()
        val localCalls = LocalDeterministicParser.tryParseMulti(userUtterance)
        if (localCalls.size > 1) {
            LatencyTracker.onRoutingEnd("LOCAL_DETERMINISTIC_MULTI")
            return@withContext ProviderPlanOutcome.MultiToolSelected(
                calls = localCalls,
                providerLabel = AiProviderType.OFFLINE_DETERMINISTIC.displayName
            )
        } else if (localCalls.size == 1) {
            val call = localCalls.first()
            if (call.toolName == "none") {
                LatencyTracker.onRoutingEnd("LOCAL_DETERMINISTIC_DIRECT")
                return@withContext ProviderPlanOutcome.DirectAnswer(
                    answerText = call.spokenResponseDraft,
                    providerLabel = "Arya (Local)"
                )
            }
            LatencyTracker.onRoutingEnd("LOCAL_DETERMINISTIC_TOOL")
            return@withContext ProviderPlanOutcome.ToolSelected(
                call = call,
                providerLabel = AiProviderType.OFFLINE_DETERMINISTIC.displayName
            )
        }

        if (settings.primaryProvider == AiProviderType.OFFLINE_DETERMINISTIC) {
            return@withContext ProviderPlanOutcome.ConfigurationOrNetworkRequired(
                statusCode = "OFFLINE_MODE_ACTIVE",
                message = "Nova is set to Offline Local Parser mode, and your request requires AI reasoning. Try a direct device command (e.g., 'open Settings', 'volume up', 'set alarm for 8:00', 'read screen', 'click Search') or switch to Gemini/Groq in Settings."
            )
        }

        if (!isNetworkOnline) {
            return@withContext ProviderPlanOutcome.ConfigurationOrNetworkRequired(
                statusCode = "NETWORK_OFFLINE",
                message = "Device has no active internet connection for ${settings.primaryProvider.displayName}, and the command did not match an offline system command."
            )
        }

        val systemInstruction = buildSystemPrompt(screenSnapshot, memoryFacts)
        val conversationPrompt = buildUserConversationPrompt(userUtterance, recentTurns)

        val providerChain = buildProviderChain(settings)
        var lastFailureMessage = "No configured AI provider available."
        var attemptedConfiguredProvider = false

        for (provider in providerChain) {
            when (provider) {
                AiProviderType.GEMINI -> {
                    val key = runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()
                    if (!PermissionAuditor.isKeyConfigured(key)) {
                        lastFailureMessage = "GEMINI_API_KEY is not configured. Add your Gemini API key in the AI Studio Secrets panel (.env)."
                        continue
                    }
                    attemptedConfiguredProvider = true
                    LatencyTracker.onAiRequestStart("Gemini (${settings.geminiModel})")
                    val result = callGeminiRest(
                        apiKey = key!!.trim(),
                        model = settings.geminiModel,
                        systemInstruction = systemInstruction,
                        userPrompt = conversationPrompt
                    )
                    if (result != null) {
                        clearProviderRuntimeError(AiProviderType.GEMINI)
                        return@withContext result
                    }
                    lastFailureMessage = "Gemini API request failed or returned an unparseable payload."
                    recordProviderRuntimeError(AiProviderType.GEMINI, lastFailureMessage)
                }

                AiProviderType.GROQ -> {
                    val key = runCatching { BuildConfig.GROQ_API_KEY }.getOrNull()
                    if (!PermissionAuditor.isKeyConfigured(key)) {
                        lastFailureMessage = "GROQ_API_KEY is not configured in the AI Studio Secrets panel (.env)."
                        continue
                    }
                    attemptedConfiguredProvider = true
                    LatencyTracker.onAiRequestStart("Groq (${settings.groqModel})")
                    val result = callOpenAiCompatibleRest(
                        endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
                        apiKey = key!!.trim(),
                        model = settings.groqModel,
                        providerLabel = "Groq (${settings.groqModel})",
                        systemInstruction = systemInstruction,
                        userPrompt = conversationPrompt
                    )
                    if (result != null) {
                        clearProviderRuntimeError(AiProviderType.GROQ)
                        return@withContext result
                    }
                    lastFailureMessage = "Groq API request failed."
                    recordProviderRuntimeError(AiProviderType.GROQ, lastFailureMessage)
                }

                AiProviderType.OPENAI_COMPATIBLE -> {
                    val key = runCatching { BuildConfig.OPENAI_API_KEY }.getOrNull()
                    if (!PermissionAuditor.isKeyConfigured(key)) {
                        lastFailureMessage = "OPENAI_API_KEY is not configured in the AI Studio Secrets panel (.env)."
                        continue
                    }
                    attemptedConfiguredProvider = true
                    LatencyTracker.onAiRequestStart("OpenAI (${settings.openAiModel})")
                    val base = settings.openAiBaseUrl.trimEnd('/')
                    val result = callOpenAiCompatibleRest(
                        endpointUrl = "$base/chat/completions",
                        apiKey = key!!.trim(),
                        model = settings.openAiModel,
                        providerLabel = "OpenAI (${settings.openAiModel})",
                        systemInstruction = systemInstruction,
                        userPrompt = conversationPrompt
                    )
                    if (result != null) {
                        clearProviderRuntimeError(AiProviderType.OPENAI_COMPATIBLE)
                        return@withContext result
                    }
                    lastFailureMessage = "OpenAI-compatible API request failed."
                    recordProviderRuntimeError(AiProviderType.OPENAI_COMPATIBLE, lastFailureMessage)
                }

                AiProviderType.OFFLINE_DETERMINISTIC -> Unit
            }
        }

        ProviderPlanOutcome.ConfigurationOrNetworkRequired(
            statusCode = if (attemptedConfiguredProvider) "PROVIDER_RUNTIME_ERROR" else "CONFIGURATION_REQUIRED",
            message = lastFailureMessage
        )
    }

    private fun buildProviderChain(settings: NovaRuntimeSettings): List<AiProviderType> {
        if (!settings.enableCloudFallback) {
            return listOf(settings.primaryProvider)
        }
        return listOf(
            settings.primaryProvider,
            AiProviderType.GEMINI,
            AiProviderType.GROQ,
            AiProviderType.OPENAI_COMPATIBLE
        ).distinct()
    }

    private fun buildSystemPrompt(
        screenSnapshot: SemanticScreenSnapshot?,
        memoryFacts: List<MemoryFactEntity>
    ): String {
        val screenSection = if (screenSnapshot != null) {
            SensitiveDataRedactor.neutralizeUntrustedScreenText(screenSnapshot.toPromptContext(maxNodes = 55))
        } else {
            "Accessibility Screen Snapshot: Not currently active or unavailable."
        }

        val memorySection = if (memoryFacts.isEmpty()) {
            "User Saved Memories: None."
        } else {
            "User Saved Memories:\n" + memoryFacts.joinToString("\n") { "- ${it.factKey}: ${it.factValue} (${it.category})" }
        }

        return """
You are Arya (आर्या), a friendly, warm, natural, and confident AI assistant for Android with real device control and Accessibility automation.
Speak respectfully and conversationally (e.g. "Hello Boss, आज क्या करें?", "बताइए Boss, मैं देखती हूँ।", "ठीक है Boss, इसे check करती हूँ।").
Keep responses concise, natural, friendly, and never robotic.
Never fabricate tool results. Choose either a single tool call or a direct answer.

Available Tools:
1. launch_app: args {"app": "<app name or package>"} (SAFE)
2. open_settings: args {"section": "accessibility|notifications|wifi|bluetooth|sound|display|battery|system"} (SAFE)
3. set_volume: args {"stream": "music|ring|alarm|notification", "level": "up|down|mute|max|0..100%"} (SAFE)
4. set_flashlight: args {"enabled": "true|false"} (SAFE)
5. set_alarm: args {"hour": "0..23", "minute": "0..59", "label": "<text>"} (SAFE)
6. set_timer: args {"seconds": "<int>", "label": "<text>"} (SAFE)
7. dial_number: args {"phone": "<number>"} (SENSITIVE_CONFIRM)
8. compose_sms: args {"phone": "<number>", "message": "<text>"} (SENSITIVE_CONFIRM)
9. web_search: args {"query": "<search query or https url>"} (SAFE)
10. save_memory: args {"key": "<topic>", "value": "<fact>", "category": "PREFERENCE|USER_NOTE"} (SAFE)
11. schedule_reminder: args {"title": "<task>", "minutes": "<int>"} (SAFE)
12. read_screen: args {} (SAFE) — Reads semantic UI targets from active window via AccessibilityService
13. query_accessibility_status: args {} (SAFE) — Queries real AccessibilityService connection and active window status
14. click_node: args {"target": "<node label, #index, or viewId>", "action": "CLICK|LONG_CLICK"} (SAFE, or SENSITIVE_CONFIRM if clicking Send/Delete/Pay/Confirm)
15. focus_node: args {"target": "<node label, #index, or viewId>"} (SAFE)
16. select_node: args {"target": "<node label, #index, or viewId>"} (SAFE)
17. input_text: args {"text": "<text to type>", "target": "<optional field hint>"} (SAFE)
18. scroll_screen: args {"direction": "down|up|forward|backward"} (SAFE)
19. navigate_global: args {"action": "back|home|recents|notifications"} (SAFE)
20. query_device_info: args {} (SAFE) — Queries real battery, charging, RAM, storage, Android version, and network status
21. query_photos: args {"mode": "mediastore|picker"} (SAFE) — Queries real accessible photos via MediaStore or Android Photo Picker
22. none: Use when answering a general question directly without executing a device action.

Respond ONLY with a valid JSON object matching this exact structure:
{
  "toolName": "none or one of the tool names above",
  "arguments": {"key": "value"},
  "reasoning": "brief explanation",
  "riskLevel": "SAFE or SENSITIVE_CONFIRM or DESTRUCTIVE_CONFIRM",
  "spokenResponse": "concise response to speak to the user"
}

<UNTRUSTED_SCREEN_CONTEXT>
$screenSection
</UNTRUSTED_SCREEN_CONTEXT>

$memorySection
""".trimIndent()
    }

    private fun buildUserConversationPrompt(
        userUtterance: String,
        recentTurns: List<ConversationTurnEntity>
    ): String {
        val history = if (recentTurns.isEmpty()) {
            ""
        } else {
            "Recent Conversation:\n" + recentTurns.takeLast(6).joinToString("\n") {
                "${it.role}: ${it.messageText}"
            } + "\n\n"
        }
        return "${history}Current User Request: $userUtterance"
    }

    private fun callGeminiRest(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String
    ): ProviderPlanOutcome? {
        // Try streaming first to minimize time-to-first-token latency
        val streamedResult = callGeminiStreaming(apiKey, model, systemInstruction, userPrompt)
        if (streamedResult != null) return streamedResult
        return callGeminiNonStreaming(apiKey, model, systemInstruction, userPrompt)
    }

    private fun callGeminiStreaming(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String
    ): ProviderPlanOutcome? {
        val safeModel = com.example.nova.core.NovaProviderModelConfig.resolveGeminiModelId(
            configuredModelId = model
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:streamGenerateContent?alt=sse&key=$apiKey"

        val payloadMap = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to userPrompt)))
            ),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "temperature" to 0.2
            )
        )

        val jsonBody = mapAdapter.toJson(payloadMap)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null
                val fullTextBuilder = StringBuilder()

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val jsonChunk = line.removePrefix("data: ").trim()
                        if (jsonChunk.isNotBlank()) {
                            @Suppress("UNCHECKED_CAST")
                            val chunkRoot = runCatching { mapAdapter.fromJson(jsonChunk) }.getOrNull()
                            val candidates = chunkRoot?.get("candidates") as? List<Map<String, Any?>>
                            val content = candidates?.firstOrNull()?.get("content") as? Map<String, Any?>
                            val parts = content?.get("parts") as? List<Map<String, Any?>>
                            val deltaText = parts?.firstOrNull()?.get("text")?.toString().orEmpty()
                            if (deltaText.isNotEmpty()) {
                                LatencyTracker.onFirstAiToken()
                                fullTextBuilder.append(deltaText)
                            }
                        }
                    }
                }

                LatencyTracker.onCompleteAiResponse()
                val fullText = fullTextBuilder.toString().trim()
                if (fullText.isNotBlank()) {
                    parseModelJsonDecision(fullText, "Gemini ($safeModel)")
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun callGeminiNonStreaming(
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String
    ): ProviderPlanOutcome? {
        val safeModel = com.example.nova.core.NovaProviderModelConfig.resolveGeminiModelId(
            configuredModelId = model
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$apiKey"

        val payloadMap = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemInstruction))
            ),
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to userPrompt)))
            ),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "temperature" to 0.2
            )
        )

        val jsonBody = mapAdapter.toJson(payloadMap)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                LatencyTracker.onFirstAiToken()
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) return null

                val root = mapAdapter.fromJson(bodyStr) ?: return null
                val candidates = root["candidates"] as? List<Map<String, Any?>>
                val content = candidates?.firstOrNull()?.get("content") as? Map<String, Any?>
                val parts = content?.get("parts") as? List<Map<String, Any?>>
                val text = parts?.firstOrNull()?.get("text")?.toString().orEmpty()

                LatencyTracker.onCompleteAiResponse()
                parseModelJsonDecision(text, "Gemini ($safeModel)")
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun callOpenAiCompatibleRest(
        endpointUrl: String,
        apiKey: String,
        model: String,
        providerLabel: String,
        systemInstruction: String,
        userPrompt: String
    ): ProviderPlanOutcome? {
        val payloadMap = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemInstruction),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.2,
            "response_format" to mapOf("type" to "json_object")
        )

        val jsonBody = mapAdapter.toJson(payloadMap)
        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) return null

                val root = mapAdapter.fromJson(bodyStr) ?: return null
                val choices = root["choices"] as? List<Map<String, Any?>>
                val message = choices?.firstOrNull()?.get("message") as? Map<String, Any?>
                val content = message?.get("content")?.toString().orEmpty()

                parseModelJsonDecision(content, providerLabel)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseModelJsonDecision(
        rawJsonText: String,
        providerLabel: String
    ): ProviderPlanOutcome? {
        if (rawJsonText.isBlank()) return null
        val cleaned = rawJsonText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val obj = mapAdapter.fromJson(cleaned) ?: return null
            val toolName = (obj["toolName"]?.toString() ?: "none").trim()
            val spoken = (obj["spokenResponse"]?.toString() ?: "").trim()
            val reasoning = (obj["reasoning"]?.toString() ?: "").trim()
            val riskRaw = (obj["riskLevel"]?.toString() ?: "SAFE").uppercase()

            if (toolName.isBlank() || toolName.equals("none", ignoreCase = true)) {
                if (spoken.isBlank()) return null
                return ProviderPlanOutcome.DirectAnswer(
                    answerText = spoken,
                    providerLabel = providerLabel
                )
            }

            val rawArgs = obj["arguments"] as? Map<String, Any?>
            val argsMap = mutableMapOf<String, String>()
            rawArgs?.forEach { (k, v) ->
                if (v != null) {
                    argsMap[k] = v.toString()
                }
            }

            val risk = when {
                riskRaw.contains("DESTRUCTIVE") -> RiskLevel.DESTRUCTIVE_CONFIRM
                riskRaw.contains("SENSITIVE") -> RiskLevel.SENSITIVE_CONFIRM
                toolName in listOf("dial_number", "compose_sms") -> RiskLevel.SENSITIVE_CONFIRM
                else -> RiskLevel.SAFE
            }

            val channel = when (toolName) {
                "read_screen", "click_node", "input_text", "scroll_screen", "navigate_global", "capture_visual_fallback" ->
                    ExecutionChannel.ACCESSIBILITY_SERVICE
                "save_memory", "schedule_reminder" -> ExecutionChannel.LOCAL_MEMORY
                else -> ExecutionChannel.ANDROID_API_INTENT
            }

            ProviderPlanOutcome.ToolSelected(
                call = PlannedToolCall(
                    toolName = toolName,
                    arguments = argsMap,
                    reasoning = reasoning.ifBlank { "Planned by $providerLabel" },
                    expectedOutcomeDescription = "Verified execution of $toolName",
                    riskLevel = risk,
                    channel = channel,
                    spokenResponseDraft = spoken
                ),
                providerLabel = providerLabel
            )
        } catch (_: Exception) {
            null
        }
    }
}
