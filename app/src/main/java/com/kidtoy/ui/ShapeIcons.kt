package com.kidtoy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kidtoy.geometry.Primitives
import com.kidtoy.model.ShapeType
import kotlin.math.cos
import kotlin.math.sin

val ShapeColors = mapOf(
    ShapeType.CUBE to Kid.Red, ShapeType.SPHERE to Kid.Sky, ShapeType.CYLINDER to Kid.Green, ShapeType.CONE to Kid.Orange,
    ShapeType.TORUS to Kid.Pink, ShapeType.STAR to Kid.Yellow, ShapeType.HEART to Kid.Red, ShapeType.PYRAMID to Kid.Purple,
)

private fun Color.shade(f: Float) = lerp(this, Color.Black, f)
private fun Color.tint(f: Float) = lerp(this, Color.White, f)

/** Hand-drawn, chunky 3D-ish icons for each building block. */
@Composable
fun ShapeIcon(type: ShapeType, size: Dp = 52.dp, color: Color = ShapeColors[type] ?: Kid.Sky) {
    Canvas(Modifier.size(size)) { drawShape(type, color) }
}

fun DrawScope.drawShape(type: ShapeType, c: Color) {
    val w = size.width; val h = size.height
    val outline = Stroke(width = w * 0.035f)
    val ink = c.shade(0.45f)
    when (type) {
        ShapeType.CUBE -> {
            val top = Path().apply { moveTo(w * .5f, h * .1f); lineTo(w * .9f, h * .3f); lineTo(w * .5f, h * .5f); lineTo(w * .1f, h * .3f); close() }
            val left = Path().apply { moveTo(w * .1f, h * .3f); lineTo(w * .5f, h * .5f); lineTo(w * .5f, h * .92f); lineTo(w * .1f, h * .72f); close() }
            val right = Path().apply { moveTo(w * .9f, h * .3f); lineTo(w * .5f, h * .5f); lineTo(w * .5f, h * .92f); lineTo(w * .9f, h * .72f); close() }
            drawPath(top, c.tint(0.35f)); drawPath(left, c); drawPath(right, c.shade(0.18f))
            for (p in listOf(top, left, right)) drawPath(p, ink, style = outline)
        }
        ShapeType.SPHERE -> {
            drawCircle(Brush.radialGradient(listOf(c.tint(0.6f), c, c.shade(0.25f)), center = Offset(w * .38f, h * .36f), radius = w * .55f), w * .42f, Offset(w / 2, h / 2))
            drawCircle(ink, w * .42f, Offset(w / 2, h / 2), style = outline)
            drawCircle(Color.White.copy(alpha = .7f), w * .07f, Offset(w * .36f, h * .33f))
        }
        ShapeType.CYLINDER -> {
            val body = Path().apply {
                moveTo(w * .14f, h * .24f); lineTo(w * .14f, h * .78f)
                arcTo(androidx.compose.ui.geometry.Rect(w * .14f, h * .66f, w * .86f, h * .9f), 180f, -180f, false)
                lineTo(w * .86f, h * .24f); close()
            }
            drawPath(body, Brush.horizontalGradient(listOf(c.shade(0.05f), c.tint(0.25f), c.shade(0.2f))))
            drawPath(body, ink, style = outline)
            drawOval(c.tint(0.4f), Offset(w * .14f, h * .12f), Size(w * .72f, h * .24f))
            drawOval(ink, Offset(w * .14f, h * .12f), Size(w * .72f, h * .24f), style = outline)
        }
        ShapeType.CONE -> {
            val body = Path().apply {
                moveTo(w * .5f, h * .08f); lineTo(w * .12f, h * .76f)
                arcTo(androidx.compose.ui.geometry.Rect(w * .12f, h * .64f, w * .88f, h * .9f), 180f, -180f, false); close()
            }
            drawPath(body, Brush.horizontalGradient(listOf(c.shade(0.05f), c.tint(0.3f), c.shade(0.2f))))
            drawPath(body, ink, style = outline)
        }
        ShapeType.TORUS -> {
            drawOval(Brush.verticalGradient(listOf(c.tint(0.4f), c.shade(0.15f))), Offset(w * .06f, h * .22f), Size(w * .88f, h * .6f))
            drawOval(ink, Offset(w * .06f, h * .22f), Size(w * .88f, h * .6f), style = outline)
            drawOval(Color.White, Offset(w * .33f, h * .41f), Size(w * .34f, h * .2f))
            drawOval(ink, Offset(w * .33f, h * .41f), Size(w * .34f, h * .2f), style = outline)
        }
        ShapeType.STAR, ShapeType.HEART -> {
            val pts = if (type == ShapeType.STAR) Primitives.starOutline() else Primitives.heartOutline()
            val path = Path()
            pts.forEachIndexed { i, (x, y) ->
                val px = w * (0.5f + x.toFloat() * 0.86f); val py = h * (0.5f - y.toFloat() * 0.86f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, Brush.linearGradient(listOf(c.tint(0.35f), c, c.shade(0.15f)), Offset(0f, 0f), Offset(w, h)))
            drawPath(path, ink, style = outline)
        }
        ShapeType.PYRAMID -> {
            val left = Path().apply { moveTo(w * .5f, h * .08f); lineTo(w * .1f, h * .78f); lineTo(w * .55f, h * .92f); close() }
            val right = Path().apply { moveTo(w * .5f, h * .08f); lineTo(w * .55f, h * .92f); lineTo(w * .9f, h * .7f); close() }
            drawPath(left, c.tint(0.15f)); drawPath(right, c.shade(0.2f))
            drawPath(left, ink, style = outline); drawPath(right, ink, style = outline)
        }
        ShapeType.MESH -> drawCircle(c, w * .4f)
        ShapeType.TEXT -> drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * .1f, h * .3f), androidx.compose.ui.geometry.Size(w * .8f, h * .4f))
    }
}

