package com.example.nova.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.example.nova.core.NovaUiOrbState
import com.example.nova.core.OverlayAttachmentState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Deterministic animation and visual specification for the Floating Nova Orb.
 * Driven strictly by real [NovaUiOrbState], real [lowRamMode], and optional real [liveRmsDb].
 */
data class FloatingOrbAnimationSpec(
    val orbState: NovaUiOrbState,
    val lowRamMode: Boolean,
    val primaryColorArgb: Int,
    val secondaryColorArgb: Int,
    val animateContinuously: Boolean,
    val cycleDurationMs: Long,
    val pulseIntensity: Float,
    val ringCount: Int,
    val arcCount: Int,
    val realNormalizedAudioLevel: Float?
)

/**
 * Resolves the real state-driven visual and animation parameters for [FloatingNovaOrbView].
 * - Never fabricates audio levels when [liveRmsDb] is null.
 * - Uses subtle animation in [NovaUiOrbState.IDLE] and stronger animation during
 *   [NovaUiOrbState.LISTENING], [NovaUiOrbState.THINKING], [NovaUiOrbState.EXECUTING], and [NovaUiOrbState.SPEAKING].
 * - Reduces ring/arc complexity and disables idle loop when [lowRamMode] is active (2–4 GB RAM devices).
 */
fun resolveFloatingOrbAnimationSpec(
    orbState: NovaUiOrbState,
    lowRamMode: Boolean,
    liveRmsDb: Float? = null
): FloatingOrbAnimationSpec {
    val normalizedAudio = computeRealAudioNormalizedLevel(orbState, liveRmsDb)

    val (primaryArgb, secondaryArgb) = when (orbState) {
        NovaUiOrbState.IDLE -> 0xFF1E88E5.toInt() to 0xFF00E5FF.toInt()
        NovaUiOrbState.LISTENING -> 0xFF00E5FF.toInt() to 0xFF1E88E5.toInt()
        NovaUiOrbState.THINKING -> 0xFF2979FF.toInt() to 0xFF00E5FF.toInt()
        NovaUiOrbState.EXECUTING -> 0xFF448AFF.toInt() to 0xFF00E5FF.toInt()
        NovaUiOrbState.SPEAKING -> 0xFF18FFFF.toInt() to 0xFF2979FF.toInt()
        NovaUiOrbState.ERROR -> 0xFFFF5252.toInt() to 0xFFFFAB40.toInt()
        NovaUiOrbState.UNAVAILABLE -> 0xFF607D8B.toInt() to 0xFF0D47A1.toInt()
    }

    val isActiveState = orbState == NovaUiOrbState.LISTENING ||
        orbState == NovaUiOrbState.THINKING ||
        orbState == NovaUiOrbState.EXECUTING ||
        orbState == NovaUiOrbState.SPEAKING

    val animateContinuously = when {
        orbState == NovaUiOrbState.UNAVAILABLE -> false
        lowRamMode -> isActiveState
        else -> true
    }

    val baseCycleMs = when (orbState) {
        NovaUiOrbState.IDLE -> 4200L
        NovaUiOrbState.LISTENING -> 1300L
        NovaUiOrbState.THINKING -> 1400L
        NovaUiOrbState.EXECUTING -> 1100L
        NovaUiOrbState.SPEAKING -> 1250L
        NovaUiOrbState.ERROR -> 2400L
        NovaUiOrbState.UNAVAILABLE -> 4000L
    }

    val cycleDurationMs = if (lowRamMode) (baseCycleMs * 1.5f).toLong() else baseCycleMs

    // Subtle idle pulse vs stronger active state animation
    val pulseIntensity = when (orbState) {
        NovaUiOrbState.IDLE -> 0.04f
        NovaUiOrbState.LISTENING -> 0.15f
        NovaUiOrbState.THINKING -> 0.11f
        NovaUiOrbState.EXECUTING -> 0.14f
        NovaUiOrbState.SPEAKING -> 0.14f
        NovaUiOrbState.ERROR -> 0.06f
        NovaUiOrbState.UNAVAILABLE -> 0.0f
    }

    val ringCount = if (lowRamMode) 1 else if (isActiveState) 3 else 2
    val arcCount = when {
        orbState == NovaUiOrbState.UNAVAILABLE -> 0
        lowRamMode -> if (isActiveState) 1 else 0
        isActiveState -> 2
        else -> 1
    }

    return FloatingOrbAnimationSpec(
        orbState = orbState,
        lowRamMode = lowRamMode,
        primaryColorArgb = primaryArgb,
        secondaryColorArgb = secondaryArgb,
        animateContinuously = animateContinuously,
        cycleDurationMs = cycleDurationMs,
        pulseIntensity = pulseIntensity,
        ringCount = ringCount,
        arcCount = arcCount,
        realNormalizedAudioLevel = normalizedAudio
    )
}

