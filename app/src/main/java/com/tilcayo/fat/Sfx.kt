package com.tilcayo.fat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Маленький синтезатор: все звуки и музыка рождаются прямо в коде, без аудиофайлов. */
private object Synth {
    const val SR = 22050
    const val SINE = 0
    const val SQUARE = 1
    const val TRI = 2
    const val NOISE = 3

    fun hz(semitonesFromA4: Int) = 440f * 2f.pow(semitonesFromA4 / 12f)

    /** Одна нота / свип. Возвращает буфер в диапазоне [-vol, vol]. */
    fun tone(
        f0: Float, f1: Float, dur: Float, wave: Int = SQUARE, vol: Float = 0.4f,
        duty: Float = 0.5f, decay: Float = 2f, attack: Float = 0.004f, vibrato: Float = 0f,
    ): FloatArray {
        val n = (dur * SR).toInt()
        val out = FloatArray(n)
        var phase = 0f
        var hold = 0f
        val rnd = Random(7)
        for (i in 0 until n) {
            val t = i / n.toFloat()
            var f = f0 + (f1 - f0) * t
            if (vibrato > 0f) f *= 1f + 0.03f * sin(i / SR.toFloat() * vibrato * 2f * PI.toFloat())
            phase += f / SR
            phase -= phase.toInt()
            val s = when (wave) {
                SINE -> sin(phase * 2f * PI.toFloat())
                SQUARE -> if (phase < duty) 1f else -1f
                TRI -> 4f * abs(phase - 0.5f) - 1f
                else -> {
                    if (i % max(1, (SR / max(f, 80f) / 2).toInt()) == 0) hold = rnd.nextFloat() * 2f - 1f
                    hold
                }
            }
            val env = min(1f, i / (attack * SR)) * (1f - t).pow(decay)
            out[i] = s * env * vol
        }
        return out
    }

    fun mix(len: Float, vararg parts: Pair<Float, FloatArray>): FloatArray {
        val out = FloatArray((len * SR).toInt())
        for ((at, buf) in parts) {
            val o = (at * SR).toInt()
            for (i in buf.indices) if (o + i < out.size) out[o + i] += buf[i]
        }
        return out
    }

    fun arp(notes: IntArray, step: Float, wave: Int, vol: Float, noteDur: Float = step * 1.6f, duty: Float = 0.25f): FloatArray {
        val parts = notes.mapIndexed { i, n -> i * step to tone(hz(n), hz(n), noteDur, wave, vol, duty, 1.6f) }
        return mix(notes.size * step + noteDur, *parts.toTypedArray())
    }

