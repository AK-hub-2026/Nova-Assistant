package com.example.nova.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nova.core.NovaUiOrbState
import com.example.ui.theme.NovaOrbCyanGlow
import com.example.ui.theme.NovaOrbDeepHalo
import com.example.ui.theme.NovaOrbElectricBlue
import com.example.ui.theme.NovaOrbErrorAmber
import com.example.ui.theme.NovaOrbErrorCrimson
import com.example.ui.theme.NovaOrbExecutingCobalt
import com.example.ui.theme.NovaOrbSapphireCore
import com.example.ui.theme.NovaOrbSpeakingAqua
import com.example.ui.theme.NovaOrbUnavailableSlate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * M11-A — State-driven Arya assistant avatar visuals.
 * Strictly reflects actual runtime state (IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR, UNAVAILABLE).
 * Never fakes facial animation or audio speech activity when audio is not active.
 */
data class AvatarStateVisuals(
    val scale: Float,
    val borderGlowColor: Color,
    val auraAlpha: Float,
    val stateDescription: String
)

fun resolveAvatarStateVisuals(
    orbState: NovaUiOrbState,
    realNormalizedAudio: Float?,
    smoothedAudioLevel: Float
): AvatarStateVisuals {
    return when (orbState) {
        NovaUiOrbState.IDLE -> AvatarStateVisuals(
            scale = 1.0f,
            borderGlowColor = NovaOrbCyanGlow.copy(alpha = 0.65f),
            auraAlpha = 0.35f,
            stateDescription = "Arya is ready and listening for commands"
        )
        NovaUiOrbState.LISTENING -> AvatarStateVisuals(
            scale = if (realNormalizedAudio != null) 1.02f + 0.05f * smoothedAudioLevel else 1.02f,
            borderGlowColor = NovaOrbCyanGlow,
            auraAlpha = 0.75f,
            stateDescription = "Arya is actively listening to microphone"
        )
        NovaUiOrbState.THINKING -> AvatarStateVisuals(
            scale = 0.98f,
            borderGlowColor = NovaOrbElectricBlue,
            auraAlpha = 0.60f,
            stateDescription = "Arya is analyzing command and planning execution"
        )
        NovaUiOrbState.EXECUTING -> AvatarStateVisuals(
            scale = 1.03f,
            borderGlowColor = NovaOrbExecutingCobalt,
            auraAlpha = 0.70f,
            stateDescription = "Arya is executing device automation"
        )
        NovaUiOrbState.SPEAKING -> AvatarStateVisuals(
            scale = 1.04f,
            borderGlowColor = NovaOrbSpeakingAqua,
            auraAlpha = 0.85f,
            stateDescription = "Arya is actively speaking voice response"
        )
        NovaUiOrbState.ERROR -> AvatarStateVisuals(
            scale = 0.96f,
            borderGlowColor = NovaOrbErrorCrimson,
            auraAlpha = 0.50f,
            stateDescription = "Arya encountered a runtime error"
        )
        NovaUiOrbState.UNAVAILABLE -> AvatarStateVisuals(
            scale = 0.92f,
            borderGlowColor = NovaOrbUnavailableSlate.copy(alpha = 0.40f),
            auraAlpha = 0.20f,
            stateDescription = "Arya capability or permission is unavailable"
        )
    }
}

/**
 * Deterministic rendering configuration for [NovaOrbCanvas].
 * Ensures Low-RAM devices (2–4 GB RAM) reduce overdraw, layer count, and unnecessary frame ticks.
 */
data class OrbRenderConfig(
    val orbState: NovaUiOrbState,
    val lowRamMode: Boolean,
    val ringLayerCount: Int,
    val orbitalArcCount: Int,
    val animateContinuously: Boolean,
    val cycleDurationMs: Int
)

/**
 * Pure helper resolving the orb's rendering complexity based on real [NovaUiOrbState] and [lowRamMode].
 */