/**
 * Creates the [WindowManager.LayoutParams] for the circular floating Nova orb using
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 */
fun createAccessibilityOverlayLayoutParams(
    sizePx: Int,
    initialX: Int,
    initialY: Int
): WindowManager.LayoutParams {
    return WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = initialX
        y = initialY
    }
}

/**
 * Abstraction over [WindowManager] so attachment, update, removal, and real OS exceptions
 * ([WindowManager.BadTokenException], [SecurityException], etc.) can be handled and verified deterministically.
 */
interface FloatingOverlayWindowHost {
    fun addOverlayView(view: View, params: WindowManager.LayoutParams)
    fun updateOverlayViewLayout(view: View, params: WindowManager.LayoutParams)
    fun removeOverlayView(view: View)
}

class AndroidWindowManagerOverlayHost(
    private val windowManager: WindowManager?
) : FloatingOverlayWindowHost {
    override fun addOverlayView(view: View, params: WindowManager.LayoutParams) {
        val wm = windowManager
            ?: throw IllegalStateException("WindowManager service is null")
        wm.addView(view, params)
    }

    override fun updateOverlayViewLayout(view: View, params: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        wm.updateViewLayout(view, params)
    }

    override fun removeOverlayView(view: View) {
        val wm = windowManager ?: return
        wm.removeView(view)
    }
}

/**
 * Result of an overlay attach/update/detach operation.
 */
data class OverlayOperationOutcome(
    val attachmentState: OverlayAttachmentState,
    val diagnosticMessage: String
)

/**
 * Manages the lifecycle and truthful [OverlayAttachmentState] reporting of [FloatingNovaOrbView].
 *
 * Guarantees:
 * - Never reports [OverlayAttachmentState.ATTACHED] unless [FloatingOverlayWindowHost.addOverlayView]
 *   actually succeeded without throwing.
 * - Reports [OverlayAttachmentState.DISABLED_BY_USER_PREFERENCE] when disabled by user preference.
 * - Reports [OverlayAttachmentState.SERVICE_NOT_BOUND] when the AccessibilityService is not bound.
 * - Reports [OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER] on [WindowManager.BadTokenException],
 *   [SecurityException], or any other WindowManager rejection.
 * - Reports [OverlayAttachmentState.DETACHED] when explicitly detached.
 */
