package com.voicecompanion.android.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voicecompanion.android.core.bool
import com.voicecompanion.android.core.num
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Streaming end-of-utterance detection with Silero VAD v6 (ONNX, CPU) — port of
 * desktop ``app/voice/vad.py``. Model I/O (same as faster_whisper's SileroVADModel):
 *   input  "input" float32 [1, 64 + 512]  (64 samples of context + 512 new, 16 kHz)
 *          "h", "c" float32 [1, 1, 128]   (LSTM state, carried across frames)
 *   output "speech_probs" [1], "hn", "cn"
 */
const val FRAME_SAMPLES = 512
private const val CONTEXT_SAMPLES = 64
private const val BARGE_IN_MIN_SPEECH_MS = 300.0

class SileroStream(modelBytes: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var h = FloatArray(128)
    private var c = FloatArray(128)
    private val context = FloatArray(CONTEXT_SAMPLES)
    private val input = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)

    init {
        val opts = OrtSession.SessionOptions()
        opts.setInterOpNumThreads(1)
        opts.setIntraOpNumThreads(1)
        session = env.createSession(modelBytes, opts)
    }

    fun reset() {
        h = FloatArray(128)
        c = FloatArray(128)
        context.fill(0f)
    }

    /** float frame of 512 samples (16 kHz, -1..1) → speech probability. */
    fun prob(frame: FloatArray): Float {
        require(frame.size == FRAME_SAMPLES) { "Silero expects 512-sample frames at 16 kHz" }
        System.arraycopy(context, 0, input, 0, CONTEXT_SAMPLES)
        System.arraycopy(frame, 0, input, CONTEXT_SAMPLES, FRAME_SAMPLES)
        val shapeIn = longArrayOf(1, (CONTEXT_SAMPLES + FRAME_SAMPLES).toLong())
        val shapeState = longArrayOf(1, 1, 128)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shapeIn).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(h), shapeState).use { ht ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(c), shapeState).use { ct ->
                    session.run(mapOf("input" to x, "h" to ht, "c" to ct)).use { res ->
                        val p = (res.get(0) as OnnxTensor).floatBuffer.get(0)
                        h = floats(res.get(1) as OnnxTensor)
                        c = floats(res.get(2) as OnnxTensor)
                        System.arraycopy(frame, FRAME_SAMPLES - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES)
                        return p
                    }
                }
            }
        }
    }

    private fun floats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    override fun close() = session.close()
}

sealed class VadEvent {
    object SpeechStart : VadEvent()
    class SpeechEnd(val audio: ByteArray) : VadEvent()   // whole utterance incl. pre-roll
    object SpeechCancel : VadEvent()                      // started but shorter than min_speech_ms
}

/**
 * Feed int16 PCM (any length), get utterance events.
 *
 * Hysteresis: speech starts at ``threshold``; a silence run starts at the first frame
 * below ``threshold - 0.15`` and is only broken by a frame >= threshold. SpeechEnd
 * fires once the silence run reaches ``end_silence_ms``.
 *
 * Modes: "normal" and "barge_in" (higher threshold, speech must last >= 300 ms
 * before SpeechStart is confirmed). Switching mode keeps an utterance in progress.
 */
class StreamingVad(cfg: JSONObject, private val probFn: (FloatArray) -> Float, private val resetFn: () -> Unit = {}) {
    private val frameMs = FRAME_SAMPLES * 1000.0 / 16000 // 32 ms
    private val lock = Any()

    var mode = "normal"
        private set
    private var normalThreshold = 0.5
    private var bargeThreshold = 0.8
    private var endSilenceMs = 1000.0
    private var minSpeechMs = 250.0
    private var prerollMs = 300.0
    private var maxSpeechS = 30.0
    private var bargeMinSpeechMs = BARGE_IN_MIN_SPEECH_MS

    private var threshold = 0.5
    private var negThreshold = 0.35
    private var endFrames = 1
    private var startFrames = 1
    private var prerollFrames = 0
    private var maxFrames = 1

    private var carry = ShortArray(0)
    private val preroll = ArrayDeque<ByteArray>()
    private var state = "idle" // idle | pending | speech
    private var frames = ArrayList<ByteArray>()
    private var speechFrames = 0
    private var pendingSilence = 0
    private var silenceStart: Int? = null
    private var lastVoiced = -1
    private var uttStart = 0

    @Volatile
    var lastProb = 0f
        private set

    init {
        configure(cfg)
        reset()
    }

    fun configure(cfg: JSONObject) = synchronized(lock) {
        normalThreshold = cfg.num("vad.threshold", 0.5)
        bargeThreshold = cfg.num("vad.barge_in_threshold", 0.8)
        endSilenceMs = cfg.num("vad.end_silence_ms", 1000.0)
        minSpeechMs = cfg.num("vad.min_speech_ms", 250.0)
        prerollMs = cfg.num("vad.preroll_ms", 300.0)
        maxSpeechS = cfg.num("vad.max_speech_s", 30.0)
        bargeMinSpeechMs = cfg.num("vad.barge_in_min_speech_ms", BARGE_IN_MIN_SPEECH_MS)
        applyMode()
    }