fun resolveOrbRenderConfig(
    orbState: NovaUiOrbState,
    lowRamMode: Boolean
): OrbRenderConfig {
    if (lowRamMode) {
        val animateInLowRam = when (orbState) {
            NovaUiOrbState.LISTENING,
            NovaUiOrbState.THINKING,
            NovaUiOrbState.EXECUTING,
            NovaUiOrbState.SPEAKING -> true
            NovaUiOrbState.IDLE,
            NovaUiOrbState.ERROR,
            NovaUiOrbState.UNAVAILABLE -> false
        }
        return OrbRenderConfig(
            orbState = orbState,
            lowRamMode = true,
            ringLayerCount = 2,
            orbitalArcCount = if (orbState == NovaUiOrbState.THINKING || orbState == NovaUiOrbState.EXECUTING) 2 else 0,
            animateContinuously = animateInLowRam,
            cycleDurationMs = 2800
        )
    }

    return when (orbState) {
        NovaUiOrbState.IDLE -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 3,
            orbitalArcCount = 2,
            animateContinuously = true,
            cycleDurationMs = 4200
        )
        NovaUiOrbState.LISTENING -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 4,
            orbitalArcCount = 2,
            animateContinuously = true,
            cycleDurationMs = 1600
        )
        NovaUiOrbState.THINKING -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 3,
            orbitalArcCount = 4,
            animateContinuously = true,
            cycleDurationMs = 1800
        )
        NovaUiOrbState.EXECUTING -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 4,
            orbitalArcCount = 3,
            animateContinuously = true,
            cycleDurationMs = 1400
        )
        NovaUiOrbState.SPEAKING -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 4,
            orbitalArcCount = 2,
            animateContinuously = true,
            cycleDurationMs = 1500
        )
        NovaUiOrbState.ERROR -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 3,
            orbitalArcCount = 2,
            animateContinuously = true,
            cycleDurationMs = 2600
        )
        NovaUiOrbState.UNAVAILABLE -> OrbRenderConfig(
            orbState = orbState,
            lowRamMode = false,
            ringLayerCount = 2,
            orbitalArcCount = 2,
            animateContinuously = false,
            cycleDurationMs = 4000
        )
    }
}

/**
 * Normalizes real microphone RMS dB from Android SpeechRecognizer into [0f, 1f].
 * Returns null if [orbState] is not [NovaUiOrbState.LISTENING] or if [liveRmsDb] is null/invalid.
 * Never fabricates or simulates audio amplitude values.
 */
fun computeRealAudioNormalizedLevel(
    orbState: NovaUiOrbState,
    liveRmsDb: Float?
): Float? {
    if (orbState != NovaUiOrbState.LISTENING || liveRmsDb == null) return null
    if (liveRmsDb.isNaN() || liveRmsDb.isInfinite()) return null
    // Android SpeechRecognizer onRmsChanged typically ranges from -2 dB (quiet) to +10..12 dB (loud speech)
    return ((liveRmsDb + 2f) / 12f).coerceIn(0f, 1f)
}

/**
 * M2 — Luminous Sapphire & Cyan Nova Orb Canvas.
 *
 * Driven strictly by real [NovaUiOrbState] from M1 and optional real [liveRmsDb] from
 * Android's SpeechRecognizer. Contains 0% simulated task progress or fabricated audio reactive values.
 */
