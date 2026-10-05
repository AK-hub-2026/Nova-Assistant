package com.example.nova.accessibility

import android.accessibilityservice.AccessibilityService
import com.example.nova.core.AccessibilityTarget
import com.example.nova.core.AccessibilityWorkspaceState
import com.example.nova.core.AccessibilityWorkspaceStatus
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.OverlayAttachmentState
import com.example.nova.core.ScreenUnderstandingFallbackStatus
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.core.SupportedAccessibilityAction
import com.example.nova.core.VerificationOutcome
import com.example.nova.core.VerifiedActionResult
import com.example.nova.security.SensitiveDataRedactor
import kotlinx.coroutines.delay

/**
 * Explicit failure causes for Accessibility target resolution, validation, execution,
 * and post-action verification in M6.
 */
enum class AccessibilityActionFailureReason {
    SERVICE_NOT_CONNECTED,
    WINDOW_UNAVAILABLE,
    TARGET_NOT_FOUND,
    AMBIGUOUS_TARGET,
    STALE_TARGET,
    TARGET_DISABLED,
    UNSUPPORTED_ACTION,
    PERFORM_ACTION_RETURNED_FALSE,
    POST_ACTION_VERIFICATION_FAILED
}

/**
 * Result of resolving a requested semantic target against the current accessibility tree.
 */
sealed class TargetResolutionOutcome {
    data class Resolved(
        val target: AccessibilityTarget,
        val targetNode: AccessibilityNodeAdapter,
        val actionNode: AccessibilityNodeAdapter,
        val snapshot: SemanticScreenSnapshot
    ) : TargetResolutionOutcome()

    data class Ambiguous(
        val query: String,
        val candidates: List<AccessibilityTarget>,
        val snapshot: SemanticScreenSnapshot
    ) : TargetResolutionOutcome()

    data class NotFound(
        val query: String,
        val message: String,
        val snapshot: SemanticScreenSnapshot
    ) : TargetResolutionOutcome()

    data class WindowUnavailable(
        val message: String
    ) : TargetResolutionOutcome()
}

/**
 * Result of verifying whether an executed accessibility action actually produced the
 * expected semantic state change in the re-read accessibility tree.
 */
data class PostActionVerificationResult(
    val verified: Boolean,
    val detail: String,
    val preStateSummary: String,
    val postStateSummary: String,
    val reResolvedTarget: AccessibilityTarget? = null
)

/**
 * Complete result of executing and verifying a semantic accessibility action.
 */
data class VerifiedAccessibilityActionOutcome(
    val outcome: VerificationOutcome,
    val failureReason: AccessibilityActionFailureReason? = null,
    val action: SupportedAccessibilityAction,
    val targetPackage: String,
    val resolvedTarget: AccessibilityTarget? = null,
    val ambiguousCandidates: List<AccessibilityTarget> = emptyList(),
    val preSnapshot: SemanticScreenSnapshot? = null,
    val postSnapshot: SemanticScreenSnapshot? = null,
    val verificationDetail: String,
    val preStateSummary: String,
    val postStateSummary: String,
    val userFacingMessage: String
)

/**
 * M6 — Real Accessibility Semantic Target Resolver, Pre-Execution Validator,
 * Action Executor, and Post-Action State Verifier.
 *
 * Strictly enforces:
 * - Primary interaction via real [AccessibilityNodeAdapter] / `AccessibilityNodeInfo` semantics.
 * - Pre-execution checks: valid/non-stale target, enabled state, and supported action.
 * - Ambiguity protection: refuses to guess when multiple distinct targets match with equal confidence.
 * - Mandatory post-action verification: `performAction(...) == true` is NEVER treated as sufficient
 *   proof of success; the accessibility tree is re-read and verified for an actual state change.
 */
object AccessibilityActionPerformer {

    data class RawActionStatus(
        val dispatched: Boolean,
        val detail: String
    )

    private data class IndexedLiveNode(
        val target: AccessibilityTarget,
        val targetNode: AccessibilityNodeAdapter,
        val actionNode: AccessibilityNodeAdapter
    )

