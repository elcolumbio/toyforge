package com.kidtoy.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Confetti burst for "ta-da" moments. */
class ConfettiState {
    class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float, val color: Color, val w: Float, val h: Float)
    val particles = ArrayList<P>()
    var tick by mutableLongStateOf(0L)
    var bursts by mutableIntStateOf(0)
    private val colors = listOf(Kid.Red, Kid.Orange, Kid.Yellow, Kid.Green, Kid.Sky, Kid.Blue, Kid.Purple, Kid.Pink)
    fun burst() {
        repeat(90) {
            val a = Random.nextFloat() * PI.toFloat() - PI.toFloat()
            val sp = 0.6f + Random.nextFloat() * 1.1f
            particles.add(P(0.5f, 0.45f, kotlin.math.cos(a) * sp * 0.5f, kotlin.math.sin(a) * sp - 0.3f, Random.nextFloat() * 360f,
                Random.nextFloat() * 720f - 360f, colors.random(), 10f + Random.nextFloat() * 10f, 6f + Random.nextFloat() * 6f))
        }
        bursts++
    }
}

@Composable
fun ConfettiLayer(state: ConfettiState) {
    LaunchedEffect(state.bursts) {
        var last = 0L
        while (state.particles.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val it = state.particles.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.vy += 1.4f * dt; p.vx *= 0.99f
                    p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
                    if (p.y > 1.1f) it.remove()
                }
                state.tick = now
            }
        }
    }
    if (state.particles.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val t = state.tick
        for (p in state.particles) {
            val c = Offset(p.x * size.width, p.y * size.height)
            rotate(p.rot, c) { drawRect(p.color, c - Offset(p.w / 2, p.h / 2), Size(p.w * density / 2, p.h * density / 2)) }
        }
    }
}

/** Tiny synthesised sound effects (no audio assets needed). */
object Sounds {
    private const val RATE = 22050
    private val cache = HashMap<Fx, ShortArray>()
    @Volatile var muted = false

    private fun synth(f: Fx): ShortArray {
        fun tone(notes: List<Pair<Float, Float>>, vol: Float, sweep: Float = 1f): ShortArray {
            val out = ArrayList<Short>()
            for ((freq, dur) in notes) {
                val n = (RATE * dur).toInt()
                var phase = 0.0
                for (i in 0 until n) {
                    val t = i.toFloat() / n
                    val fr = freq * (1f + (sweep - 1f) * t)
                    phase += 2 * PI * fr / RATE
                    val env = (if (t < 0.08f) t / 0.08f else 1f) * (1f - t) * (1f - t)
                    val v = (sin(phase) + 0.25 * sin(phase * 2)) * env * vol
                    out.add((v * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort())
                }
            }
            return out.toShortArray()
        }
        return when (f) {
            Fx.POP -> tone(listOf(520f to 0.11f), 0.35f, sweep = 2.2f)
            Fx.TICK -> tone(listOf(1600f to 0.025f), 0.12f)
            Fx.DELETE -> tone(listOf(700f to 0.18f), 0.3f, sweep = 0.35f)
            Fx.MAGIC -> tone(listOf(660f to 0.08f, 880f to 0.08f, 1100f to 0.08f, 1320f to 0.16f), 0.28f)
            Fx.CELEBRATE -> tone(listOf(523f to 0.09f, 659f to 0.09f, 784f to 0.09f, 1046f to 0.2f), 0.3f)
            Fx.ERROR -> tone(listOf(330f to 0.12f, 247f to 0.2f), 0.3f)
        }
    }

    fun play(f: Fx) {
        if (muted) return
        Thread {
            runCatching {
                val pcm = synchronized(cache) { cache.getOrPut(f) { synth(f) } }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L / RATE) + 60)
                track.release()
            }
        }.start()
    }
}