@Composable
fun NovaOrbCanvas(
    orbState: NovaUiOrbState,
    liveRmsDb: Float?,
    lowRamMode: Boolean,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 240.dp
) {
    val renderConfig = remember(orbState, lowRamMode) {
        resolveOrbRenderConfig(orbState, lowRamMode)
    }
    val realNormalizedAudio = remember(orbState, liveRmsDb) {
        computeRealAudioNormalizedLevel(orbState, liveRmsDb)
    }

    val smoothedAudioLevel by animateFloatAsState(
        targetValue = realNormalizedAudio ?: 0f,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "real_audio_level"
    )

    val primaryCoreColor by animateColorAsState(
        targetValue = when (orbState) {
            NovaUiOrbState.IDLE -> NovaOrbSapphireCore
            NovaUiOrbState.LISTENING -> NovaOrbCyanGlow
            NovaUiOrbState.THINKING -> NovaOrbElectricBlue
            NovaUiOrbState.EXECUTING -> NovaOrbExecutingCobalt
            NovaUiOrbState.SPEAKING -> NovaOrbSpeakingAqua
            NovaUiOrbState.ERROR -> NovaOrbErrorCrimson
            NovaUiOrbState.UNAVAILABLE -> NovaOrbUnavailableSlate
        },
        animationSpec = tween(durationMillis = 350),
        label = "orb_core_color"
    )

    val secondaryGlowColor by animateColorAsState(
        targetValue = when (orbState) {
            NovaUiOrbState.IDLE -> NovaOrbCyanGlow
            NovaUiOrbState.LISTENING -> NovaOrbSapphireCore
            NovaUiOrbState.THINKING -> NovaOrbCyanGlow
            NovaUiOrbState.EXECUTING -> NovaOrbCyanGlow
            NovaUiOrbState.SPEAKING -> NovaOrbElectricBlue
            NovaUiOrbState.ERROR -> NovaOrbErrorAmber
            NovaUiOrbState.UNAVAILABLE -> NovaOrbDeepHalo.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 350),
        label = "orb_glow_color"
    )

    val phase: Float = if (renderConfig.animateContinuously) {
        val infiniteTransition = rememberInfiniteTransition(label = "nova_orb_transition")
        val animatedPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = renderConfig.cycleDurationMs,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "nova_orb_phase"
        )
        animatedPhase
    } else {
        0.25f
    }

    val accessibilityLabel = remember(orbState) {
        when (orbState) {
            NovaUiOrbState.IDLE -> "Nova Orb: Idle. Tap to speak."
            NovaUiOrbState.LISTENING -> "Nova Orb: Listening for speech. Tap to stop."
            NovaUiOrbState.THINKING -> "Nova Orb: Thinking and planning."
            NovaUiOrbState.EXECUTING -> "Nova Orb: Executing and verifying action. Tap to stop."
            NovaUiOrbState.SPEAKING -> "Nova Orb: Speaking response. Tap to interrupt."
            NovaUiOrbState.ERROR -> "Nova Orb: Error state. Tap to retry with voice."
            NovaUiOrbState.UNAVAILABLE -> "Nova Orb: Capability or permission unavailable."
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(orbSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onOrbClick
            )
            .semantics {
                role = Role.Button
                contentDescription = accessibilityLabel
            }
            .testTag("nova_orb_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDimension = min(size.width, size.height)
            if (minDimension <= 0f) return@Canvas

            val centerOffset = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = minDimension / 2f
            val angleRad = (phase * 2.0 * PI).toFloat()
            val breathWave = ((sin(angleRad.toDouble()) + 1.0) / 2.0).toFloat() // [0f..1f]

            // State-specific scale modulation (reacts to real audio ONLY when realNormalizedAudio != null)
            val coreScale = when (orbState) {
                NovaUiOrbState.IDLE -> 0.42f + 0.04f * breathWave
                NovaUiOrbState.LISTENING -> {
                    if (realNormalizedAudio != null) {
                        0.45f + 0.14f * smoothedAudioLevel
                    } else {
                        0.45f + 0.07f * breathWave
                    }
                }
                NovaUiOrbState.THINKING -> 0.40f + 0.05f * sin(angleRad * 2f)
                NovaUiOrbState.EXECUTING -> 0.44f + 0.06f * breathWave
                NovaUiOrbState.SPEAKING -> {
                    val harmonic = ((sin((angleRad * 3f).toDouble()) + 1.0) / 2.0).toFloat()
                    0.44f + 0.08f * harmonic
                }
                NovaUiOrbState.ERROR -> 0.41f + 0.03f * breathWave
                NovaUiOrbState.UNAVAILABLE -> 0.38f
            }

            val coreRadius = maxRadius * coreScale

            // 1. Deep Ambient Outer Halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        secondaryGlowColor.copy(alpha = if (orbState == NovaUiOrbState.UNAVAILABLE) 0.14f else 0.32f),
                        primaryCoreColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = centerOffset,
                    radius = maxRadius * 0.98f
                ),
                radius = maxRadius * 0.98f,
                center = centerOffset
            )

            // 2. State-Driven Concentric Acoustic / Pulse Rings
            for (layerIndex in 0 until renderConfig.ringLayerCount) {
                val layerFraction = (layerIndex + 1).toFloat() / renderConfig.ringLayerCount.toFloat()
                val dynamicExpansion = when (orbState) {
                    NovaUiOrbState.LISTENING -> {
                        if (realNormalizedAudio != null) {
                            smoothedAudioLevel * 0.16f * layerFraction
                        } else {
                            ((phase + layerFraction) % 1f) * 0.14f
                        }
                    }
                    NovaUiOrbState.SPEAKING -> {
                        val wave = sin((angleRad * 2f + layerIndex * 1.1f).toDouble()).toFloat()
                        0.05f * wave
                    }
                    NovaUiOrbState.EXECUTING -> {
                        ((phase + layerFraction * 0.5f) % 1f) * 0.15f
                    }
                    else -> 0.04f * breathWave * layerFraction
                }

                val ringRadius = (coreRadius + (maxRadius - coreRadius) * (0.32f + 0.52f * layerFraction) + maxRadius * dynamicExpansion)
                    .coerceAtMost(maxRadius * 0.95f)

                val ringAlpha = when (orbState) {
                    NovaUiOrbState.UNAVAILABLE -> 0.18f * (1f - layerFraction * 0.4f)
                    NovaUiOrbState.IDLE -> 0.22f * (1f - layerFraction * 0.5f)
                    else -> 0.42f * (1f - layerFraction * 0.55f)
                }.coerceIn(0.05f, 0.75f)

                drawCircle(
                    color = if (layerIndex % 2 == 0) primaryCoreColor.copy(alpha = ringAlpha)
                    else secondaryGlowColor.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = centerOffset,
                    style = Stroke(width = (2.5f - layerIndex * 0.4f).coerceAtLeast(1.2f).dp.toPx())
                )
            }

            // 3. Orbital Energy Arcs (Thinking, Executing, Listening, Idle, Error, Unavailable)
            val arcCount = renderConfig.orbitalArcCount
            if (arcCount > 0) {
                val baseRotationDeg = when (orbState) {
                    NovaUiOrbState.THINKING -> phase * 360f * 2f
                    NovaUiOrbState.EXECUTING -> phase * 360f * 1.5f
                    NovaUiOrbState.LISTENING -> phase * 360f
                    NovaUiOrbState.SPEAKING -> phase * 180f
                    NovaUiOrbState.IDLE -> phase * 360f
                    NovaUiOrbState.ERROR -> sin(angleRad.toDouble()).toFloat() * 25f
                    NovaUiOrbState.UNAVAILABLE -> 45f
                }

                for (arcIndex in 0 until arcCount) {
                    val direction = if (arcIndex % 2 == 0) 1f else -1f
                    val orbitRadius = coreRadius * (1.22f + arcIndex * 0.18f)
                    val topLeft = Offset(centerOffset.x - orbitRadius, centerOffset.y - orbitRadius)
                    val arcSize = Size(orbitRadius * 2f, orbitRadius * 2f)
                    val startAngle = (baseRotationDeg * direction) + (arcIndex * (360f / arcCount))
                    val sweepAngle = when (orbState) {
                        NovaUiOrbState.THINKING -> 105f
                        NovaUiOrbState.EXECUTING -> 135f
                        NovaUiOrbState.UNAVAILABLE -> 50f
                        NovaUiOrbState.ERROR -> 80f
                        else -> 75f
                    }

                    drawArc(
                        color = if (arcIndex % 2 == 0) secondaryGlowColor.copy(alpha = 0.68f)
                        else primaryCoreColor.copy(alpha = 0.52f),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(
                            width = if (orbState == NovaUiOrbState.THINKING || orbState == NovaUiOrbState.EXECUTING) {
                                3.dp.toPx()
                            } else {
                                2.dp.toPx()
                            },
                            cap = StrokeCap.Round
                        )
                    )
                }
            }

            // 4. Inner Luminous Sapphire/Cyan Core Sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (orbState == NovaUiOrbState.UNAVAILABLE) 0.35f else 0.90f),
                        secondaryGlowColor.copy(alpha = if (orbState == NovaUiOrbState.UNAVAILABLE) 0.45f else 0.88f),
                        primaryCoreColor.copy(alpha = if (orbState == NovaUiOrbState.UNAVAILABLE) 0.50f else 0.95f),
                        NovaOrbDeepHalo.copy(alpha = 0.85f)
                    ),
                    center = Offset(
                        x = centerOffset.x - coreRadius * 0.18f,
                        y = centerOffset.y - coreRadius * 0.18f
                    ),
                    radius = coreRadius * 1.15f
                ),
                radius = coreRadius,
                center = centerOffset
            )

            // 5. Core Specular Rim
            drawCircle(
                color = secondaryGlowColor.copy(
                    alpha = if (orbState == NovaUiOrbState.UNAVAILABLE) 0.30f else 0.80f
                ),
                radius = coreRadius,
                center = centerOffset,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // M11-A — Real Arya Assistant Avatar Presentation in Main UI
        val avatarVisuals = resolveAvatarStateVisuals(
            orbState = orbState,
            realNormalizedAudio = realNormalizedAudio,
            smoothedAudioLevel = smoothedAudioLevel
        )
        val avatarSize = orbSize * 0.44f
        val animatedScale by animateFloatAsState(
            targetValue = avatarVisuals.scale,
            animationSpec = tween(durationMillis = 250),
            label = "avatarScale"
        )
        val animatedBorderColor by animateColorAsState(
            targetValue = avatarVisuals.borderGlowColor,
            animationSpec = tween(durationMillis = 300),
            label = "avatarBorderColor"
        )

        Surface(
            modifier = Modifier
                .size(avatarSize)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .border(
                    width = 2.dp,
                    brush = Brush.radialGradient(
                        colors = listOf(animatedBorderColor, animatedBorderColor.copy(alpha = 0.4f))
                    ),
                    shape = CircleShape
                )
                .clip(CircleShape)
                .testTag("arya_avatar_image"),
            shape = CircleShape,
            color = Color(0xFF0A0F1D)
        ) {
            Image(
                painter = painterResource(id = com.example.R.drawable.img_arya_avatar),
                contentDescription = "Arya Assistant Avatar (${avatarVisuals.stateDescription})",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