    /**
     * Resolves a semantic target in the current accessibility tree without guessing on ambiguity.
     */
    fun resolveTargetInTree(
        rootAdapter: AccessibilityNodeAdapter?,
        targetQuery: String,
        requestedAction: SupportedAccessibilityAction,
        activePackageOverride: String = "",
        windowTitleOverride: String = "",
        lowRamMode: Boolean = false
    ): TargetResolutionOutcome {
        if (rootAdapter == null || !rootAdapter.isNodeValid()) {
            return TargetResolutionOutcome.WindowUnavailable(
                "Active window root is null or inaccessible."
            )
        }

        val snapshot = SemanticScreenExtractor.extractSnapshotFromAdapter(
            rootAdapter = rootAdapter,
            activePackageOverride = activePackageOverride,
            windowTitleOverride = windowTitleOverride,
            lowRamMode = lowRamMode
        ) ?: return TargetResolutionOutcome.WindowUnavailable(
            "Unable to extract semantic snapshot from active window root."
        )

        val indexedNodes = collectIndexedLiveNodes(rootAdapter, snapshot, lowRamMode)
        val trimmedQuery = targetQuery.trim()

        // 1. Scroll actions without an explicit target query resolve the primary scrollable container
        if ((requestedAction == SupportedAccessibilityAction.SCROLL_FORWARD ||
                requestedAction == SupportedAccessibilityAction.SCROLL_BACKWARD) &&
            trimmedQuery.isBlank()
        ) {
            val scrollableCandidates = indexedNodes.filter {
                it.target.isScrollable || requestedAction in it.target.supportedActions
            }
            if (scrollableCandidates.isEmpty()) {
                return TargetResolutionOutcome.NotFound(
                    query = requestedAction.name,
                    message = "No scrollable container was found in the active window (${snapshot.packageName}).",
                    snapshot = snapshot
                )
            }
            val chosen = scrollableCandidates.firstOrNull {
                requestedAction in it.target.supportedActions && it.target.isEnabled
            } ?: scrollableCandidates.first()

            return TargetResolutionOutcome.Resolved(
                target = chosen.target,
                targetNode = chosen.targetNode,
                actionNode = chosen.actionNode,
                snapshot = snapshot
            )
        }

        // 2. SET_TEXT without an explicit target query
        if (requestedAction == SupportedAccessibilityAction.SET_TEXT && trimmedQuery.isBlank()) {
            val editableCandidates = indexedNodes.filter {
                it.target.isEditable || SupportedAccessibilityAction.SET_TEXT in it.target.supportedActions
            }
            if (editableCandidates.isEmpty()) {
                return TargetResolutionOutcome.NotFound(
                    query = "editable field",
                    message = "No editable text field was found in the active window (${snapshot.packageName}).",
                    snapshot = snapshot
                )
            }
            val focusedEditable = editableCandidates.filter { it.target.isFocused }
            if (focusedEditable.size == 1) {
                val chosen = focusedEditable.first()
                return TargetResolutionOutcome.Resolved(
                    target = chosen.target,
                    targetNode = chosen.targetNode,
                    actionNode = chosen.actionNode,
                    snapshot = snapshot
                )
            }
            if (editableCandidates.size == 1) {
                val chosen = editableCandidates.first()
                return TargetResolutionOutcome.Resolved(
                    target = chosen.target,
                    targetNode = chosen.targetNode,
                    actionNode = chosen.actionNode,
                    snapshot = snapshot
                )
            }
            return TargetResolutionOutcome.Ambiguous(
                query = "editable field",
                candidates = editableCandidates.map { it.target },
                snapshot = snapshot
            )
        }

        if (trimmedQuery.isBlank()) {
            return TargetResolutionOutcome.NotFound(
                query = "",
                message = "Please specify which visible UI element to interact with.",
                snapshot = snapshot
            )
        }

        // 3. Explicit #index lookup from current snapshot
        val indexMatch = Regex("""^#?(\d+)$""").find(trimmedQuery)
        if (indexMatch != null) {
            val idx = indexMatch.groupValues[1].toIntOrNull()
            if (idx != null) {
                val byIndex = indexedNodes.firstOrNull { it.target.targetIndex == idx }
                if (byIndex != null) {
                    return TargetResolutionOutcome.Resolved(
                        target = byIndex.target,
                        targetNode = byIndex.targetNode,
                        actionNode = byIndex.actionNode,
                        snapshot = snapshot
                    )
                }
            }
        }

        // 4. Tiered semantic matching (Exact -> Whole Word -> Substring)
        val tier3Exact = mutableListOf<IndexedLiveNode>()
        val tier2WholeWord = mutableListOf<IndexedLiveNode>()
        val tier1Substring = mutableListOf<IndexedLiveNode>()

        val lowerQuery = trimmedQuery.lowercase()
        val wholeWordRegex = Regex("""\b${Regex.escape(lowerQuery)}\b""", RegexOption.IGNORE_CASE)

        for (entry in indexedNodes) {
            val t = entry.target
            val text = t.visibleText.trim()
            val desc = t.contentDescription.trim()
            val shortId = t.viewIdResourceName.substringAfterLast('/', t.viewIdResourceName).trim()

            val isExact = (text.isNotEmpty() && text.equals(trimmedQuery, ignoreCase = true)) ||
                (desc.isNotEmpty() && desc.equals(trimmedQuery, ignoreCase = true)) ||
                (shortId.isNotEmpty() && shortId.equals(trimmedQuery, ignoreCase = true))

            if (isExact) {
                tier3Exact.add(entry)
                continue
            }

            val isWholeWord = (text.isNotEmpty() && wholeWordRegex.containsMatchIn(text)) ||
                (desc.isNotEmpty() && wholeWordRegex.containsMatchIn(desc))
            if (isWholeWord) {
                tier2WholeWord.add(entry)
                continue
            }

            if (lowerQuery.length >= 2) {
                val isSub = text.lowercase().contains(lowerQuery) ||
                    desc.lowercase().contains(lowerQuery) ||
                    shortId.lowercase().contains(lowerQuery)
                if (isSub) {
                    tier1Substring.add(entry)
                }
            }
        }

        val highestTier = when {
            tier3Exact.isNotEmpty() -> tier3Exact
            tier2WholeWord.isNotEmpty() -> tier2WholeWord
            tier1Substring.isNotEmpty() -> tier1Substring
            else -> emptyList()
        }

        if (highestTier.isEmpty()) {
            return TargetResolutionOutcome.NotFound(
                query = trimmedQuery,
                message = "No visible UI element matching \"$trimmedQuery\" was found in ${snapshot.packageName}.",
                snapshot = snapshot
            )
        }

        // If only one candidate matched in the highest confidence tier, resolve it
        if (highestTier.size == 1) {
            val unique = highestTier.first()
            return TargetResolutionOutcome.Resolved(
                target = unique.target,
                targetNode = unique.targetNode,
                actionNode = unique.actionNode,
                snapshot = snapshot
            )
        }

        // If multiple candidates matched, check if exactly one supports the requested action
        val actionCompatible = highestTier.filter {
            requestedAction in it.target.supportedActions
        }
        if (actionCompatible.size == 1) {
            val uniqueCompatible = actionCompatible.first()
            return TargetResolutionOutcome.Resolved(
                target = uniqueCompatible.target,
                targetNode = uniqueCompatible.targetNode,
                actionNode = uniqueCompatible.actionNode,
                snapshot = snapshot
            )
        }

        // Multiple actionable targets matched with equal confidence -> refuse to guess!
        val ambiguousList = if (actionCompatible.size > 1) {
            actionCompatible.map { it.target }
        } else {
            highestTier.map { it.target }
        }
        return TargetResolutionOutcome.Ambiguous(
            query = trimmedQuery,
            candidates = ambiguousList,
            snapshot = snapshot
        )
    }

