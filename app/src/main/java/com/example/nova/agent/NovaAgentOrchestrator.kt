package com.example.nova.agent

import android.content.Context
import com.example.nova.accessibility.AccessibilityActionPerformer
import com.example.nova.accessibility.NovaAccessibilityService
import com.example.nova.ai.NovaAiProviderRouter
import com.example.nova.ai.ProviderPlanOutcome
import com.example.nova.core.AssistantSessionStage
import com.example.nova.core.CrossAppInteractionChannel
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.ExecutionChannel
import com.example.nova.core.LiveTaskItem
import com.example.nova.core.LiveTaskStateMachine
import com.example.nova.core.LiveTaskStatus
import com.example.nova.core.NovaTaskCapabilityType
import com.example.nova.core.PendingConfirmationRequest
import com.example.nova.core.PlannedToolCall
import com.example.nova.core.RiskLevel
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.core.SupportedAccessibilityAction
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.VerifiedActionResult
import com.example.nova.memory.NovaPreferences
import com.example.nova.memory.NovaRepository
import com.example.nova.memory.NovaRuntimeSettings
import com.example.nova.security.PermissionAuditor
import com.example.nova.services.NovaActiveTaskService
import com.example.nova.tools.SystemToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * M7 — Event-driven bounded concurrency controller for real task execution.
 *
 * Enforces:
 * - Standard mode max concurrent tasks (`MAX_CONCURRENT_TASKS_STANDARD = 3`).
 * - Low-RAM mode max concurrent tasks (`MAX_CONCURRENT_TASKS_LOW_RAM = 1`).
 * - Single-flight serialization for [NovaTaskCapabilityType.ACCESSIBILITY] so multiple
 *   accessibility tree traversals or actions never collide simultaneously.
 * - Tasks waiting for a permit remain truthfully in [LiveTaskStatus.PENDING] until acquired.
 */
class TaskConcurrencyController(
    private val maxConcurrentStandard: Int = NovaMultiTaskCoordinator.MAX_CONCURRENT_TASKS_STANDARD,
    private val maxConcurrentLowRam: Int = NovaMultiTaskCoordinator.MAX_CONCURRENT_TASKS_LOW_RAM
) {
    private val stateMutex = Mutex()
    private var runningCount = 0
    private var runningAccessibilityCount = 0
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withSlot(
        lowRamMode: Boolean,
        capabilityType: NovaTaskCapabilityType,
        block: suspend () -> T
    ): T {
        acquireSlot(lowRamMode, capabilityType)
        try {
            return block()
        } finally {
            releaseSlot(capabilityType)
        }
    }

    private suspend fun acquireSlot(
        lowRamMode: Boolean,
        capabilityType: NovaTaskCapabilityType
    ) {
        while (true) {
            val waiter: CompletableDeferred<Unit>? = stateMutex.withLock {
                val maxAllowed = if (lowRamMode) maxConcurrentLowRam else maxConcurrentStandard
                val canRunCount = runningCount < maxAllowed
                val canRunAccessibility = capabilityType != NovaTaskCapabilityType.ACCESSIBILITY ||
                    runningAccessibilityCount == 0
                if (canRunCount && canRunAccessibility) {
                    runningCount++
                    if (capabilityType == NovaTaskCapabilityType.ACCESSIBILITY) {
                        runningAccessibilityCount++
                    }
                    null
                } else {
                    CompletableDeferred<Unit>().also { waiters.addLast(it) }
                }
            }
            if (waiter == null) return
            try {
                waiter.await()
            } catch (e: CancellationException) {
                stateMutex.withLock {
                    waiters.remove(waiter)
                }
                throw e
            }
        }
    }

    private suspend fun releaseSlot(capabilityType: NovaTaskCapabilityType) {
        val toWake = mutableListOf<CompletableDeferred<Unit>>()
        stateMutex.withLock {
            runningCount = (runningCount - 1).coerceAtLeast(0)
            if (capabilityType == NovaTaskCapabilityType.ACCESSIBILITY) {
                runningAccessibilityCount = (runningAccessibilityCount - 1).coerceAtLeast(0)
            }
            while (waiters.isNotEmpty()) {
                toWake.add(waiters.removeFirst())
            }
        }
        toWake.forEach { it.complete(Unit) }
    }
}

sealed class EnqueueTaskOutcome {
    data class Enqueued(val task: LiveTaskItem) : EnqueueTaskOutcome()
    data class ReusedActiveTask(val existingTask: LiveTaskItem) : EnqueueTaskOutcome()
    data class PausedForConfirmation(val task: LiveTaskItem) : EnqueueTaskOutcome()
    data class RejectedQueueFull(val maxCapacity: Int) : EnqueueTaskOutcome()
}

/**
 * M7 — Real multi-task orchestration engine with bounded concurrency, duplicate prevention,
 * deterministic state transitions, error isolation, and cancellation propagation.
 */