    private fun applyMode() {
        val startMs: Double
        if (mode == "barge_in") {
            threshold = bargeThreshold
            startMs = bargeMinSpeechMs
        } else {
            threshold = normalThreshold
            startMs = 0.0
        }
        negThreshold = max(threshold - 0.15, 0.01)
        endFrames = max(1, ceil(endSilenceMs / frameMs).toInt())
        startFrames = max(1, ceil(startMs / frameMs).toInt())
        prerollFrames = max(0, ceil(prerollMs / frameMs).toInt())
        maxFrames = max(1, (maxSpeechS * 1000 / frameMs).toInt())
    }

    /** "normal" or "barge_in". */
    fun setMode(m: String) = synchronized(lock) {
        require(m == "normal" || m == "barge_in") { m }
        mode = m
        applyMode()
    }

    /** Drop any utterance in progress and the model state. */
    fun reset() = synchronized(lock) {
        carry = ShortArray(0)
        preroll.clear()
        state = "idle"
        frames = ArrayList()
        speechFrames = 0
        pendingSilence = 0
        silenceStart = null
        lastVoiced = -1
        lastProb = 0f
        runCatching { resetFn() }
    }

    val inSpeech: Boolean get() = state == "speech"

    private fun pushPreroll(fb: ByteArray) {
        preroll.addLast(fb)
        while (preroll.size > max(1, prerollFrames)) preroll.removeFirst()
    }

    fun process(pcm: ShortArray, len: Int = pcm.size): List<VadEvent> = synchronized(lock) {
        val events = ArrayList<VadEvent>()
        val samples = ShortArray(carry.size + len)
        System.arraycopy(carry, 0, samples, 0, carry.size)
        System.arraycopy(pcm, 0, samples, carry.size, len)
        val n = samples.size / FRAME_SAMPLES
        val f = FloatArray(FRAME_SAMPLES)
        for (k in 0 until n) {
            val off = k * FRAME_SAMPLES
            val fb = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until FRAME_SAMPLES) {
                val s = samples[off + i]
                f[i] = s / 32768f
                fb.putShort(s)
            }
            step(f, fb.array(), events)
        }
        carry = samples.copyOfRange(n * FRAME_SAMPLES, samples.size)
        events
    }

    private fun step(f: FloatArray, fb: ByteArray, events: MutableList<VadEvent>) {
        val p = probFn(f)
        lastProb = p
        if (state == "idle") {
            if (p >= threshold) {
                frames = if (prerollFrames > 0) ArrayList(preroll) else ArrayList()
                preroll.clear()
                uttStart = frames.size
                frames.add(fb)
                speechFrames = 1
                pendingSilence = 0
                silenceStart = null
                lastVoiced = frames.size - 1
                state = "pending"
                if (speechFrames >= startFrames) {
                    state = "speech"
                    events.add(VadEvent.SpeechStart)
                }
            } else if (prerollFrames > 0) {
                pushPreroll(fb)
            }
            return
        }

        frames.add(fb)
        val idx = frames.size - 1

        if (state == "pending") {
            if (p >= negThreshold) {
                speechFrames++
                pendingSilence = 0
                lastVoiced = idx
            } else {
                pendingSilence++
                if (pendingSilence * frameMs > 100) { // blip: give up
                    val keep = if (prerollFrames > 0) frames.takeLast(prerollFrames) else emptyList()
                    state = "idle"
                    frames = ArrayList()
                    preroll.clear()
                    keep.forEach { pushPreroll(it) }
                    return
                }
            }
            if (speechFrames >= startFrames) {
                state = "speech"
                silenceStart = null
                events.add(VadEvent.SpeechStart)
            }
            return
        }

        // state == "speech"
        if (p >= threshold) {
            silenceStart = null
        } else if (p < negThreshold && silenceStart == null) {
            silenceStart = idx
        }
        if (silenceStart == null) {
            speechFrames++
            lastVoiced = idx
        }
        val uttFrames = idx - uttStart + 1
        val silenceFrames = silenceStart?.let { idx - it + 1 } ?: 0
        if (silenceFrames >= endFrames || uttFrames >= maxFrames) finish(events)
    }

    private fun finish(events: MutableList<VadEvent>) {
        val voicedMs = speechFrames * frameMs
        if (voicedMs < minSpeechMs) {
            events.add(VadEvent.SpeechCancel)
        } else {
            // keep ~preroll_ms of trailing silence after the last voiced frame
            val tail = max(prerollFrames, 3)
            val end = minOf(frames.size, lastVoiced + 1 + tail)
            val out = ByteArray(end * FRAME_SAMPLES * 2)
            for (i in 0 until end) System.arraycopy(frames[i], 0, out, i * FRAME_SAMPLES * 2, FRAME_SAMPLES * 2)
            events.add(VadEvent.SpeechEnd(out))
        }
        state = "idle"
        frames = ArrayList()
        silenceStart = null
        speechFrames = 0
        preroll.clear()
    }

    companion object {
        fun bargeInEnabled(cfg: JSONObject) = cfg.bool("vad.barge_in", true)
    }
}
