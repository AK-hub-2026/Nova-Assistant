package com.example.nova.core

import android.graphics.Rect

/**
 * Represents the real, unfabricated operational state of any hardware, OS permission,
 * service binding, or AI provider capability in Nova.
 */
enum class CapabilityStatus {
    AVAILABLE,
    PERMISSION_REQUIRED,
    SERVICE_DISABLED,
    CONFIGURATION_REQUIRED,
    UNSUPPORTED_HARDWARE_OR_SDK,
    OFFLINE,
    ERROR
}

enum class AiProviderType(val displayName: String, val defaultModel: String) {
    GEMINI("Google Gemini REST", "gemini-3.5-flash"),
    GROQ("Groq Cloud Fast Inference", "llama-3.3-70b-versatile"),
    OPENAI_COMPATIBLE("OpenAI-Compatible REST", "gpt-4o-mini"),
    OFFLINE_DETERMINISTIC("Local Deterministic Intent Parser (Offline)", "nova-local-rules-v1")
}

enum class RiskLevel {
    SAFE,
    SENSITIVE_CONFIRM,
    DESTRUCTIVE_CONFIRM
}

enum class ExecutionChannel {
    ANDROID_API_INTENT,
    ACCESSIBILITY_SERVICE,
    LOCAL_MEMORY,
    DIRECT_RESPONSE
}

enum class VerificationOutcome {
    VERIFIED_SUCCESS,
    VERIFICATION_FAILED,
    PERMISSION_REQUIRED,
    SERVICE_DISABLED,
    CONFIGURATION_REQUIRED,
    UNSUPPORTED,
    USER_CANCELLED,
    AWAITING_CONFIRMATION
}

/**
 * Real AccessibilityNodeInfo actions supported by Nova's M6 semantic interaction engine.
 */
enum class SupportedAccessibilityAction {
    CLICK,
    LONG_CLICK,
    FOCUS,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SELECT
}

/**
 * Safe semantic target model (M6) representing an actionable or readable UI element
 * in the active window.
 *
 * Screen coordinates ([boundsInScreen]) are supporting information only and are NEVER
 * used as the primary identity of a target.
 */
data class AccessibilityTarget(
    val targetIndex: Int,
    val semanticDescription: String,
    val visibleText: String,
    val contentDescription: String,
    val viewIdResourceName: String,
    val className: String,
    val boundsInScreen: Rect,
    val supportedActions: Set<SupportedAccessibilityAction>,
    val isEnabled: Boolean,
    val isClickable: Boolean,
    val isLongClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isFocused: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isSelected: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val wasRedacted: Boolean = false
) {
    val isSensitiveRedacted: Boolean
        get() = wasRedacted

    /**
     * Primary semantic identity key excluding screen coordinates.
     */
    fun semanticIdentityKey(): String {
        val shortId = viewIdResourceName.substringAfterLast('/', viewIdResourceName).lowercase()
        val normText = visibleText.trim().lowercase()
        val normDesc = contentDescription.trim().lowercase()
        return "$className|$shortId|$normText|$normDesc"
    }

    /**
     * Primary display label for UI summaries and ambiguity clarification prompts.
     */
    fun primaryLabel(): String {
        val shortId = viewIdResourceName.substringAfterLast('/', viewIdResourceName)
        return when {
            visibleText.isNotBlank() -> visibleText
            contentDescription.isNotBlank() -> contentDescription
            shortId.isNotBlank() -> shortId
            else -> className
        }
    }
}

/**
 * Real semantic node extracted from the active Accessibility window.
 */
data class SemanticUiNode(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val viewIdResourceName: String,
    val className: String,
    val boundsInScreen: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val wasRedacted: Boolean,
    val isEnabled: Boolean = true,
    val isFocusable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isSelected: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val supportedActions: Set<SupportedAccessibilityAction> = buildSet {
        if (isClickable) add(SupportedAccessibilityAction.CLICK)
        if (isEditable) {
            add(SupportedAccessibilityAction.SET_TEXT)
            add(SupportedAccessibilityAction.FOCUS)
        }
        if (isScrollable) {
            add(SupportedAccessibilityAction.SCROLL_FORWARD)
            add(SupportedAccessibilityAction.SCROLL_BACKWARD)
        }
    }
) {
    fun toAccessibilityTarget(): AccessibilityTarget {
        val shortId = viewIdResourceName.substringAfterLast('/', viewIdResourceName)
        val labelPart = when {
            text.isNotBlank() && contentDescription.isNotBlank() && text != contentDescription ->
                "$text ($contentDescription)"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            shortId.isNotBlank() -> shortId
            else -> className
        }
        val idSuffix = if (shortId.isNotBlank() && labelPart != shortId) " [id=$shortId]" else ""
        return AccessibilityTarget(
            targetIndex = index,
            semanticDescription = "$className: $labelPart$idSuffix",
            visibleText = text,
            contentDescription = contentDescription,
            viewIdResourceName = viewIdResourceName,
            className = className,
            boundsInScreen = boundsInScreen,
            supportedActions = supportedActions,
            isEnabled = isEnabled,
            isClickable = isClickable,
            isLongClickable = isLongClickable,
            isFocusable = isFocusable,
            isFocused = isFocused,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isCheckable = isCheckable,
            isChecked = isChecked,
            isSelected = isSelected,
            isVisibleToUser = isVisibleToUser,
            wasRedacted = wasRedacted
        )
    }

    fun toCompactPromptLine(): String {
        val flags = buildList {
            if (!isEnabled) add("DISABLED")
            if (isClickable) add("CLICK")
            if (isLongClickable) add("LONG_CLICK")
            if (isEditable) add("EDIT")
            if (isScrollable) add("SCROLL")
            if (isChecked) add("CHECKED")
            if (isSelected) add("SELECTED")
            if (isFocused) add("FOCUSED")
            if (wasRedacted) add("REDACTED")
        }.joinToString(",")
        val label = when {
            text.isNotBlank() && contentDescription.isNotBlank() && text != contentDescription ->
                "\"$text\" (desc: \"$contentDescription\")"
            text.isNotBlank() -> "\"$text\""
            contentDescription.isNotBlank() -> "(desc: \"$contentDescription\")"
            else -> "(unlabeled)"
        }
        val shortId = viewIdResourceName.substringAfterLast('/', viewIdResourceName)
        val idPart = if (shortId.isNotBlank()) " id=$shortId" else ""
        val flagPart = if (flags.isNotEmpty()) " [$flags]" else ""
        return "[#$index] $label$idPart$flagPart"
    }
}