    /**
     * Executes the complete M6 semantic action lifecycle:
     * 1. Resolve target in current tree
     * 2. Validate non-stale, enabled, and action-supported
     * 3. Re-resolve if needed
     * 4. Execute real [AccessibilityNodeInfo] action
     * 5. Re-read tree and verify post-action state change
     */
    suspend fun executeAndVerifySemanticAction(
        rootProvider: () -> AccessibilityNodeAdapter?,
        targetQuery: String,
        requestedAction: SupportedAccessibilityAction,
        textArgument: String? = null,
        activePackageOverride: String = "",
        windowTitleOverride: String = "",
        lowRamMode: Boolean = false,
        settleDelayMs: Long = 250L
    ): VerifiedAccessibilityActionOutcome {
        val initialRoot = rootProvider()
        val initialResolution = resolveTargetInTree(
            rootAdapter = initialRoot,
            targetQuery = targetQuery,
            requestedAction = requestedAction,
            activePackageOverride = activePackageOverride,
            windowTitleOverride = windowTitleOverride,
            lowRamMode = lowRamMode
        )

        val resolved: TargetResolutionOutcome.Resolved = when (initialResolution) {
            is TargetResolutionOutcome.WindowUnavailable -> {
                return VerifiedAccessibilityActionOutcome(
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    failureReason = AccessibilityActionFailureReason.WINDOW_UNAVAILABLE,
                    action = requestedAction,
                    targetPackage = activePackageOverride.ifBlank { "Unavailable" },
                    verificationDetail = initialResolution.message,
                    preStateSummary = "ActiveWindow=Unavailable",
                    postStateSummary = "Unchanged",
                    userFacingMessage = "The active window did not expose an accessible screen hierarchy."
                )
            }

            is TargetResolutionOutcome.NotFound -> {
                return VerifiedAccessibilityActionOutcome(
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    failureReason = AccessibilityActionFailureReason.TARGET_NOT_FOUND,
                    action = requestedAction,
                    targetPackage = initialResolution.snapshot.packageName,
                    preSnapshot = initialResolution.snapshot,
                    verificationDetail = initialResolution.message,
                    preStateSummary = "Pkg=${initialResolution.snapshot.packageName} (#targets=${initialResolution.snapshot.nodes.size})",
                    postStateSummary = "Target not found",
                    userFacingMessage = initialResolution.message
                )
            }

            is TargetResolutionOutcome.Ambiguous -> {
                val candidateLabels = initialResolution.candidates.take(4).joinToString(", ") {
                    "[#${it.targetIndex}] ${it.semanticDescription}"
                }
                val clarificationMsg =
                    "Multiple UI elements matched \"${initialResolution.query}\" (${initialResolution.candidates.size} matches: $candidateLabels). Please specify which one you mean (for example by #index or full label)."
                return VerifiedAccessibilityActionOutcome(
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    failureReason = AccessibilityActionFailureReason.AMBIGUOUS_TARGET,
                    action = requestedAction,
                    targetPackage = initialResolution.snapshot.packageName,
                    ambiguousCandidates = initialResolution.candidates,
                    preSnapshot = initialResolution.snapshot,
                    verificationDetail = "Refused to guess among ${initialResolution.candidates.size} ambiguous targets matching '${initialResolution.query}': $candidateLabels",
                    preStateSummary = "Ambiguous matches=${initialResolution.candidates.size}",
                    postStateSummary = "Clarification required (no action executed)",
                    userFacingMessage = clarificationMsg
                )
            }

            is TargetResolutionOutcome.Resolved -> initialResolution
        }

        // Pre-execution step 1: Confirm target is still valid (not stale/recycled); re-resolve once if stale
        var activeResolved = resolved
        if (!activeResolved.targetNode.isNodeValid() ||
            !activeResolved.actionNode.isNodeValid() ||
            !activeResolved.targetNode.isVisibleToUser
        ) {
            val freshRoot = rootProvider()
            val reResolved = resolveTargetInTree(
                rootAdapter = freshRoot,
                targetQuery = targetQuery,
                requestedAction = requestedAction,
                activePackageOverride = activePackageOverride,
                windowTitleOverride = windowTitleOverride,
                lowRamMode = lowRamMode
            )
            if (reResolved is TargetResolutionOutcome.Resolved &&
                reResolved.targetNode.isNodeValid() &&
                reResolved.actionNode.isNodeValid() &&
                reResolved.targetNode.isVisibleToUser
            ) {
                activeResolved = reResolved
            } else {
                return VerifiedAccessibilityActionOutcome(
                    outcome = VerificationOutcome.VERIFICATION_FAILED,
                    failureReason = AccessibilityActionFailureReason.STALE_TARGET,
                    action = requestedAction,
                    targetPackage = activeResolved.snapshot.packageName,
                    resolvedTarget = activeResolved.target,
                    preSnapshot = activeResolved.snapshot,
                    verificationDetail = "Target '${activeResolved.target.semanticDescription}' became stale or detached before execution and could not be safely re-resolved.",
                    preStateSummary = "Target=${activeResolved.target.semanticDescription}",
                    postStateSummary = "Stale target rejected",
                    userFacingMessage = "The target element \"${activeResolved.target.primaryLabel()}\" changed or disappeared before it could be activated."
                )
            }
        }

        val target = activeResolved.target
        val preSnapshot = activeResolved.snapshot
        val pkg = preSnapshot.packageName

        // Pre-execution step 2: Confirm target is enabled
        if (!target.isEnabled || !activeResolved.actionNode.isEnabled) {
            return VerifiedAccessibilityActionOutcome(
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                failureReason = AccessibilityActionFailureReason.TARGET_DISABLED,
                action = requestedAction,
                targetPackage = pkg,
                resolvedTarget = target,
                preSnapshot = preSnapshot,
                verificationDetail = "Rejected ${requestedAction.name} because target '${target.semanticDescription}' is disabled (isEnabled=false).",
                preStateSummary = "Target=${target.semanticDescription} (enabled=false)",
                postStateSummary = "Rejected disabled target",
                userFacingMessage = "Cannot perform ${requestedAction.name.lowercase().replace('_', ' ')} because \"${target.primaryLabel()}\" is currently disabled."
            )
        }

        // Pre-execution step 3: Confirm requested action is supported by the target
        val isActionSupported = when (requestedAction) {
            SupportedAccessibilityAction.SET_TEXT ->
                target.isEditable && SupportedAccessibilityAction.SET_TEXT in target.supportedActions
            SupportedAccessibilityAction.SCROLL_FORWARD,
            SupportedAccessibilityAction.SCROLL_BACKWARD ->
                target.isScrollable && requestedAction in target.supportedActions
            else ->
                requestedAction in target.supportedActions
        }
        if (!isActionSupported) {
            return VerifiedAccessibilityActionOutcome(
                outcome = VerificationOutcome.UNSUPPORTED,
                failureReason = AccessibilityActionFailureReason.UNSUPPORTED_ACTION,
                action = requestedAction,
                targetPackage = pkg,
                resolvedTarget = target,
                preSnapshot = preSnapshot,
                verificationDetail = "Target '${target.semanticDescription}' does not support ${requestedAction.name} (supported=${target.supportedActions}).",
                preStateSummary = "Target=${target.semanticDescription}",
                postStateSummary = "Unsupported action ${requestedAction.name}",
                userFacingMessage = "The element \"${target.primaryLabel()}\" does not support ${requestedAction.name.lowercase().replace('_', ' ')}."
            )
        }

        // Execute real AccessibilityNodeInfo action
        if (requestedAction == SupportedAccessibilityAction.SET_TEXT) {
            activeResolved.actionNode.performAction(SupportedAccessibilityAction.FOCUS)
        }
        val performed = activeResolved.actionNode.performAction(
            action = requestedAction,
            textArgument = textArgument
        )

        if (!performed) {
            return VerifiedAccessibilityActionOutcome(
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                failureReason = AccessibilityActionFailureReason.PERFORM_ACTION_RETURNED_FALSE,
                action = requestedAction,
                targetPackage = pkg,
                resolvedTarget = target,
                preSnapshot = preSnapshot,
                verificationDetail = "AccessibilityNodeInfo.performAction(${requestedAction.name}) returned false on '${target.semanticDescription}'.",
                preStateSummary = "Target=${target.semanticDescription}",
                postStateSummary = "performAction returned false",
                userFacingMessage = "Android rejected ${requestedAction.name.lowercase().replace('_', ' ')} on \"${target.primaryLabel()}\"."
            )
        }

        // Mandatory Post-Action Verification (Requirement E)
        if (settleDelayMs > 0L) {
            delay(settleDelayMs)
        }

        val postRoot = rootProvider()
        val postSnapshot = SemanticScreenExtractor.extractSnapshotFromAdapter(
            rootAdapter = postRoot,
            activePackageOverride = activePackageOverride,
            windowTitleOverride = windowTitleOverride,
            lowRamMode = lowRamMode
        )

        val verification = verifyPostActionState(
            preSnapshot = preSnapshot,
            postSnapshot = postSnapshot,
            preTarget = target,
            action = requestedAction,
            textArgument = textArgument
        )

        val finalPkg = postSnapshot?.packageName ?: pkg
        return if (verification.verified) {
            VerifiedAccessibilityActionOutcome(
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                failureReason = null,
                action = requestedAction,
                targetPackage = finalPkg,
                resolvedTarget = verification.reResolvedTarget ?: target,
                preSnapshot = preSnapshot,
                postSnapshot = postSnapshot,
                verificationDetail = verification.detail,
                preStateSummary = verification.preStateSummary,
                postStateSummary = verification.postStateSummary,
                userFacingMessage = buildVerifiedSuccessMessage(
                    action = requestedAction,
                    target = target,
                    targetPackage = finalPkg
                )
            )
        } else {
            VerifiedAccessibilityActionOutcome(
                outcome = VerificationOutcome.VERIFICATION_FAILED,
                failureReason = AccessibilityActionFailureReason.POST_ACTION_VERIFICATION_FAILED,
                action = requestedAction,
                targetPackage = finalPkg,
                resolvedTarget = verification.reResolvedTarget ?: target,
                preSnapshot = preSnapshot,
                postSnapshot = postSnapshot,
                verificationDetail = verification.detail,
                preStateSummary = verification.preStateSummary,
                postStateSummary = verification.postStateSummary,
                userFacingMessage = "Dispatched ${requestedAction.name.lowercase().replace('_', ' ')} on \"${target.primaryLabel()}\", but post-action verification could not confirm any resulting screen state change."
            )
        }
    }

