package com.example.nova.accessibility

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.nova.core.SemanticScreenSnapshot
import com.example.nova.core.SemanticUiNode
import com.example.nova.core.SupportedAccessibilityAction
import com.example.nova.security.SensitiveDataRedactor

/**
 * Abstraction over an accessibility tree node used by [SemanticScreenExtractor] and
 * [AccessibilityActionPerformer].
 *
 * Production code uses [AndroidAccessibilityNodeAdapter] backed directly by Android's
 * [AccessibilityNodeInfo] from [ android.accessibilityservice.AccessibilityService.getRootInActiveWindow ].
 */
interface AccessibilityNodeAdapter {
    val nodeIdentityId: Int
    val packageName: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val viewIdResourceName: String?
    val isClickable: Boolean
    val isLongClickable: Boolean
    val isFocusable: Boolean
    val isFocused: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isSelected: Boolean
    val isEnabled: Boolean
    val isVisibleToUser: Boolean
    val isPassword: Boolean
    val boundsInScreen: Rect
    val supportedActions: Set<SupportedAccessibilityAction>
    val childCount: Int

    fun isNodeValid(): Boolean
    fun refreshNode(): Boolean
    fun getChild(index: Int): AccessibilityNodeAdapter?
    fun getParent(): AccessibilityNodeAdapter?
    fun performAction(action: SupportedAccessibilityAction, textArgument: String? = null): Boolean
    fun releaseTransientNode()
}

/**
 * Real Android [AccessibilityNodeInfo] adapter with defensive catches against stale,
 * recycled, or cross-process detached nodes.
 */
