package com.example.nova.security

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.core.SystemHealthSnapshot

/**
 * Queries real Android OS services, hardware managers, and permission registries.
 * Never returns fabricated or placeholder values.
 */
object PermissionAuditor {

    fun isRecordAudioGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isPostNotificationsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
        val expectedComponent = ComponentName(context, NovaAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = enabledServicesSetting.split(':')
        for (componentString in colonSplitter) {
            val enabledComponent = ComponentName.unflattenFromString(componentString)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?: emptyList()
        return enabledList.any {
            it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                it.resolveInfo?.serviceInfo?.name == NovaAccessibilityService::class.java.name
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    fun isSpeechRecognitionAvailable(context: Context): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
    }

    fun isKeyConfigured(rawKey: String?): Boolean {
        if (rawKey.isNullOrBlank()) return false
        val trimmed = rawKey.trim()
        return trimmed != "MY_GEMINI_API_KEY" &&
            trimmed != "MY_GROQ_API_KEY" &&
            trimmed != "MY_OPENAI_API_KEY" &&
            !trimmed.startsWith("YOUR_") &&
            trimmed.length >= 10
    }

    fun hasFlashlightHardware(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return false
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return try {
            cameraManager.cameraIdList.any { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isNetworkOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun captureSystemHealthSnapshot(
        context: Context,
        lowRamModeUserOverride: Boolean?,
        isTorchCurrentlyOn: Boolean
    ): SystemHealthSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val totalRamMb = if (memInfo.totalMem > 0) memInfo.totalMem / (1024 * 1024) else 0L
        val availRamMb = if (memInfo.availMem > 0) memInfo.availMem / (1024 * 1024) else 0L
        val isSystemLowRam = (activityManager?.isLowRamDevice == true) || (totalRamMb in 1..4096)
        val effectiveLowRam = lowRamModeUserOverride ?: isSystemLowRam

        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val geminiConfigured = isKeyConfigured(
            runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()
        ) || ProviderSecretVault.isProviderSecretConfigured(context, com.example.nova.core.AiProviderType.GEMINI)
        val groqConfigured = isKeyConfigured(
            runCatching { BuildConfig.GROQ_API_KEY }.getOrNull()
        ) || ProviderSecretVault.isProviderSecretConfigured(context, com.example.nova.core.AiProviderType.GROQ)
        val openAiConfigured = isKeyConfigured(
            runCatching { BuildConfig.OPENAI_API_KEY }.getOrNull()
        ) || ProviderSecretVault.isProviderSecretConfigured(context, com.example.nova.core.AiProviderType.OPENAI_COMPATIBLE)

        return SystemHealthSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            isSystemLowRamDevice = isSystemLowRam,
            effectiveLowRamMode = effectiveLowRam,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            isNetworkOnline = isNetworkOnline(context),
            hasFlashlightHardware = hasFlashlightHardware(context),
            isTorchCurrentlyOn = isTorchCurrentlyOn,
            recordAudioGranted = isRecordAudioGranted(context),
            postNotificationsGranted = isPostNotificationsGranted(context),
            speechRecognitionAvailable = isSpeechRecognitionAvailable(context),
            accessibilityServiceEnabled = isAccessibilityServiceEnabledInSettings(context),
            accessibilityServiceConnected = NovaAccessibilityService.isServiceConnected.value,
            notificationListenerEnabled = isNotificationListenerEnabled(context),
            geminiKeyConfigured = geminiConfigured,
            groqKeyConfigured = groqConfigured,
            openAiKeyConfigured = openAiConfigured
        )
    }

    fun hasMicrophoneHardware(context: Context): Boolean {
        return try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        } catch (_: Exception) {
            false
        }
    }

    fun isTextToSpeechEngineInstalled(context: Context): Boolean {
        return try {
            val checkIntent = Intent(android.speech.tts.TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val activities = context.packageManager.queryIntentActivities(checkIntent, 0)
            activities.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun isReadMediaImagesGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isVisualUserSelectedMediaGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    /**
     * M8 Section E — Real Android permission audit for Nova.
     * Never treats AndroidManifest.xml declaration as equivalent to runtime grant.
     */
    fun auditNovaPermissions(
        context: Context,
        hasRequestedPermission: (String) -> Boolean = { false }
    ): List<com.example.nova.core.PermissionAuditItemState> {
        val sdkInt = Build.VERSION.SDK_INT
        val mediaPermKey = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return auditNovaPermissions(
            sdkInt = sdkInt,
            hasMicrophoneHardware = hasMicrophoneHardware(context),
            recordAudioGranted = isRecordAudioGranted(context),
            recordAudioPreviouslyRequested = hasRequestedPermission(Manifest.permission.RECORD_AUDIO),
            postNotificationsGranted = isPostNotificationsGranted(context),
            postNotificationsPreviouslyRequested = hasRequestedPermission(Manifest.permission.POST_NOTIFICATIONS),
            notificationsEnabledAtOsLevel = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            readMediaImagesGranted = isReadMediaImagesGranted(context),
            readMediaVisualUserSelectedGranted = isVisualUserSelectedMediaGranted(context),
            readMediaPreviouslyRequested = hasRequestedPermission(mediaPermKey),
            accessibilityServiceEnabledInSettings = isAccessibilityServiceEnabledInSettings(context),
            accessibilityServiceConnected = NovaAccessibilityService.isServiceConnected.value,
            notificationListenerEnabled = isNotificationListenerEnabled(context)
        )
    }

    fun auditNovaPermissions(
        sdkInt: Int,
        hasMicrophoneHardware: Boolean,
        recordAudioGranted: Boolean,
        recordAudioPreviouslyRequested: Boolean,
        postNotificationsGranted: Boolean,
        postNotificationsPreviouslyRequested: Boolean,
        notificationsEnabledAtOsLevel: Boolean,
        readMediaImagesGranted: Boolean,
        readMediaVisualUserSelectedGranted: Boolean,
        readMediaPreviouslyRequested: Boolean,
        accessibilityServiceEnabledInSettings: Boolean,
        accessibilityServiceConnected: Boolean,
        notificationListenerEnabled: Boolean
    ): List<com.example.nova.core.PermissionAuditItemState> {
        // 1. Microphone (RECORD_AUDIO)
        val (micStatus, micDetail) = when {
            !hasMicrophoneHardware ->
                com.example.nova.core.NovaPermissionStatus.UNAVAILABLE to
                    "Unavailable — Device does not report microphone hardware (FEATURE_MICROPHONE)."
            recordAudioGranted ->
                com.example.nova.core.NovaPermissionStatus.GRANTED to
                    "Granted — Real-time SpeechRecognizer voice input is permitted."
            recordAudioPreviouslyRequested ->
                com.example.nova.core.NovaPermissionStatus.DENIED to
                    "Denied — Microphone access was denied. Request again or grant in App Settings."
            else ->
                com.example.nova.core.NovaPermissionStatus.NOT_REQUESTED to
                    "Not requested yet — Declared in manifest, but runtime grant is required before listening."
        }

        // 2. Notifications (POST_NOTIFICATIONS)
        val (notifStatus, notifDetail) = when {
            sdkInt < Build.VERSION_CODES.TIRAMISU -> {
                if (notificationsEnabledAtOsLevel) {
                    com.example.nova.core.NovaPermissionStatus.NOT_APPLICABLE to
                        "Not applicable on Android < 13 (API $sdkInt) — Notifications are enabled without runtime prompt."
                } else {
                    com.example.nova.core.NovaPermissionStatus.RESTRICTED to
                        "Restricted in OS Settings on Android < 13 (API $sdkInt)."
                }
            }
            postNotificationsGranted ->
                com.example.nova.core.NovaPermissionStatus.GRANTED to
                    "Granted — Runtime POST_NOTIFICATIONS permission granted."
            postNotificationsPreviouslyRequested ->
                com.example.nova.core.NovaPermissionStatus.DENIED to
                    "Denied — Runtime POST_NOTIFICATIONS permission denied."
            else ->
                com.example.nova.core.NovaPermissionStatus.NOT_REQUESTED to
                    "Not requested yet — Required on Android 13+ for reminder alerts."
        }

        // 3. Photos & Media (READ_MEDIA_IMAGES / READ_MEDIA_VISUAL_USER_SELECTED / Photo Picker)
        val mediaAndroidName = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val (photoStatus, photoDetail) = when {
            readMediaImagesGranted ->
                com.example.nova.core.NovaPermissionStatus.GRANTED to
                    "Granted — Full MediaStore photo query access is granted."
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && readMediaVisualUserSelectedGranted ->
                com.example.nova.core.NovaPermissionStatus.RESTRICTED to
                    "Restricted (Selective Access) — User granted access to selected photos only (READ_MEDIA_VISUAL_USER_SELECTED)."
            readMediaPreviouslyRequested ->
                com.example.nova.core.NovaPermissionStatus.DENIED to
                    "Denied — MediaStore permission denied; zero-permission Android Photo Picker remains available."
            else ->
                com.example.nova.core.NovaPermissionStatus.NOT_REQUESTED to
                    "Not requested — Zero-permission Android Photo Picker or MediaStore grant can be used."
        }

        // 4. Legacy External Storage (READ_EXTERNAL_STORAGE) — demonstrates API-level NOT_APPLICABLE on Android 13+
        val (legacyStorageStatus, legacyStorageDetail) = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            com.example.nova.core.NovaPermissionStatus.NOT_APPLICABLE to
                "Not applicable on Android 13+ (API $sdkInt) — Replaced by granular media permissions and Photo Picker."
        } else if (readMediaImagesGranted) {
            com.example.nova.core.NovaPermissionStatus.GRANTED to
                "Granted on Android ≤ 12 (API $sdkInt)."
        } else if (readMediaPreviouslyRequested) {
            com.example.nova.core.NovaPermissionStatus.DENIED to
                "Denied on Android ≤ 12 (API $sdkInt)."
        } else {
            com.example.nova.core.NovaPermissionStatus.NOT_REQUESTED to
                "Not requested yet on Android ≤ 12 (API $sdkInt)."
        }

        // 5. Accessibility Service capability (NovaAccessibilityService)
        val (a11yStatus, a11yDetail) = when {
            accessibilityServiceConnected ->
                com.example.nova.core.NovaPermissionStatus.GRANTED to
                    "Granted & Connected — Enabled in Settings and actively bound by Android OS."
            accessibilityServiceEnabledInSettings ->
                com.example.nova.core.NovaPermissionStatus.RESTRICTED to
                    "Enabled in Settings, Not Connected — Toggled on in Settings, but OS has not bound the service yet."
            else ->
                com.example.nova.core.NovaPermissionStatus.DENIED to
                    "Disabled — Must be enabled by the user in Android Accessibility Settings."
        }

        // 6. Notification Listener capability (NovaNotificationService)
        val (notifListenerStatus, notifListenerDetail) = if (notificationListenerEnabled) {
            com.example.nova.core.NovaPermissionStatus.GRANTED to
                "Granted — Enabled in Android Notification Listener Settings."
        } else {
            com.example.nova.core.NovaPermissionStatus.DENIED to
                "Disabled — Optional; enable in Android Notification Listener Settings to read status bar notifications."
        }

        return listOf(
            com.example.nova.core.PermissionAuditItemState(
                permissionId = "microphone",
                title = "Microphone (Speech Input)",
                androidPermissionName = Manifest.permission.RECORD_AUDIO,
                status = micStatus,
                statusDetail = micDetail,
                canRequestInApp = hasMicrophoneHardware && !recordAudioGranted,
                opensSystemSettings = true
            ),
            com.example.nova.core.PermissionAuditItemState(
                permissionId = "notifications",
                title = "Notifications (Reminders & Alerts)",
                androidPermissionName = Manifest.permission.POST_NOTIFICATIONS,
                status = notifStatus,
                statusDetail = notifDetail,
                canRequestInApp = sdkInt >= Build.VERSION_CODES.TIRAMISU && !postNotificationsGranted,
                opensSystemSettings = true
            ),
            com.example.nova.core.PermissionAuditItemState(
                permissionId = "photos_media",
                title = "Photos & Media (MediaStore / Picker)",
                androidPermissionName = mediaAndroidName,
                status = photoStatus,
                statusDetail = photoDetail,
                canRequestInApp = !readMediaImagesGranted,
                opensSystemSettings = true
            ),
            com.example.nova.core.PermissionAuditItemState(
                permissionId = "legacy_external_storage",
                title = "Legacy External Storage (Android ≤ 12)",
                androidPermissionName = "android.permission.READ_EXTERNAL_STORAGE",
                status = legacyStorageStatus,
                statusDetail = legacyStorageDetail,
                canRequestInApp = sdkInt < Build.VERSION_CODES.TIRAMISU && !readMediaImagesGranted,
                opensSystemSettings = sdkInt < Build.VERSION_CODES.TIRAMISU
            ),
            com.example.nova.core.PermissionAuditItemState(
                permissionId = "accessibility_service",
                title = "Accessibility Service (Screen Automation)",
                androidPermissionName = Settings.ACTION_ACCESSIBILITY_SETTINGS,
                status = a11yStatus,
                statusDetail = a11yDetail,
                canRequestInApp = false,
                opensSystemSettings = true
            ),
            com.example.nova.core.PermissionAuditItemState(
                permissionId = "notification_listener",
                title = "Notification Listener (Active Notifications)",
                androidPermissionName = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                status = notifListenerStatus,
                statusDetail = notifListenerDetail,
                canRequestInApp = false,
                opensSystemSettings = true
            )
        )
    }
}
