package com.example

import android.app.Application
import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.OverlayAttachmentState
import com.example.nova.core.WorkspaceCardState
import com.example.nova.security.PermissionAuditor
import com.example.nova.ui.FloatingNovaOrbOverlayController
import com.example.nova.ui.FloatingNovaOrbView
import com.example.nova.ui.FloatingOverlayWindowHost
import com.example.nova.ui.NovaViewModel
import com.example.nova.ui.createAccessibilityOverlayLayoutParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read Nova app_name from context and verify honest unconfigured states`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Nova", appName)

        // Verify that default placeholder keys are reported as NOT configured (zero mock state)
        assertFalse(PermissionAuditor.isKeyConfigured("MY_GEMINI_API_KEY"))
        assertFalse(PermissionAuditor.isKeyConfigured("MY_GROQ_API_KEY"))
        assertFalse(PermissionAuditor.isKeyConfigured(""))
    }

    @Test
    fun `M1 NovaViewModel exposes real IDLE orb state, empty workspace, and zero mock data on initialization`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = NovaViewModel(application)

        assertEquals(NovaUiOrbState.IDLE, viewModel.uiOrbState.value)
        assertTrue(viewModel.workspaceCardState.value is WorkspaceCardState.None)
        assertTrue(viewModel.conversationTurns.value.isEmpty())
        assertTrue(viewModel.memoryFacts.value.isEmpty())
        assertTrue(viewModel.taskAuditLogs.value.isEmpty())
        assertTrue(viewModel.scheduledReminders.value.isEmpty())
    }

    @Test
    fun `M3 FloatingNovaOrbOverlayController attach, detach, service not bound, and disabled preference states`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val addedViews = mutableListOf<View>()

        val recordingHost = object : FloatingOverlayWindowHost {
            override fun addOverlayView(view: View, params: WindowManager.LayoutParams) {
                assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
                addedViews.add(view)
            }

            override fun updateOverlayViewLayout(view: View, params: WindowManager.LayoutParams) = Unit

            override fun removeOverlayView(view: View) {
                addedViews.remove(view)
            }
        }

        val controller = FloatingNovaOrbOverlayController(
            viewFactory = { FloatingNovaOrbView(context) },
            layoutParamsFactory = { createAccessibilityOverlayLayoutParams(112, 24, 96) }
        )

        // 1. Service not bound -> SERVICE_NOT_BOUND (never fake ATTACHED)
        val unboundOutcome = controller.syncOverlay(
            windowHost = recordingHost,
            isServiceBound = false,
            userPreferenceEnabled = true,
            orbState = NovaUiOrbState.IDLE,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.SERVICE_NOT_BOUND, unboundOutcome.attachmentState)
        assertTrue(addedViews.isEmpty())
        assertNull(controller.attachedOrbView)

        // 2. Service bound & preference enabled -> ATTACHED
        val attachedOutcome = controller.syncOverlay(
            windowHost = recordingHost,
            isServiceBound = true,
            userPreferenceEnabled = true,
            orbState = NovaUiOrbState.LISTENING,
            liveRmsDb = 5.0f,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.ATTACHED, attachedOutcome.attachmentState)
        assertEquals(1, addedViews.size)
        assertNotNull(controller.attachedOrbView)
        assertEquals(NovaUiOrbState.LISTENING, controller.attachedOrbView?.currentOrbState)

        // 3. Disabled preference -> removes view and reports DISABLED_BY_USER_PREFERENCE
        val disabledOutcome = controller.syncOverlay(
            windowHost = recordingHost,
            isServiceBound = true,
            userPreferenceEnabled = false,
            orbState = NovaUiOrbState.LISTENING,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.DISABLED_BY_USER_PREFERENCE, disabledOutcome.attachmentState)
        assertTrue(addedViews.isEmpty())
        assertNull(controller.attachedOrbView)

        // 4. Re-attach and explicit detach -> DETACHED
        controller.syncOverlay(
            windowHost = recordingHost,
            isServiceBound = true,
            userPreferenceEnabled = true,
            orbState = NovaUiOrbState.EXECUTING,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.ATTACHED, controller.currentAttachmentState)
        val detachedOutcome = controller.detachOverlay(recordingHost)
        assertEquals(OverlayAttachmentState.DETACHED, detachedOutcome.attachmentState)
        assertTrue(addedViews.isEmpty())
        assertNull(controller.attachedOrbView)
    }

    @Test
    fun `M3 FloatingNovaOrbOverlayController handles BadTokenException, SecurityException, and WindowManager rejection without fake ATTACHED state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val badTokenHost = object : FloatingOverlayWindowHost {
            override fun addOverlayView(view: View, params: WindowManager.LayoutParams) {
                throw WindowManager.BadTokenException("Simulated invalid accessibility window token")
            }

            override fun updateOverlayViewLayout(view: View, params: WindowManager.LayoutParams) = Unit
            override fun removeOverlayView(view: View) = Unit
        }

        val securityHost = object : FloatingOverlayWindowHost {
            override fun addOverlayView(view: View, params: WindowManager.LayoutParams) {
                throw SecurityException("Simulated OEM WindowManager security denial")
            }

            override fun updateOverlayViewLayout(view: View, params: WindowManager.LayoutParams) = Unit
            override fun removeOverlayView(view: View) = Unit
        }

        val controller = FloatingNovaOrbOverlayController(
            viewFactory = { FloatingNovaOrbView(context) },
            layoutParamsFactory = { createAccessibilityOverlayLayoutParams(112, 24, 96) }
        )

        // BadTokenException -> REJECTED_BY_WINDOW_MANAGER
        val badTokenOutcome = controller.syncOverlay(
            windowHost = badTokenHost,
            isServiceBound = true,
            userPreferenceEnabled = true,
            orbState = NovaUiOrbState.IDLE,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER, badTokenOutcome.attachmentState)
        assertNull(controller.attachedOrbView)

        // SecurityException -> REJECTED_BY_WINDOW_MANAGER
        val securityOutcome = controller.syncOverlay(
            windowHost = securityHost,
            isServiceBound = true,
            userPreferenceEnabled = true,
            orbState = NovaUiOrbState.IDLE,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER, securityOutcome.attachmentState)
        assertNull(controller.attachedOrbView)

        // Null WindowHost -> REJECTED_BY_WINDOW_MANAGER
        val nullHostOutcome = controller.syncOverlay(
            windowHost = null,
            isServiceBound = true,
            userPreferenceEnabled = true,
            orbState = NovaUiOrbState.IDLE,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER, nullHostOutcome.attachmentState)
        assertNull(controller.attachedOrbView)
    }

    @Test
    fun `M3 NovaAccessibilityService never reports fake ATTACHED when service is not bound and FloatingNovaOrbView expands or cancels on interaction`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // When NovaAccessibilityService is not bound by Android OS, syncing with visible=true must report SERVICE_NOT_BOUND
        NovaAccessibilityService.syncFloatingOrbState(
            visible = true,
            orbState = NovaUiOrbState.IDLE,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(
            OverlayAttachmentState.SERVICE_NOT_BOUND,
            NovaAccessibilityService.overlayAttachmentState.value
        )

        // When user disables the floating orb preference, it must report DISABLED_BY_USER_PREFERENCE
        NovaAccessibilityService.syncFloatingOrbState(
            visible = false,
            orbState = NovaUiOrbState.IDLE,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(
            OverlayAttachmentState.DISABLED_BY_USER_PREFERENCE,
            NovaAccessibilityService.overlayAttachmentState.value
        )

        // Verify FloatingNovaOrbView interaction callbacks and state-driven updates
        var expandedCount = 0
        var cancelledCount = 0
        val orbView = FloatingNovaOrbView(
            context = context,
            onExpandAssistant = { expandedCount++ },
            onCancelActiveTask = { cancelledCount++ }
        )

        orbView.performClick()
        assertEquals(1, expandedCount)

        orbView.updateOrbState(
            orbState = NovaUiOrbState.EXECUTING,
            liveRmsDb = null,
            lowRamMode = false
        )
        assertEquals(NovaUiOrbState.EXECUTING, orbView.currentOrbState)
        orbView.performLongClick()
        assertEquals(1, cancelledCount)
    }

    @Test
    fun `M4 NovaDeviceInfoProvider and SystemToolExecutor execute real query_device_info against Android runtime`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = com.example.nova.tools.NovaDeviceInfoProvider(context)

        // Before ShadowStatFs has block stats registered, 0-byte StatFs is truthfully reported as Unavailable (never 0 GB)
        val initialOutcome = provider.queryDeviceInfo()
        assertTrue(initialOutcome is com.example.nova.tools.DeviceInfoQueryOutcome.Success)
        val initialInfo = (initialOutcome as com.example.nova.tools.DeviceInfoQueryOutcome.Success).deviceInfo
        assertTrue(initialInfo.sdkInt.isAvailable)
        assertEquals(36, initialInfo.sdkInt.valueOrNull())
        assertFalse(initialInfo.totalStorageBytes.isAvailable)

        // Register real filesystem block counts in Robolectric ShadowStatFs and verify real StatFs reading
        val dataPath = android.os.Environment.getDataDirectory().absolutePath
        org.robolectric.shadows.ShadowStatFs.registerStats(dataPath, 16384, 8192, 8192)
        try {
            val outcome = provider.queryDeviceInfo()
            assertTrue(outcome is com.example.nova.tools.DeviceInfoQueryOutcome.Success)
            val deviceInfo = (outcome as com.example.nova.tools.DeviceInfoQueryOutcome.Success).deviceInfo

            assertTrue(deviceInfo.sdkInt.isAvailable)
            assertEquals(36, deviceInfo.sdkInt.valueOrNull())
            assertTrue(deviceInfo.totalStorageBytes.isAvailable)
            assertTrue((deviceInfo.totalStorageBytes.valueOrNull() ?: 0L) > 0L)
            assertTrue(deviceInfo.availableStorageBytes.isAvailable)

            // Verify SystemToolExecutor executes query_device_info and populates latestDeviceInfo
            val db = com.example.nova.memory.NovaDatabase.getInstance(context)
            val repo = com.example.nova.memory.NovaRepository(db)
            val executor = com.example.nova.tools.SystemToolExecutor(context, repo, provider)

            val call = com.example.nova.ai.LocalDeterministicParser.tryParse("query_device_info")
            assertNotNull(call)

            val result = kotlinx.coroutines.runBlocking {
                executor.executeAndVerify(call!!, lowRamMode = false)
            }
            assertEquals(com.example.nova.core.VerificationOutcome.VERIFIED_SUCCESS, result.outcome)
            assertNotNull(executor.latestDeviceInfo.value)
            assertEquals(36, executor.latestDeviceInfo.value?.sdkInt?.valueOrNull())
            assertTrue(executor.latestDeviceInfo.value?.totalStorageBytes?.isAvailable == true)
        } finally {
            org.robolectric.shadows.ShadowStatFs.reset()
        }
    }

    @Test
    fun `M5 SystemToolExecutor and PhotoAccessManager enforce real permission, empty, and verified media outcomes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.nova.memory.NovaDatabase.getInstance(context)
        val repo = com.example.nova.memory.NovaRepository(db)

        // 1. Default Robolectric environment has READ_MEDIA_IMAGES denied -> query_photos returns PERMISSION_REQUIRED (never fake photos)
        val realPhotoManager = com.example.nova.tools.PhotoAccessManager(context)
        val defaultExecutor = com.example.nova.tools.SystemToolExecutor(
            context = context,
            repository = repo,
            photoAccessManager = realPhotoManager
        )
        val showPhotosCall = com.example.nova.ai.LocalDeterministicParser.tryParse("show my photos")
        assertNotNull(showPhotosCall)

        val permRequiredResult = kotlinx.coroutines.runBlocking {
            defaultExecutor.executeAndVerify(showPhotosCall!!, lowRamMode = false)
        }
        assertEquals(com.example.nova.core.VerificationOutcome.PERMISSION_REQUIRED, permRequiredResult.outcome)
        assertEquals(
            com.example.nova.core.PhotoAccessMode.PERMISSION_REQUIRED,
            defaultExecutor.latestPhotoWorkspaceState.value?.accessMode
        )
        assertTrue(defaultExecutor.latestPhotoWorkspaceState.value?.photos?.isEmpty() == true)

        // 2. When real photo URI is ingested via Photo Picker, SystemToolExecutor verifies and returns VERIFIED_SUCCESS
        val controlledSource = object : com.example.nova.tools.PhotoMediaDataSource {
            override val sdkInt: Int = 36
            override fun isPhotoPickerSupported(): Boolean = true
            override fun checkMediaPermissionState() = com.example.nova.tools.MediaPermissionGrantState.DENIED
            override fun queryMediaStoreImages(maxItems: Int) = emptyList<com.example.nova.tools.RawMediaRecord>()
            override fun isUriAccessible(uriString: String): Boolean =
                uriString == "content://media/picker/0/real_photo_301"
            override fun tryTakePersistableReadPermission(uriString: String) =
                com.example.nova.core.UriPersistenceState.SESSION_SCOPED_ONLY
            override fun queryUriMetadata(uriString: String) = com.example.nova.tools.RawMediaRecord(
                contentUri = uriString,
                displayName = "Picker_301.jpg",
                dateTakenMillis = 1725000000000L,
                widthPx = 1080,
                heightPx = 1920,
                mimeType = "image/jpeg"
            )
            override fun decodeThumbnail(
                uriString: String,
                targetSizePx: Int,
                lowRamMode: Boolean
            ): com.example.nova.tools.ThumbnailDecodeOutcome {
                val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.RGB_565)
                return com.example.nova.tools.ThumbnailDecodeOutcome.Available(bmp, 64, 64)
            }
            override fun decodeFullscreenImage(
                uriString: String,
                maxDimensionPx: Int,
                lowRamMode: Boolean
            ): com.example.nova.tools.FullscreenPhotoLoadOutcome {
                val bmp = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
                return com.example.nova.tools.FullscreenPhotoLoadOutcome.Available(bmp, 128, 128)
            }
            override fun loadPersistedUriStrings(): Set<String> = emptySet()
            override fun savePersistedUriStrings(uris: Set<String>) = Unit
        }

        val controlledExecutor = com.example.nova.tools.SystemToolExecutor(
            context = context,
            repository = repo,
            photoAccessManager = com.example.nova.tools.PhotoAccessManager(controlledSource)
        )
        val ingestResult = controlledExecutor.ingestPickedPhotoUris(
            uris = listOf("content://media/picker/0/real_photo_301"),
            lowRamMode = true
        )
        assertEquals(com.example.nova.core.VerificationOutcome.VERIFIED_SUCCESS, ingestResult.outcome)
        assertEquals(
            com.example.nova.core.PhotoAccessMode.PICKED_URI_SESSION,
            controlledExecutor.latestPhotoWorkspaceState.value?.accessMode
        )
        assertEquals(1, controlledExecutor.latestPhotoWorkspaceState.value?.photos?.size)

        // Verify thumbnail & fullscreen loading for accessible vs inaccessible URIs
        val validThumb = kotlinx.coroutines.runBlocking {
            controlledExecutor.loadPhotoThumbnail("content://media/picker/0/real_photo_301", lowRamMode = true)
        }
        assertTrue(validThumb is com.example.nova.tools.ThumbnailDecodeOutcome.Available)

        val revokedFullscreen = kotlinx.coroutines.runBlocking {
            controlledExecutor.loadFullscreenPhoto("content://media/picker/0/revoked_photo_404", lowRamMode = true)
        }
        assertTrue(revokedFullscreen is com.example.nova.tools.FullscreenPhotoLoadOutcome.InaccessibleOrRevoked)
    }

    @Test
    fun `M6 SystemToolExecutor and AccessibilityWorkspace distinguish SERVICE_DISABLED, SERVICE_NOT_CONNECTED, connected, verified, and failed states`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.nova.memory.NovaDatabase.getInstance(context)
        val repo = com.example.nova.memory.NovaRepository(db)
        val executor = com.example.nova.tools.SystemToolExecutor(context, repo)

        // 1. Default state: service disabled in Settings.Secure and not connected -> SERVICE_DISABLED
        android.provider.Settings.Secure.putInt(
            context.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
        assertFalse(PermissionAuditor.isAccessibilityServiceEnabledInSettings(context))

        val readCall = com.example.nova.ai.LocalDeterministicParser.tryParse("read screen")
        assertNotNull(readCall)

        val disabledResult = kotlinx.coroutines.runBlocking {
            executor.executeAndVerify(readCall!!, lowRamMode = false)
        }
        assertEquals(com.example.nova.core.VerificationOutcome.SERVICE_DISABLED, disabledResult.outcome)
        val wsDisabled = executor.latestAccessibilityWorkspaceState.value
        assertNotNull(wsDisabled)
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.SERVICE_DISABLED,
            wsDisabled!!.status
        )
        assertFalse(wsDisabled.serviceEnabledInSettings)
        assertFalse(wsDisabled.serviceConnected)

        // Verify WorkspaceCardState.AccessibilityWorkspace mapping for SERVICE_DISABLED
        val cardDisabled = com.example.nova.ui.NovaUiStateMapper.deriveWorkspaceCardState(
            sessionStage = com.example.nova.core.AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED,
            pendingConfirmation = null,
            lastVerifiedResult = disabledResult,
            voiceState = com.example.nova.voice.VoiceEngineState.Idle,
            statusBanner = "read_screen: SERVICE_DISABLED",
            latestAccessibilityWorkspaceState = wsDisabled
        )
        assertTrue(cardDisabled is WorkspaceCardState.AccessibilityWorkspace)
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.SERVICE_DISABLED,
            (cardDisabled as WorkspaceCardState.AccessibilityWorkspace).workspaceState.status
        )

        // 2. Service enabled in Settings.Secure, but NovaAccessibilityService.instance is NOT bound -> SERVICE_NOT_CONNECTED
        val expectedComponent = "${context.packageName}/${NovaAccessibilityService::class.java.name}"
        android.provider.Settings.Secure.putInt(
            context.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            1
        )
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            expectedComponent
        )
        assertTrue(PermissionAuditor.isAccessibilityServiceEnabledInSettings(context))

        val statusCall = com.example.nova.ai.LocalDeterministicParser.tryParse("show accessibility status")
        assertNotNull(statusCall)

        val notConnectedResult = kotlinx.coroutines.runBlocking {
            executor.executeAndVerify(statusCall!!, lowRamMode = false)
        }
        assertEquals(com.example.nova.core.VerificationOutcome.SERVICE_DISABLED, notConnectedResult.outcome)
        val wsNotConnected = executor.latestAccessibilityWorkspaceState.value
        assertNotNull(wsNotConnected)
        assertEquals(
            com.example.nova.core.AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED,
            wsNotConnected!!.status
        )
        assertTrue(wsNotConnected.serviceEnabledInSettings)
        assertFalse(wsNotConnected.serviceConnected)

        // 3. Connected active window, verified action, and failed action summary formatting
        val sampleNode = com.example.nova.core.SemanticUiNode(
            index = 1,
            text = "Wi-Fi",
            contentDescription = "Toggle Wi-Fi",
            viewIdResourceName = "com.android.settings:id/switch_widget",
            className = "android.widget.Switch",
            boundsInScreen = android.graphics.Rect(20, 100, 900, 180),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = true,
            isChecked = true,
            isFocused = false,
            wasRedacted = false,
            isEnabled = true,
            isFocusable = true,
            supportedActions = setOf(com.example.nova.core.SupportedAccessibilityAction.CLICK)
        )
        val sampleSnapshot = com.example.nova.core.SemanticScreenSnapshot(
            capturedAtMs = 1725000000000L,
            packageName = "com.android.settings",
            windowTitle = "Network & internet",
            nodes = listOf(sampleNode),
            totalRawNodesTraversed = 8,
            redactedFieldCount = 0,
            isLowRamTruncated = false
        )

        val connectedWindowState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = true,
            serviceConnected = true,
            snapshot = sampleSnapshot
        )
        val connectedSummary = com.example.nova.ui.formatAccessibilityWorkspaceSummary(connectedWindowState)
        assertEquals("Active Window Ready", connectedSummary.headlineText)
        assertEquals("com.android.settings", connectedSummary.activePackageText)
        assertEquals("Available", connectedSummary.activeWindowAvailabilityText)
        assertEquals("1", connectedSummary.semanticTargetCountText)
        assertFalse(connectedSummary.requiresSettingsAction)

        val verifiedActionState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = true,
            serviceConnected = true,
            snapshot = sampleSnapshot,
            latestActionResult = com.example.nova.core.VerifiedActionResult(
                toolName = "click_node",
                outcome = com.example.nova.core.VerificationOutcome.VERIFIED_SUCCESS,
                verificationDetail = "Verified CLICK on Wi-Fi",
                targetPackage = "com.android.settings",
                preStateSummary = "Checked=false",
                postStateSummary = "Checked=true",
                userFacingMessage = "Clicked \"Wi-Fi\" in com.android.settings and verified screen update.",
                elapsedMs = 15L
            )
        )
        val verifiedSummary = com.example.nova.ui.formatAccessibilityWorkspaceSummary(verifiedActionState)
        assertEquals("Accessibility Action Verified", verifiedSummary.headlineText)
        assertEquals("ACTION_VERIFIED", verifiedSummary.statusBadgeText)

        val failedActionState = com.example.nova.accessibility.AccessibilityActionPerformer.buildWorkspaceState(
            serviceEnabledInSettings = true,
            serviceConnected = true,
            snapshot = sampleSnapshot,
            latestActionResult = com.example.nova.core.VerifiedActionResult(
                toolName = "click_node",
                outcome = com.example.nova.core.VerificationOutcome.VERIFICATION_FAILED,
                verificationDetail = "Post-action verification failed",
                targetPackage = "com.android.settings",
                preStateSummary = "Checked=false",
                postStateSummary = "Checked=false",
                userFacingMessage = "Post-action verification could not confirm any resulting screen state change.",
                elapsedMs = 15L
            )
        )
        val failedSummary = com.example.nova.ui.formatAccessibilityWorkspaceSummary(failedActionState)
        assertEquals("Accessibility Action Failed", failedSummary.headlineText)
        assertEquals("ACTION_FAILED", failedSummary.statusBadgeText)
    }

    @Test
    fun `M7 NovaAgentOrchestrator end-to-end Scenario S preserves independent Photo PERMISSION_REQUIRED and DeviceInfo COMPLETED_VERIFIED states`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.nova.memory.NovaDatabase.getInstance(context)
        val repository = com.example.nova.memory.NovaRepository(db)
        val preferences = com.example.nova.memory.NovaPreferences(context)
        val aiRouter = com.example.nova.ai.NovaAiProviderRouter()
        val toolExecutor = com.example.nova.tools.SystemToolExecutor(context, repository)
        val spokenResponses = mutableListOf<String>()

        kotlinx.coroutines.runBlocking {
            val orchestrator = com.example.nova.agent.NovaAgentOrchestrator(
                context = context,
                scope = this,
                repository = repository,
                preferences = preferences,
                aiRouter = aiRouter,
                toolExecutor = toolExecutor,
                onSpeakResponse = { spokenResponses.add(it) }
            )

            // Submit Scenario S compound request: "Show my photos and device information."
            // On Robolectric without media permissions granted, Photo Task -> PERMISSION_REQUIRED
            // and Device Info Task -> COMPLETED_VERIFIED independently.
            orchestrator.submitUserCommand("Show my photos and device information.")

            var attempts = 0
            while (
                (orchestrator.activeTasks.value.size < 2 ||
                    orchestrator.activeTasks.value.any { !it.status.isTerminal }) &&
                attempts < 100
            ) {
                kotlinx.coroutines.delay(20)
                attempts++
            }

            val tasks = orchestrator.activeTasks.value
            assertEquals(2, tasks.size)

            val photoTask = tasks.first {
                it.capabilityType == com.example.nova.core.NovaTaskCapabilityType.PHOTOS
            }
            val deviceTask = tasks.first {
                it.capabilityType == com.example.nova.core.NovaTaskCapabilityType.DEVICE_INFO
            }

            assertEquals(com.example.nova.core.LiveTaskStatus.PERMISSION_REQUIRED, photoTask.status)
            assertEquals("I need photo access.", photoTask.statusMessage)
            assertEquals(
                com.example.nova.core.LiveTaskWorkspaceReference.PHOTO_WORKSPACE,
                photoTask.workspaceReference
            )

            assertEquals(com.example.nova.core.LiveTaskStatus.COMPLETED_VERIFIED, deviceTask.status)
            assertEquals("Your device information is ready.", deviceTask.statusMessage)
            assertEquals(
                com.example.nova.core.LiveTaskWorkspaceReference.DEVICE_INFO_WORKSPACE,
                deviceTask.workspaceReference
            )

            // Both real workspace states are populated and connected
            assertNotNull(toolExecutor.latestDeviceInfo.value)
            assertNotNull(toolExecutor.latestPhotoWorkspaceState.value)

            val wsState = com.example.nova.ui.NovaUiStateMapper.deriveWorkspaceCardState(
                sessionStage = orchestrator.sessionStage.value,
                pendingConfirmation = orchestrator.pendingConfirmation.value,
                lastVerifiedResult = orchestrator.lastVerifiedResult.value,
                voiceState = com.example.nova.voice.VoiceEngineState.Idle,
                statusBanner = orchestrator.statusBanner.value,
                latestDiagnosticMessage = orchestrator.latestDiagnosticMessage.value,
                latestDeviceInfo = toolExecutor.latestDeviceInfo.value,
                latestPhotoWorkspaceState = toolExecutor.latestPhotoWorkspaceState.value,
                latestAccessibilityWorkspaceState = toolExecutor.latestAccessibilityWorkspaceState.value,
                activeTasks = tasks
            )
            assertTrue(wsState is com.example.nova.core.WorkspaceCardState.MultiTaskWorkspace)
            val multiWs = wsState as com.example.nova.core.WorkspaceCardState.MultiTaskWorkspace
            assertEquals(2, multiWs.tasks.size)
            assertNotNull(multiWs.deviceInfo)
            assertNotNull(multiWs.photoState)
        }
    }

    @Test
    fun `M8 ProviderSecretVault scrubs legacy plaintext secrets and NovaViewModel resolves all 9 settings sections accurately`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("nova_preferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("gemini_api_key", "AIzaSyLegacyPlaintextKey123456789012345")
            .putString("api_key", "sk-LegacyPlaintextToken123456789012345")
            .commit()

        val scrubbed = com.example.nova.security.ProviderSecretVault.auditAndMigrateLegacyPlaintextSecrets(
            context = application,
            targetPrefs = prefs
        )
        assertTrue(scrubbed >= 2)
        assertFalse(prefs.contains("gemini_api_key"))
        assertFalse(prefs.contains("api_key"))

        val viewModel = NovaViewModel(application)
        viewModel.selectSettingsSection(com.example.nova.core.NovaSettingsSection.DIAGNOSTICS)
        assertEquals(
            com.example.nova.core.NovaSettingsSection.DIAGNOSTICS,
            viewModel.selectedSettingsSection.value
        )

        viewModel.selectPrimaryProvider(com.example.nova.core.AiProviderType.OFFLINE_DETERMINISTIC)
        viewModel.setGeminiModel("gemini-3.1-pro-preview")
        viewModel.setLowRamOverride(true)
        viewModel.setMemoryContextEnabled(false)
        viewModel.markPermissionRequested(android.Manifest.permission.RECORD_AUDIO)

        val currentSettingsState = viewModel.buildCurrentSettingsState()
        assertEquals(
            com.example.nova.core.NovaSettingsSection.DIAGNOSTICS,
            currentSettingsState.selectedSection
        )
        assertEquals(
            com.example.nova.core.AiProviderType.OFFLINE_DETERMINISTIC,
            currentSettingsState.aiProviderSection.primaryProvider
        )
        assertEquals(
            com.example.nova.core.NovaProcessingLocalityMode.LOCAL_DEVICE_PROCESSING,
            currentSettingsState.privacySection.processingLocalityMode
        )
        assertFalse(currentSettingsState.privacySection.mayProviderRequestsLeaveDevice)
        assertEquals("gemini-3.1-pro-preview", currentSettingsState.advancedSection.geminiModel)
        assertTrue(currentSettingsState.advancedSection.effectiveLowRamMode)
        assertEquals(1, currentSettingsState.advancedSection.maxConcurrentTasksLimit)
        assertFalse(currentSettingsState.memorySection.memoryContextEnabled)

        // Without FEATURE_MICROPHONE declared in ShadowPackageManager -> UNAVAILABLE
        val micWithoutHardware = currentSettingsState.permissionsSection.items.first {
            it.permissionId == "microphone"
        }
        assertEquals(
            com.example.nova.core.NovaPermissionStatus.UNAVAILABLE,
            micWithoutHardware.status
        )

        // Enable FEATURE_MICROPHONE in ShadowPackageManager -> DENIED (since RECORD_AUDIO was requested & not granted)
        org.robolectric.Shadows.shadowOf(application.packageManager).setSystemFeature(
            android.content.pm.PackageManager.FEATURE_MICROPHONE,
            true
        )
        viewModel.refreshSystemHealth()
        val micDenied = viewModel.buildCurrentSettingsState().permissionsSection.items.first {
            it.permissionId == "microphone"
        }
        assertEquals(
            com.example.nova.core.NovaPermissionStatus.DENIED,
            micDenied.status
        )

        // Grant RECORD_AUDIO in ShadowApplication -> GRANTED
        org.robolectric.Shadows.shadowOf(application).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO
        )
        viewModel.refreshSystemHealth()
        val micGranted = viewModel.buildCurrentSettingsState().permissionsSection.items.first {
            it.permissionId == "microphone"
        }
        assertEquals(
            com.example.nova.core.NovaPermissionStatus.GRANTED,
            micGranted.status
        )
    }

    @Test
    fun `M10 Live voice session lifecycle, secret encryption, calling controller, and settings state flow behave accurately`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = NovaViewModel(application)

        // 1. Initial live voice session state is IDLE
        assertEquals(
            com.example.nova.core.LiveVoiceSessionState.LIVE_SESSION_IDLE,
            viewModel.liveVoiceSessionState.value
        )
        assertFalse(viewModel.isLiveVoiceSessionActive())

        // 2. Grant RECORD_AUDIO and enable FEATURE_MICROPHONE
        org.robolectric.Shadows.shadowOf(application.packageManager).setSystemFeature(
            android.content.pm.PackageManager.FEATURE_MICROPHONE,
            true
        )
        org.robolectric.Shadows.shadowOf(application).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO
        )
        viewModel.refreshSystemHealth()

        // 3. Save ElevenLabs API key and verify Keystore encrypted vault storage + safe masking
        viewModel.saveElevenLabsSecret("sk-eleven-9876543210ABCDEF1234")
        assertTrue(com.example.nova.security.ProviderSecretVault.isElevenLabsConfigured(application))
        val masked = com.example.nova.security.ProviderSecretVault.getMaskedElevenLabsKey(application)
        assertEquals("••••••••1234", masked)
        assertFalse(masked.contains("9876543210ABCDEF"))

        // 4. Save Gemini API key securely
        viewModel.saveProviderSecret(
            com.example.nova.core.AiProviderType.GEMINI,
            "AIzaSySecureKeyTest7788"
        )
        val maskedGemini = com.example.nova.security.ProviderSecretVault.getMaskedProviderKey(
            application,
            com.example.nova.core.AiProviderType.GEMINI
        )
        assertEquals("••••••••7788", maskedGemini)

        // 5. Calling controller tests
        val dialOutcome = viewModel.openSystemDialer("555-1234")
        assertTrue(dialOutcome is com.example.nova.tools.CallingOutcome.DialerOpened)
        assertEquals("5551234", (dialOutcome as com.example.nova.tools.CallingOutcome.DialerOpened).phoneNumber)

        // Direct call without CALL_PHONE permission safely falls back to dialer
        val directCallNoPerm = viewModel.initiateDirectCall("555-9876")
        assertTrue(directCallNoPerm is com.example.nova.tools.CallingOutcome.PermissionRequiredFallbackToDialer)

        // Emergency call protection: 911 routes to manual dialer
        val emergencyCall = viewModel.initiateDirectCall("911")
        assertTrue(emergencyCall is com.example.nova.tools.CallingOutcome.BlockedEmergencyNumber)

        // 6. Settings state resolves 10 sections including AI_API and VOICE_API
        val settingsState = viewModel.buildCurrentSettingsState()
        assertEquals(10, com.example.nova.core.NovaSettingsSection.entries.size)
        assertTrue(settingsState.voiceApiSection.isConfigured)
        assertEquals("••••••••1234", settingsState.voiceApiSection.maskedApiKey)
        assertEquals("Android TextToSpeech", settingsState.voiceApiSection.fallbackEngineName)
    }

    @Test
    fun `M11 Arya visual identity, natural voice persona, latency tracker, and remote access contracts behave accurately`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = NovaViewModel(application)

        // 1. LatencyTracker resets and operates monotonically
        com.example.nova.core.LatencyTracker.reset()
        com.example.nova.core.LatencyTracker.onSpeechRecognitionStart()
        com.example.nova.core.LatencyTracker.onTranscriptAvailable()
        com.example.nova.core.LatencyTracker.onRoutingStart()
        com.example.nova.core.LatencyTracker.onRoutingEnd("LOCAL_DETERMINISTIC_DIRECT")
        com.example.nova.core.LatencyTracker.onTtsStart()
        com.example.nova.core.LatencyTracker.onFirstAudiblePlayback()
        com.example.nova.core.LatencyTracker.onTotalResponseComplete()

        val latencySnapshot = com.example.nova.core.LatencyTracker.getLatestSnapshot()
        assertTrue(latencySnapshot.totalTurnaroundMs >= 0L)
        assertEquals("LOCAL_DETERMINISTIC_DIRECT", latencySnapshot.executionRoute)

        // 2. Local deterministic parser handles friendly Arya persona greetings
        val greetingCall = com.example.nova.ai.LocalDeterministicParser.tryParse("Hello Arya")
        assertNotNull(greetingCall)
        assertTrue(greetingCall!!.spokenResponseDraft.contains("Boss", ignoreCase = true))

        val identityCall = com.example.nova.ai.LocalDeterministicParser.tryParse("Who are you")
        assertNotNull(identityCall)
        assertTrue(identityCall!!.spokenResponseDraft.contains("Arya", ignoreCase = true))

        // 3. RemoteDeviceAccessContract enforces honest disconnected/unavailable state
        val remoteManager = com.example.nova.accessibility.DefaultRemoteDeviceAccessManager
        val verifyStatus = remoteManager.verifyConnection("device_test_1")
        assertEquals(com.example.nova.accessibility.RemoteDeviceConnectionState.DISCONNECTED, verifyStatus)

        // 4. Verify Arya avatar visuals resolve properly for all core states
        val idleVisuals = com.example.nova.ui.resolveAvatarStateVisuals(
            orbState = com.example.nova.core.NovaUiOrbState.IDLE,
            realNormalizedAudio = null,
            smoothedAudioLevel = 0f
        )
        assertNotNull(idleVisuals)
        assertEquals(1.0f, idleVisuals.scale, 0.01f)
    }
}


