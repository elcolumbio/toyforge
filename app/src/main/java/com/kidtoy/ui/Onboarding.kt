package com.kidtoy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One onboarding card: a big picture (emojis), a two-to-four-word title and one short line. */
private data class Step(val pictures: String, val title: String, val line: String, val bg: Brush)

private val steps = listOf(
    Step("🧱 👉 🚀", "Tap a shape", "Pick blocks from the shelf on the left.", Brush.linearGradient(listOf(Color(0xFF2EC4F1), Color(0xFF5B6CFF)))),
    Step("👆 ↔️ 🤏", "Drag & pinch", "Drag a part to move it. Two fingers to look around.", Brush.linearGradient(listOf(Color(0xFFFF7EC7), Color(0xFFB15CFF)))),
    Step("✨ 🖨️ 🎉", "Make it real", "Ask the magic bar for ideas, then press the green printer.", Brush.linearGradient(listOf(Color(0xFF3DDC97), Color(0xFF2EC4F1)))),
)

/** First-run tour: three icon-led cards, swipe-free (big Next button), skippable at any time. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val bounce = rememberInfiniteTransition(label = "bounce")
    val lift by bounce.animateFloat(0f, -14f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "lift")
    Box(
        Modifier.fillMaxSize().background(Color(0xB3141A2E))
            .clickable(remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(page, transitionSpec = {
            (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 2 } + fadeOut())
        }, label = "step") { p ->
            val s = steps[p]
            Column(
                Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(40.dp)).background(s.bg).padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.pictures, fontSize = 88.sp, modifier = Modifier.graphicsLayer { translationY = lift * density })
                Spacer(Modifier.height(20.dp))
                Text(s.title, fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(s.line, fontSize = 22.sp, color = Color.White.copy(alpha = 0.92f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Rounded.Close, "Skip", onDone, size = 64.dp, background = SolidColor(Color.White.copy(alpha = 0.25f)), tint = Color.White)
                    Spacer(Modifier.width(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        steps.indices.forEach { i ->
                            Box(Modifier.size(if (i == p) 18.dp else 12.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = if (i == p) 1f else 0.45f)))
                        }
                    }
                    Spacer(Modifier.width(32.dp))
                    val last = p == steps.lastIndex
                    RoundIconButton(if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward, if (last) "Let's build" else "Next",
                        { if (last) onDone() else page++ }, size = 92.dp, background = SolidColor(Color.White), tint = Kid.Ink)
                }
            }
        }
    }
}