class FloatingNovaOrbOverlayController(
    private val viewFactory: () -> FloatingNovaOrbView,
    private val layoutParamsFactory: () -> WindowManager.LayoutParams
) {
    var currentAttachmentState: OverlayAttachmentState = OverlayAttachmentState.SERVICE_NOT_BOUND
        private set

    var lastDiagnosticMessage: String = "AccessibilityService not bound."
        private set

    var attachedOrbView: FloatingNovaOrbView? = null
        private set

    private var activeLayoutParams: WindowManager.LayoutParams? = null

    fun syncOverlay(
        windowHost: FloatingOverlayWindowHost?,
        isServiceBound: Boolean,
        userPreferenceEnabled: Boolean,
        orbState: NovaUiOrbState,
        liveRmsDb: Float?,
        lowRamMode: Boolean
    ): OverlayOperationOutcome {
        // 1. User preference disabled takes priority and detaches any existing view
        if (!userPreferenceEnabled) {
            removeViewSafely(windowHost)
            currentAttachmentState = OverlayAttachmentState.DISABLED_BY_USER_PREFERENCE
            lastDiagnosticMessage = "Floating Nova orb disabled by user preference."
            return OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
        }

        // 2. Service must actually be bound
        if (!isServiceBound) {
            removeViewSafely(windowHost)
            currentAttachmentState = OverlayAttachmentState.SERVICE_NOT_BOUND
            lastDiagnosticMessage = "NovaAccessibilityService is not bound."
            return OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
        }

        // 3. WindowHost must be available
        if (windowHost == null) {
            attachedOrbView = null
            activeLayoutParams = null
            currentAttachmentState = OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER
            lastDiagnosticMessage = "WindowManager is null; cannot attach TYPE_ACCESSIBILITY_OVERLAY."
            return OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
        }

        // 4. Attach if not already attached
        val existingView = attachedOrbView
        if (existingView == null || currentAttachmentState != OverlayAttachmentState.ATTACHED) {
            val newOrbView = viewFactory()
            val params = layoutParamsFactory()
            newOrbView.bindWindowHostForDrag(windowHost, params)
            newOrbView.updateOrbState(
                orbState = orbState,
                liveRmsDb = liveRmsDb,
                lowRamMode = lowRamMode
            )

            return try {
                windowHost.addOverlayView(newOrbView, params)
                attachedOrbView = newOrbView
                activeLayoutParams = params
                currentAttachmentState = OverlayAttachmentState.ATTACHED
                lastDiagnosticMessage = "Floating Nova orb attached via TYPE_ACCESSIBILITY_OVERLAY."
                OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
            } catch (e: WindowManager.BadTokenException) {
                newOrbView.stopAnimations()
                attachedOrbView = null
                activeLayoutParams = null
                currentAttachmentState = OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER
                lastDiagnosticMessage = "WindowManager rejected token (BadTokenException): ${e.message}"
                OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
            } catch (e: SecurityException) {
                newOrbView.stopAnimations()
                attachedOrbView = null
                activeLayoutParams = null
                currentAttachmentState = OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER
                lastDiagnosticMessage = "WindowManager security rejection (SecurityException): ${e.message}"
                OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
            } catch (e: Throwable) {
                newOrbView.stopAnimations()
                attachedOrbView = null
                activeLayoutParams = null
                currentAttachmentState = OverlayAttachmentState.REJECTED_BY_WINDOW_MANAGER
                lastDiagnosticMessage = "WindowManager rejected overlay (${e.javaClass.simpleName}): ${e.message}"
                OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
            }
        }

        // 5. Already attached — update real state on the live orb view
        existingView.updateOrbState(
            orbState = orbState,
            liveRmsDb = liveRmsDb,
            lowRamMode = lowRamMode
        )
        currentAttachmentState = OverlayAttachmentState.ATTACHED
        lastDiagnosticMessage = "Floating Nova orb updated to state ${orbState.name}."
        return OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
    }

    fun detachOverlay(windowHost: FloatingOverlayWindowHost?): OverlayOperationOutcome {
        removeViewSafely(windowHost)
        currentAttachmentState = OverlayAttachmentState.DETACHED
        lastDiagnosticMessage = "Floating Nova orb detached."
        return OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
    }

    fun onServiceUnbound(windowHost: FloatingOverlayWindowHost?): OverlayOperationOutcome {
        removeViewSafely(windowHost)
        currentAttachmentState = OverlayAttachmentState.SERVICE_NOT_BOUND
        lastDiagnosticMessage = "NovaAccessibilityService unbound; floating orb removed."
        return OverlayOperationOutcome(currentAttachmentState, lastDiagnosticMessage)
    }

    private fun removeViewSafely(windowHost: FloatingOverlayWindowHost?) {
        val view = attachedOrbView
        attachedOrbView = null
        activeLayoutParams = null
        if (view != null) {
            view.stopAnimations()
            if (windowHost != null) {
                runCatching { windowHost.removeOverlayView(view) }
            }
        }
    }
}

