package com.example.nova.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.MainActivity
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.OverlayAttachmentState
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.ui.AndroidWindowManagerOverlayHost
import com.example.nova.ui.FloatingNovaOrbOverlayController
import com.example.nova.ui.FloatingNovaOrbView
import com.example.nova.ui.createAccessibilityOverlayLayoutParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real Android AccessibilityService for Nova.
 * - Tracks active window package metadata via event-driven callbacks without polling.
 * - Extracts semantic screen trees strictly on demand and clears them when no longer needed.
 * - Hosts the circular Floating Nova Orb ([FloatingNovaOrbView]) via TYPE_ACCESSIBILITY_OVERLAY,
 *   reporting real [OverlayAttachmentState] (DETACHED, ATTACHED, SERVICE_NOT_BOUND,
 *   REJECTED_BY_WINDOW_MANAGER, DISABLED_BY_USER_PREFERENCE).
 * - Exposes [ScreenUnderstandingFallback] extension point for future vision milestones.
 */
class NovaAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var windowHost: AndroidWindowManagerOverlayHost? = null

    private val overlayController = FloatingNovaOrbOverlayController(
        viewFactory = {
            FloatingNovaOrbView(
                context = this,
                onExpandAssistant = { expandAssistantActivity() },
                onCancelActiveTask = { onUserCancelTaskCallback?.invoke() }
            )
        },
        layoutParamsFactory = {
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            val orbDiameterPx = (56f * density).toInt()
            createAccessibilityOverlayLayoutParams(
                sizePx = orbDiameterPx,
                initialX = (16f * density).toInt(),
                initialY = (64f * density).toInt()
            )
        }
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        windowHost = windowManager?.let { AndroidWindowManagerOverlayHost(it) }
        syncFloatingOrbOnMainThread()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) {
            _activeWindowPackage.value = pkg
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val title = event.text?.firstOrNull()?.toString()?.trim().orEmpty()
            if (title.isNotEmpty()) {
                _activeWindowTitle.value = title
            }
        }
    }

    override fun onInterrupt() {
        // Required override when system interrupts accessibility feedback
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val outcome = overlayController.onServiceUnbound(windowHost)
        _overlayAttachmentState.value = outcome.attachmentState
        _overlayDiagnosticMessage.value = outcome.diagnosticMessage
        _isServiceConnected.value = false
        _activeWindowPackage.value = ""
        _activeWindowTitle.value = ""
        _latestSnapshot.value = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        val outcome = overlayController.onServiceUnbound(windowHost)
        _overlayAttachmentState.value = outcome.attachmentState
        _overlayDiagnosticMessage.value = outcome.diagnosticMessage
        _isServiceConnected.value = false
        _activeWindowPackage.value = ""
        _activeWindowTitle.value = ""
        _latestSnapshot.value = null
        instance = null
        super.onDestroy()
    }

    private fun expandAssistantActivity() {
        runCatching {
            val launchIntent = Intent(this@NovaAccessibilityService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launchIntent)
        }
    }

    fun obtainActiveRootAdapter(): AccessibilityNodeAdapter? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        return AndroidAccessibilityNodeAdapter(root, isRoot = true)
    }

    fun captureSemanticSnapshotOnDemand(lowRamMode: Boolean = false): SemanticScreenSnapshot? {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            _latestSnapshot.value = null
            return null
        }
        val snapshot = SemanticScreenExtractor.extractSnapshot(
            rootNode = root,
            activePackageOverride = _activeWindowPackage.value,
            windowTitleOverride = _activeWindowTitle.value,
            lowRamMode = lowRamMode
        )
        _latestSnapshot.value = snapshot
        if (snapshot != null && snapshot.packageName != "Unavailable") {
            _activeWindowPackage.value = snapshot.packageName
        }
        return snapshot
    }

    /**
     * M6 Requirement L — Delegates to [ScreenUnderstandingFallback] extension point without
     * executing pixel/OCR screenshot capture in M6.
     */
    fun captureOnDemandVisualSummary(
        fallback: ScreenUnderstandingFallback = DefaultScreenUnderstandingFallback
    ): VisualCaptureOutcome {
        return when (val outcome = fallback.inspectScreenFallback()) {
            is ScreenUnderstandingFallbackOutcome.NotImplemented -> VisualCaptureOutcome(
                succeeded = false,
                summary = outcome.message,
                width = 0,
                height = 0
            )
            is ScreenUnderstandingFallbackOutcome.Unavailable -> VisualCaptureOutcome(
                succeeded = false,
                summary = outcome.message,
                width = 0,
                height = 0
            )
        }
    }

    private fun syncFloatingOrbOnMainThread() {
        mainHandler.post {
            val outcome = overlayController.syncOverlay(
                windowHost = windowHost,
                isServiceBound = _isServiceConnected.value,
                userPreferenceEnabled = _overlayRequestedVisible.value,
                orbState = _overlayOrbState.value,
                liveRmsDb = _overlayLiveRmsDb.value,
                lowRamMode = _overlayLowRamMode.value
            )
            _overlayAttachmentState.value = outcome.attachmentState
            _overlayDiagnosticMessage.value = outcome.diagnosticMessage
        }
    }

    private fun detachFloatingOrbOnMainThread() {
        mainHandler.post {
            val outcome = overlayController.detachOverlay(windowHost)
            _overlayAttachmentState.value = outcome.attachmentState
            _overlayDiagnosticMessage.value = outcome.diagnosticMessage
        }
    }

    data class VisualCaptureOutcome(
        val succeeded: Boolean,
        val summary: String,
        val width: Int,
        val height: Int
    )

    companion object {
        @Volatile
        var instance: NovaAccessibilityService? = null
            private set

        @Volatile
        var onUserCancelTaskCallback: (() -> Unit)? = null

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _activeWindowPackage = MutableStateFlow("")
        val activeWindowPackage: StateFlow<String> = _activeWindowPackage.asStateFlow()

        private val _activeWindowTitle = MutableStateFlow("")
        val activeWindowTitle: StateFlow<String> = _activeWindowTitle.asStateFlow()

        private val _latestSnapshot = MutableStateFlow<SemanticScreenSnapshot?>(null)
        val latestSnapshot: StateFlow<SemanticScreenSnapshot?> = _latestSnapshot.asStateFlow()

        private val _overlayRequestedVisible = MutableStateFlow(true)
        private val _overlayStatusLabel = MutableStateFlow("Nova • Ready")
        private val _isTaskRunning = MutableStateFlow(false)
        private val _overlayOrbState = MutableStateFlow(NovaUiOrbState.IDLE)
        val overlayOrbState: StateFlow<NovaUiOrbState> = _overlayOrbState.asStateFlow()

        private val _overlayLiveRmsDb = MutableStateFlow<Float?>(null)
        private val _overlayLowRamMode = MutableStateFlow(false)

        private val _overlayAttachmentState = MutableStateFlow(OverlayAttachmentState.SERVICE_NOT_BOUND)
        val overlayAttachmentState: StateFlow<OverlayAttachmentState> = _overlayAttachmentState.asStateFlow()

        private val _overlayDiagnosticMessage = MutableStateFlow("NovaAccessibilityService is not bound.")
        val overlayDiagnosticMessage: StateFlow<String> = _overlayDiagnosticMessage.asStateFlow()

        fun clearInMemorySnapshot() {
            _latestSnapshot.value = null
        }

        /**
         * Synchronizes the floating Nova orb with the authoritative real [NovaUiOrbState],
         * microphone RMS dB, Low-RAM flag, and user visibility preference.
         * Never reports [OverlayAttachmentState.ATTACHED] unless the service is bound and
         * WindowManager actually attached the view.
         */
        fun syncFloatingOrbState(
            visible: Boolean,
            orbState: NovaUiOrbState,
            liveRmsDb: Float? = null,
            lowRamMode: Boolean = false
        ) {
            _overlayRequestedVisible.value = visible
            _overlayOrbState.value = orbState
            _overlayLiveRmsDb.value = liveRmsDb
            _overlayLowRamMode.value = lowRamMode

            val svc = instance
            if (!visible) {
                if (svc != null) {
                    svc.syncFloatingOrbOnMainThread()
                } else {
                    _overlayAttachmentState.value = OverlayAttachmentState.DISABLED_BY_USER_PREFERENCE
                    _overlayDiagnosticMessage.value = "Floating Nova orb disabled by user preference."
                }
                return
            }

            if (svc == null || !_isServiceConnected.value) {
                _overlayAttachmentState.value = OverlayAttachmentState.SERVICE_NOT_BOUND
                _overlayDiagnosticMessage.value = "NovaAccessibilityService is not bound."
                return
            }

            svc.syncFloatingOrbOnMainThread()
        }

        /**
         * Explicitly detaches the floating Nova orb if attached, reporting [OverlayAttachmentState.DETACHED].
         */
        fun detachFloatingOrb() {
            val svc = instance
            if (svc != null) {
                svc.detachFloatingOrbOnMainThread()
            } else {
                _overlayAttachmentState.value = OverlayAttachmentState.DETACHED
                _overlayDiagnosticMessage.value = "Floating Nova orb detached."
            }
        }

        fun updateOverlayHudState(
            visible: Boolean,
            statusLabel: String,
            isTaskRunning: Boolean
        ) {
            _overlayStatusLabel.value = statusLabel
            _isTaskRunning.value = isTaskRunning
            val derivedOrbState = when {
                isTaskRunning && statusLabel.contains("Planning", ignoreCase = true) ->
                    NovaUiOrbState.THINKING
                isTaskRunning ->
                    NovaUiOrbState.EXECUTING
                statusLabel.contains("ERROR", ignoreCase = true) ||
                    statusLabel.contains("FAILED", ignoreCase = true) ->
                    NovaUiOrbState.ERROR
                statusLabel.contains("REQUIRED", ignoreCase = true) ||
                    statusLabel.contains("DISABLED", ignoreCase = true) ||
                    statusLabel.contains("UNSUPPORTED", ignoreCase = true) ->
                    NovaUiOrbState.UNAVAILABLE
                else -> _overlayOrbState.value
            }
            syncFloatingOrbState(
                visible = visible,
                orbState = derivedOrbState,
                liveRmsDb = _overlayLiveRmsDb.value,
                lowRamMode = _overlayLowRamMode.value
            )
        }
    }
}