    /**
     * Pure, deterministic post-action verifier (Requirement E).
     * Never treats `performAction(...) == true` as sufficient proof; requires an observable
     * semantic change in the re-read accessibility tree.
     */
    fun verifyPostActionState(
        preSnapshot: SemanticScreenSnapshot,
        postSnapshot: SemanticScreenSnapshot?,
        preTarget: AccessibilityTarget,
        action: SupportedAccessibilityAction,
        textArgument: String? = null
    ): PostActionVerificationResult {
        val preSummary = "Pkg=${preSnapshot.packageName}, Target=${preTarget.semanticDescription}, Sig=${preSnapshot.signatureHash()}"
        if (postSnapshot == null) {
            return PostActionVerificationResult(
                verified = false,
                detail = "Post-action verification failed: active window root became null after ${action.name}.",
                preStateSummary = preSummary,
                postStateSummary = "PostWindow=null"
            )
        }

        val postTargets = postSnapshot.targets
        val reIdentified = reIdentifyTargetInSnapshot(preTarget, postTargets)

        return when (action) {
            SupportedAccessibilityAction.SET_TEXT -> {
                val expectedSanitized = SensitiveDataRedactor.sanitizeNodeText(
                    rawText = textArgument.orEmpty().trim(),
                    isPasswordNode = false,
                    viewIdResourceName = preTarget.viewIdResourceName
                ).sanitizedText

                val postTarget = reIdentified
                    ?: postTargets.firstOrNull {
                        it.isEditable &&
                            it.viewIdResourceName == preTarget.viewIdResourceName &&
                            it.className == preTarget.className
                    }

                val textMatched = if (postTarget != null) {
                    (expectedSanitized.isNotEmpty() &&
                        postTarget.visibleText.contains(expectedSanitized, ignoreCase = true)) ||
                        (postTarget.wasRedacted && postTarget.visibleText != preTarget.visibleText)
                } else {
                    false
                }

                if (textMatched && postTarget != null) {
                    PostActionVerificationResult(
                        verified = true,
                        detail = "Verified ACTION_SET_TEXT: re-identified editable target '${postTarget.semanticDescription}' now reflects updated text.",
                        preStateSummary = "PreText='${preTarget.visibleText}'",
                        postStateSummary = "PostText='${postTarget.visibleText}'",
                        reResolvedTarget = postTarget
                    )
                } else {
                    PostActionVerificationResult(
                        verified = false,
                        detail = "Post-action verification failed for ACTION_SET_TEXT: re-read editable field did not reflect expected text (postText='${postTarget?.visibleText ?: "target_missing"}').",
                        preStateSummary = "PreText='${preTarget.visibleText}'",
                        postStateSummary = "PostText='${postTarget?.visibleText ?: "missing"}'",
                        reResolvedTarget = postTarget
                    )
                }
            }

            SupportedAccessibilityAction.FOCUS -> {
                val focusedVerified = reIdentified?.isFocused == true
                PostActionVerificationResult(
                    verified = focusedVerified,
                    detail = if (focusedVerified) {
                        "Verified ACTION_FOCUS: target '${reIdentified?.semanticDescription}' now has input/accessibility focus (isFocused=true)."
                    } else {
                        "Post-action verification failed for ACTION_FOCUS: target '${preTarget.semanticDescription}' did not acquire focus."
                    },
                    preStateSummary = "PreFocused=${preTarget.isFocused}",
                    postStateSummary = "PostFocused=${reIdentified?.isFocused ?: false}",
                    reResolvedTarget = reIdentified
                )
            }

            SupportedAccessibilityAction.SELECT -> {
                val selectedVerified = reIdentified?.isSelected == true
                PostActionVerificationResult(
                    verified = selectedVerified,
                    detail = if (selectedVerified) {
                        "Verified ACTION_SELECT: target '${reIdentified?.semanticDescription}' is now selected (isSelected=true)."
                    } else {
                        "Post-action verification failed for ACTION_SELECT: target '${preTarget.semanticDescription}' did not become selected."
                    },
                    preStateSummary = "PreSelected=${preTarget.isSelected}",
                    postStateSummary = "PostSelected=${reIdentified?.isSelected ?: false}",
                    reResolvedTarget = reIdentified
                )
            }

            SupportedAccessibilityAction.SCROLL_FORWARD,
            SupportedAccessibilityAction.SCROLL_BACKWARD -> {
                val preSig = preSnapshot.signatureHash()
                val postSig = postSnapshot.signatureHash()
                val boundsOrNodesChanged = preSig != postSig ||
                    preSnapshot.nodes.map { n -> "${n.text}:${n.boundsInScreen.left},${n.boundsInScreen.top},${n.boundsInScreen.right},${n.boundsInScreen.bottom}" } !=
                    postSnapshot.nodes.map { n -> "${n.text}:${n.boundsInScreen.left},${n.boundsInScreen.top},${n.boundsInScreen.right},${n.boundsInScreen.bottom}" }

                PostActionVerificationResult(
                    verified = boundsOrNodesChanged,
                    detail = if (boundsOrNodesChanged) {
                        "Verified ${action.name}: semantic tree content/bounds shifted after scroll (preSig=$preSig -> postSig=$postSig)."
                    } else {
                        "Post-action verification failed for ${action.name}: screen hierarchy and node bounds remained completely unchanged after scroll."
                    },
                    preStateSummary = "PreSig=$preSig (#nodes=${preSnapshot.nodes.size})",
                    postStateSummary = "PostSig=$postSig (#nodes=${postSnapshot.nodes.size})",
                    reResolvedTarget = reIdentified
                )
            }

            SupportedAccessibilityAction.CLICK,
            SupportedAccessibilityAction.LONG_CLICK -> {
                val packageChanged = postSnapshot.packageName != preSnapshot.packageName
                val titleChanged = postSnapshot.windowTitle != preSnapshot.windowTitle
                val targetStateChanged = if (reIdentified != null) {
                    reIdentified.isChecked != preTarget.isChecked ||
                        reIdentified.isSelected != preTarget.isSelected ||
                        reIdentified.isFocused != preTarget.isFocused ||
                        reIdentified.isEnabled != preTarget.isEnabled ||
                        reIdentified.visibleText != preTarget.visibleText ||
                        reIdentified.contentDescription != preTarget.contentDescription
                } else {
                    false
                }
                val hierarchyChanged = postSnapshot.signatureHash() != preSnapshot.signatureHash() ||
                    postSnapshot.nodes.size != preSnapshot.nodes.size ||
                    (reIdentified == null && postTargets.isNotEmpty())

                val verified = packageChanged || titleChanged || targetStateChanged || hierarchyChanged
                PostActionVerificationResult(
                    verified = verified,
                    detail = if (verified) {
                        "Verified ${action.name} on '${preTarget.semanticDescription}': " +
                            "pkgChanged=$packageChanged, titleChanged=$titleChanged, " +
                            "targetStateChanged=$targetStateChanged, hierarchyChanged=$hierarchyChanged."
                    } else {
                        "Post-action verification failed for ${action.name}: target '${preTarget.semanticDescription}' and active window (${postSnapshot.packageName}) showed zero state or hierarchy change."
                    },
                    preStateSummary = preSummary,
                    postStateSummary = "Pkg=${postSnapshot.packageName}, Sig=${postSnapshot.signatureHash()}, TargetPresent=${reIdentified != null}",
                    reResolvedTarget = reIdentified
                )
            }
        }
    }