class NovaMultiTaskCoordinator(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxConcurrentStandard: Int = MAX_CONCURRENT_TASKS_STANDARD,
    private val maxConcurrentLowRam: Int = MAX_CONCURRENT_TASKS_LOW_RAM,
    private val maxRetainedStandard: Int = MAX_RETAINED_TASKS_STANDARD,
    private val maxRetainedLowRam: Int = MAX_RETAINED_TASKS_LOW_RAM,
    private val onTasksUpdated: (List<LiveTaskItem>) -> Unit = {},
    private val onTaskFinished: suspend (
        task: LiveTaskItem,
        originalQuery: String,
        call: PlannedToolCall,
        providerLabel: String,
        verifiedResult: VerifiedActionResult?
    ) -> Unit = { _, _, _, _, _ -> }
) {
    private val lock = Any()
    private val _activeTasks = MutableStateFlow<List<LiveTaskItem>>(emptyList())
    val activeTasks: StateFlow<List<LiveTaskItem>> = _activeTasks.asStateFlow()

    private val taskJobs = mutableMapOf<String, Job>()
    private val pausedConfirmations = mutableMapOf<String, PausedTaskSpec>()
    private val concurrencyController = TaskConcurrencyController(
        maxConcurrentStandard = maxConcurrentStandard,
        maxConcurrentLowRam = maxConcurrentLowRam
    )

    private data class PausedTaskSpec(
        val taskId: String,
        val originalQuery: String,
        val call: PlannedToolCall,
        val providerLabel: String
    )

    fun enqueueTask(
        originalQuery: String,
        call: PlannedToolCall,
        providerLabel: String,
        lowRamMode: Boolean,
        requiresConfirmation: Boolean = false,
        confirmationReason: String = "",
        executor: suspend (PlannedToolCall, Boolean) -> VerifiedActionResult
    ): EnqueueTaskOutcome {
        val dedupKey = LiveTaskStateMachine.buildDeduplicationKey(call)
        val capability = NovaTaskCapabilityType.fromToolName(call.toolName)
        val maxRetained = if (lowRamMode) maxRetainedLowRam else maxRetainedStandard

        val createdOrReused: EnqueueTaskOutcome = synchronized(lock) {
            // 1. Duplicate in-flight check: reuse existing non-terminal task with identical deduplicationKey
            val existingActive = _activeTasks.value.firstOrNull {
                !it.status.isTerminal && it.deduplicationKey == dedupKey
            }
            if (existingActive != null) {
                return@synchronized EnqueueTaskOutcome.ReusedActiveTask(existingActive)
            }

            // 2. Bounded task retention: prune oldest terminal tasks if at capacity
            val currentList = _activeTasks.value.toMutableList()
            while (currentList.size >= maxRetained) {
                val oldestTerminalIdx = currentList.indexOfFirst { it.status.isTerminal }
                if (oldestTerminalIdx >= 0) {
                    currentList.removeAt(oldestTerminalIdx)
                } else {
                    break
                }
            }
            if (currentList.size >= maxRetained) {
                return@synchronized EnqueueTaskOutcome.RejectedQueueFull(maxRetained)
            }

            val nowMs = clock()
            val pendingTask = LiveTaskItem(
                taskId = UUID.randomUUID().toString(),
                title = LiveTaskStateMachine.humanReadableTaskTitle(call),
                toolName = call.toolName,
                channel = mapExecutionChannel(call.channel),
                status = LiveTaskStatus.PENDING,
                verificationSummary = "Queued for execution",
                elapsedMs = 0L,
                capabilityType = capability,
                statusMessage = LiveTaskStateMachine.deriveTaskConversationalMessage(
                    capabilityType = capability,
                    toolName = call.toolName,
                    status = LiveTaskStatus.PENDING
                ),
                createdAtMs = if (nowMs > 0L) {
                    DeviceFieldState.Available(nowMs)
                } else {
                    DeviceFieldState.Unavailable("Timestamp unavailable")
                },
                startedAtMs = DeviceFieldState.Unavailable("Not started"),
                completedAtMs = DeviceFieldState.Unavailable("Not completed"),
                verifiedResult = null,
                workspaceReference = null,
                failureOrUnavailableReason = "",
                isCancellationRequested = false,
                measurableProgressFraction = null,
                deduplicationKey = dedupKey
            )

            if (requiresConfirmation) {
                val awaitingTask = LiveTaskStateMachine.transition(
                    task = pendingTask,
                    targetStatus = LiveTaskStatus.AWAITING_CONFIRMATION,
                    timestampMs = nowMs,
                    reason = confirmationReason
                )
                pausedConfirmations[awaitingTask.taskId] = PausedTaskSpec(
                    taskId = awaitingTask.taskId,
                    originalQuery = originalQuery,
                    call = call,
                    providerLabel = providerLabel
                )
                currentList.add(awaitingTask)
                _activeTasks.value = currentList
                EnqueueTaskOutcome.PausedForConfirmation(awaitingTask)
            } else {
                currentList.add(pendingTask)
                _activeTasks.value = currentList
                EnqueueTaskOutcome.Enqueued(pendingTask)
            }
        }

        onTasksUpdated(_activeTasks.value)

        if (createdOrReused is EnqueueTaskOutcome.Enqueued) {
            launchTaskCoroutine(
                taskId = createdOrReused.task.taskId,
                originalQuery = originalQuery,
                call = call,
                providerLabel = providerLabel,
                lowRamMode = lowRamMode,
                executor = executor
            )
        }
        return createdOrReused
    }

    fun approveConfirmationTask(
        taskId: String,
        lowRamMode: Boolean,
        executor: suspend (PlannedToolCall, Boolean) -> VerifiedActionResult
    ): Boolean {
        val pausedSpec: PausedTaskSpec = synchronized(lock) {
            val spec = pausedConfirmations.remove(taskId) ?: return@synchronized null
            val currentList = _activeTasks.value.toMutableList()
            val idx = currentList.indexOfFirst { it.taskId == taskId }
            if (idx < 0) return@synchronized null
            val currentTask = currentList[idx]
            if (currentTask.status != LiveTaskStatus.AWAITING_CONFIRMATION) {
                return@synchronized null
            }
            val pendingTask = LiveTaskStateMachine.transition(
                task = currentTask,
                targetStatus = LiveTaskStatus.PENDING,
                timestampMs = clock()
            )
            currentList[idx] = pendingTask
            _activeTasks.value = currentList
            spec
        } ?: return false

        onTasksUpdated(_activeTasks.value)

        launchTaskCoroutine(
            taskId = pausedSpec.taskId,
            originalQuery = pausedSpec.originalQuery,
            call = pausedSpec.call,
            providerLabel = pausedSpec.providerLabel,
            lowRamMode = lowRamMode,
            executor = executor
        )
        return true
    }

    fun rejectConfirmationTask(
        taskId: String,
        reason: String = "Action cancelled at confirmation gate."
    ): Boolean {
        return cancelTask(taskId = taskId, reason = reason)
    }

    fun cancelTask(
        taskId: String,
        reason: String = "Cancelled by user."
    ): Boolean {
        val jobToCancel: Job?
        val updated: Boolean = synchronized(lock) {
            pausedConfirmations.remove(taskId)
            val currentList = _activeTasks.value.toMutableList()
            val idx = currentList.indexOfFirst { it.taskId == taskId }
            if (idx < 0) {
                jobToCancel = null
                return@synchronized false
            }
            val currentTask = currentList[idx]
            if (currentTask.status.isTerminal) {
                jobToCancel = null
                return@synchronized false
            }
            val cancelledTask = LiveTaskStateMachine.transition(
                task = currentTask.copy(isCancellationRequested = true),
                targetStatus = LiveTaskStatus.CANCELLED,
                timestampMs = clock(),
                reason = reason
            )
            currentList[idx] = cancelledTask
            _activeTasks.value = currentList
            jobToCancel = taskJobs.remove(taskId)
            true
        }
        jobToCancel?.cancel(CancellationException(reason))
        if (updated) {
            onTasksUpdated(_activeTasks.value)
        }
        return updated
    }

    fun cancelAllActiveTasks(reason: String = "Task stopped by user."): Int {
        val activeIds = synchronized(lock) {
            _activeTasks.value.filter { !it.status.isTerminal }.map { it.taskId }
        }
        var count = 0
        activeIds.forEach { id ->
            if (cancelTask(id, reason)) count++
        }
        return count
    }

    fun clearTerminalTasks() {
        val changed = synchronized(lock) {
            val remaining = _activeTasks.value.filter { !it.status.isTerminal }
            if (remaining.size != _activeTasks.value.size) {
                _activeTasks.value = remaining
                true
            } else {
                false
            }
        }
        if (changed) {
            onTasksUpdated(_activeTasks.value)
        }
    }

    fun recordDirectCallbackResult(
        call: PlannedToolCall,
        verifiedResult: VerifiedActionResult,
        lowRamMode: Boolean
    ): LiveTaskItem {
        val capability = NovaTaskCapabilityType.fromToolName(call.toolName)
        val maxRetained = if (lowRamMode) maxRetainedLowRam else maxRetainedStandard
        val finalTask = synchronized(lock) {
            val currentList = _activeTasks.value.toMutableList()
            val existingIdx = currentList.indexOfLast {
                it.capabilityType == capability &&
                    (it.status == LiveTaskStatus.PERMISSION_REQUIRED || !it.status.isTerminal)
            }
            if (existingIdx >= 0) {
                currentList.removeAt(existingIdx)
            }
            while (currentList.size >= maxRetained) {
                val oldestTerminalIdx = currentList.indexOfFirst { it.status.isTerminal }
                if (oldestTerminalIdx >= 0) {
                    currentList.removeAt(oldestTerminalIdx)
                } else {
                    break
                }
            }
            val nowMs = clock()
            val pending = LiveTaskItem(
                taskId = UUID.randomUUID().toString(),
                title = LiveTaskStateMachine.humanReadableTaskTitle(call),
                toolName = call.toolName,
                channel = mapExecutionChannel(call.channel),
                status = LiveTaskStatus.PENDING,
                capabilityType = capability,
                createdAtMs = if (nowMs > 0L) DeviceFieldState.Available(nowMs) else DeviceFieldState.Unavailable(),
                deduplicationKey = LiveTaskStateMachine.buildDeduplicationKey(call)
            )
            val running = LiveTaskStateMachine.transition(
                task = pending,
                targetStatus = LiveTaskStatus.RUNNING,
                timestampMs = nowMs
            )
            val targetStatus = LiveTaskStateMachine.outcomeToTaskStatus(verifiedResult.outcome)
            val completed = LiveTaskStateMachine.transition(
                task = running,
                targetStatus = targetStatus,
                timestampMs = nowMs,
                verifiedResult = verifiedResult
            )
            currentList.add(completed)
            _activeTasks.value = currentList
            completed
        }
        onTasksUpdated(_activeTasks.value)
        return finalTask
    }

    private fun launchTaskCoroutine(
        taskId: String,
        originalQuery: String,
        call: PlannedToolCall,
        providerLabel: String,
        lowRamMode: Boolean,
        executor: suspend (PlannedToolCall, Boolean) -> VerifiedActionResult
    ) {
        val capability = NovaTaskCapabilityType.fromToolName(call.toolName)
        val job = scope.launch {
            try {
                concurrencyController.withSlot(
                    lowRamMode = lowRamMode,
                    capabilityType = capability
                ) {
                    coroutineContext.ensureActive()

                    val transitionedToRunning = synchronized(lock) {
                        val currentList = _activeTasks.value.toMutableList()
                        val idx = currentList.indexOfFirst { it.taskId == taskId }
                        if (idx < 0) return@synchronized null
                        val current = currentList[idx]
                        if (current.status != LiveTaskStatus.PENDING || current.isCancellationRequested) {
                            return@synchronized null
                        }
                        val running = LiveTaskStateMachine.transition(
                            task = current,
                            targetStatus = LiveTaskStatus.RUNNING,
                            timestampMs = clock()
                        )
                        currentList[idx] = running
                        _activeTasks.value = currentList
                        running
                    } ?: return@withSlot

                    onTasksUpdated(_activeTasks.value)

                    val verifiedResult = executor(call, lowRamMode)
                    coroutineContext.ensureActive()

                    val completedTask = synchronized(lock) {
                        val currentList = _activeTasks.value.toMutableList()
                        val idx = currentList.indexOfFirst { it.taskId == taskId }
                        if (idx < 0) return@synchronized null
                        val current = currentList[idx]
                        if (current.status != LiveTaskStatus.RUNNING || current.isCancellationRequested) {
                            return@synchronized null
                        }
                        val targetStatus = LiveTaskStateMachine.outcomeToTaskStatus(verifiedResult.outcome)
                        val finished = LiveTaskStateMachine.transition(
                            task = current,
                            targetStatus = targetStatus,
                            timestampMs = clock(),
                            verifiedResult = verifiedResult
                        )
                        currentList[idx] = finished
                        _activeTasks.value = currentList
                        finished
                    }

                    if (completedTask != null) {
                        onTasksUpdated(_activeTasks.value)
                        onTaskFinished(completedTask, originalQuery, call, providerLabel, verifiedResult)
                    }
                }
            } catch (ce: CancellationException) {
                val cancelledTask = synchronized(lock) {
                    val currentList = _activeTasks.value.toMutableList()
                    val idx = currentList.indexOfFirst { it.taskId == taskId }
                    if (idx < 0) return@synchronized null
                    val current = currentList[idx]
                    if (current.status.isTerminal) return@synchronized null
                    val cancelled = LiveTaskStateMachine.transition(
                        task = current.copy(isCancellationRequested = true),
                        targetStatus = LiveTaskStatus.CANCELLED,
                        timestampMs = clock(),
                        reason = ce.message?.takeIf { it.isNotBlank() } ?: "Cancelled by user."
                    )
                    currentList[idx] = cancelled
                    _activeTasks.value = currentList
                    cancelled
                }
                if (cancelledTask != null) {
                    onTasksUpdated(_activeTasks.value)
                }
                throw ce
            } catch (t: Throwable) {
                val errMsg = "${t.javaClass.simpleName}: ${t.message ?: "Unexpected task failure."}"
                val failedResult = VerifiedActionResult(
                    toolName = call.toolName,
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    verificationDetail = errMsg,
                    targetPackage = "",
                    preStateSummary = "Task=${call.toolName}",
                    postStateSummary = "Exception",
                    userFacingMessage = "That task failed ($errMsg).",
                    elapsedMs = 0L
                )
                val failedTask = synchronized(lock) {
                    val currentList = _activeTasks.value.toMutableList()
                    val idx = currentList.indexOfFirst { it.taskId == taskId }
                    if (idx < 0) return@synchronized null
                    val current = currentList[idx]
                    if (current.status.isTerminal) return@synchronized null
                    val runningOrCurrent = if (current.status == LiveTaskStatus.PENDING) {
                        LiveTaskStateMachine.transition(current, LiveTaskStatus.RUNNING, clock())
                    } else {
                        current
                    }
                    val failed = LiveTaskStateMachine.transition(
                        task = runningOrCurrent,
                        targetStatus = LiveTaskStatus.FAILED,
                        timestampMs = clock(),
                        verifiedResult = failedResult,
                        reason = failedResult.userFacingMessage
                    )
                    currentList[idx] = failed
                    _activeTasks.value = currentList
                    failed
                }
                if (failedTask != null) {
                    onTasksUpdated(_activeTasks.value)
                    onTaskFinished(failedTask, originalQuery, call, providerLabel, failedResult)
                }
            } finally {
                synchronized(lock) {
                    taskJobs.remove(taskId)
                }
            }
        }

        synchronized(lock) {
            val current = _activeTasks.value.firstOrNull { it.taskId == taskId }
            if (current != null && !current.status.isTerminal) {
                taskJobs[taskId] = job
            } else {
                job.cancel()
            }
        }
    }

    companion object {
        const val MAX_CONCURRENT_TASKS_STANDARD = 3
        const val MAX_CONCURRENT_TASKS_LOW_RAM = 1
        const val MAX_RETAINED_TASKS_STANDARD = 8
        const val MAX_RETAINED_TASKS_LOW_RAM = 4

        fun mapExecutionChannel(channel: ExecutionChannel): CrossAppInteractionChannel {
            return when (channel) {
                ExecutionChannel.ACCESSIBILITY_SERVICE -> CrossAppInteractionChannel.ACCESSIBILITY_PRIMARY
                ExecutionChannel.ANDROID_API_INTENT,
                ExecutionChannel.LOCAL_MEMORY,
                ExecutionChannel.DIRECT_RESPONSE -> CrossAppInteractionChannel.ANDROID_SYSTEM_INTENT
            }
        }
    }
}

