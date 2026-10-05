package com.example.nova.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.agent.NovaAgentOrchestrator
import com.example.nova.ai.NovaAiProviderRouter
import com.example.nova.core.AiProviderType
import com.example.nova.core.AssistantSessionStage
import com.example.nova.core.InstalledAppInfo
import com.example.nova.core.LiveNotificationItem
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.PendingConfirmationRequest
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.core.SystemHealthSnapshot
import com.example.nova.core.VerifiedActionResult
import com.example.nova.core.WorkspaceCardState
import com.example.nova.memory.ConversationTurnEntity
import com.example.nova.memory.MemoryFactEntity
import com.example.nova.memory.NovaDatabase
import com.example.nova.memory.NovaPreferences
import com.example.nova.memory.NovaRepository
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.memory.ScheduledReminderEntity
import com.example.nova.memory.TaskAuditLogEntity
import com.example.nova.security.PermissionAuditor
import com.example.nova.security.ProviderSecretVault
import com.example.nova.services.NovaNotificationService
import com.example.nova.tools.SystemToolExecutor
import com.example.nova.voice.ElevenLabsVoiceService
import com.example.nova.voice.LiveVoiceSessionCoordinator
import com.example.nova.voice.VoiceEngineState
import com.example.nova.voice.VoiceSessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovaViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = NovaDatabase.getInstance(appContext)
    private val repository = NovaRepository(database)
    private val preferences = NovaPreferences(appContext)
    private val aiRouter = NovaAiProviderRouter()
    private val toolExecutor = SystemToolExecutor(appContext, repository)

    private val elevenLabsService = ElevenLabsVoiceService(
        context = appContext,
        serviceScope = viewModelScope
    )

    private val voiceController: VoiceSessionController = VoiceSessionController(
        context = appContext,
        onFinalTranscriptRecognized = { transcript ->
            onSpeechTranscriptRecognized(transcript)
        }
    )

    private val liveSessionCoordinator: LiveVoiceSessionCoordinator = LiveVoiceSessionCoordinator(
        context = appContext,
        coroutineScope = viewModelScope,
        voiceSessionController = voiceController,
        elevenLabsVoiceService = elevenLabsService,
        onUserUtteranceSubmitted = { transcript ->
            submitCommand(transcript)
        }
    )

    private val orchestrator: NovaAgentOrchestrator = NovaAgentOrchestrator(
        context = appContext,
        scope = viewModelScope,
        repository = repository,
        preferences = preferences,
        aiRouter = aiRouter,
        toolExecutor = toolExecutor,
        onSpeakResponse = { responseText ->
            if (liveSessionCoordinator.isLiveSessionActive()) {
                liveSessionCoordinator.speakResponse(
                    text = responseText,
                    spokenResponsesEnabled = preferences.settings.value.spokenResponsesEnabled
                )
            } else {
                voiceController.speak(
                    text = responseText,
                    enabledInSettings = preferences.settings.value.spokenResponsesEnabled
                )
            }
        }
    )

    private fun onSpeechTranscriptRecognized(transcript: String) {
        if (liveSessionCoordinator.isLiveSessionActive()) {
            liveSessionCoordinator.onTranscriptReceived(transcript)
        } else {
            submitCommand(transcript)
        }
    }

    val conversationTurns: StateFlow<List<ConversationTurnEntity>> =
        repository.conversationTurns.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val memoryFacts: StateFlow<List<MemoryFactEntity>> =
        repository.memoryFacts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val taskAuditLogs: StateFlow<List<TaskAuditLogEntity>> =
        repository.taskAuditLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val scheduledReminders: StateFlow<List<ScheduledReminderEntity>> =
        repository.scheduledReminders.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val runtimeSettings: StateFlow<NovaRuntimeSettings> = preferences.settings
    val voiceState: StateFlow<VoiceEngineState> = voiceController.voiceState
    val liveRmsDb: StateFlow<Float?> = voiceController.liveRmsDb
    val sessionStage: StateFlow<AssistantSessionStage> = orchestrator.sessionStage
    val statusBanner: StateFlow<String> = orchestrator.statusBanner
    val pendingConfirmation: StateFlow<PendingConfirmationRequest?> = orchestrator.pendingConfirmation
    val lastVerifiedResult: StateFlow<VerifiedActionResult?> = orchestrator.lastVerifiedResult
    val activeTasks: StateFlow<List<com.example.nova.core.LiveTaskItem>> = orchestrator.activeTasks

    val latestScreenSnapshot: StateFlow<SemanticScreenSnapshot?> =
        NovaAccessibilityService.latestSnapshot
    val activeNotifications: StateFlow<List<LiveNotificationItem>> =
        NovaNotificationService.activeNotifications
    val overlayAttachmentState = NovaAccessibilityService.overlayAttachmentState
    val overlayDiagnosticMessage = NovaAccessibilityService.overlayDiagnosticMessage
    val latestDeviceInfo = toolExecutor.latestDeviceInfo
    val latestPhotoWorkspaceState = toolExecutor.latestPhotoWorkspaceState
    val latestAccessibilityWorkspaceState = toolExecutor.latestAccessibilityWorkspaceState

    private val _systemHealth = MutableStateFlow(
        PermissionAuditor.captureSystemHealthSnapshot(
            context = appContext,
            lowRamModeUserOverride = preferences.settings.value.lowRamModeOverride,
            isTorchCurrentlyOn = false
        )
    )
    val systemHealth: StateFlow<SystemHealthSnapshot> = _systemHealth.asStateFlow()

    private val _selectedSettingsSection = MutableStateFlow(com.example.nova.core.NovaSettingsSection.AI_PROVIDER)
    val selectedSettingsSection: StateFlow<com.example.nova.core.NovaSettingsSection> = _selectedSettingsSection.asStateFlow()

    private val _permissionAuditItems = MutableStateFlow(
        PermissionAuditor.auditNovaPermissions(
            context = appContext,
            hasRequestedPermission = { key -> preferences.hasRequestedPermission(key) }
        )
    )
    val permissionAuditItems: StateFlow<List<com.example.nova.core.PermissionAuditItemState>> = _permissionAuditItems.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    /**
     * M8 / M10 — Authoritative NovaSettingsState resolved from real runtime, OS, Room, and provider states.
     */
    fun buildCurrentSettingsState(): com.example.nova.core.NovaSettingsState {
        return NovaSettingsStateResolver.resolveSettingsState(
            selectedSection = _selectedSettingsSection.value,
            settings = runtimeSettings.value,
            health = _systemHealth.value,
            voiceState = voiceState.value,
            isTtsReady = voiceController.isTtsReadyFlow.value,
            isTtsEngineInstalled = PermissionAuditor.isTextToSpeechEngineInstalled(appContext),
            ttsErrorMessage = voiceController.ttsErrorFlow.value,
            hasMicrophoneHardware = PermissionAuditor.hasMicrophoneHardware(appContext),
            overlayAttachmentState = overlayAttachmentState.value,
            screenSnapshot = latestScreenSnapshot.value,
            accessibilityWorkspaceState = latestAccessibilityWorkspaceState.value,
            photoWorkspaceState = latestPhotoWorkspaceState.value,
            latestDeviceInfo = latestDeviceInfo.value,
            activeTasks = activeTasks.value,
            permissionAuditItems = _permissionAuditItems.value,
            providerRuntimeErrors = aiRouter.providerRuntimeErrors.value,
            savedMemoryFactCount = memoryFacts.value.size,
            conversationTurnCount = conversationTurns.value.size,
            scheduledReminderCount = scheduledReminders.value.size,
            latestDiagnosticMessage = orchestrator.latestDiagnosticMessage.value,
            isElevenLabsConfigured = ProviderSecretVault.isElevenLabsConfigured(appContext),
            maskedElevenLabsKey = ProviderSecretVault.getMaskedElevenLabsKey(appContext),
            elevenLabsStatus = elevenLabsService.status.value,
            elevenLabsStatusDetail = elevenLabsService.statusDetail.value,
            isElevenLabsFallbackActive = elevenLabsService.isFallbackActive.value
        )
    }

    val liveVoiceSessionState: StateFlow<com.example.nova.core.LiveVoiceSessionState>
        get() = liveSessionCoordinator.sessionState

    val settingsState: StateFlow<com.example.nova.core.NovaSettingsState> = combine(
        _selectedSettingsSection,
        runtimeSettings,
        systemHealth,
        combine(
            voiceState,
            voiceController.isTtsReadyFlow,
            voiceController.ttsErrorFlow,
            overlayAttachmentState,
            _permissionAuditItems
        ) { _, _, _, _, _ -> Unit },
        combine(
            latestScreenSnapshot,
            latestAccessibilityWorkspaceState,
            latestPhotoWorkspaceState,
            latestDeviceInfo,
            combine(
                activeTasks,
                aiRouter.providerRuntimeErrors,
                memoryFacts,
                conversationTurns,
                combine(scheduledReminders, orchestrator.latestDiagnosticMessage) { _, _ -> Unit }
            ) { _, _, _, _, _ -> Unit }
        ) { _, _, _, _, _ -> Unit }
    ) { _, _, _, _, _ ->
        buildCurrentSettingsState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = buildCurrentSettingsState()
    )

    /**
     * M1 / M7 / M10 — Authoritative NovaUiOrbState mapped reactively from real voice, live session, and agent states.
     */
    val uiOrbState: StateFlow<NovaUiOrbState> = combine(
        combine(voiceState, sessionStage, liveSessionCoordinator.sessionState) { v, s, l -> Triple(v, s, l) },
        combine(lastVerifiedResult, statusBanner, activeTasks) { r, b, t -> Triple(r, b, t) }
    ) { (voice, stage, liveState), (verifiedResult, banner, tasks) ->
        NovaUiStateMapper.deriveOrbState(
            voiceState = voice,
            sessionStage = stage,
            lastVerifiedResult = verifiedResult,
            statusBanner = banner,
            activeTasks = tasks,
            liveVoiceSessionState = liveState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NovaUiOrbState.IDLE
    )

    /**
     * M1 / M4 / M5 / M6 / M7 — Authoritative WorkspaceCardState mapped reactively from real active tasks,
     * confirmations, verified outcomes, live device info, photo workspace state, accessibility
     * workspace state, and capability/error states.
     */
    @Suppress("UNCHECKED_CAST")
    val workspaceCardState: StateFlow<WorkspaceCardState> = combine(
        sessionStage,
        pendingConfirmation,
        lastVerifiedResult,
        voiceState,
        combine(
            statusBanner,
            orchestrator.latestDiagnosticMessage,
            latestDeviceInfo,
            latestPhotoWorkspaceState,
            combine(latestAccessibilityWorkspaceState, activeTasks) { a11y, tasks -> a11y to tasks }
        ) { b, d, info, photos, a11yAndTasks -> listOf(b, d, info, photos, a11yAndTasks.first, a11yAndTasks.second) }
    ) { stage, pending, verifiedResult, voice, extra ->
        val banner = extra[0] as String
        val diagnostic = extra[1] as String
        val deviceInfo = extra[2] as? com.example.nova.core.NovaDeviceInfo
        val photoState = extra[3] as? com.example.nova.core.PhotoAccessWorkspaceState
        val a11yState = extra[4] as? com.example.nova.core.AccessibilityWorkspaceState
        val tasks = (extra[5] as? List<com.example.nova.core.LiveTaskItem>).orEmpty()
        NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = stage,
            pendingConfirmation = pending,
            lastVerifiedResult = verifiedResult,
            voiceState = voice,
            statusBanner = banner,
            latestDiagnosticMessage = diagnostic,
            latestDeviceInfo = deviceInfo,
            latestPhotoWorkspaceState = photoState,
            latestAccessibilityWorkspaceState = a11yState,
            activeTasks = tasks
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WorkspaceCardState.None
    )

    init {
        refreshSystemHealthAndApps()
        viewModelScope.launch {
            NovaAccessibilityService.isServiceConnected.collect {
                refreshSystemHealth()
            }
        }
        viewModelScope.launch {
            toolExecutor.isTorchOn.collect {
                refreshSystemHealth()
            }
        }
        viewModelScope.launch {
            combine(
                uiOrbState,
                liveRmsDb,
                runtimeSettings,
                systemHealth
            ) { orb, rms, settings, health ->
                NovaAccessibilityService.syncFloatingOrbState(
                    visible = settings.showAccessibilityFloatingHud,
                    orbState = orb,
                    liveRmsDb = rms,
                    lowRamMode = health.effectiveLowRamMode
                )
            }.collect {}
        }
    }

    fun refreshSystemHealth() {
        _systemHealth.value = PermissionAuditor.captureSystemHealthSnapshot(
            context = appContext,
            lowRamModeUserOverride = preferences.settings.value.lowRamModeOverride,
            isTorchCurrentlyOn = toolExecutor.isTorchOn.value
        )
        _permissionAuditItems.value = PermissionAuditor.auditNovaPermissions(
            context = appContext,
            hasRequestedPermission = { key -> preferences.hasRequestedPermission(key) }
        )
    }

    fun refreshSystemHealthAndApps() {
        refreshSystemHealth()
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                toolExecutor.queryInstalledLaunchableApps()
            }
            _installedApps.value = apps
        }
    }

    fun selectSettingsSection(section: com.example.nova.core.NovaSettingsSection) {
        _selectedSettingsSection.value = section
    }

    fun markPermissionRequested(permissionKey: String) {
        preferences.markPermissionRequested(permissionKey)
        refreshSystemHealth()
    }

    fun submitCommand(command: String) {
        orchestrator.submitUserCommand(command)
        refreshSystemHealth()
    }

    fun submitPlannedTasks(
        originalQuery: String,
        calls: List<com.example.nova.core.PlannedToolCall>
    ) {
        orchestrator.submitPlannedTasks(
            originalQuery = originalQuery,
            calls = calls,
            lowRamMode = _systemHealth.value.effectiveLowRamMode
        )
        refreshSystemHealth()
    }

    fun startVoiceListening() {
        voiceController.startListening()
    }

    fun stopVoiceListeningOrSpeaking() {
        voiceController.stopListening()
        voiceController.stopSpeaking()
    }

    fun approvePendingAction() {
        orchestrator.approvePendingConfirmation()
    }

    fun rejectPendingAction() {
        orchestrator.rejectPendingConfirmation()
    }

    fun cancelTask(taskId: String): Boolean {
        return orchestrator.cancelTask(taskId, "Cancelled by user.")
    }

    fun stopActiveTask() {
        voiceController.stopSpeaking()
        orchestrator.cancelActiveTask("Stopped by user.")
    }

    fun dismissWorkspaceCard() {
        orchestrator.clearLastVerifiedResult()
        voiceController.clearVoiceErrorOrUnavailableState()
    }

    fun getRequiredMediaStorePermissions(): List<String> {
        return toolExecutor.getRequiredMediaStorePermissions()
    }

    fun onPhotoPickerUrisSelected(uris: List<String>) {
        orchestrator.onPhotoPickerUrisSelected(
            uris = uris,
            lowRamMode = _systemHealth.value.effectiveLowRamMode
        )
    }

    fun onMediaPermissionResult() {
        refreshSystemHealth()
        orchestrator.onMediaPermissionResult(
            lowRamMode = _systemHealth.value.effectiveLowRamMode
        )
    }

    suspend fun loadPhotoThumbnail(
        uriString: String,
        lowRamMode: Boolean
    ) = toolExecutor.loadPhotoThumbnail(uriString, lowRamMode)

    suspend fun loadFullscreenPhoto(
        uriString: String,
        lowRamMode: Boolean
    ) = toolExecutor.loadFullscreenPhoto(uriString, lowRamMode)

    fun captureScreenNow() {
        val lowRam = _systemHealth.value.effectiveLowRamMode
        val svc = NovaAccessibilityService.instance
        if (svc != null) {
            svc.captureSemanticSnapshotOnDemand(lowRamMode = lowRam)
        } else {
            submitCommand("read screen")
        }
        refreshSystemHealth()
    }

    fun addMemoryFact(key: String, value: String, category: String) {
        if (key.isBlank() || value.isBlank()) return
        viewModelScope.launch {
            repository.saveMemoryFact(key, value, category)
        }
    }

    fun deleteMemoryFact(id: Long) {
        viewModelScope.launch {
            repository.deleteMemoryFact(id)
        }
    }

    fun clearAllMemoryFacts() {
        viewModelScope.launch {
            repository.clearAllMemoryFacts()
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch {
            repository.deleteReminder(id)
        }
    }

    fun clearConversationHistory() {
        viewModelScope.launch {
            repository.clearConversationHistory()
        }
    }

    fun clearAuditLogs() {
        viewModelScope.launch {
            repository.clearTaskAuditLogs()
        }
    }

    fun selectPrimaryProvider(provider: AiProviderType) {
        preferences.updatePrimaryProvider(provider)
        refreshSystemHealth()
    }

    fun setCloudFallback(enabled: Boolean) {
        preferences.updateCloudFallback(enabled)
    }

    fun setGeminiModel(modelId: String) {
        preferences.updateGeminiModel(modelId)
    }

    fun setGroqModel(modelId: String) {
        preferences.updateGroqModel(modelId)
    }

    fun setOpenAiModel(modelId: String) {
        preferences.updateOpenAiModel(modelId)
    }

    fun setMemoryContextEnabled(enabled: Boolean) {
        preferences.updateMemoryContextEnabled(enabled)
    }

    fun setSpokenResponses(enabled: Boolean) {
        preferences.updateSpokenResponses(enabled)
        if (!enabled) voiceController.stopSpeaking()
    }

    fun setLowRamOverride(override: Boolean?) {
        preferences.updateLowRamOverride(override)
        refreshSystemHealth()
    }

    fun setRequireConfirmation(required: Boolean) {
        preferences.updateRequireConfirmation(required)
    }

    fun setShowAccessibilityHud(show: Boolean) {
        preferences.updateShowAccessibilityHud(show)
        NovaAccessibilityService.syncFloatingOrbState(
            visible = show,
            orbState = uiOrbState.value,
            liveRmsDb = liveRmsDb.value,
            lowRamMode = _systemHealth.value.effectiveLowRamMode
        )
    }

    // M10 Live Voice Session Controls
    fun startLiveVoiceSession(): Boolean {
        val started = liveSessionCoordinator.startLiveSession()
        refreshSystemHealth()
        return started
    }

    fun endLiveVoiceSession() {
        liveSessionCoordinator.endLiveSession()
        refreshSystemHealth()
    }

    fun toggleLiveVoiceMute(): Boolean {
        return liveSessionCoordinator.toggleMuteMicrophone()
    }

    fun bargeInLiveSession() {
        liveSessionCoordinator.bargeIn()
    }

    fun isLiveVoiceSessionActive(): Boolean {
        return liveSessionCoordinator.isLiveSessionActive()
    }

    fun saveProviderSecret(provider: com.example.nova.core.AiProviderType, plaintextSecret: String) {
        ProviderSecretVault.saveProviderSecret(appContext, provider, plaintextSecret)
        refreshSystemHealth()
    }

    fun saveElevenLabsSecret(plaintextSecret: String) {
        ProviderSecretVault.saveElevenLabsSecret(appContext, plaintextSecret)
        elevenLabsService.refreshConfigurationStatus()
        refreshSystemHealth()
    }

    fun testElevenLabsVoice(voiceId: String = "21m00Tcm4TlvDq8ikWAM") {
        elevenLabsService.speakWithFallback(
            text = "Hello, I am Arya. Your ElevenLabs voice connection is active.",
            voiceId = voiceId,
            onDone = {},
            onFallbackNeeded = { reason: String ->
                voiceController.speak(
                    text = "ElevenLabs unavailable ($reason). Using Android TextToSpeech.",
                    enabledInSettings = true
                )
            }
        )
    }

    fun initiateDirectCall(phoneNumber: String): com.example.nova.tools.CallingOutcome {
        return com.example.nova.tools.CallingController.initiateCall(appContext, phoneNumber)
    }

    fun openSystemDialer(phoneNumber: String): com.example.nova.tools.CallingOutcome {
        return com.example.nova.tools.CallingController.openDialer(appContext, phoneNumber)
    }

    override fun onCleared() {
        liveSessionCoordinator.shutdown()
        voiceController.shutdown()
        toolExecutor.cleanup()
        super.onCleared()
    }
}
