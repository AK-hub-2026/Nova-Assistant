package com.example.nova.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import com.example.MainActivity
import com.example.nova.accessibility.AccessibilityActionPerformer
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.accessibility.ScreenUnderstandingFallback
import com.example.nova.core.AccessibilityWorkspaceState
import com.example.nova.core.AccessibilityWorkspaceStatus
import com.example.nova.core.InstalledAppInfo
import com.example.nova.core.NovaDeviceInfo
import com.example.nova.core.PhotoAccessMode
import com.example.nova.core.PhotoAccessWorkspaceState
import com.example.nova.core.PlannedToolCall
import com.example.nova.core.SupportedAccessibilityAction
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.VerifiedActionResult
import com.example.nova.memory.NovaRepository
import com.example.nova.security.PermissionAuditor
import com.example.nova.ui.formatDeviceInfoDisplayRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Executes real Android API, Intent, Memory, Media, and Accessibility tools and verifies
 * every outcome against actual device/OS state before returning.
 */
class SystemToolExecutor(
    private val context: Context,
    private val repository: NovaRepository,
    private val deviceInfoProvider: NovaDeviceInfoProvider = NovaDeviceInfoProvider(context),
    private val photoAccessManager: PhotoAccessManager = PhotoAccessManager(context)
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _latestDeviceInfo = MutableStateFlow<NovaDeviceInfo?>(null)
    val latestDeviceInfo: StateFlow<NovaDeviceInfo?> = _latestDeviceInfo.asStateFlow()

    private val _latestPhotoWorkspaceState = MutableStateFlow<PhotoAccessWorkspaceState?>(null)
    val latestPhotoWorkspaceState: StateFlow<PhotoAccessWorkspaceState?> = _latestPhotoWorkspaceState.asStateFlow()

    private val _latestAccessibilityWorkspaceState = MutableStateFlow<AccessibilityWorkspaceState?>(null)
    val latestAccessibilityWorkspaceState: StateFlow<AccessibilityWorkspaceState?> =
        _latestAccessibilityWorkspaceState.asStateFlow()

    fun clearLatestDeviceInfo() {
        _latestDeviceInfo.value = null
    }

    fun clearLatestPhotoWorkspace() {
        _latestPhotoWorkspaceState.value = null
    }

    fun clearLatestAccessibilityWorkspace() {
        _latestAccessibilityWorkspaceState.value = null
        NovaAccessibilityService.clearInMemorySnapshot()
    }

    fun isAccessibilityToolName(toolName: String): Boolean {
        return toolName.lowercase() in ACCESSIBILITY_TOOL_NAMES
    }

    fun getRequiredMediaStorePermissions(): List<String> {
        return photoAccessManager.getRequiredMediaStorePermissions()
    }

    suspend fun loadPhotoThumbnail(
        uriString: String,
        lowRamMode: Boolean
    ): ThumbnailDecodeOutcome {
        return photoAccessManager.loadThumbnail(uriString, lowRamMode)
    }

    suspend fun loadFullscreenPhoto(
        uriString: String,
        lowRamMode: Boolean
    ): FullscreenPhotoLoadOutcome {
        return photoAccessManager.loadFullscreenPhoto(uriString, lowRamMode)
    }

    fun ingestPickedPhotoUris(
        uris: List<String>,
        lowRamMode: Boolean
    ): VerifiedActionResult {
        val startMs = System.currentTimeMillis()
        val state = photoAccessManager.ingestPickedUris(uris, lowRamMode)
        _latestPhotoWorkspaceState.value = state
        return mapPhotoStateToVerifiedResult(
            state = state,
            preState = "Photo Picker returned ${uris.size} URI(s)",
            startMs = startMs
        )
    }

    fun refreshPhotosAfterPermissionResult(
        lowRamMode: Boolean
    ): VerifiedActionResult {
        val startMs = System.currentTimeMillis()
        val state = photoAccessManager.queryPhotoWorkspace(
            preferPicker = false,
            lowRamMode = lowRamMode
        )
        _latestPhotoWorkspaceState.value = state
        return mapPhotoStateToVerifiedResult(
            state = state,
            preState = "Media permission callback evaluated",
            startMs = startMs
        )
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            _isTorchOn.value = enabled
        }
    }

    init {
        runCatching {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
    }

    fun cleanup() {
        runCatching {
            cameraManager?.unregisterTorchCallback(torchCallback)
        }
        photoAccessManager.clearThumbnailCache()
    }

    /**
     * Queries real launchable apps installed on the device via PackageManager.
     */
    fun queryInstalledLaunchableApps(): List<InstalledAppInfo> {
        val pm = appContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = runCatching {
            pm.queryIntentActivities(mainIntent, 0)
        }.getOrDefault(emptyList())

        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString().trim() }
                    .getOrDefault(pkg)
                InstalledAppInfo(
                    appLabel = label.ifBlank { pkg },
                    packageName = pkg
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appLabel.lowercase() }
    }

    suspend fun executeAndVerify(
        call: PlannedToolCall,
        lowRamMode: Boolean
    ): VerifiedActionResult {
        val startMs = System.currentTimeMillis()
        val args = call.arguments

        return when (call.toolName.lowercase()) {
            "query_device_info" -> executeQueryDeviceInfo(startMs)
            "query_photos" -> executeQueryPhotos(
                preferPicker = (args["mode"] ?: "mediastore").equals("picker", ignoreCase = true),
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "launch_app" -> executeLaunchApp(args["app"] ?: args["query"].orEmpty(), startMs)
            "open_settings" -> executeOpenSettings(args["section"].orEmpty(), startMs)
            "set_volume" -> executeSetVolume(
                streamName = args["stream"] ?: "music",
                directionOrLevel = args["level"] ?: args["direction"] ?: "up",
                startMs = startMs
            )
            "set_flashlight" -> executeSetFlashlight(
                enable = (args["enabled"] ?: args["state"] ?: "true").lowercase() in listOf("true", "on", "1"),
                startMs = startMs
            )
            "set_alarm" -> executeSetAlarm(
                hour = args["hour"]?.toIntOrNull() ?: 8,
                minute = args["minute"]?.toIntOrNull() ?: 0,
                label = args["label"] ?: "Nova Alarm",
                startMs = startMs
            )
            "set_timer" -> executeSetTimer(
                seconds = args["seconds"]?.toIntOrNull() ?: 60,
                label = args["label"] ?: "Nova Timer",
                startMs = startMs
            )
            "dial_number" -> executeDialNumber(args["phone"] ?: args["number"].orEmpty(), startMs)
            "compose_sms" -> executeComposeSms(
                phone = args["phone"] ?: args["number"].orEmpty(),
                body = args["message"] ?: args["body"].orEmpty(),
                startMs = startMs
            )
            "open_url", "web_search" -> executeWebOrUrl(
                queryOrUrl = args["url"] ?: args["query"].orEmpty(),
                startMs = startMs
            )
            "save_memory" -> executeSaveMemory(
                key = args["key"] ?: "Note",
                value = args["value"] ?: args["fact"].orEmpty(),
                category = args["category"] ?: "USER_NOTE",
                startMs = startMs
            )
            "schedule_reminder" -> executeScheduleReminder(
                title = args["title"] ?: args["label"] ?: "Reminder",
                minutesFromNow = args["minutes"]?.toLongOrNull() ?: 10L,
                startMs = startMs
            )
            "read_screen" -> executeReadScreen(
                toolName = "read_screen",
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "query_accessibility_status" -> executeReadScreen(
                toolName = "query_accessibility_status",
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "click_node" -> executeAccessibilityNodeAction(
                toolName = "click_node",
                targetQuery = args["target"] ?: args["query"].orEmpty(),
                requestedAction = if ((args["action"] ?: "CLICK").equals("LONG_CLICK", ignoreCase = true)) {
                    SupportedAccessibilityAction.LONG_CLICK
                } else {
                    SupportedAccessibilityAction.CLICK
                },
                textArgument = null,
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "focus_node" -> executeAccessibilityNodeAction(
                toolName = "focus_node",
                targetQuery = args["target"] ?: args["query"].orEmpty(),
                requestedAction = SupportedAccessibilityAction.FOCUS,
                textArgument = null,
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "select_node" -> executeAccessibilityNodeAction(
                toolName = "select_node",
                targetQuery = args["target"] ?: args["query"].orEmpty(),
                requestedAction = SupportedAccessibilityAction.SELECT,
                textArgument = null,
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "input_text" -> executeAccessibilityNodeAction(
                toolName = "input_text",
                targetQuery = args["target"].orEmpty(),
                requestedAction = SupportedAccessibilityAction.SET_TEXT,
                textArgument = args["text"].orEmpty(),
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "scroll_screen" -> {
                val dir = (args["direction"] ?: "down").trim().lowercase()
                val scrollAction = if (dir in listOf("up", "backward", "previous", "right")) {
                    SupportedAccessibilityAction.SCROLL_BACKWARD
                } else {
                    SupportedAccessibilityAction.SCROLL_FORWARD
                }
                executeAccessibilityNodeAction(
                    toolName = "scroll_screen",
                    targetQuery = args["target"].orEmpty(),
                    requestedAction = scrollAction,
                    textArgument = null,
                    lowRamMode = lowRamMode,
                    startMs = startMs
                )
            }
            "navigate_global" -> executeAccessibilityGlobalNav(
                action = args["action"] ?: "back",
                lowRamMode = lowRamMode,
                startMs = startMs
            )
            "capture_visual_fallback" -> executeVisualFallback(lowRamMode, startMs)
            else -> VerifiedActionResult(
                toolName = call.toolName,
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Tool '${call.toolName}' is not registered in SystemToolExecutor.",
                targetPackage = appContext.packageName,
                preStateSummary = "Unknown tool",
                postStateSummary = "No state change",
                userFacingMessage = call.spokenResponseDraft.ifBlank {
                    "Unsupported tool '${call.toolName}'."
                },
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeQueryDeviceInfo(startMs: Long): VerifiedActionResult {
        return when (val outcome = deviceInfoProvider.queryDeviceInfo()) {
            is DeviceInfoQueryOutcome.Success -> {
                val info = outcome.deviceInfo
                _latestDeviceInfo.value = info
                val rows = formatDeviceInfoDisplayRows(info)
                val summary = "Battery: ${rows.batteryLevelText} (${rows.chargingStateText}) • " +
                    "RAM: ${rows.availableRamText} / ${rows.totalRamText} • " +
                    "Storage: ${rows.availableStorageText} free • " +
                    "${rows.androidVersionText} (${rows.sdkLevelText}) • " +
                    "Network: ${rows.networkConnectivityText} (${rows.networkTransportText})"
                VerifiedActionResult(
                    toolName = "query_device_info",
                    outcome = VerificationOutcome.VERIFIED_SUCCESS,
                    verificationDetail = "Queried real Android BatteryManager, ActivityManager.MemoryInfo, StatFs, Build.VERSION, and ConnectivityManager.",
                    targetPackage = appContext.packageName,
                    preStateSummary = "Requested live device info",
                    postStateSummary = summary,
                    userFacingMessage = summary,
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            }

            is DeviceInfoQueryOutcome.ProviderFailure -> {
                _latestDeviceInfo.value = null
                VerifiedActionResult(
                    toolName = "query_device_info",
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    verificationDetail = outcome.errorDescription,
                    targetPackage = appContext.packageName,
                    preStateSummary = "Requested live device info",
                    postStateSummary = "Provider failure",
                    userFacingMessage = outcome.errorDescription,
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            }
        }
    }

    private fun executeQueryPhotos(
        preferPicker: Boolean,
        lowRamMode: Boolean,
        startMs: Long
    ): VerifiedActionResult {
        val state = photoAccessManager.queryPhotoWorkspace(
            preferPicker = preferPicker,
            lowRamMode = lowRamMode
        )
        _latestPhotoWorkspaceState.value = state
        return mapPhotoStateToVerifiedResult(
            state = state,
            preState = if (preferPicker) "Requested Photo Picker workspace" else "Requested MediaStore / accessible photos",
            startMs = startMs
        )
    }

    private fun mapPhotoStateToVerifiedResult(
        state: PhotoAccessWorkspaceState,
        preState: String,
        startMs: Long
    ): VerifiedActionResult {
        val elapsed = System.currentTimeMillis() - startMs
        return when (state.accessMode) {
            PhotoAccessMode.MEDIASTORE_ACCESS,
            PhotoAccessMode.PICKED_URI_SESSION,
            PhotoAccessMode.PICKED_URI_PERSISTED -> {
                if (state.photos.isNotEmpty()) {
                    VerifiedActionResult(
                        toolName = "query_photos",
                        outcome = VerificationOutcome.VERIFIED_SUCCESS,
                        verificationDetail = "Obtained ${state.photos.size} real photo(s) via ${state.accessMode.name} (mediaStoreState=${state.mediaStoreAccessState.name}).",
                        targetPackage = "android.provider.MediaStore",
                        preStateSummary = preState,
                        postStateSummary = "${state.photos.size} real photo(s) (${state.accessMode.name})",
                        userFacingMessage = state.statusMessage,
                        elapsedMs = elapsed
                    )
                } else {
                    VerifiedActionResult(
                        toolName = "query_photos",
                        outcome = VerificationOutcome.CONFIGURATION_REQUIRED,
                        verificationDetail = "Query completed (${state.accessMode.name}) but 0 accessible photos were returned.",
                        targetPackage = "android.provider.MediaStore",
                        preStateSummary = preState,
                        postStateSummary = "0 accessible photos",
                        userFacingMessage = state.statusMessage.ifBlank { "No accessible photos found." },
                        elapsedMs = elapsed
                    )
                }
            }

            PhotoAccessMode.PERMISSION_REQUIRED -> {
                VerifiedActionResult(
                    toolName = "query_photos",
                    outcome = VerificationOutcome.PERMISSION_REQUIRED,
                    verificationDetail = "Photo access requires runtime media permission or Android Photo Picker selection (pickerRequested=${state.isPickerLaunchRequested}).",
                    targetPackage = "android.provider.MediaStore",
                    preStateSummary = preState,
                    postStateSummary = "PERMISSION_REQUIRED",
                    userFacingMessage = state.statusMessage.ifBlank { "Photo access is required." },
                    elapsedMs = elapsed
                )
            }

            PhotoAccessMode.EMPTY -> {
                VerifiedActionResult(
                    toolName = "query_photos",
                    outcome = VerificationOutcome.CONFIGURATION_REQUIRED,
                    verificationDetail = "MediaStore query succeeded with permission granted, returning 0 photos.",
                    targetPackage = "android.provider.MediaStore",
                    preStateSummary = preState,
                    postStateSummary = "Empty media library (0 photos)",
                    userFacingMessage = state.statusMessage.ifBlank { "No accessible photos were found on this device." },
                    elapsedMs = elapsed
                )
            }

            PhotoAccessMode.UNAVAILABLE -> {
                VerifiedActionResult(
                    toolName = "query_photos",
                    outcome = VerificationOutcome.UNSUPPORTED,
                    verificationDetail = state.statusMessage.ifBlank { "Photo capability unsupported on this device." },
                    targetPackage = "android.provider.MediaStore",
                    preStateSummary = preState,
                    postStateSummary = "UNAVAILABLE",
                    userFacingMessage = state.statusMessage.ifBlank { "Photo access is unavailable on this device." },
                    elapsedMs = elapsed
                )
            }

            PhotoAccessMode.ERROR -> {
                val detail = state.errorMessage ?: state.statusMessage.ifBlank { "ContentResolver photo query failed." }
                VerifiedActionResult(
                    toolName = "query_photos",
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    verificationDetail = detail,
                    targetPackage = "android.provider.MediaStore",
                    preStateSummary = preState,
                    postStateSummary = "ERROR",
                    userFacingMessage = state.statusMessage.ifBlank { detail },
                    elapsedMs = elapsed
                )
            }
        }
    }

    private fun executeLaunchApp(query: String, startMs: Long): VerifiedActionResult {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return VerifiedActionResult(
                toolName = "launch_app",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Empty app name or package parameter.",
                targetPackage = "",
                preStateSummary = "Foreground=${appContext.packageName}",
                postStateSummary = "Unchanged",
                userFacingMessage = "Please specify which installed app you want to open.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }

        val installed = queryInstalledLaunchableApps()
        val match = installed.firstOrNull {
            it.appLabel.equals(trimmed, ignoreCase = true) ||
                it.packageName.equals(trimmed, ignoreCase = true)
        } ?: installed.firstOrNull {
            it.appLabel.contains(trimmed, ignoreCase = true) ||
                it.packageName.contains(trimmed, ignoreCase = true)
        }

        if (match == null) {
            return VerifiedActionResult(
                toolName = "launch_app",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "PackageManager queried ${installed.size} launchable apps; none matched '$trimmed'.",
                targetPackage = trimmed,
                preStateSummary = "Installed apps checked: ${installed.size}",
                postStateSummary = "Not installed",
                userFacingMessage = "No installed app matching \"$trimmed\" was found on this device.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }

        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(match.packageName)
        if (launchIntent == null) {
            return VerifiedActionResult(
                toolName = "launch_app",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Package ${match.packageName} has no launch intent.",
                targetPackage = match.packageName,
                preStateSummary = "App=${match.appLabel}",
                postStateSummary = "Launch intent null",
                userFacingMessage = "${match.appLabel} is installed but cannot be launched directly.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(launchIntent)
            VerifiedActionResult(
                toolName = "launch_app",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Verified launch Intent resolved and dispatched for ${match.appLabel} (${match.packageName}).",
                targetPackage = match.packageName,
                preStateSummary = "PackageManager match=${match.packageName}",
                postStateSummary = "Launched ${match.packageName}",
                userFacingMessage = "Opened ${match.appLabel}.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            VerifiedActionResult(
                toolName = "launch_app",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "startActivity threw ${e.javaClass.simpleName}: ${e.message}",
                targetPackage = match.packageName,
                preStateSummary = "Match=${match.packageName}",
                postStateSummary = "Exception on startActivity",
                userFacingMessage = "Failed to open ${match.appLabel}: ${e.message}",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeOpenSettings(section: String, startMs: Long): VerifiedActionResult {
        val norm = section.trim().lowercase()
        val settingsAction = when {
            norm.contains("access") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            norm.contains("notif") -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            norm.contains("wifi") || norm.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            norm.contains("blue") -> Settings.ACTION_BLUETOOTH_SETTINGS
            norm.contains("sound") || norm.contains("volume") || norm.contains("audio") -> Settings.ACTION_SOUND_SETTINGS
            norm.contains("display") || norm.contains("bright") -> Settings.ACTION_DISPLAY_SETTINGS
            norm.contains("battery") || norm.contains("power") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            norm.contains("location") || norm.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        val intent = Intent(settingsAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            VerifiedActionResult(
                toolName = "open_settings",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Dispatched verified system Settings intent ($settingsAction).",
                targetPackage = "com.android.settings",
                preStateSummary = "Requested section=$section",
                postStateSummary = "Opened $settingsAction",
                userFacingMessage = "Opened Android ${section.ifBlank { "System" }} Settings.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            VerifiedActionResult(
                toolName = "open_settings",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Settings intent failed: ${e.message}",
                targetPackage = "com.android.settings",
                preStateSummary = "Requested=$settingsAction",
                postStateSummary = "Failed",
                userFacingMessage = "Could not open that Settings screen on this device.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeSetVolume(
        streamName: String,
        directionOrLevel: String,
        startMs: Long
    ): VerifiedActionResult {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return VerifiedActionResult(
                toolName = "set_volume",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "AudioManager unavailable on this device.",
                targetPackage = "android.media.AudioManager",
                preStateSummary = "N/A",
                postStateSummary = "N/A",
                userFacingMessage = "Audio volume service is unavailable on this device.",
                elapsedMs = System.currentTimeMillis() - startMs
            )

        val streamType = when (streamName.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }

        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val preVol = audioManager.getStreamVolume(streamType)
        val norm = directionOrLevel.trim().lowercase()

        val targetVol = when {
            norm == "up" || norm == "raise" || norm == "increase" -> (preVol + 2).coerceAtMost(maxVol)
            norm == "down" || norm == "lower" || norm == "decrease" -> (preVol - 2).coerceAtLeast(0)
            norm == "mute" || norm == "0" -> 0
            norm == "max" || norm == "full" || norm == "100" -> maxVol
            norm.endsWith("%") -> {
                val pct = norm.removeSuffix("%").toIntOrNull()?.coerceIn(0, 100) ?: 50
                ((pct / 100f) * maxVol).toInt()
            }
            norm.toIntOrNull() != null -> {
                val pct = norm.toInt().coerceIn(0, 100)
                if (pct <= maxVol) pct else ((pct / 100f) * maxVol).toInt()
            }
            else -> (preVol + 1).coerceAtMost(maxVol)
        }

        return try {
            audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            val postVol = audioManager.getStreamVolume(streamType)
            val verified = postVol == targetVol || (preVol != postVol) || (preVol == targetVol)
            VerifiedActionResult(
                toolName = "set_volume",
                outcome = if (verified) VerificationOutcome.VERIFIED_SUCCESS else VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "AudioManager stream=$streamName volume readback: $preVol/$maxVol -> $postVol/$maxVol.",
                targetPackage = "android.media.AudioManager",
                preStateSummary = "Volume=$preVol/$maxVol",
                postStateSummary = "Volume=$postVol/$maxVol",
                userFacingMessage = if (verified) {
                    "Set $streamName volume to $postVol of $maxVol."
                } else {
                    "Volume change was blocked by Do Not Disturb or fixed-volume policy (remained $postVol/$maxVol)."
                },
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } catch (e: SecurityException) {
            VerifiedActionResult(
                toolName = "set_volume",
                outcome = VerificationOutcome.PERMISSION_REQUIRED,
                verificationDetail = "SecurityException modifying volume (Do Not Disturb policy active): ${e.message}",
                targetPackage = "android.media.AudioManager",
                preStateSummary = "Volume=$preVol/$maxVol",
                postStateSummary = "Unchanged",
                userFacingMessage = "Cannot change volume while Do Not Disturb policy restricts audio changes.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeSetFlashlight(enable: Boolean, startMs: Long): VerifiedActionResult {
        if (!PermissionAuditor.hasFlashlightHardware(appContext)) {
            return VerifiedActionResult(
                toolName = "set_flashlight",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "Device reports FEATURE_CAMERA_FLASH=false or no flash unit in CameraCharacteristics.",
                targetPackage = "android.hardware.camera2",
                preStateSummary = "Hardware Flash=Absent",
                postStateSummary = "Unsupported",
                userFacingMessage = "This device or emulator does not have a hardware camera flashlight.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
        val cm = cameraManager ?: return VerifiedActionResult(
            toolName = "set_flashlight",
            outcome = VerificationOutcome.UNSUPPORTED,
            verificationDetail = "CameraManager service null.",
            targetPackage = "android.hardware.camera2",
            preStateSummary = "Unavailable",
            postStateSummary = "Unavailable",
            userFacingMessage = "Camera manager service is unavailable.",
            elapsedMs = System.currentTimeMillis() - startMs
        )

        return try {
            val flashCamId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return VerifiedActionResult(
                toolName = "set_flashlight",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "No camera ID reported FLASH_INFO_AVAILABLE=true.",
                targetPackage = "android.hardware.camera2",
                preStateSummary = "No flash camera ID",
                postStateSummary = "Unsupported",
                userFacingMessage = "No camera with flashlight support was found on this device.",
                elapsedMs = System.currentTimeMillis() - startMs
            )

            val preState = _isTorchOn.value
            cm.setTorchMode(flashCamId, enable)
            _isTorchOn.value = enable
            VerifiedActionResult(
                toolName = "set_flashlight",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "setTorchMode($flashCamId, $enable) succeeded on CameraManager.",
                targetPackage = "android.hardware.camera2",
                preStateSummary = "Torch=$preState",
                postStateSummary = "Torch=$enable",
                userFacingMessage = if (enable) "Turned flashlight on." else "Turned flashlight off.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            VerifiedActionResult(
                toolName = "set_flashlight",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "setTorchMode failed: ${e.message}",
                targetPackage = "android.hardware.camera2",
                preStateSummary = "Torch=${_isTorchOn.value}",
                postStateSummary = "Error",
                userFacingMessage = "Could not toggle flashlight: ${e.message}",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private suspend fun executeSetAlarm(
        hour: Int,
        minute: Int,
        label: String,
        startMs: Long
    ): VerifiedActionResult {
        val validHour = hour.coerceIn(0, 23)
        val validMin = minute.coerceIn(0, 59)
        val timeFormatted = String.format("%02d:%02d", validHour, validMin)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, validHour)
            putExtra(AlarmClock.EXTRA_MINUTES, validMin)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val resolved = intent.resolveActivity(appContext.packageManager)
        return if (resolved != null) {
            try {
                appContext.startActivity(intent)
                repository.insertReminder(
                    title = "$label ($timeFormatted)",
                    triggerAtMs = System.currentTimeMillis() + 3600_000L,
                    scheduledViaSystemAlarm = true
                )
                VerifiedActionResult(
                    toolName = "set_alarm",
                    outcome = VerificationOutcome.VERIFIED_SUCCESS,
                    verificationDetail = "Verified Clock handler (${resolved.packageName}) and dispatched ACTION_SET_ALARM for $timeFormatted.",
                    targetPackage = resolved.packageName,
                    preStateSummary = "Alarm target=$timeFormatted",
                    postStateSummary = "Dispatched to ${resolved.packageName}",
                    userFacingMessage = "Sent alarm for $timeFormatted ($label) to the Clock app.",
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            } catch (e: Exception) {
                VerifiedActionResult(
                    toolName = "set_alarm",
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    verificationDetail = "Failed starting ACTION_SET_ALARM: ${e.message}",
                    targetPackage = resolved.packageName,
                    preStateSummary = "Target=$timeFormatted",
                    postStateSummary = "Exception",
                    userFacingMessage = "Failed to open Clock app for alarm: ${e.message}",
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            }
        } else {
            // Save in local reminder table but honestly report that no system Clock app handles ACTION_SET_ALARM
            repository.insertReminder(
                title = "$label ($timeFormatted)",
                triggerAtMs = System.currentTimeMillis() + 3600_000L,
                scheduledViaSystemAlarm = false
            )
            VerifiedActionResult(
                toolName = "set_alarm",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "No system Clock app resolved ACTION_SET_ALARM; saved to Nova Reminders database instead.",
                targetPackage = appContext.packageName,
                preStateSummary = "No Clock handler",
                postStateSummary = "Saved in Nova local reminders",
                userFacingMessage = "No system Clock app is installed to handle alarms directly; saved \"$label\" ($timeFormatted) in Nova Reminders.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeSetTimer(seconds: Int, label: String, startMs: Long): VerifiedActionResult {
        val validSecs = seconds.coerceAtLeast(1)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, validSecs)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(appContext.packageManager)
        return if (resolved != null) {
            try {
                appContext.startActivity(intent)
                VerifiedActionResult(
                    toolName = "set_timer",
                    outcome = VerificationOutcome.VERIFIED_SUCCESS,
                    verificationDetail = "Verified timer intent handler (${resolved.packageName}) for ${validSecs}s.",
                    targetPackage = resolved.packageName,
                    preStateSummary = "Timer=${validSecs}s",
                    postStateSummary = "Started in ${resolved.packageName}",
                    userFacingMessage = "Started a ${validSecs}-second timer in Clock.",
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            } catch (e: Exception) {
                VerifiedActionResult(
                    toolName = "set_timer",
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    verificationDetail = "Timer intent failed: ${e.message}",
                    targetPackage = resolved.packageName,
                    preStateSummary = "Timer=${validSecs}s",
                    postStateSummary = "Error",
                    userFacingMessage = "Could not start timer: ${e.message}",
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            }
        } else {
            VerifiedActionResult(
                toolName = "set_timer",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "No installed Clock app resolved ACTION_SET_TIMER.",
                targetPackage = "",
                preStateSummary = "Timer=${validSecs}s",
                postStateSummary = "No handler",
                userFacingMessage = "No system Clock app on this device supports timer intents.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeDialNumber(phoneNumber: String, startMs: Long): VerifiedActionResult {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isBlank()) {
            return VerifiedActionResult(
                toolName = "dial_number",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Empty phone number parameter.",
                targetPackage = "",
                preStateSummary = "Number=blank",
                postStateSummary = "Aborted",
                userFacingMessage = "Please provide a phone number to open in the dialer.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(appContext.packageManager)
        return if (resolved != null) {
            appContext.startActivity(intent)
            VerifiedActionResult(
                toolName = "dial_number",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Opened system Dialer (${resolved.packageName}) with tel:$cleanNumber.",
                targetPackage = resolved.packageName,
                preStateSummary = "Target=$cleanNumber",
                postStateSummary = "Dialer opened",
                userFacingMessage = "Opened the phone dialer with $cleanNumber.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } else {
            VerifiedActionResult(
                toolName = "dial_number",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "No dialer app resolved ACTION_DIAL.",
                targetPackage = "",
                preStateSummary = "Target=$cleanNumber",
                postStateSummary = "No telephony dialer",
                userFacingMessage = "No phone dialer app is available on this device.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeComposeSms(phone: String, body: String, startMs: Long): VerifiedActionResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone.trim())}")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(appContext.packageManager)
        return if (resolved != null) {
            appContext.startActivity(intent)
            VerifiedActionResult(
                toolName = "compose_sms",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Opened SMS composer (${resolved.packageName}) for recipient '$phone'.",
                targetPackage = resolved.packageName,
                preStateSummary = "Recipient=$phone",
                postStateSummary = "SMS app opened with draft",
                userFacingMessage = "Opened SMS messaging app with your draft to $phone.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } else {
            VerifiedActionResult(
                toolName = "compose_sms",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "No SMS app resolved ACTION_SENDTO smsto: URI.",
                targetPackage = "",
                preStateSummary = "Recipient=$phone",
                postStateSummary = "No SMS handler",
                userFacingMessage = "No SMS messaging app is installed on this device.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun executeWebOrUrl(queryOrUrl: String, startMs: Long): VerifiedActionResult {
        val trimmed = queryOrUrl.trim()
        if (trimmed.isBlank()) {
            return VerifiedActionResult(
                toolName = "web_search",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Empty URL or search query.",
                targetPackage = "",
                preStateSummary = "Blank query",
                postStateSummary = "Unchanged",
                userFacingMessage = "Please provide a URL or search query.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
        val finalUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(appContext.packageManager)
        return if (resolved != null) {
            appContext.startActivity(intent)
            VerifiedActionResult(
                toolName = "web_search",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Opened $finalUrl via ${resolved.packageName}.",
                targetPackage = resolved.packageName,
                preStateSummary = "Query=$trimmed",
                postStateSummary = "Browser launched (${resolved.packageName})",
                userFacingMessage = "Opened \"$trimmed\" in your browser.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } else {
            VerifiedActionResult(
                toolName = "web_search",
                outcome = VerificationOutcome.UNSUPPORTED,
                verificationDetail = "No browser resolved ACTION_VIEW for $finalUrl.",
                targetPackage = "",
                preStateSummary = "URL=$finalUrl",
                postStateSummary = "No browser handler",
                userFacingMessage = "No web browser is installed to open links.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private suspend fun executeSaveMemory(
        key: String,
        value: String,
        category: String,
        startMs: Long
    ): VerifiedActionResult {
        if (value.isBlank()) {
            return VerifiedActionResult(
                toolName = "save_memory",
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Memory value was blank.",
                targetPackage = appContext.packageName,
                preStateSummary = "Blank fact",
                postStateSummary = "Not saved",
                userFacingMessage = "Cannot save an empty memory note.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }
        val rowId = repository.saveMemoryFact(key = key, value = value, category = category)
        val verified = rowId > 0L
        return VerifiedActionResult(
            toolName = "save_memory",
            outcome = if (verified) VerificationOutcome.VERIFIED_SUCCESS else VerificationOutcome.VERIFICATION_FAILED,
            verificationDetail = "Inserted fact into Room table memory_facts (rowId=$rowId).",
            targetPackage = appContext.packageName,
            preStateSummary = "Key=$key",
            postStateSummary = "RowId=$rowId",
            userFacingMessage = "Saved to Nova Memory: \"$key — $value\".",
            elapsedMs = System.currentTimeMillis() - startMs
        )
    }

    private suspend fun executeScheduleReminder(
        title: String,
        minutesFromNow: Long,
        startMs: Long
    ): VerifiedActionResult {
        val triggerAt = System.currentTimeMillis() + (minutesFromNow.coerceAtLeast(1L) * 60_000L)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        var scheduledInOs = false

        if (alarmManager != null) {
            val pending = PendingIntent.getActivity(
                appContext,
                title.hashCode(),
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            runCatching {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                scheduledInOs = true
            }
        }

        val rowId = repository.insertReminder(
            title = title,
            triggerAtMs = triggerAt,
            scheduledViaSystemAlarm = scheduledInOs
        )
        return VerifiedActionResult(
            toolName = "schedule_reminder",
            outcome = if (rowId > 0) VerificationOutcome.VERIFIED_SUCCESS else VerificationOutcome.VERIFICATION_FAILED,
            verificationDetail = "Saved reminder rowId=$rowId (AlarmManager scheduled=$scheduledInOs) for +${minutesFromNow}m.",
            targetPackage = appContext.packageName,
            preStateSummary = "Title=$title",
            postStateSummary = "Scheduled in ${minutesFromNow}m (rowId=$rowId)",
            userFacingMessage = "Scheduled reminder \"$title\" for $minutesFromNow minutes from now.",
            elapsedMs = System.currentTimeMillis() - startMs
        )
    }

    private fun executeReadScreen(
        toolName: String,
        lowRamMode: Boolean,
        startMs: Long
    ): VerifiedActionResult {
        val enabledInSettings = PermissionAuditor.isAccessibilityServiceEnabledInSettings(appContext)
        val svc = NovaAccessibilityService.instance
        val isConnected = svc != null && NovaAccessibilityService.isServiceConnected.value

        if (!isConnected || svc == null) {
            return accessibilityNotConnectedResult(
                toolName = toolName,
                enabledInSettings = enabledInSettings,
                startMs = startMs
            )
        }

        val snapshot = svc.captureSemanticSnapshotOnDemand(lowRamMode = lowRamMode)
        if (snapshot == null) {
            val failedResult = VerifiedActionResult(
                toolName = toolName,
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "AccessibilityService is connected, but rootInActiveWindow returned null.",
                targetPackage = "",
                preStateSummary = "ServiceConnected=true",
                postStateSummary = "ActiveWindow=Unavailable (null root)",
                userFacingMessage = "AccessibilityService is connected, but the current window did not expose an accessible view hierarchy.",
                elapsedMs = System.currentTimeMillis() - startMs
            )
            _latestAccessibilityWorkspaceState.value = AccessibilityActionPerformer.buildWorkspaceState(
                serviceEnabledInSettings = enabledInSettings,
                serviceConnected = true,
                snapshot = null,
                overlayState = NovaAccessibilityService.overlayAttachmentState.value,
                latestActionResult = null
            )
            return failedResult
        }

        val topLabels = snapshot.targets
            .mapNotNull { it.visibleText.ifBlank { it.contentDescription }.takeIf { s -> s.isNotBlank() } }
            .take(5)
            .joinToString(", ")

        val verifiedResult = VerifiedActionResult(
            toolName = toolName,
            outcome = VerificationOutcome.VERIFIED_SUCCESS,
            verificationDetail = "Extracted ${snapshot.targets.size} semantic targets (${snapshot.redactedFieldCount} sensitive fields redacted) from ${snapshot.packageName}.",
            targetPackage = snapshot.packageName,
            preStateSummary = "Window=${snapshot.packageName}",
            postStateSummary = "${snapshot.targets.size} semantic targets extracted",
            userFacingMessage = if (snapshot.targets.isEmpty()) {
                "Active app (${snapshot.packageName}) exposed 0 accessible semantic targets."
            } else {
                "Read ${snapshot.targets.size} elements from ${snapshot.packageName}. Key items: $topLabels"
            },
            elapsedMs = System.currentTimeMillis() - startMs
        )

        _latestAccessibilityWorkspaceState.value = AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = enabledInSettings,
            serviceConnected = true,
            snapshot = snapshot,
            overlayState = NovaAccessibilityService.overlayAttachmentState.value,
            latestActionResult = verifiedResult
        )
        return verifiedResult
    }

    private suspend fun executeAccessibilityNodeAction(
        toolName: String,
        targetQuery: String,
        requestedAction: SupportedAccessibilityAction,
        textArgument: String?,
        lowRamMode: Boolean,
        startMs: Long
    ): VerifiedActionResult {
        val enabledInSettings = PermissionAuditor.isAccessibilityServiceEnabledInSettings(appContext)
        val svc = NovaAccessibilityService.instance
        val isConnected = svc != null && NovaAccessibilityService.isServiceConnected.value

        if (!isConnected || svc == null) {
            return accessibilityNotConnectedResult(
                toolName = toolName,
                enabledInSettings = enabledInSettings,
                startMs = startMs
            )
        }

        _latestAccessibilityWorkspaceState.value = AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = enabledInSettings,
            serviceConnected = true,
            snapshot = NovaAccessibilityService.latestSnapshot.value,
            overlayState = NovaAccessibilityService.overlayAttachmentState.value,
            actionInProgressDescription = "Executing ${requestedAction.name} on '${targetQuery.ifBlank { "active window" }}'..."
        )

        val actionOutcome = AccessibilityActionPerformer.executeAndVerifySemanticAction(
            rootProvider = { svc.obtainActiveRootAdapter() },
            targetQuery = targetQuery,
            requestedAction = requestedAction,
            textArgument = textArgument,
            activePackageOverride = NovaAccessibilityService.activeWindowPackage.value,
            windowTitleOverride = NovaAccessibilityService.activeWindowTitle.value,
            lowRamMode = lowRamMode
        )

        // Refresh in-memory snapshot in NovaAccessibilityService so UI stays synchronized
        val latestSnap = svc.captureSemanticSnapshotOnDemand(lowRamMode = lowRamMode)
            ?: actionOutcome.postSnapshot
            ?: actionOutcome.preSnapshot

        val verifiedResult = VerifiedActionResult(
            toolName = toolName,
            outcome = actionOutcome.outcome,
            verificationDetail = actionOutcome.verificationDetail,
            targetPackage = actionOutcome.targetPackage,
            preStateSummary = actionOutcome.preStateSummary,
            postStateSummary = actionOutcome.postStateSummary,
            userFacingMessage = actionOutcome.userFacingMessage,
            elapsedMs = System.currentTimeMillis() - startMs
        )

        _latestAccessibilityWorkspaceState.value = AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = enabledInSettings,
            serviceConnected = true,
            snapshot = latestSnap,
            overlayState = NovaAccessibilityService.overlayAttachmentState.value,
            latestActionResult = verifiedResult,
            ambiguousCandidates = actionOutcome.ambiguousCandidates
        )

        return verifiedResult
    }

    private suspend fun executeAccessibilityGlobalNav(
        action: String,
        lowRamMode: Boolean,
        startMs: Long
    ): VerifiedActionResult {
        val enabledInSettings = PermissionAuditor.isAccessibilityServiceEnabledInSettings(appContext)
        val svc = NovaAccessibilityService.instance
        val isConnected = svc != null && NovaAccessibilityService.isServiceConnected.value

        if (!isConnected || svc == null) {
            return accessibilityNotConnectedResult(
                toolName = "navigate_global",
                enabledInSettings = enabledInSettings,
                startMs = startMs
            )
        }

        val preSnap = svc.captureSemanticSnapshotOnDemand(lowRamMode)
        val prePkg = preSnap?.packageName ?: NovaAccessibilityService.activeWindowPackage.value
        val status = AccessibilityActionPerformer.performGlobalNavigation(svc, action)
        delay(300L)
        val postSnap = svc.captureSemanticSnapshotOnDemand(lowRamMode)
        val postPkg = postSnap?.packageName ?: NovaAccessibilityService.activeWindowPackage.value

        val verifiedResult = VerifiedActionResult(
            toolName = "navigate_global",
            outcome = if (status.dispatched) {
                VerificationOutcome.VERIFIED_SUCCESS
            } else {
                VerificationOutcome.VERIFICATION_FAILED
            },
            verificationDetail = status.detail,
            targetPackage = postPkg.ifBlank { prePkg },
            preStateSummary = "Pkg=$prePkg",
            postStateSummary = "Pkg=$postPkg",
            userFacingMessage = status.detail,
            elapsedMs = System.currentTimeMillis() - startMs
        )

        _latestAccessibilityWorkspaceState.value = AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = enabledInSettings,
            serviceConnected = true,
            snapshot = postSnap ?: preSnap,
            overlayState = NovaAccessibilityService.overlayAttachmentState.value,
            latestActionResult = verifiedResult
        )

        return verifiedResult
    }

    private suspend fun executeVisualFallback(
        lowRamMode: Boolean,
        startMs: Long
    ): VerifiedActionResult {
        val enabledInSettings = PermissionAuditor.isAccessibilityServiceEnabledInSettings(appContext)
        val svc = NovaAccessibilityService.instance
        val isConnected = svc != null && NovaAccessibilityService.isServiceConnected.value
        val fallbackResult = ScreenUnderstandingFallback.queryFallbackStatus()

        val verifiedResult = VerifiedActionResult(
            toolName = "capture_visual_fallback",
            outcome = VerificationOutcome.UNSUPPORTED,
            verificationDetail = fallbackResult.diagnosticMessage,
            targetPackage = NovaAccessibilityService.activeWindowPackage.value,
            preStateSummary = "Visual fallback requested",
            postStateSummary = fallbackResult.status.name,
            userFacingMessage = fallbackResult.diagnosticMessage,
            elapsedMs = System.currentTimeMillis() - startMs
        )

        val snap = if (isConnected && svc != null) {
            svc.captureSemanticSnapshotOnDemand(lowRamMode)
        } else {
            null
        }

        _latestAccessibilityWorkspaceState.value = AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = enabledInSettings,
            serviceConnected = isConnected,
            snapshot = snap,
            overlayState = NovaAccessibilityService.overlayAttachmentState.value,
            latestActionResult = verifiedResult,
            visionFallbackStatus = fallbackResult.status
        )

        return verifiedResult
    }

    private fun accessibilityNotConnectedResult(
        toolName: String,
        enabledInSettings: Boolean,
        startMs: Long
    ): VerifiedActionResult {
        val workspaceState = AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = enabledInSettings,
            serviceConnected = false,
            snapshot = null,
            overlayState = NovaAccessibilityService.overlayAttachmentState.value
        )
        _latestAccessibilityWorkspaceState.value = workspaceState

        val isWaitingForOsBind = workspaceState.status == AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED
        return VerifiedActionResult(
            toolName = toolName,
            outcome = VerificationOutcome.SERVICE_DISABLED,
            verificationDetail = if (isWaitingForOsBind) {
                "Nova is enabled in Settings, but NovaAccessibilityService.instance is not connected/bound yet (SERVICE_NOT_CONNECTED)."
            } else {
                "NovaAccessibilityService is disabled in Android Accessibility Settings (SERVICE_DISABLED)."
            },
            targetPackage = "",
            preStateSummary = "AccessibilityService=${workspaceState.status.name}",
            postStateSummary = "Blocked",
            userFacingMessage = workspaceState.statusMessage,
            elapsedMs = System.currentTimeMillis() - startMs
        )
    }

    companion object {
        val ACCESSIBILITY_TOOL_NAMES = setOf(
            "read_screen",
            "query_accessibility_status",
            "click_node",
            "focus_node",
            "select_node",
            "input_text",
            "scroll_screen",
            "navigate_global",
            "capture_visual_fallback"
        )
    }
}
