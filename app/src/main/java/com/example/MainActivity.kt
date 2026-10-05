package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nova.security.PermissionAuditor
import com.example.nova.ui.NovaAssistantScreen
import com.example.nova.ui.NovaViewModel
import com.example.nova.ui.SettingsAndLicensesScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                NovaRootScreen()
            }
        }
    }
}

@Composable
fun NovaRootScreen(novaViewModel: NovaViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val turns by novaViewModel.conversationTurns.collectAsStateWithLifecycle()
    val memoryFacts by novaViewModel.memoryFacts.collectAsStateWithLifecycle()
    val settings by novaViewModel.runtimeSettings.collectAsStateWithLifecycle()
    val settingsState by novaViewModel.settingsState.collectAsStateWithLifecycle()
    val voiceState by novaViewModel.voiceState.collectAsStateWithLifecycle()
    val liveRmsDb by novaViewModel.liveRmsDb.collectAsStateWithLifecycle()
    val liveVoiceSessionState by novaViewModel.liveVoiceSessionState.collectAsStateWithLifecycle()
    val orbState by novaViewModel.uiOrbState.collectAsStateWithLifecycle()
    val workspaceCardState by novaViewModel.workspaceCardState.collectAsStateWithLifecycle()
    val statusBanner by novaViewModel.statusBanner.collectAsStateWithLifecycle()
    val health by novaViewModel.systemHealth.collectAsStateWithLifecycle()
    val overlayAttachmentState by novaViewModel.overlayAttachmentState.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Return from Settings to the full-screen Nova Assistant experience on system Back
    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    // Refresh real permission & service state whenever user returns from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                novaViewModel.refreshSystemHealthAndApps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        novaViewModel.refreshSystemHealth()
        if (granted) {
            novaViewModel.startVoiceListening()
        }
    }

    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        novaViewModel.refreshSystemHealth()
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        novaViewModel.onMediaPermissionResult()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 12)
    ) { uris ->
        novaViewModel.onPhotoPickerUrisSelected(uris.map { it.toString() })
    }

    val openAccessibilitySettings = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        Unit
    }

    val openNotificationListenerSettings = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        Unit
    }

    val openAppDetailsSettings = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        Unit
    }

    val openExternalUrl: (String) -> Unit = { url ->
        if (url.startsWith("https://")) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    val latestAssistantTurn = turns.firstOrNull { it.role != "USER" }

    if (showSettings) {
        SettingsAndLicensesScreen(
            settings = settings,
            health = health,
            overlayAttachmentState = overlayAttachmentState,
            settingsState = settingsState,
            savedMemoryFacts = memoryFacts,
            onSelectSection = { section -> novaViewModel.selectSettingsSection(section) },
            onSelectProvider = { p -> novaViewModel.selectPrimaryProvider(p) },
            onToggleCloudFallback = { en -> novaViewModel.setCloudFallback(en) },
            onSelectGeminiModel = { model -> novaViewModel.setGeminiModel(model) },
            onSelectGroqModel = { model -> novaViewModel.setGroqModel(model) },
            onSelectOpenAiModel = { model -> novaViewModel.setOpenAiModel(model) },
            onToggleSpokenResponses = { en -> novaViewModel.setSpokenResponses(en) },
            onToggleRequireConfirmation = { req -> novaViewModel.setRequireConfirmation(req) },
            onToggleAccessibilityHud = { show -> novaViewModel.setShowAccessibilityHud(show) },
            onToggleMemoryContext = { en -> novaViewModel.setMemoryContextEnabled(en) },
            onSelectLowRamOverride = { ov -> novaViewModel.setLowRamOverride(ov) },
            onRequestRecordAudioPermission = {
                novaViewModel.markPermissionRequested(Manifest.permission.RECORD_AUDIO)
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRequestNotificationsPermission = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    novaViewModel.markPermissionRequested(Manifest.permission.POST_NOTIFICATIONS)
                    notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppDetailsSettings()
                }
            },
            onRequestMediaPermission = {
                val perms = novaViewModel.getRequiredMediaStorePermissions()
                perms.forEach { novaViewModel.markPermissionRequested(it) }
                if (perms.isNotEmpty()) {
                    mediaPermissionLauncher.launch(perms.toTypedArray())
                }
            },
            onOpenAccessibilitySettings = openAccessibilitySettings,
            onOpenNotificationListenerSettings = openNotificationListenerSettings,
            onOpenAppDetailsSettings = openAppDetailsSettings,
            onOpenExternalUrl = openExternalUrl,
            onClearConversationHistory = { novaViewModel.clearConversationHistory() },
            onClearMemoryFacts = { novaViewModel.clearAllMemoryFacts() },
            onClearAuditLogs = { novaViewModel.clearAuditLogs() },
            onRefreshDiagnostics = { novaViewModel.refreshSystemHealthAndApps() },
            onCloseSettings = { showSettings = false },
            onSaveProviderSecret = { provider, key -> novaViewModel.saveProviderSecret(provider, key) },
            onSaveElevenLabsSecret = { key -> novaViewModel.saveElevenLabsSecret(key) },
            onTestElevenLabsVoice = { voiceId -> novaViewModel.testElevenLabsVoice(voiceId) }
        )
    } else {
        NovaAssistantScreen(
            orbState = orbState,
            workspaceCardState = workspaceCardState,
            voiceState = voiceState,
            liveRmsDb = liveRmsDb,
            lowRamMode = health.effectiveLowRamMode,
            statusBanner = statusBanner,
            latestAssistantTurn = latestAssistantTurn,
            onMicClick = {
                if (PermissionAuditor.isRecordAudioGranted(context)) {
                    novaViewModel.startLiveVoiceSession()
                } else {
                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopVoiceOrTask = { novaViewModel.stopActiveTask() },
            onSubmitTextCommand = { cmd -> novaViewModel.submitCommand(cmd) },
            onConfirmPendingAction = { novaViewModel.approvePendingAction() },
            onRejectPendingAction = { novaViewModel.rejectPendingAction() },
            onDismissWorkspaceCard = { novaViewModel.dismissWorkspaceCard() },
            onOpenSettings = { showSettings = true },
            onRequestRecordAudioPermission = {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onOpenAccessibilitySettings = openAccessibilitySettings,
            onRequestMediaPermission = {
                val perms = novaViewModel.getRequiredMediaStorePermissions()
                if (perms.isNotEmpty()) {
                    mediaPermissionLauncher.launch(perms.toTypedArray())
                }
            },
            onLaunchPhotoPicker = {
                runCatching {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            },
            onLoadPhotoThumbnail = { uri, lowRam ->
                novaViewModel.loadPhotoThumbnail(uri, lowRam)
            },
            onLoadFullscreenPhoto = { uri, lowRam ->
                novaViewModel.loadFullscreenPhoto(uri, lowRam)
            },
            onCancelTask = { taskId ->
                novaViewModel.cancelTask(taskId)
            },
            liveVoiceSessionState = liveVoiceSessionState,
            onStartLiveVoiceSession = {
                if (PermissionAuditor.isRecordAudioGranted(context)) {
                    novaViewModel.startLiveVoiceSession()
                } else {
                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onEndLiveVoiceSession = { novaViewModel.endLiveVoiceSession() },
            onToggleMuteLiveVoiceSession = { novaViewModel.toggleLiveVoiceMute() },
            onBargeIn = { novaViewModel.bargeInLiveSession() }
        )
    }
}