/**
 * Orchestrates the complete Nova execution loop (M0–M7):
 * User -> Voice/Text -> AI/Parser -> Single or Multi-Task Selection -> Permission & Risk Check
 * -> Bounded Concurrent Android/Accessibility Execution -> Real State Verification
 * -> Independent LiveTaskItem Lifecycle -> Room Audit Log -> Output.
 */
class NovaAgentOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: NovaRepository,
    private val preferences: NovaPreferences,
    private val aiRouter: NovaAiProviderRouter,
    private val toolExecutor: SystemToolExecutor,
    private val onSpeakResponse: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val customTaskExecutor: (suspend (PlannedToolCall, Boolean) -> VerifiedActionResult)? = null
) {
    private val appContext = context.applicationContext

    private val _sessionStage = MutableStateFlow(AssistantSessionStage.IDLE)
    val sessionStage: StateFlow<AssistantSessionStage> = _sessionStage.asStateFlow()

    private val _statusBanner = MutableStateFlow("Ready — Voice & Device Control Active")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    private val _lastVerifiedResult = MutableStateFlow<VerifiedActionResult?>(null)
    val lastVerifiedResult: StateFlow<VerifiedActionResult?> = _lastVerifiedResult.asStateFlow()

    private val _latestDiagnosticMessage = MutableStateFlow("")
    val latestDiagnosticMessage: StateFlow<String> = _latestDiagnosticMessage.asStateFlow()

    private var planningJob: Job? = null

    private val multiTaskCoordinator = NovaMultiTaskCoordinator(
        scope = scope,
        clock = clock,
        onTasksUpdated = { tasks ->
            syncOrchestratorStateFromTasks(tasks)
        },
        onTaskFinished = { task, originalQuery, call, providerLabel, verifiedResult ->
            handleCompletedTaskSideEffects(task, originalQuery, call, providerLabel, verifiedResult)
        }
    )

    /**
     * M7 — Authoritative reactive list of active and recent session tasks.
     */
    val activeTasks: StateFlow<List<LiveTaskItem>> = multiTaskCoordinator.activeTasks

    init {
        NovaAccessibilityService.onUserCancelTaskCallback = {
            cancelActiveTask("Cancelled by user via Accessibility HUD.")
        }
    }

    fun clearLastVerifiedResult() {
        _lastVerifiedResult.value = null
        _latestDiagnosticMessage.value = ""
        multiTaskCoordinator.clearTerminalTasks()
        if (activeTasks.value.isEmpty()) {
            toolExecutor.clearLatestDeviceInfo()
            toolExecutor.clearLatestPhotoWorkspace()
            toolExecutor.clearLatestAccessibilityWorkspace()
        }
        if (_sessionStage.value == AssistantSessionStage.ERROR ||
            _sessionStage.value == AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED
        ) {
            _sessionStage.value = AssistantSessionStage.IDLE
        }
    }

    fun onPhotoPickerUrisSelected(uris: List<String>, lowRamMode: Boolean) {
        val verifiedResult = toolExecutor.ingestPickedPhotoUris(uris, lowRamMode)
        applyPhotoCallbackResult(verifiedResult, "Photo Picker (${uris.size} URI)", lowRamMode)
    }

    fun onMediaPermissionResult(lowRamMode: Boolean) {
        if (toolExecutor.latestPhotoWorkspaceState.value == null &&
            _lastVerifiedResult.value?.toolName != "query_photos" &&
            activeTasks.value.none { it.capabilityType == NovaTaskCapabilityType.PHOTOS }
        ) {
            return
        }
        val verifiedResult = toolExecutor.refreshPhotosAfterPermissionResult(lowRamMode)
        applyPhotoCallbackResult(verifiedResult, "MediaStore Permission Callback", lowRamMode)
    }

    private fun applyPhotoCallbackResult(
        verifiedResult: VerifiedActionResult,
        sourceLabel: String,
        lowRamMode: Boolean
    ) {
        val photoCall = PlannedToolCall(
            toolName = "query_photos",
            arguments = mapOf("mode" to "mediastore"),
            reasoning = sourceLabel,
            expectedOutcomeDescription = "Real accessible photos updated",
            riskLevel = RiskLevel.SAFE,
            channel = ExecutionChannel.ANDROID_API_INTENT,
            spokenResponseDraft = verifiedResult.userFacingMessage
        )
        multiTaskCoordinator.recordDirectCallbackResult(
            call = photoCall,
            verifiedResult = verifiedResult,
            lowRamMode = lowRamMode
        )
        _lastVerifiedResult.value = verifiedResult
        _latestDiagnosticMessage.value = verifiedResult.userFacingMessage
        scope.launch {
            repository.recordTaskAuditLog(
                userCommand = sourceLabel,
                toolName = verifiedResult.toolName,
                executionChannel = "ANDROID_API_INTENT",
                targetPackage = verifiedResult.targetPackage,
                verificationOutcome = verifiedResult.outcome.name,
                verificationDetail = verifiedResult.verificationDetail,
                elapsedMs = verifiedResult.elapsedMs
            )
        }
    }

    fun submitUserCommand(rawCommand: String): Job? {
        val command = rawCommand.trim()
        if (command.isBlank()) return null

        planningJob?.cancel()
        val job = scope.launch {
            val hasRunningOrPending = activeTasks.value.any { !it.status.isTerminal }
            if (!hasRunningOrPending) {
                _lastVerifiedResult.value = null
                _latestDiagnosticMessage.value = ""
                multiTaskCoordinator.clearTerminalTasks()
                toolExecutor.clearLatestDeviceInfo()
                toolExecutor.clearLatestPhotoWorkspace()
                toolExecutor.clearLatestAccessibilityWorkspace()
            }

            _sessionStage.value = AssistantSessionStage.SANITIZING_AND_PLANNING
            _statusBanner.value = "Analyzing request..."
            NovaAccessibilityService.updateOverlayHudState(
                visible = preferences.settings.value.showAccessibilityFloatingHud,
                statusLabel = "Nova • Planning...",
                isTaskRunning = true
            )

            repository.recordUserTurn(command)

            val currentSettings = preferences.settings.value
            val health = PermissionAuditor.captureSystemHealthSnapshot(
                context = appContext,
                lowRamModeUserOverride = currentSettings.lowRamModeOverride,
                isTorchCurrentlyOn = toolExecutor.isTorchOn.value
            )

            val screenSnapshot = NovaAccessibilityService.instance
                ?.captureSemanticSnapshotOnDemand(lowRamMode = health.effectiveLowRamMode)

            val recentTurns = repository.getRecentConversationTurns(limit = 6)
            val memoryFacts = if (currentSettings.memoryContextInjectionEnabled) {
                repository.getRecentMemoryFacts(limit = 15)
            } else {
                emptyList()
            }

            when (
                val planOutcome = aiRouter.planUserRequest(
                    userUtterance = command,
                    settings = currentSettings,
                    isNetworkOnline = health.isNetworkOnline,
                    screenSnapshot = screenSnapshot,
                    recentTurns = recentTurns,
                    memoryFacts = memoryFacts
                )
            ) {
                is ProviderPlanOutcome.DirectAnswer -> {
                    repository.recordAssistantTurn(
                        text = planOutcome.answerText,
                        toolCalled = "direct_answer",
                        verificationStatus = VerificationOutcome.VERIFIED_SUCCESS.name,
                        verificationDetail = "Direct conversational response from ${planOutcome.providerLabel}",
                        providerUsed = planOutcome.providerLabel
                    )
                    if (activeTasks.value.none { !it.status.isTerminal }) {
                        _sessionStage.value = AssistantSessionStage.IDLE
                        _statusBanner.value = "Responded via ${planOutcome.providerLabel}"
                        NovaAccessibilityService.updateOverlayHudState(
                            visible = currentSettings.showAccessibilityFloatingHud,
                            statusLabel = "Nova • Ready",
                            isTaskRunning = false
                        )
                    }
                    onSpeakResponse(planOutcome.answerText)
                }

                is ProviderPlanOutcome.ConfigurationOrNetworkRequired -> {
                    repository.recordAssistantTurn(
                        text = planOutcome.message,
                        toolCalled = "none",
                        verificationStatus = planOutcome.statusCode,
                        verificationDetail = planOutcome.message,
                        providerUsed = currentSettings.primaryProvider.displayName
                    )
                    _latestDiagnosticMessage.value = planOutcome.message
                    _sessionStage.value = if (planOutcome.statusCode == "PROVIDER_RUNTIME_ERROR") {
                        AssistantSessionStage.ERROR
                    } else {
                        AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED
                    }
                    _statusBanner.value = planOutcome.statusCode
                    NovaAccessibilityService.updateOverlayHudState(
                        visible = currentSettings.showAccessibilityFloatingHud,
                        statusLabel = "Nova • ${planOutcome.statusCode}",
                        isTaskRunning = false
                    )
                    onSpeakResponse(planOutcome.message)
                }

                is ProviderPlanOutcome.ToolSelected -> {
                    orchestratePlannedCalls(
                        originalQuery = command,
                        calls = listOf(planOutcome.call),
                        providerLabel = planOutcome.providerLabel,
                        lowRamMode = health.effectiveLowRamMode,
                        screenSnapshot = screenSnapshot,
                        currentSettings = currentSettings
                    )
                }

                is ProviderPlanOutcome.MultiToolSelected -> {
                    orchestratePlannedCalls(
                        originalQuery = command,
                        calls = planOutcome.calls,
                        providerLabel = planOutcome.providerLabel,
                        lowRamMode = health.effectiveLowRamMode,
                        screenSnapshot = screenSnapshot,
                        currentSettings = currentSettings
                    )
                }
            }
        }
        planningJob = job
        return job
    }

    /**
     * M7 — Enqueues one or more real [PlannedToolCall]s through the multi-task orchestration layer.
     */
    fun submitPlannedTasks(
        originalQuery: String,
        calls: List<PlannedToolCall>,
        providerLabel: String = "Direct",
        lowRamMode: Boolean = false
    ) {
        if (calls.isEmpty()) return
        val currentSettings = preferences.settings.value
        val screenSnapshot = NovaAccessibilityService.instance
            ?.captureSemanticSnapshotOnDemand(lowRamMode = lowRamMode)
        orchestratePlannedCalls(
            originalQuery = originalQuery,
            calls = calls,
            providerLabel = providerLabel,
            lowRamMode = lowRamMode,
            screenSnapshot = screenSnapshot,
            currentSettings = currentSettings
        )
    }

    private fun orchestratePlannedCalls(
        originalQuery: String,
        calls: List<PlannedToolCall>,
        providerLabel: String,
        lowRamMode: Boolean,
        screenSnapshot: SemanticScreenSnapshot?,
        currentSettings: NovaRuntimeSettings
    ) {
        for (call in calls) {
            val a11yAssessment = evaluateAccessibilityConfirmation(call, screenSnapshot)
            val needsConfirm = currentSettings.requireConfirmationForSensitiveActions &&
                (call.riskLevel == RiskLevel.SENSITIVE_CONFIRM ||
                    call.riskLevel == RiskLevel.DESTRUCTIVE_CONFIRM ||
                    a11yAssessment.requiresConfirmation)

            val warningReason = if (needsConfirm) {
                a11yAssessment.reason.ifBlank {
                    "Sensitive external action (${call.toolName}): ${call.arguments.entries.joinToString { "${it.key}=${it.value}" }}"
                }
            } else {
                ""
            }

            val outcome = multiTaskCoordinator.enqueueTask(
                originalQuery = originalQuery,
                call = call,
                providerLabel = providerLabel,
                lowRamMode = lowRamMode,
                requiresConfirmation = needsConfirm,
                confirmationReason = warningReason,
                executor = { plannedCall, isLowRam ->
                    executeSingleToolCall(plannedCall, isLowRam)
                }
            )

            if (outcome is EnqueueTaskOutcome.PausedForConfirmation) {
                val confirmReq = PendingConfirmationRequest(
                    id = outcome.task.taskId,
                    originalUserQuery = originalQuery,
                    plannedCall = call,
                    providerUsed = providerLabel,
                    warningReason = warningReason
                )
                _pendingConfirmation.value = confirmReq
                _sessionStage.value = AssistantSessionStage.AWAITING_USER_CONFIRMATION
                _statusBanner.value = "Confirmation required for ${call.toolName}"
                NovaAccessibilityService.updateOverlayHudState(
                    visible = currentSettings.showAccessibilityFloatingHud,
                    statusLabel = "Nova • Confirm ${call.toolName}",
                    isTaskRunning = false
                )
                onSpeakResponse("Please confirm before I execute ${call.toolName.replace('_', ' ')}.")
            }
        }
    }

    private suspend fun executeSingleToolCall(
        call: PlannedToolCall,
        lowRamMode: Boolean
    ): VerifiedActionResult {
        val custom = customTaskExecutor
        if (custom != null) {
            return custom(call, lowRamMode)
        }
        return toolExecutor.executeAndVerify(call, lowRamMode = lowRamMode)
    }

    fun approvePendingConfirmation() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        val currentSettings = preferences.settings.value
        val health = PermissionAuditor.captureSystemHealthSnapshot(
            context = appContext,
            lowRamModeUserOverride = currentSettings.lowRamModeOverride,
            isTorchCurrentlyOn = toolExecutor.isTorchOn.value
        )
        val resumed = multiTaskCoordinator.approveConfirmationTask(
            taskId = pending.id,
            lowRamMode = health.effectiveLowRamMode,
            executor = { plannedCall, isLowRam ->
                executeSingleToolCall(plannedCall, isLowRam)
            }
        )
        if (!resumed) {
            orchestratePlannedCalls(
                originalQuery = pending.originalUserQuery,
                calls = listOf(pending.plannedCall),
                providerLabel = pending.providerUsed,
                lowRamMode = health.effectiveLowRamMode,
                screenSnapshot = NovaAccessibilityService.latestSnapshot.value,
                currentSettings = currentSettings.copy(requireConfirmationForSensitiveActions = false)
            )
        }
    }

    fun rejectPendingConfirmation() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        multiTaskCoordinator.rejectConfirmationTask(
            taskId = pending.id,
            reason = "User declined confirmation gate for ${pending.plannedCall.toolName}."
        )
        scope.launch {
            repository.recordTaskAuditLog(
                userCommand = pending.originalUserQuery,
                toolName = pending.plannedCall.toolName,
                executionChannel = pending.plannedCall.channel.name,
                targetPackage = "",
                verificationOutcome = VerificationOutcome.USER_CANCELLED.name,
                verificationDetail = "User declined confirmation gate for ${pending.plannedCall.toolName}.",
                elapsedMs = 0L
            )
            repository.recordAssistantTurn(
                text = "Cancelled ${pending.plannedCall.toolName} as requested.",
                toolCalled = pending.plannedCall.toolName,
                verificationStatus = VerificationOutcome.USER_CANCELLED.name,
                verificationDetail = "Declined at confirmation gate",
                providerUsed = pending.providerUsed
            )
            syncOrchestratorStateFromTasks(activeTasks.value)
            if (activeTasks.value.none { !it.status.isTerminal }) {
                _statusBanner.value = "Action cancelled by user"
            }
        }
    }

    /**
     * M7 — Cancels a specific live task by [taskId] without affecting other independent tasks.
     */
    fun cancelTask(taskId: String, reason: String = "Cancelled by user."): Boolean {
        if (_pendingConfirmation.value?.id == taskId) {
            _pendingConfirmation.value = null
        }
        val cancelled = multiTaskCoordinator.cancelTask(taskId = taskId, reason = reason)
        if (cancelled && activeTasks.value.none { !it.status.isTerminal }) {
            NovaActiveTaskService.stopForCompletedTask(appContext)
            _sessionStage.value = AssistantSessionStage.IDLE
            _statusBanner.value = reason
        }
        return cancelled
    }

    fun cancelActiveTask(reason: String = "Task stopped by user.") {
        planningJob?.cancel()
        planningJob = null
        _pendingConfirmation.value = null
        multiTaskCoordinator.cancelAllActiveTasks(reason)
        NovaActiveTaskService.stopForCompletedTask(appContext)
        _sessionStage.value = AssistantSessionStage.IDLE
        _statusBanner.value = reason
        NovaAccessibilityService.updateOverlayHudState(
            visible = preferences.settings.value.showAccessibilityFloatingHud,
            statusLabel = "Nova • Stopped",
            isTaskRunning = false
        )
    }

    private fun syncOrchestratorStateFromTasks(tasks: List<LiveTaskItem>) {
        if (tasks.isEmpty()) return

        val runningTasks = tasks.filter { it.status == LiveTaskStatus.RUNNING }
        val pendingTasks = tasks.filter { it.status == LiveTaskStatus.PENDING }
        val awaitingConfirm = tasks.filter { it.status == LiveTaskStatus.AWAITING_CONFIRMATION }
        val showHud = preferences.settings.value.showAccessibilityFloatingHud

        if (runningTasks.isNotEmpty() || pendingTasks.isNotEmpty()) {
            _sessionStage.value = AssistantSessionStage.EXECUTING_AND_VERIFYING
            val banner = when {
                runningTasks.size == 1 && pendingTasks.isEmpty() ->
                    runningTasks.first().statusMessage.ifBlank {
                        "Executing & verifying ${runningTasks.first().toolName}..."
                    }
                runningTasks.isNotEmpty() ->
                    "Running ${runningTasks.size} task(s)" +
                        if (pendingTasks.isNotEmpty()) " (${pendingTasks.size} queued)..." else "..."
                else ->
                    "Queued ${pendingTasks.size} task(s)..."
            }
            _statusBanner.value = banner
            NovaActiveTaskService.startForActiveTask(appContext, banner)
            NovaAccessibilityService.updateOverlayHudState(
                visible = showHud,
                statusLabel = "Nova • $banner",
                isTaskRunning = true
            )
            return
        }

        NovaActiveTaskService.stopForCompletedTask(appContext)

        if (awaitingConfirm.isNotEmpty() || _pendingConfirmation.value != null) {
            _sessionStage.value = AssistantSessionStage.AWAITING_USER_CONFIRMATION
            return
        }

        // All tasks in list have reached terminal states
        if (tasks.size == 1) {
            val single = tasks.first()
            val result = single.verifiedResult
            if (result != null) {
                _lastVerifiedResult.value = result
                _latestDiagnosticMessage.value = result.userFacingMessage
                _sessionStage.value = mapSingleOutcomeToSessionStage(result.outcome)
                _statusBanner.value = "${result.toolName}: ${result.outcome.name}"
                NovaAccessibilityService.updateOverlayHudState(
                    visible = showHud,
                    statusLabel = "Nova • ${result.outcome.name}",
                    isTaskRunning = false
                )
            } else if (single.status == LiveTaskStatus.CANCELLED) {
                _sessionStage.value = AssistantSessionStage.IDLE
                _statusBanner.value = single.statusMessage
            }
        } else {
            val latestWithResult = tasks.lastOrNull { it.verifiedResult != null }?.verifiedResult
            if (latestWithResult != null) {
                _lastVerifiedResult.value = latestWithResult
            }
            val verifiedCount = tasks.count { it.status == LiveTaskStatus.COMPLETED_VERIFIED }
            val permOrUnavailCount = tasks.count {
                it.status == LiveTaskStatus.PERMISSION_REQUIRED ||
                    it.status == LiveTaskStatus.UNAVAILABLE
            }
            val failedCount = tasks.count { it.status == LiveTaskStatus.FAILED }

            _sessionStage.value = when {
                verifiedCount > 0 -> AssistantSessionStage.IDLE
                permOrUnavailCount > 0 -> AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED
                failedCount > 0 -> AssistantSessionStage.ERROR
                else -> AssistantSessionStage.IDLE
            }
            val summaryBanner = buildString {
                append("${tasks.size} tasks: $verifiedCount verified")
                if (permOrUnavailCount > 0) append(", $permOrUnavailCount need access")
                if (failedCount > 0) append(", $failedCount failed")
            }
            _statusBanner.value = summaryBanner
            _latestDiagnosticMessage.value = tasks.joinToString(" ") { it.statusMessage }
            NovaAccessibilityService.updateOverlayHudState(
                visible = showHud,
                statusLabel = "Nova • $summaryBanner",
                isTaskRunning = false
            )
        }
    }

    private suspend fun handleCompletedTaskSideEffects(
        task: LiveTaskItem,
        originalQuery: String,
        call: PlannedToolCall,
        providerLabel: String,
        verifiedResult: VerifiedActionResult?
    ) {
        if (verifiedResult == null) return

        repository.recordTaskAuditLog(
            userCommand = originalQuery,
            toolName = verifiedResult.toolName,
            executionChannel = call.channel.name,
            targetPackage = verifiedResult.targetPackage,
            verificationOutcome = verifiedResult.outcome.name,
            verificationDetail = verifiedResult.verificationDetail,
            elapsedMs = verifiedResult.elapsedMs
        )

        repository.recordAssistantTurn(
            text = verifiedResult.userFacingMessage,
            toolCalled = verifiedResult.toolName,
            verificationStatus = verifiedResult.outcome.name,
            verificationDetail = verifiedResult.verificationDetail,
            providerUsed = providerLabel
        )

        val currentTasks = activeTasks.value
        val allFinished = currentTasks.all { it.status.isTerminal }
        if (allFinished) {
            if (currentTasks.size == 1) {
                onSpeakResponse(verifiedResult.userFacingMessage)
            } else {
                val combinedSpeech = currentTasks.joinToString(" ") { it.statusMessage }
                onSpeakResponse(combinedSpeech)
            }
        }
    }

    private fun mapSingleOutcomeToSessionStage(outcome: VerificationOutcome): AssistantSessionStage {
        return when (outcome) {
            VerificationOutcome.VERIFIED_SUCCESS,
            VerificationOutcome.USER_CANCELLED -> AssistantSessionStage.IDLE

            VerificationOutcome.PERMISSION_REQUIRED,
            VerificationOutcome.SERVICE_DISABLED,
            VerificationOutcome.CONFIGURATION_REQUIRED,
            VerificationOutcome.UNSUPPORTED -> AssistantSessionStage.PERMISSION_OR_CONFIG_REQUIRED

            VerificationOutcome.AWAITING_CONFIRMATION -> AssistantSessionStage.AWAITING_USER_CONFIRMATION

            VerificationOutcome.VERIFICATION_FAILED -> AssistantSessionStage.ERROR
        }
    }

    private fun evaluateAccessibilityConfirmation(
        call: PlannedToolCall,
        screenSnapshot: SemanticScreenSnapshot?
    ): AccessibilityActionPerformer.ConfirmationGateAssessment {
        val requestedAction = when (call.toolName.lowercase()) {
            "click_node" -> {
                if ((call.arguments["action"] ?: "CLICK").equals("LONG_CLICK", ignoreCase = true)) {
                    SupportedAccessibilityAction.LONG_CLICK
                } else {
                    SupportedAccessibilityAction.CLICK
                }
            }
            "select_node" -> SupportedAccessibilityAction.SELECT
            "input_text" -> SupportedAccessibilityAction.SET_TEXT
            else -> return AccessibilityActionPerformer.ConfirmationGateAssessment(false)
        }

        val rawTarget = call.arguments["target"] ?: call.arguments["query"].orEmpty()
        val textArg = call.arguments["text"]
        val activePkg = screenSnapshot?.packageName.orEmpty()

        val matchedTarget = if (screenSnapshot != null && rawTarget.isNotBlank()) {
            val indexMatch = Regex("""^#?(\d+)$""").find(rawTarget.trim())
            val idx = indexMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (idx != null) {
                screenSnapshot.targets.firstOrNull { it.targetIndex == idx }
            } else {
                screenSnapshot.targets.firstOrNull {
                    it.visibleText.equals(rawTarget, ignoreCase = true) ||
                        it.contentDescription.equals(rawTarget, ignoreCase = true) ||
                        it.viewIdResourceName.endsWith("/$rawTarget", ignoreCase = true)
                }
            }
        } else {
            null
        }

        return AccessibilityActionPerformer.evaluateConfirmationRequirement(
            requestedAction = requestedAction,
            resolvedTarget = matchedTarget,
            activePackage = activePkg,
            rawTargetQuery = rawTarget,
            textArgument = textArg
        )
    }
}
