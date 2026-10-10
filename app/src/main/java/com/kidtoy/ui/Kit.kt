package com.kidtoy.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

object Kid {
    val Sky = Color(0xFF2EC4F1)
    val Blue = Color(0xFF5B6CFF)
    val Purple = Color(0xFFB15CFF)
    val Pink = Color(0xFFFF7EC7)
    val Red = Color(0xFFFF5A5F)
    val Orange = Color(0xFFFF9F1C)
    val Yellow = Color(0xFFFFD23F)
    val Green = Color(0xFF3DDC97)
    val Ink = Color(0xFF283044)
    val Panel = Color(0xF7FFFFFF)
    val Magic = Brush.linearGradient(listOf(Color(0xFFFF7EC7), Color(0xFFB15CFF), Color(0xFF5B6CFF)))
    val Sunny = Brush.linearGradient(listOf(Color(0xFFFFD23F), Color(0xFFFF9F1C)))
}

/** Rounded white card with a soft shadow, used for every floating panel. */
@Composable
fun KidPanel(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), content: @Composable () -> Unit) {
    Box(
        modifier
            .shadow(14.dp, shape, ambientColor = Color(0x553A5BA0), spotColor = Color(0x553A5BA0))
            .clip(shape)
            .background(Kid.Panel)
            .border(2.dp, Color(0x14000000), shape)
    ) { content() }
}

/**
 * Big squishy button: scales down while pressed, springs back, gives a haptic tick.
 * With [repeat] = true, holding it keeps firing (handy for "bigger" / "turn").
 */
@Composable
fun BouncyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    background: Brush = Brush.linearGradient(listOf(Color(0xFFF1F5FF), Color(0xFFE4ECFF))),
    shape: Shape = RoundedCornerShape(20.dp),
    enabled: Boolean = true,
    repeat: Boolean = false,
    description: String,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val click by rememberUpdatedState(onClick)
    var held by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed || held) 0.86f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    if (repeat) {
        LaunchedEffect(held) {
            if (held) {
                delay(420)
                while (isActive && held) { click(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); delay(110) }
            }
        }
    }
    Box(
        modifier
            .size(size)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.35f)
            .shadow(if (enabled) 4.dp else 0.dp, shape)
            .clip(shape)
            .background(background)
            .semantics { contentDescription = description; role = Role.Button }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    held = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    click()
                    tryAwaitRelease()
                    held = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun ToolButton(
    icon: ImageVector? = null,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Kid.Ink,
    background: Brush = Brush.linearGradient(listOf(Color(0xFFF1F5FF), Color(0xFFE4ECFF))),
    enabled: Boolean = true,
    repeat: Boolean = false,
    size: Dp = 68.dp,
    custom: (@Composable () -> Unit)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BouncyButton(onClick, size = size, background = background, enabled = enabled, repeat = repeat, description = label) {
            if (custom != null) custom() else if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Kid.Ink.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 60.dp,
                    background: Brush = Brush.linearGradient(listOf(Color.White, Color(0xFFF0F4FF))), tint: Color = Kid.Ink,
                    badge: Color? = null) {
    Box {
        BouncyButton(onClick, size = size, shape = CircleShape, background = background, enabled = enabled, description = description) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.48f))
        }
        if (badge != null) Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(badge).border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Kid.Ink.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
}

val Gap8 = Arrangement.spacedBy(8.dp)
val Gap12 = Arrangement.spacedBy(12.dp)