    private fun reIdentifyTargetInSnapshot(
        preTarget: AccessibilityTarget,
        postTargets: List<AccessibilityTarget>
    ): AccessibilityTarget? {
        // 1. Exact semantic identity key match
        val preKey = preTarget.semanticIdentityKey()
        val exactMatches = postTargets.filter { it.semanticIdentityKey() == preKey }
        if (exactMatches.size == 1) return exactMatches.first()

        // 2. Same non-blank viewIdResourceName + className
        if (preTarget.viewIdResourceName.isNotBlank()) {
            val idMatches = postTargets.filter {
                it.viewIdResourceName == preTarget.viewIdResourceName &&
                    it.className == preTarget.className
            }
            if (idMatches.size == 1) return idMatches.first()
        }

        // 3. Same className and same index/supporting bounds when text or checked state mutated
        val sameSlot = postTargets.firstOrNull {
            it.className == preTarget.className &&
                it.targetIndex == preTarget.targetIndex &&
                (it.viewIdResourceName == preTarget.viewIdResourceName ||
                    sameBounds(it.boundsInScreen, preTarget.boundsInScreen))
        }
        return sameSlot
    }

    private fun sameBounds(a: android.graphics.Rect, b: android.graphics.Rect): Boolean {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom
    }

    private fun buildVerifiedSuccessMessage(
        action: SupportedAccessibilityAction,
        target: AccessibilityTarget,
        targetPackage: String
    ): String {
        val label = target.primaryLabel()
        val pkgDisplay = if (targetPackage.isNotBlank() && targetPackage != "Unavailable") {
            " in $targetPackage"
        } else {
            ""
        }
        return when (action) {
            SupportedAccessibilityAction.CLICK -> "Clicked \"$label\"$pkgDisplay and verified screen update."
            SupportedAccessibilityAction.LONG_CLICK -> "Long-pressed \"$label\"$pkgDisplay and verified screen update."
            SupportedAccessibilityAction.FOCUS -> "Focused \"$label\"$pkgDisplay."
            SupportedAccessibilityAction.SET_TEXT -> "Entered text into \"$label\"$pkgDisplay and verified field update."
            SupportedAccessibilityAction.SCROLL_FORWARD -> "Scrolled forward$pkgDisplay and verified content shift."
            SupportedAccessibilityAction.SCROLL_BACKWARD -> "Scrolled backward$pkgDisplay and verified content shift."
            SupportedAccessibilityAction.SELECT -> "Selected \"$label\"$pkgDisplay."
        }
    }