/**
 * M3 — Circular Floating Nova Orb View hosted inside [com.example.nova.accessibility.NovaAccessibilityService]
 * via [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * Features:
 * - Sapphire / Cyan / Electric Blue glowing circular orb (replaces the old rectangular overlay).
 * - Driven strictly by real [NovaUiOrbState] and optional real [liveRmsDb].
 * - Lightweight custom [View] with zero ComposeView lifecycle overhead on 2–4 GB RAM devices.
 * - Tap expands the full-screen Nova Assistant UI; long-press cancels an active task if running;
 *   drag repositions the orb smoothly on screen.
 */
class FloatingNovaOrbView(
    context: Context,
    private val onExpandAssistant: () -> Unit = {},
    private val onCancelActiveTask: () -> Unit = {}
) : View(context) {

    var currentOrbState: NovaUiOrbState = NovaUiOrbState.IDLE
        private set

    var currentLowRamMode: Boolean = false
        private set

    var currentLiveRmsDb: Float? = null
        private set

    var currentAnimationSpec: FloatingOrbAnimationSpec =
        resolveFloatingOrbAnimationSpec(NovaUiOrbState.IDLE, lowRamMode = false, liveRmsDb = null)
        private set

    private var phase: Float = 0.25f
    private var animator: ValueAnimator? = null

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arcBounds = RectF()

    private var dragWindowHost: FloatingOverlayWindowHost? = null
    private var dragLayoutParams: WindowManager.LayoutParams? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isClickable = true
        isLongClickable = true
        contentDescription = buildAccessibilityDescription(NovaUiOrbState.IDLE)
        setOnClickListener {
            onExpandAssistant()
        }
        setOnLongClickListener {
            if (currentOrbState == NovaUiOrbState.EXECUTING ||
                currentOrbState == NovaUiOrbState.THINKING ||
                currentOrbState == NovaUiOrbState.LISTENING ||
                currentOrbState == NovaUiOrbState.SPEAKING
            ) {
                onCancelActiveTask()
                true
            } else {
                onExpandAssistant()
                true
            }
        }
    }

    fun bindWindowHostForDrag(
        host: FloatingOverlayWindowHost,
        params: WindowManager.LayoutParams
    ) {
        dragWindowHost = host
        dragLayoutParams = params
    }

    fun updateOrbState(
        orbState: NovaUiOrbState,
        liveRmsDb: Float?,
        lowRamMode: Boolean
    ) {
        val prevSpec = currentAnimationSpec
        currentOrbState = orbState
        currentLiveRmsDb = liveRmsDb
        currentLowRamMode = lowRamMode
        currentAnimationSpec = resolveFloatingOrbAnimationSpec(
            orbState = orbState,
            lowRamMode = lowRamMode,
            liveRmsDb = liveRmsDb
        )
        contentDescription = buildAccessibilityDescription(orbState)

        if (prevSpec.animateContinuously != currentAnimationSpec.animateContinuously ||
            prevSpec.cycleDurationMs != currentAnimationSpec.cycleDurationMs
        ) {
            configureAnimator()
        } else {
            invalidate()
        }
    }

    fun stopAnimations() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configureAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    private fun configureAnimator() {
        stopAnimations()
        if (!currentAnimationSpec.animateContinuously) {
            phase = 0.25f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = currentAnimationSpec.cycleDurationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                phase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val minDim = min(w, h)
        if (minDim <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = minDim / 2f
        val spec = currentAnimationSpec
        val density = resources.displayMetrics.density.coerceAtLeast(1f)

        val angleRad = (phase * 2.0 * PI).toFloat()
        val wave = ((sin(angleRad.toDouble()) + 1.0) / 2.0).toFloat()

        val dynamicPulse = if (spec.realNormalizedAudioLevel != null) {
            spec.realNormalizedAudioLevel * 0.18f
        } else {
            spec.pulseIntensity * wave
        }

        val coreRadius = maxRadius * (0.46f + dynamicPulse).coerceIn(0.36f, 0.68f)

        // 1. Outer Sapphire/Cyan Radial Glow
        haloPaint.shader = RadialGradient(
            cx,
            cy,
            maxRadius * 0.96f,
            intArrayOf(
                withAlpha(spec.secondaryColorArgb, if (currentOrbState == NovaUiOrbState.UNAVAILABLE) 45 else 95),
                withAlpha(spec.primaryColorArgb, 35),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, maxRadius * 0.96f, haloPaint)

        // 2. Concentric State Rings
        for (i in 0 until spec.ringCount) {
            val frac = (i + 1).toFloat() / spec.ringCount.toFloat()
            val ringRadius = (coreRadius + (maxRadius - coreRadius) * (0.35f + 0.50f * frac))
                .coerceAtMost(maxRadius * 0.94f)
            val alpha = if (currentOrbState == NovaUiOrbState.UNAVAILABLE) 55 else (130 * (1f - frac * 0.45f)).toInt()
            ringPaint.color = withAlpha(if (i % 2 == 0) spec.primaryColorArgb else spec.secondaryColorArgb, alpha)
            ringPaint.strokeWidth = (1.6f * density).coerceAtLeast(1f)
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }

        // 3. Orbital Arcs for Active States
        if (spec.arcCount > 0) {
            val orbitRadius = (coreRadius * 1.25f).coerceAtMost(maxRadius * 0.90f)
            arcBounds.set(cx - orbitRadius, cy - orbitRadius, cx + orbitRadius, cy + orbitRadius)
            arcPaint.strokeWidth = (2.2f * density).coerceAtLeast(1.5f)
            val baseDeg = phase * 360f
            for (arcIndex in 0 until spec.arcCount) {
                val dir = if (arcIndex % 2 == 0) 1f else -1f
                val startAngle = (baseDeg * dir) + (arcIndex * (360f / spec.arcCount))
                val sweep = if (currentOrbState == NovaUiOrbState.THINKING || currentOrbState == NovaUiOrbState.EXECUTING) {
                    120f
                } else {
                    75f
                }
                arcPaint.color = withAlpha(spec.secondaryColorArgb, 185)
                canvas.drawArc(arcBounds, startAngle, sweep, false, arcPaint)
            }
        }

        // 4. Luminous Core Sphere
        corePaint.shader = RadialGradient(
            cx - coreRadius * 0.2f,
            cy - coreRadius * 0.2f,
            coreRadius * 1.15f,
            intArrayOf(
                withAlpha(Color.WHITE, if (currentOrbState == NovaUiOrbState.UNAVAILABLE) 95 else 230),
                withAlpha(spec.secondaryColorArgb, if (currentOrbState == NovaUiOrbState.UNAVAILABLE) 120 else 225),
                withAlpha(spec.primaryColorArgb, if (currentOrbState == NovaUiOrbState.UNAVAILABLE) 140 else 245)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        // 5. Specular Rim
        rimPaint.color = withAlpha(
            spec.secondaryColorArgb,
            if (currentOrbState == NovaUiOrbState.UNAVAILABLE) 80 else 210
        )
        rimPaint.strokeWidth = (1.5f * density).coerceAtLeast(1f)
        canvas.drawCircle(cx, cy, coreRadius, rimPaint)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.onTouchEvent(null)
        val params = dragLayoutParams
        val host = dragWindowHost

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                initialParamX = params?.x ?: 0
                initialParamY = params?.y ?: 0
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging && params != null && host != null) {
                    // Gravity is TOP or END, so x increases toward the left
                    params.x = (initialParamX - dx.toInt()).coerceAtLeast(0)
                    params.y = (initialParamY + dy.toInt()).coerceAtLeast(0)
                    runCatching { host.updateOverlayViewLayout(this, params) }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun buildAccessibilityDescription(state: NovaUiOrbState): String {
        return "Floating Nova Orb (${state.name}). Tap to open Nova Assistant."
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val clamped = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }
}