/** Little pictograms for the stretch tools: a block with arrows. */
@Composable
fun StretchIcon(kind: String, size: Dp = 36.dp, color: Color = Kid.Blue) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = Stroke(width = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun arrow(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), s.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val a = kotlin.math.atan2(y2 - y1, x2 - x1); val l = w * 0.18f
            for (d in listOf(2.5f, -2.5f)) drawLine(color, Offset(x2, y2), Offset(x2 - l * cos(a + d * 0.3f), y2 - l * sin(a + d * 0.3f)), s.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        val box = color.copy(alpha = 0.25f)
        when (kind) {
            "bigger" -> { drawRoundRect(box, Offset(w * .3f, h * .3f), Size(w * .4f, h * .4f)); arrow(w * .3f, h * .7f, w * .06f, h * .94f); arrow(w * .7f, h * .3f, w * .94f, h * .06f) }
            "smaller" -> { drawRoundRect(box, Offset(w * .35f, h * .35f), Size(w * .3f, h * .3f)); arrow(w * .02f, h * .98f, w * .3f, h * .7f); arrow(w * .98f, h * .02f, w * .7f, h * .3f) }
            "taller" -> { drawRoundRect(box, Offset(w * .3f, h * .25f), Size(w * .4f, h * .5f)); arrow(w * .5f, h * .4f, w * .5f, h * .02f); arrow(w * .5f, h * .6f, w * .5f, h * .98f) }
            "shorter" -> { drawRoundRect(box, Offset(w * .25f, h * .38f), Size(w * .5f, h * .24f)); arrow(w * .5f, h * .0f, w * .5f, h * .32f); arrow(w * .5f, h * 1f, w * .5f, h * .68f) }
            "wider" -> { drawRoundRect(box, Offset(w * .25f, h * .3f), Size(w * .5f, h * .4f)); arrow(w * .4f, h * .5f, w * .02f, h * .5f); arrow(w * .6f, h * .5f, w * .98f, h * .5f) }
            "narrower" -> { drawRoundRect(box, Offset(w * .38f, h * .25f), Size(w * .24f, h * .5f)); arrow(w * .0f, h * .5f, w * .32f, h * .5f); arrow(w * 1f, h * .5f, w * .68f, h * .5f) }
            "deeper", "flatter" -> {
                // A little box seen from the side-front: the back face slides away (deeper) or hugs the front (flatter).
                val d = if (kind == "deeper") w * .30f else w * .08f
                val fx = w * .12f; val fy = h * .40f; val fs = w * .44f
                val back = color.copy(alpha = 0.18f)
                drawRect(back, Offset(fx + d, fy - d), Size(fs, fs))
                drawRect(box, Offset(fx, fy), Size(fs, fs))
                val thin = Stroke(width = w * 0.045f)
                drawRect(color, Offset(fx, fy), Size(fs, fs), style = thin)
                for ((x, y) in listOf(fx to fy, fx + fs to fy, fx + fs to fy + fs)) drawLine(color, Offset(x, y), Offset(x + d, y - d), thin.width)
                drawLine(color, Offset(fx + d, fy - d), Offset(fx + fs + d, fy - d), thin.width)
                drawLine(color, Offset(fx + fs + d, fy - d), Offset(fx + fs + d, fy + fs - d), thin.width)
                if (kind == "deeper") arrow(w * .70f, h * .30f, w * .96f, h * .04f)
                else arrow(w * .98f, h * .02f, w * .74f, h * .26f)
            }
        }
    }
}