    private fun collectIndexedLiveNodes(
        rootAdapter: AccessibilityNodeAdapter,
        snapshot: SemanticScreenSnapshot,
        lowRamMode: Boolean
    ): List<IndexedLiveNode> {
        val maxTargets = if (lowRamMode) {
            SemanticScreenExtractor.MAX_TARGETS_LOW_RAM
        } else {
            SemanticScreenExtractor.MAX_TARGETS_STANDARD
        }
        val maxVisited = if (lowRamMode) {
            SemanticScreenExtractor.MAX_VISITED_LOW_RAM
        } else {
            SemanticScreenExtractor.MAX_VISITED_STANDARD
        }
        val maxDepth = if (lowRamMode) {
            SemanticScreenExtractor.MAX_DEPTH_LOW_RAM
        } else {
            SemanticScreenExtractor.MAX_DEPTH_STANDARD
        }

        val liveNodes = mutableListOf<IndexedLiveNode>()
        val visitedNodeIds = HashSet<Int>()
        var rawTraversed = 0

        fun traverse(node: AccessibilityNodeAdapter?, depth: Int) {
            if (node == null) return
            if (depth > maxDepth || liveNodes.size >= maxTargets || rawTraversed >= maxVisited) return
            if (!node.isNodeValid()) return
            if (!visitedNodeIds.add(node.nodeIdentityId)) return

            rawTraversed++

            if (node.isVisibleToUser) {
                val rawText = node.text?.trim().orEmpty()
                val rawDesc = node.contentDescription?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.trim().orEmpty()
                val bounds = node.boundsInScreen
                val boundsWidth = bounds.right - bounds.left
                val boundsHeight = bounds.bottom - bounds.top
                val hasValidBounds = boundsWidth > 2 && boundsHeight > 2

                val clickableActionNode = findClickableSelfOrAncestor(node)
                val effectiveClickable = node.isClickable || clickableActionNode != null
                val effectiveActions = buildSet {
                    addAll(node.supportedActions)
                    if (effectiveClickable) add(SupportedAccessibilityAction.CLICK)
                }

                val isInteractive = effectiveClickable ||
                    node.isLongClickable ||
                    node.isEditable ||
                    node.isScrollable ||
                    node.isCheckable ||
                    node.isFocusable ||
                    SupportedAccessibilityAction.SELECT in effectiveActions
                val hasSemanticLabel = rawText.isNotBlank() || rawDesc.isNotBlank() || viewId.isNotBlank()

                if (hasValidBounds && (isInteractive || hasSemanticLabel)) {
                    val nextIndex = liveNodes.size + 1
                    val matchingSnapshotNode = snapshot.nodes.getOrNull(nextIndex - 1)
                    if (matchingSnapshotNode != null) {
                        liveNodes.add(
                            IndexedLiveNode(
                                target = matchingSnapshotNode.toAccessibilityTarget(),
                                targetNode = node,
                                actionNode = if (node.isClickable) node else (clickableActionNode ?: node)
                            )
                        )
                    }
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (liveNodes.size >= maxTargets || rawTraversed >= maxVisited) break
                val child = node.getChild(i)
                if (child != null) {
                    traverse(child, depth + 1)
                }
            }
        }

        traverse(rootAdapter, 0)
        return liveNodes
    }

    private fun findClickableSelfOrAncestor(
        node: AccessibilityNodeAdapter,
        maxLevels: Int = 3
    ): AccessibilityNodeAdapter? {
        if (node.isClickable) return node
        var current: AccessibilityNodeAdapter? = node.getParent()
        var level = 0
        while (current != null && level < maxLevels) {
            if (current.isNodeValid() && current.isClickable) {
                return current
            }
            current = current.getParent()
            level++
        }
        return null
    }

    fun performGlobalNavigation(
        service: NovaAccessibilityService,
        navigationCommand: String
    ): RawActionStatus {
        val cmd = navigationCommand.trim().lowercase()
        val globalAction = when {
            cmd.contains("back") -> AccessibilityService.GLOBAL_ACTION_BACK
            cmd.contains("home") -> AccessibilityService.GLOBAL_ACTION_HOME
            cmd.contains("recent") || cmd.contains("overview") -> AccessibilityService.GLOBAL_ACTION_RECENTS
            cmd.contains("notification") -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            cmd.contains("quick") -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return RawActionStatus(false, "Unsupported global navigation command: '$navigationCommand'.")
        }
        val ok = service.performGlobalAction(globalAction)
        return if (ok) {
            RawActionStatus(true, "Executed Android global action: ${cmd.uppercase()}.")
        } else {
            RawActionStatus(false, "Android OS rejected global action: ${cmd.uppercase()}.")
        }
    }

    /**
     * M6 Requirement F — Safety & Confirmation Gate assessment for semantic accessibility actions.
     */
    data class ConfirmationGateAssessment(
        val requiresConfirmation: Boolean,
        val reason: String = ""
    )

    private val consequentialTargetKeywords = listOf(
        "delete",
        "remove",
        "uninstall",
        "erase",
        "wipe",
        "format",
        "reset",
        "clear all",
        "send",
        "submit",
        "pay",
        "purchase",
        "checkout",
        "transfer",
        "confirm",
        "approve",
        "authorize",
        "grant",
        "allow",
        "sign out",
        "log out"
    )

    private val sensitiveFieldKeywords = listOf(
        "password",
        "passcode",
        "pin",
        "otp",
        "cvv",
        "cvc",
        "card",
        "ssn",
        "secret",
        "token",
        "credit"
    )

    private val sensitiveContextPackages = listOf(
        "packageinstaller",
        "permissioncontroller",
        "wallet",
        "bank",
        "pay",
        "biometric",
        "authenticator",
        "keychain"
    )

    fun evaluateConfirmationRequirement(
        requestedAction: SupportedAccessibilityAction,
        resolvedTarget: AccessibilityTarget? = null,
        activePackage: String = "",
        rawTargetQuery: String = "",
        textArgument: String? = null
    ): ConfirmationGateAssessment {
        // Low-risk focus or scroll actions do not require destructive confirmation
        if (requestedAction == SupportedAccessibilityAction.FOCUS ||
            requestedAction == SupportedAccessibilityAction.SCROLL_FORWARD ||
            requestedAction == SupportedAccessibilityAction.SCROLL_BACKWARD
        ) {
            return ConfirmationGateAssessment(requiresConfirmation = false)
        }

        val combinedLabels = buildString {
            append(rawTargetQuery.lowercase())
            if (resolvedTarget != null) {
                append(' ')
                append(resolvedTarget.visibleText.lowercase())
                append(' ')
                append(resolvedTarget.contentDescription.lowercase())
                append(' ')
                append(resolvedTarget.viewIdResourceName.lowercase())
            }
        }

        // 1. Entering text into sensitive / password / financial fields
        if (requestedAction == SupportedAccessibilityAction.SET_TEXT) {
            val isSensitiveField = resolvedTarget?.isSensitiveRedacted == true ||
                sensitiveFieldKeywords.any { combinedLabels.contains(it) } ||
                SensitiveDataRedactor.sanitizeNodeText(
                    rawText = textArgument.orEmpty(),
                    isPasswordNode = false,
                    viewIdResourceName = resolvedTarget?.viewIdResourceName.orEmpty()
                ).wasRedacted

            if (isSensitiveField) {
                return ConfirmationGateAssessment(
                    requiresConfirmation = true,
                    reason = "Entering text into sensitive or credential field '${resolvedTarget?.primaryLabel() ?: rawTargetQuery}' requires explicit user confirmation."
                )
            }
        }

        // 2. Tapping or activating destructive, submission, or consequential controls
        val matchedKeyword = consequentialTargetKeywords.firstOrNull { kw ->
            Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(combinedLabels)
        }
        if (matchedKeyword != null) {
            return ConfirmationGateAssessment(
                requiresConfirmation = true,
                reason = "Consequential accessibility action (${requestedAction.name}) on '${resolvedTarget?.primaryLabel() ?: rawTargetQuery}' matched '$matchedKeyword' and requires user confirmation."
            )
        }

        // 3. Performing actions in financial, authentication, or system permission contexts
        val lowerPkg = activePackage.lowercase()
        val matchedPkg = sensitiveContextPackages.firstOrNull { lowerPkg.contains(it) }
        if (matchedPkg != null) {
            return ConfirmationGateAssessment(
                requiresConfirmation = true,
                reason = "Accessibility action (${requestedAction.name}) inside sensitive context '$activePackage' requires explicit user confirmation."
            )
        }

        return ConfirmationGateAssessment(requiresConfirmation = false)
    }

    /**
     * M6 Requirement A & G — Pure, truthful builder for [AccessibilityWorkspaceState].
     * Never infers [AccessibilityWorkspaceStatus.SERVICE_CONNECTED] merely because the service
     * is enabled in Settings.
     */
    fun buildWorkspaceState(
        serviceEnabledInSettings: Boolean,
        serviceConnected: Boolean,
        snapshot: SemanticScreenSnapshot?,
        overlayState: OverlayAttachmentState = OverlayAttachmentState.SERVICE_NOT_BOUND,
        actionInProgressDescription: String = "",
        latestActionResult: VerifiedActionResult? = null,
        ambiguousCandidates: List<AccessibilityTarget> = emptyList(),
        visionFallbackStatus: ScreenUnderstandingFallbackStatus =
            ScreenUnderstandingFallback.queryFallbackStatus().status,
        hasInspectedWindow: Boolean = true,
        explicitErrorMessage: String? = null
    ): AccessibilityWorkspaceState {
        val status = when {
            explicitErrorMessage != null -> AccessibilityWorkspaceStatus.ERROR

            !serviceConnected -> {
                if (!serviceEnabledInSettings) {
                    AccessibilityWorkspaceStatus.SERVICE_DISABLED
                } else {
                    AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED
                }
            }

            actionInProgressDescription.isNotBlank() -> AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS

            latestActionResult != null &&
                latestActionResult.toolName !in setOf("read_screen", "query_accessibility_status") -> {
                when (latestActionResult.outcome) {
                    VerificationOutcome.VERIFIED_SUCCESS ->
                        AccessibilityWorkspaceStatus.ACTION_VERIFIED
                    VerificationOutcome.PERMISSION_REQUIRED,
                    VerificationOutcome.AWAITING_CONFIRMATION ->
                        AccessibilityWorkspaceStatus.PERMISSION_REQUIRED
                    VerificationOutcome.UNSUPPORTED ->
                        AccessibilityWorkspaceStatus.UNSUPPORTED
                    VerificationOutcome.SERVICE_DISABLED ->
                        if (serviceEnabledInSettings) {
                            AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED
                        } else {
                            AccessibilityWorkspaceStatus.SERVICE_DISABLED
                        }
                    VerificationOutcome.VERIFICATION_FAILED,
                    VerificationOutcome.CONFIGURATION_REQUIRED ->
                        AccessibilityWorkspaceStatus.ACTION_FAILED
                    VerificationOutcome.USER_CANCELLED ->
                        if (snapshot != null) {
                            AccessibilityWorkspaceStatus.ACTIVE_WINDOW_AVAILABLE
                        } else {
                            AccessibilityWorkspaceStatus.ACTIVE_WINDOW_UNAVAILABLE
                        }
                }
            }

            !hasInspectedWindow -> AccessibilityWorkspaceStatus.SERVICE_CONNECTED

            snapshot != null -> AccessibilityWorkspaceStatus.ACTIVE_WINDOW_AVAILABLE

            else -> AccessibilityWorkspaceStatus.ACTIVE_WINDOW_UNAVAILABLE
        }

        val activeWindowAvailable = serviceConnected && snapshot != null
        val activePackageField = if (snapshot != null &&
            snapshot.packageName.isNotBlank() &&
            snapshot.packageName != "Unavailable"
        ) {
            DeviceFieldState.Available(snapshot.packageName)
        } else {
            DeviceFieldState.Unavailable("Active package unavailable")
        }

        val activeTitleField = if (snapshot != null &&
            snapshot.windowTitle.isNotBlank() &&
            snapshot.windowTitle != "Unavailable"
        ) {
            DeviceFieldState.Available(snapshot.windowTitle)
        } else {
            DeviceFieldState.Unavailable("Window title unavailable")
        }

        val statusMessage = explicitErrorMessage
            ?: latestActionResult?.userFacingMessage?.takeIf { it.isNotBlank() }
            ?: when (status) {
                AccessibilityWorkspaceStatus.SERVICE_DISABLED ->
                    "Nova Accessibility Service is disabled in Android Settings."
                AccessibilityWorkspaceStatus.SERVICE_NOT_CONNECTED ->
                    "Nova is enabled in Settings, but the OS has not bound NovaAccessibilityService yet."
                AccessibilityWorkspaceStatus.SERVICE_CONNECTED ->
                    "Nova Accessibility Service is connected."
                AccessibilityWorkspaceStatus.ACTIVE_WINDOW_AVAILABLE ->
                    "Inspected ${snapshot?.targets?.size ?: 0} semantic target(s) in ${snapshot?.packageName ?: "active window"}."
                AccessibilityWorkspaceStatus.ACTIVE_WINDOW_UNAVAILABLE ->
                    "AccessibilityService is connected, but the active window did not expose a root hierarchy."
                AccessibilityWorkspaceStatus.ACTION_IN_PROGRESS ->
                    actionInProgressDescription
                AccessibilityWorkspaceStatus.ACTION_VERIFIED ->
                    "Accessibility action succeeded and post-action state change was verified."
                AccessibilityWorkspaceStatus.ACTION_FAILED ->
                    "Accessibility action or post-action verification failed."
                AccessibilityWorkspaceStatus.PERMISSION_REQUIRED ->
                    "Explicit user confirmation is required before executing this accessibility action."
                AccessibilityWorkspaceStatus.UNSUPPORTED ->
                    "The requested accessibility action or fallback capability is unsupported."
                AccessibilityWorkspaceStatus.ERROR ->
                    "An unexpected Accessibility runtime error occurred."
            }

        return AccessibilityWorkspaceState(
            status = status,
            serviceEnabledInSettings = serviceEnabledInSettings,
            serviceConnected = serviceConnected,
            activeWindowAvailable = activeWindowAvailable,
            activePackage = activePackageField,
            activeWindowTitle = activeTitleField,
            semanticTargets = snapshot?.targets ?: emptyList(),
            totalNodesVisited = snapshot?.totalNodesVisited ?: 0,
            redactedFieldCount = snapshot?.redactedFieldCount ?: 0,
            isLowRamTruncated = snapshot?.isLowRamTruncated ?: false,
            overlayState = overlayState,
            currentActionDescription = actionInProgressDescription,
            latestActionResult = latestActionResult,
            ambiguousCandidates = ambiguousCandidates,
            visionFallbackStatus = visionFallbackStatus,
            statusMessage = statusMessage,
            snapshot = snapshot
        )
    }
}