    fun pcm(f: FloatArray, gain: Float = 1f): ShortArray {
        var peak = 0.0001f
        for (v in f) peak = max(peak, abs(v))
        val k = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(f.size) { (f[it] * k * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun wav(s: ShortArray): ByteArray {
        val bo = ByteArrayOutputStream()
        fun i32(v: Int) { bo.write(v and 255); bo.write(v shr 8 and 255); bo.write(v shr 16 and 255); bo.write(v shr 24 and 255) }
        fun i16(v: Int) { bo.write(v and 255); bo.write(v shr 8 and 255) }
        bo.write("RIFF".toByteArray()); i32(36 + s.size * 2)
        bo.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
        bo.write("data".toByteArray()); i32(s.size * 2)
        for (v in s) i16(v.toInt())
        return bo.toByteArray()
    }
}

class Sfx(private val ctx: Context) {
    enum class S { JUMP, AIR, LAND, COIN, EAT, CAN_IN, CAN_FIRE, SHIELD, SMASH, DEATH, RECORD, CLICK, BUY, LEVEL, BREED, BUMP, LASER, HOP }

    var enabled = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()
    private val ids = HashMap<S, Int>()

    init {
        try {
            for (s in S.values()) {
                val f = File(ctx.cacheDir, "sfx_${s.name}.wav")
                f.writeBytes(Synth.wav(Synth.pcm(build(s))))
                ids[s] = pool.load(f.absolutePath, 1)
            }
        } catch (_: Exception) {
            // без звука игра всё равно работает
        }
    }

    fun play(s: S, rate: Float = 1f, vol: Float = 1f) {
        if (!enabled) return
        ids[s]?.let { pool.play(it, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f)) }
    }

    fun release() = pool.release()

    private fun build(s: S): FloatArray = with(Synth) {
        when (s) {
            S.JUMP -> tone(280f, 640f, 0.16f, SQUARE, 0.30f, 0.25f, 1.5f)
            S.AIR -> mix(0.2f, 0f to tone(480f, 980f, 0.14f, TRI, 0.45f, decay = 1.3f), 0.05f to tone(960f, 1500f, 0.1f, SQUARE, 0.12f, 0.125f))
            S.LAND -> mix(0.12f, 0f to tone(140f, 60f, 0.1f, SINE, 0.6f, decay = 1.5f), 0f to tone(900f, 900f, 0.04f, NOISE, 0.15f))
            S.COIN -> mix(0.3f, 0f to tone(hz(14), hz(14), 0.06f, SQUARE, 0.22f, 0.5f), 0.055f to tone(hz(19), hz(19), 0.22f, SQUARE, 0.22f, 0.5f, 2.5f))
            S.EAT -> mix(0.5f,
                0f to tone(260f, 150f, 0.1f, SINE, 0.6f), 0.14f to tone(240f, 140f, 0.1f, SINE, 0.6f),
                0.28f to tone(250f, 120f, 0.12f, SINE, 0.6f), 0.28f to tone(600f, 600f, 0.05f, NOISE, 0.12f))
            S.CAN_IN -> mix(0.3f, 0f to tone(120f, 70f, 0.2f, SINE, 0.7f), 0.02f to tone(500f, 260f, 0.12f, SQUARE, 0.2f, 0.5f))
            S.CAN_FIRE -> mix(0.7f,
                0f to tone(400f, 400f, 0.5f, NOISE, 0.55f, decay = 2.2f),
                0f to tone(150f, 35f, 0.5f, SINE, 0.9f, decay = 1.4f),
                0.03f to tone(200f, 1400f, 0.4f, TRI, 0.35f, decay = 1.2f))
            S.SHIELD -> arp(intArrayOf(3, 7, 10, 15), 0.06f, TRI, 0.5f, 0.2f)
            S.SMASH -> mix(0.3f, 0f to tone(700f, 700f, 0.18f, NOISE, 0.5f, decay = 2.5f), 0f to tone(900f, 180f, 0.2f, SQUARE, 0.25f, 0.5f))
            S.DEATH -> mix(0.8f,
                0f to tone(520f, 70f, 0.7f, SQUARE, 0.32f, 0.5f, 1.2f, vibrato = 12f),
                0.0f to tone(300f, 300f, 0.4f, NOISE, 0.28f, decay = 2f))
            S.RECORD -> arp(intArrayOf(3, 7, 10, 15, 19, 22), 0.085f, SQUARE, 0.28f, 0.28f, 0.5f)
            S.CLICK -> tone(760f, 560f, 0.05f, SQUARE, 0.25f, 0.5f)
            S.BUY -> mix(0.3f, 0f to tone(hz(7), hz(7), 0.07f, SQUARE, 0.25f), 0.07f to tone(hz(14), hz(14), 0.18f, SQUARE, 0.25f))
            S.LEVEL -> arp(intArrayOf(0, 4, 7, 12, 16, 19, 24), 0.07f, SQUARE, 0.26f, 0.3f, 0.25f)
            S.BREED -> mix(0.7f,
                0f to tone(hz(19), hz(19), 0.12f, SINE, 0.5f, vibrato = 18f), 0.14f to tone(hz(23), hz(23), 0.12f, SINE, 0.5f, vibrato = 18f),
                0.28f to tone(hz(26), hz(26), 0.3f, SINE, 0.5f, vibrato = 18f))
            S.BUMP -> tone(200f, 720f, 0.22f, SINE, 0.7f, decay = 1.2f, vibrato = 16f)
            S.LASER -> tone(1300f, 1300f, 0.07f, SQUARE, 0.12f, 0.5f)
            S.HOP -> tone(330f, 520f, 0.09f, TRI, 0.5f, decay = 1.2f)
        }
    }
}

/** Бесконечная чиптюн-музыка: Am – F – C – G, генерируется один раз и крутится по кругу. */
class Music {
    private var track: AudioTrack? = null
    private var wantPlay = false

    fun start() {
        Thread {
            try {
                val data = Synth.pcm(compose(), 0.8f)
                val t = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Synth.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    data.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                t.write(data, 0, data.size)
                t.setLoopPoints(0, data.size, -1)
                t.setVolume(0.35f)
                synchronized(this) {
                    track = t
                    if (wantPlay) t.play()
                }
            } catch (_: Exception) {
                // музыка не критична
            }
        }.start()
    }

    @Synchronized
    fun setPlaying(on: Boolean) {
        wantPlay = on
        try {
            val t = track ?: return
            if (on) t.play() else t.pause()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun release() {
        wantPlay = false
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun compose(): FloatArray = with(Synth) {
        val eighth = 60f / 150f / 2f
        val bars = 8
        val total = bars * 8
        val buf = FloatArray((total * eighth * SR).toInt())
        fun put(at: Float, part: FloatArray) {
            val o = (at * SR).toInt()
            for (i in part.indices) if (o + i < buf.size) buf[o + i] += part[i]
        }
        // корни аккордов относительно A4: A2, F2, C3, G2
        val roots = intArrayOf(-24, -28, -21, -26)
        val chords = arrayOf(intArrayOf(0, 3, 7), intArrayOf(0, 4, 7), intArrayOf(0, 4, 7), intArrayOf(0, 4, 7))
        val melody = intArrayOf(
            3, -1, 3, 4, 3, -1, 1, -1, 0, -1, 1, 2, 3, -1, -1, -1,
            4, -1, 4, 3, 4, -1, 3, -1, 2, -1, 3, 2, 1, -1, 0, -1,
            3, -1, 3, 4, 5, -1, 4, 3, 4, -1, 3, 1, 3, -1, -1, -1,
            4, -1, 5, 4, 3, -1, 4, -1, 2, 3, 2, 1, 0, -1, -1, -1,
        )
        val penta = intArrayOf(0, 3, 5, 7, 10, 12, 15) // A минорная пентатоника от A4
        for (e in 0 until total) {
            val at = e * eighth
            val ch = e / 16
            val root = roots[ch]
            val pos = e % 8
            // бас
            val bassNote = when (pos) { 0, 2, 4 -> root; 3, 7 -> root + 12; 6 -> root + 7; else -> Int.MIN_VALUE }
            if (bassNote != Int.MIN_VALUE) put(at, tone(hz(bassNote), hz(bassNote), eighth * 0.95f, TRI, 0.5f, decay = 1.1f))
            // арпеджио по шестнадцатым
            for (h in 0..1) {
                val ct = chords[ch][(e * 2 + h) % 3]
                val n = root + 24 + ct
                put(at + h * eighth / 2, tone(hz(n), hz(n), eighth * 0.55f, SQUARE, 0.07f, 0.125f, 1.8f))
            }
            // мелодия
            val m = melody[e]
            if (m >= 0) {
                val n = penta[m]
                put(at, tone(hz(n), hz(n), eighth * 1.7f, SQUARE, 0.12f, 0.25f, 1.4f, vibrato = 5f))
            }
            // ударные
            if (pos == 0 || pos == 4) put(at, tone(130f, 45f, 0.12f, SINE, 0.5f, decay = 1.5f))
            if (pos == 2 || pos == 6) put(at, tone(900f, 900f, 0.1f, NOISE, 0.12f, decay = 2f))
            put(at + eighth / 2, tone(7000f, 7000f, 0.025f, NOISE, 0.035f, decay = 1f))
        }
        buf
    }
}