/**
 * Real snapshot of the current foreground window from NovaAccessibilityService.
 */
data class SemanticScreenSnapshot(
    val capturedAtMs: Long,
    val packageName: String,
    val windowTitle: String,
    val nodes: List<SemanticUiNode>,
    val totalRawNodesTraversed: Int,
    val redactedFieldCount: Int,
    val isLowRamTruncated: Boolean
) {
    val targets: List<AccessibilityTarget>
        get() = nodes.map { it.toAccessibilityTarget() }

    val totalNodesVisited: Int
        get() = totalRawNodesTraversed

    fun signatureHash(): Int {
        var result = packageName.hashCode()
        result = 31 * result + windowTitle.hashCode()
        result = 31 * result + nodes.size
        nodes.take(30).forEach { node ->
            result = 31 * result + node.text.hashCode()
            result = 31 * result + node.contentDescription.hashCode()
            result = 31 * result + node.viewIdResourceName.hashCode()
            result = 31 * result + node.isChecked.hashCode()
            result = 31 * result + node.isSelected.hashCode()
            result = 31 * result + node.isFocused.hashCode()
            result = 31 * result + node.isEnabled.hashCode()
            val b = node.boundsInScreen
            val boundsHash = (((b.left * 31 + b.top) * 31 + b.right) * 31 + b.bottom)
            result = 31 * result + boundsHash
        }
        return result
    }

    fun toPromptContext(maxNodes: Int = 45): String {
        if (nodes.isEmpty()) {
            return "Active Window Package: $packageName | Title: $windowTitle | UI Nodes: 0 accessible semantic nodes exposed."
        }
        val capped = nodes.take(maxNodes)
        val header = "Active Window Package: $packageName | Title: $windowTitle | Showing ${capped.size} of ${nodes.size} semantic targets:"
        val body = capped.joinToString("\n") { it.toCompactPromptLine() }
        return "$header\n$body"
    }
}

/**
 * Real installed launchable application discovered via Android PackageManager.
 */
data class InstalledAppInfo(
    val appLabel: String,
    val packageName: String
)

/**
 * Real active status bar notification captured by NovaNotificationService.
 */
data class LiveNotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtMs: Long
)

/**
 * Real hardware and OS permission/service telemetry snapshot.
 */
data class SystemHealthSnapshot(
    val sdkInt: Int,
    val androidRelease: String,
    val deviceModel: String,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isSystemLowRamDevice: Boolean,
    val effectiveLowRamMode: Boolean,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isNetworkOnline: Boolean,
    val hasFlashlightHardware: Boolean,
    val isTorchCurrentlyOn: Boolean,
    val recordAudioGranted: Boolean,
    val postNotificationsGranted: Boolean,
    val speechRecognitionAvailable: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceConnected: Boolean,
    val notificationListenerEnabled: Boolean,
    val geminiKeyConfigured: Boolean,
    val groqKeyConfigured: Boolean,
    val openAiKeyConfigured: Boolean
)

/**
 * Structured tool call selected by either the AI provider or the local deterministic parser.
 */
data class PlannedToolCall(
    val toolName: String,
    val arguments: Map<String, String>,
    val reasoning: String,
    val expectedOutcomeDescription: String,
    val riskLevel: RiskLevel,
    val channel: ExecutionChannel,
    val spokenResponseDraft: String
)

/**
 * Result of executing and verifying a single action.
 */
data class VerifiedActionResult(
    val toolName: String,
    val outcome: VerificationOutcome,
    val verificationDetail: String,
    val targetPackage: String,
    val preStateSummary: String,
    val postStateSummary: String,
    val userFacingMessage: String,
    val elapsedMs: Long
)

/**
 * Represents a sensitive or destructive action paused at ActionConfirmationGate
 * awaiting explicit user approval.
 */
data class PendingConfirmationRequest(
    val id: String,
    val originalUserQuery: String,
    val plannedCall: PlannedToolCall,
    val providerUsed: String,
    val warningReason: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class AssistantSessionStage {
    IDLE,
    LISTENING,
    SANITIZING_AND_PLANNING,
    AWAITING_USER_CONFIRMATION,
    EXECUTING_AND_VERIFYING,
    SPEAKING_RESPONSE,
    PERMISSION_OR_CONFIG_REQUIRED,
    ERROR
}