class AndroidAccessibilityNodeAdapter(
    private val node: AccessibilityNodeInfo,
    private val isRoot: Boolean = false
) : AccessibilityNodeAdapter {

    override val nodeIdentityId: Int
        get() = System.identityHashCode(node)

    override val packageName: String?
        get() = runCatching { node.packageName?.toString() }.getOrNull()

    override val className: String?
        get() = runCatching { node.className?.toString() }.getOrNull()

    override val text: String?
        get() = runCatching { node.text?.toString() }.getOrNull()

    override val contentDescription: String?
        get() = runCatching { node.contentDescription?.toString() }.getOrNull()

    override val viewIdResourceName: String?
        get() = runCatching { node.viewIdResourceName }.getOrNull()

    override val isClickable: Boolean
        get() = runCatching { node.isClickable }.getOrDefault(false)

    override val isLongClickable: Boolean
        get() = runCatching { node.isLongClickable }.getOrDefault(false)

    override val isFocusable: Boolean
        get() = runCatching { node.isFocusable }.getOrDefault(false)

    override val isFocused: Boolean
        get() = runCatching { node.isFocused }.getOrDefault(false)

    override val isEditable: Boolean
        get() = runCatching { node.isEditable }.getOrDefault(false)

    override val isScrollable: Boolean
        get() = runCatching { node.isScrollable }.getOrDefault(false)

    override val isCheckable: Boolean
        get() = runCatching { node.isCheckable }.getOrDefault(false)

    override val isChecked: Boolean
        get() = runCatching {
            @Suppress("DEPRECATION")
            node.isChecked
        }.getOrDefault(false)

    override val isSelected: Boolean
        get() = runCatching { node.isSelected }.getOrDefault(false)

    override val isEnabled: Boolean
        get() = runCatching { node.isEnabled }.getOrDefault(false)

    override val isVisibleToUser: Boolean
        get() = runCatching { node.isVisibleToUser }.getOrDefault(false)

    override val isPassword: Boolean
        get() = runCatching { node.isPassword }.getOrDefault(false)

    override val boundsInScreen: Rect
        get() = runCatching {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect
        }.getOrElse { Rect() }

    override val supportedActions: Set<SupportedAccessibilityAction>
        get() = runCatching {
            val actionIds = node.actionList?.map { it.id }?.toSet().orEmpty()
            buildSet {
                if (AccessibilityNodeInfo.ACTION_CLICK in actionIds || node.isClickable) {
                    add(SupportedAccessibilityAction.CLICK)
                }
                if (AccessibilityNodeInfo.ACTION_LONG_CLICK in actionIds || node.isLongClickable) {
                    add(SupportedAccessibilityAction.LONG_CLICK)
                }
                if (AccessibilityNodeInfo.ACTION_FOCUS in actionIds || node.isFocusable) {
                    add(SupportedAccessibilityAction.FOCUS)
                }
                if (AccessibilityNodeInfo.ACTION_SET_TEXT in actionIds || node.isEditable) {
                    add(SupportedAccessibilityAction.SET_TEXT)
                }
                if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds || node.isScrollable) {
                    add(SupportedAccessibilityAction.SCROLL_FORWARD)
                }
                if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actionIds || node.isScrollable) {
                    add(SupportedAccessibilityAction.SCROLL_BACKWARD)
                }
                if (AccessibilityNodeInfo.ACTION_SELECT in actionIds) {
                    add(SupportedAccessibilityAction.SELECT)
                }
            }
        }.getOrDefault(emptySet())

    override val childCount: Int
        get() = runCatching { node.childCount.coerceAtLeast(0) }.getOrDefault(0)

    override fun isNodeValid(): Boolean {
        return try {
            // Accessing basic properties verifies the underlying parcel/node is not recycled
            val b = Rect()
            node.getBoundsInScreen(b)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun refreshNode(): Boolean {
        return try {
            node.refresh()
        } catch (_: IllegalStateException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun getChild(index: Int): AccessibilityNodeAdapter? {
        return try {
            val child = node.getChild(index) ?: return null
            AndroidAccessibilityNodeAdapter(child, isRoot = false)
        } catch (_: Exception) {
            null
        }
    }

    override fun getParent(): AccessibilityNodeAdapter? {
        return try {
            val parent = node.parent ?: return null
            AndroidAccessibilityNodeAdapter(parent, isRoot = false)
        } catch (_: Exception) {
            null
        }
    }

    override fun performAction(
        action: SupportedAccessibilityAction,
        textArgument: String?
    ): Boolean {
        return try {
            when (action) {
                SupportedAccessibilityAction.CLICK ->
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SupportedAccessibilityAction.LONG_CLICK ->
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                SupportedAccessibilityAction.FOCUS ->
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                SupportedAccessibilityAction.SET_TEXT -> {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            textArgument.orEmpty()
                        )
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                SupportedAccessibilityAction.SCROLL_FORWARD ->
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SupportedAccessibilityAction.SCROLL_BACKWARD ->
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                SupportedAccessibilityAction.SELECT ->
                    node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            }
        } catch (_: IllegalStateException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun releaseTransientNode() {
        if (isRoot) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
    }
}

/**
 * Extracts a compact, privacy-redacted semantic tree from the real Android
 * [AccessibilityNodeInfo] hierarchy on demand.
 *
 * Rules enforced (M6):
 * - Bounded depth (`MAX_DEPTH_LOW_RAM` / `MAX_DEPTH_STANDARD`), bounded visited nodes,
 *   and bounded extracted semantic targets.
 * - Cycle and duplicate-node protection via `visitedNodeIds`.
 * - Stale/recycled/inaccessible node protection (`isNodeValid()` + `runCatching`).
 * - Sensitive text redaction via [SensitiveDataRedactor].
 * - Active package is `"Unavailable"` when neither the root node nor active window event
 *   provides a real package name (never inferred from user commands or hardcoded).
 * - Never retains [AccessibilityNodeInfo] references after extraction completes.
 */
object SemanticScreenExtractor {

    const val MAX_TARGETS_LOW_RAM = 40
    const val MAX_TARGETS_STANDARD = 80
    const val MAX_VISITED_LOW_RAM = 150
    const val MAX_VISITED_STANDARD = 300
    const val MAX_DEPTH_LOW_RAM = 18
    const val MAX_DEPTH_STANDARD = 24

    fun extractSnapshot(
        rootNode: AccessibilityNodeInfo?,
        activePackageOverride: String = "",
        windowTitleOverride: String = "",
        lowRamMode: Boolean = false
    ): SemanticScreenSnapshot? {
        if (rootNode == null) return null
        return extractSnapshotFromAdapter(
            rootAdapter = AndroidAccessibilityNodeAdapter(rootNode, isRoot = true),
            activePackageOverride = activePackageOverride,
            windowTitleOverride = windowTitleOverride,
            lowRamMode = lowRamMode
        )
    }

    fun extractSnapshotFromAdapter(
        rootAdapter: AccessibilityNodeAdapter?,
        activePackageOverride: String = "",
        windowTitleOverride: String = "",
        lowRamMode: Boolean = false
    ): SemanticScreenSnapshot? {
        if (rootAdapter == null || !rootAdapter.isNodeValid()) return null

        val maxTargets = if (lowRamMode) MAX_TARGETS_LOW_RAM else MAX_TARGETS_STANDARD
        val maxVisited = if (lowRamMode) MAX_VISITED_LOW_RAM else MAX_VISITED_STANDARD
        val maxDepth = if (lowRamMode) MAX_DEPTH_LOW_RAM else MAX_DEPTH_STANDARD

        val collected = mutableListOf<SemanticUiNode>()
        val visitedNodeIds = HashSet<Int>()
        var rawTraversed = 0
        var redactedCount = 0
        var truncatedByBounds = false

        val resolvedPackageName = rootAdapter.packageName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: activePackageOverride.trim().takeIf { it.isNotEmpty() }
            ?: "Unavailable"

        val resolvedWindowTitle = windowTitleOverride.trim().takeIf { it.isNotEmpty() }
            ?: if (resolvedPackageName != "Unavailable") {
                resolvedPackageName.substringAfterLast('.')
            } else {
                "Unavailable"
            }

        fun traverse(node: AccessibilityNodeAdapter?, depth: Int) {
            if (node == null) return
            if (depth > maxDepth || collected.size >= maxTargets || rawTraversed >= maxVisited) {
                truncatedByBounds = true
                return
            }
            if (!node.isNodeValid()) return
            if (!visitedNodeIds.add(node.nodeIdentityId)) return

            rawTraversed++

            if (node.isVisibleToUser) {
                val rawText = node.text?.trim().orEmpty()
                val rawDesc = node.contentDescription?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.trim().orEmpty()
                val className = node.className?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "View"

                val bounds = node.boundsInScreen
                val boundsWidth = bounds.right - bounds.left
                val boundsHeight = bounds.bottom - bounds.top
                val hasValidBounds = boundsWidth > 2 && boundsHeight > 2

                val parentClickableInfo = resolveClickableAncestorState(node)
                val effectiveClickable = node.isClickable || parentClickableInfo.hasClickableAncestor
                val effectiveEnabled = if (node.isClickable) {
                    node.isEnabled
                } else if (parentClickableInfo.hasClickableAncestor) {
                    node.isEnabled && parentClickableInfo.ancestorEnabled
                } else {
                    node.isEnabled
                }

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
                    val sanitizedTextResult = SensitiveDataRedactor.sanitizeNodeText(
                        rawText = rawText,
                        isPasswordNode = node.isPassword,
                        viewIdResourceName = viewId
                    )
                    val sanitizedDescResult = SensitiveDataRedactor.sanitizeNodeText(
                        rawText = rawDesc,
                        isPasswordNode = node.isPassword,
                        viewIdResourceName = viewId
                    )
                    val wasRedacted = sanitizedTextResult.wasRedacted || sanitizedDescResult.wasRedacted
                    if (wasRedacted) redactedCount++

                    val nextIndex = collected.size + 1
                    collected.add(
                        SemanticUiNode(
                            index = nextIndex,
                            text = sanitizedTextResult.sanitizedText.take(120),
                            contentDescription = sanitizedDescResult.sanitizedText.take(120),
                            viewIdResourceName = viewId,
                            className = className,
                            boundsInScreen = bounds,
                            isClickable = effectiveClickable,
                            isEditable = node.isEditable,
                            isScrollable = node.isScrollable,
                            isCheckable = node.isCheckable,
                            isChecked = node.isChecked,
                            isFocused = node.isFocused,
                            wasRedacted = wasRedacted,
                            isEnabled = effectiveEnabled,
                            isFocusable = node.isFocusable,
                            isLongClickable = node.isLongClickable,
                            isSelected = node.isSelected,
                            isVisibleToUser = node.isVisibleToUser,
                            supportedActions = effectiveActions
                        )
                    )
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (collected.size >= maxTargets || rawTraversed >= maxVisited) {
                    truncatedByBounds = true
                    break
                }
                val child = node.getChild(i)
                if (child != null) {
                    try {
                        traverse(child, depth + 1)
                    } finally {
                        child.releaseTransientNode()
                    }
                }
            }
        }

        traverse(rootAdapter, 0)

        return SemanticScreenSnapshot(
            capturedAtMs = System.currentTimeMillis(),
            packageName = resolvedPackageName,
            windowTitle = resolvedWindowTitle,
            nodes = collected,
            totalRawNodesTraversed = rawTraversed,
            redactedFieldCount = redactedCount,
            isLowRamTruncated = truncatedByBounds || (lowRamMode && collected.size >= maxTargets)
        )
    }

    private data class ClickableAncestorState(
        val hasClickableAncestor: Boolean,
        val ancestorEnabled: Boolean
    )

    private fun resolveClickableAncestorState(
        node: AccessibilityNodeAdapter,
        maxLevels: Int = 3
    ): ClickableAncestorState {
        var current: AccessibilityNodeAdapter? = node.getParent()
        var level = 0
        while (current != null && level < maxLevels) {
            val currRef = current
            try {
                if (currRef.isNodeValid() && currRef.isClickable) {
                    return ClickableAncestorState(
                        hasClickableAncestor = true,
                        ancestorEnabled = currRef.isEnabled
                    )
                }
                current = currRef.getParent()
                level++
            } finally {
                currRef.releaseTransientNode()
            }
        }
        current?.releaseTransientNode()
        return ClickableAncestorState(hasClickableAncestor = false, ancestorEnabled = false)
    }
}
