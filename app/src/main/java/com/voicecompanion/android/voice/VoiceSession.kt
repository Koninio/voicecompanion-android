package com.voicecompanion.android.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.voicecompanion.android.core.Cancel
import com.voicecompanion.android.core.Character
import com.voicecompanion.android.core.bool
import com.voicecompanion.android.core.int
import com.voicecompanion.android.core.str
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

sealed class VoiceEvent {
    /** idle | listening | user_speaking | transcribing | speaking */
    class State(val state: String) : VoiceEvent()
    class Level(val rms: Float) : VoiceEvent()
    class UserFinal(val text: String) : VoiceEvent()
    /** The user started speaking while the bot was speaking; playback is already interrupted. */
    object BargeIn : VoiceEvent()
    class Error(val where: String, val message: String) : VoiceEvent()
}

private const val TAG = "VoiceSession"
private const val LEVEL_INTERVAL_MS = 60L // ~15 Hz

/** Perceptual mic level 0..1 from int16 samples (RMS mapped from -60..-10 dBFS). */
fun levelFromFrame(s: ShortArray, n: Int): Float {
    if (n <= 0) return 0f
    var sum = 0.0
    for (i in 0 until n) {
        val v = s[i] / 32768.0
        sum += v * v
    }
    val rms = sqrt(sum / n)
    if (rms <= 1e-6) return 0f
    val db = 20.0 * log10(rms)
    return ((db + 60.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Port of desktop ``app/voice/pipeline.py`` VoiceSession: mic → end-of-turn → STT →
 * UserFinal, and pipelined TTS playback with barge-in.
 *
 * Two listening back-ends (``stt.mode``):
 *  - "whisper": AudioRecord (VOICE_COMMUNICATION, echo cancel) → Silero VAD →
 *    Whisper-compatible HTTP API. Same behaviour as the desktop.
 *  - "android": the system SpeechRecognizer (no setup, end-of-turn decided by it).
 */
class VoiceSession(
    private val context: Context,
    @Volatile var cfg: JSONObject,
    @Volatile var tts: TtsEngine,
    private val onEvent: (VoiceEvent) -> Unit,
) {
    private val lock = Any()
    private val speakLock = Any()
    private val player = Player()

    @Volatile
    var active = false
        private set
    private var userSpeaking = false
    private var transcribing = 0
    private var speaking = false
    private var barged = false
    private var cancel: Cancel? = null
    @Volatile
    var state = "idle"
        private set
    private var lastLevel = 0L

    // whisper back-end
    private var vad: StreamingVad? = null
    private var silero: SileroStream? = null
    private var micThread: Thread? = null
    private var sttThread: Thread? = null
    private var sttQueue: LinkedBlockingQueue<ByteArray>? = null
    private var stopFlag: Cancel = Cancel()

    // android back-end
    private var speech: AndroidSpeechInput? = null

    private val mode: String get() = cfg.str("stt.mode", "android")

    private fun emit(ev: VoiceEvent) {
        try {
            onEvent(ev)
        } catch (e: Exception) {
            Log.e(TAG, "onEvent failed", e)
        }
    }

    private fun error(where: String, message: String) = emit(VoiceEvent.Error(where, message))

    private fun computeState(): String = when {
        userSpeaking && active -> "user_speaking"
        speaking -> "speaking"
        transcribing > 0 && active -> "transcribing"
        active -> "listening"
        else -> "idle"
    }

    private fun updateState() = synchronized(lock) {
        val s = computeState()
        if (s != state) {
            state = s
            emit(VoiceEvent.State(s))
        }
    }

    private fun bargeEnabled() = cfg.bool("vad.barge_in", true)

    fun updateConfig(c: JSONObject) {
        cfg = c
        vad?.configure(c)
    }

    // ------------------------------------------------------------------ mic side
    fun start() {
        synchronized(lock) {
            if (active) return
            if (mode == "whisper") {
                if (!startWhisper()) return
            } else {
                val s = AndroidSpeechInput(context, this)
                speech = s
                s.start()
            }
            active = true
            userSpeaking = false
            updateState()
        }
    }

    fun stop() {
        val mt: Thread?
        synchronized(lock) {
            if (!active) return
            active = false
            stopFlag.set()
            mt = micThread
            micThread = null
            sttQueue?.put(ByteArray(0)) // sentinel
            sttQueue = null
            speech?.stop()
            speech = null
            userSpeaking = false
        }
        if (mt != null && mt !== Thread.currentThread()) mt.join(2000)
        interrupt()
        vad?.reset()
        updateState()
    }

    private fun getVad(): StreamingVad {
        vad?.let { return it }
        val model = context.assets.open("silero_vad_v6.onnx").use { it.readBytes() }
        val s = SileroStream(model)
        silero = s
        return StreamingVad(cfg, { s.prob(it) }, { s.reset() }).also { vad = it }
    }

    @SuppressLint("MissingPermission")
    private fun startWhisper(): Boolean {
        val v = try {
            getVad().also {
                it.reset()
                if (speaking && bargeEnabled()) it.setMode("barge_in")
            }
        } catch (e: Throwable) {
            error("mic", "VAD 초기화 실패: ${e.message}")
            return false
        }
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_SAMPLES * 2 * 8))
        } catch (e: Exception) {
            error("mic", "마이크를 열 수 없습니다: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("mic", "마이크를 열 수 없습니다 (권한 또는 다른 앱이 사용 중)")
            return false
        }
        val effects = ArrayList<android.media.audiofx.AudioEffect>()
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) } }

        val stop = Cancel()
        stopFlag = stop
        val q = LinkedBlockingQueue<ByteArray>()
        sttQueue = q
        val transcriber = WhisperApiTranscriber(cfg)
        micThread = Thread({
            try {
                rec.startRecording()
                val buf = ShortArray(FRAME_SAMPLES)
                while (!stop.isSet) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) {
                        if (!stop.isSet) {
                            error("mic", "마이크 읽기 오류 ($n)")
                            Thread { stop() }.start()
                        }
                        break
                    }
                    if (n == 0) continue
                    try {
                        processFrame(v, buf, n)
                    } catch (e: Exception) {
                        Log.e(TAG, "voice frame processing failed", e)
                        error("mic", "음성 처리 오류: ${e.message}")
                    }
                }
            } finally {
                runCatching { rec.stop() }
                effects.forEach { runCatching { it.release() } }
                rec.release()
            }
        }, "voice-mic").apply { isDaemon = true; start() }
        sttThread = Thread({ sttLoop(q, transcriber) }, "voice-stt").apply { isDaemon = true; start() }
        return true
    }

    private fun processFrame(vad: StreamingVad, frame: ShortArray, n: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevel >= LEVEL_INTERVAL_MS && (state == "listening" || state == "user_speaking")) {
            lastLevel = now
            emit(VoiceEvent.Level(levelFromFrame(frame, n)))
        }
        if (speaking && !bargeEnabled()) return // no barge-in: ignore the mic (and our own echo) while talking
        for (ev in vad.process(frame, n)) {
            when (ev) {
                is VadEvent.SpeechStart -> synchronized(lock) {
                    if (speaking && bargeEnabled() && !barged) {
                        barged = true
                        userSpeaking = true
                        speaking = false
                        interrupt()           // contract: already interrupted when BargeIn arrives
                        vad.setMode("normal") // keep recording this utterance normally
                        emit(VoiceEvent.BargeIn)
                    } else {
                        userSpeaking = true
                    }
                    updateState()
                }
                is VadEvent.SpeechEnd -> {
                    val q = sttQueue
                    synchronized(lock) {
                        userSpeaking = false
                        if (q != null) transcribing++
                        updateState()
                    }
                    q?.put(ev.audio)
                }
                is VadEvent.SpeechCancel -> synchronized(lock) {
                    userSpeaking = false
                    updateState()
                }
            }
        }
    }

    private fun sttLoop(q: LinkedBlockingQueue<ByteArray>, transcriber: WhisperApiTranscriber) {
        while (true) {
            val audio = q.take()
            if (audio.isEmpty()) return
            var text = ""
            try {
                text = transcriber.transcribe(audio)
            } catch (e: Exception) {
                Log.w(TAG, "transcription failed", e)
                error("stt", "음성 인식 실패: ${e.message}")
            }
            synchronized(lock) {
                transcribing = maxOf(0, transcribing - 1)
                if (text.isNotBlank() && active && sttQueue === q) emit(VoiceEvent.UserFinal(text.trim()))
                updateState()
            }
        }
    }

    // ------------------------------------------------- android SpeechRecognizer hooks
    internal fun srLevel(level: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevel >= LEVEL_INTERVAL_MS && (state == "listening" || state == "user_speaking")) {
            lastLevel = now
            emit(VoiceEvent.Level(level))
        }
    }

    internal fun srSpeechStart() = synchronized(lock) {
        if (!speaking) {
            userSpeaking = true
            updateState()
        }
    }

    /** Partial text while listening; while the bot speaks this is the barge-in signal. */
    /** Returns true when this partial caused a barge-in. */
    internal fun srPartial(text: String): Boolean = synchronized(lock) {
        if (speaking && bargeEnabled() && !barged && text.trim().length >= 2) {
            barged = true
            userSpeaking = true
            speaking = false
            interrupt()
            emit(VoiceEvent.BargeIn)
            updateState()
            return@synchronized true
        } else if (!speaking && text.isNotBlank() && !userSpeaking) {
            userSpeaking = true
            updateState()
        }
        false
    }

    internal fun srEndOfSpeech() = synchronized(lock) {
        if (userSpeaking) {
            userSpeaking = false
            transcribing = 1
            updateState()
        }
    }

    /** heardWhileSpeaking: the utterance began while the bot was talking (likely its own echo). */
    internal fun srFinal(text: String, heardWhileSpeaking: Boolean) = synchronized(lock) {
        val echo = heardWhileSpeaking && !barged
        userSpeaking = false
        transcribing = 0
        if (text.isNotBlank() && active && !speaking && !echo) emit(VoiceEvent.UserFinal(text.trim()))
        updateState()
    }

    internal fun srIdle() = synchronized(lock) {
        if (userSpeaking || transcribing > 0) {
            userSpeaking = false
            transcribing = 0
            updateState()
        }
    }

    internal fun srError(message: String) = error("stt", message)

    internal val isSpeaking: Boolean get() = speaking

    // ------------------------------------------------------------------ speaker side
    fun interrupt() {
        val c = synchronized(lock) { cancel }
        c?.set()
        player.interrupt()
    }

    private fun beginSpeaking() {
        synchronized(lock) {
            speaking = true
            barged = false
            if (bargeEnabled()) vad?.setMode("barge_in")
            updateState()
        }
        speech?.onSpeakingChanged(true, bargeEnabled())
    }

    private fun endSpeaking() {
        val wasBarged: Boolean
        synchronized(lock) {
            val was = speaking || barged
            wasBarged = barged
            speaking = false
            val v = vad
            if (was && v != null) {
                v.setMode("normal")
                if (!barged && !v.inSpeech) v.reset() // forget echo of our own voice
            }
            barged = false
            updateState()
        }
        speech?.onSpeakingChanged(false, bargeEnabled(), wasBarged)
    }

    private sealed class Item {
        class Audio(val rate: Int, val pcm: ByteArray) : Item()
        class Err(val e: Throwable) : Item()
        object End : Item()
    }

    private fun qGet(q: LinkedBlockingQueue<Item>, cancel: Cancel): Item? {
        while (!cancel.isSet) {
            return q.poll(50, TimeUnit.MILLISECONDS) ?: continue
        }
        return null
    }

    /** Blocking. Synthesizes chunk N+1 while chunk N plays. False if interrupted. */
    fun speak(chunks: ChunkSource, persona: Character): Boolean = synchronized(speakLock) {
        val c = Cancel()
        synchronized(lock) { cancel = c }
        val engine = tts
        val q = LinkedBlockingQueue<Item>()
        Thread({ synthLoop(engine, chunks, persona, c, q) }, "voice-tts").apply { isDaemon = true; start() }
        var pending: Item? = null
        var done = false
        var talking = false
        try {
            while (!done && !c.isSet) {
                val item = pending ?: qGet(q, c)
                pending = null
                if (item == null) break // cancelled while waiting
                when (item) {
                    is Item.Audio -> {
                        if (!talking) {
                            talking = true
                            beginSpeaking()
                        }
                        val sr = item.rate
                        var first: ByteArray? = item.pcm
                        fun next(): ByteArray? {
                            first?.let { first = null; return it }
                            if (c.isSet) return null
                            return when (val it = qGet(q, c)) {
                                null -> null
                                is Item.Audio -> if (it.rate != sr) { pending = it; null } else it.pcm // rate changed: re-open
                                is Item.End -> { done = true; null }
                                else -> { pending = it; null }
                            }
                        }
                        player.play(::next, sr)
                    }
                    is Item.Err -> {
                        // TTS failure is reported, not treated as an interruption;
                        // the remaining chunks are dropped (finally cancels synthesis).
                        error("tts", item.e.message ?: item.e.toString())
                        return@synchronized true
                    }
                    is Item.End -> done = true
                }
            }
            !c.isSet
        } finally {
            if (!done) c.set()
            if (talking) endSpeaking()
            synchronized(lock) { if (cancel === c) cancel = null }
        }
    }

    private fun synthLoop(engine: TtsEngine, chunks: ChunkSource, persona: Character, c: Cancel,
                          q: LinkedBlockingQueue<Item>) {
        val deliver: (ByteArray) -> Unit = { pcm ->
            if (!c.isSet && pcm.isNotEmpty()) q.put(Item.Audio(engine.sampleRate, pcm))
        }
        try {
            if (engine.textStreaming) {
                // one live session for the whole reply; the engine drains `chunks` itself
                engine.synthesizeTextStream(chunks, persona, c, deliver)
            } else {
                while (!c.isSet) {
                    val text = chunks.next() ?: break
                    if (text.isBlank()) continue
                    engine.synthesizeStream(text, persona, c, deliver)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "TTS failed", e)
            q.put(Item.Err(e))
            // keep consuming so the producer feeding `chunks` finishes
            runCatching { while (chunks.next() != null) Unit }
            return
        }
        q.put(Item.End)
    }

    fun release() {
        stop()
        runCatching { silero?.close() }
        silero = null
        vad = null
    }
}

/**
 * Continuous listening on top of Android's SpeechRecognizer (main thread only).
 * Each recognition ends after the recognizer's own end-of-speech detection; it is
 * restarted right away while the session is active.
 */
class AndroidSpeechInput(private val context: Context, private val session: VoiceSession) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var running = false
    private var listening = false
    private var paused = false
    private var heardWhileSpeaking = false
    private var errorStreak = 0

    private fun language(): String {
        val l = session.cfg.str("stt.language", "ko").ifEmpty { "ko" }
        return when (l) {
            "ko" -> "ko-KR"
            "en" -> "en-US"
            "ja" -> "ja-JP"
            else -> l
        }
    }

    fun start() = main.post {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            session.srError("이 기기에서 Android 음성 인식을 쓸 수 없어요. 설정에서 음성 인식을 'Whisper API'로 바꿔 주세요.")
            return@post
        }
        running = true
        paused = false
        create()
        listen()
    }

    fun stop() = main.post {
        running = false
        listening = false
        main.removeCallbacksAndMessages(null)
        sr?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        sr = null
    }

    /** Called from the speak thread when bot playback starts / ends. */
    fun onSpeakingChanged(speaking: Boolean, bargeIn: Boolean, barged: Boolean = false) = main.post {
        if (!running) return@post
        if (speaking) {
            if (!bargeIn) { // no barge-in: don't listen to our own voice
                paused = true
                cancelListening()
            }
        } else {
            paused = false
            if (!barged) cancelListening() // drop a recognition that may hold our own echo
            listen()
        }
    }

    private fun cancelListening() {
        if (listening) runCatching { sr?.cancel() }
        listening = false
        session.srIdle()
    }

    private fun create() {
        sr?.let { runCatching { it.destroy() } }
        sr = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
    }

    private fun listen() {
        if (!running || paused || listening) return
        val silence = session.cfg.int("vad.end_silence_ms", 1000).toLong()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        heardWhileSpeaking = false
        try {
            sr?.startListening(intent)
            listening = true
        } catch (e: Exception) {
            session.srError("음성 인식 시작 실패: ${e.message}")
            restart(1000)
        }
    }

    private fun restart(delayMs: Long) {
        listening = false
        main.postDelayed({ listen() }, delayMs)
    }

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            errorStreak = 0
        }

        override fun onBeginningOfSpeech() {
            if (session.isSpeaking) heardWhileSpeaking = true
            session.srSpeechStart()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // recognizer reports roughly -2..10 dB
            session.srLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            session.srEndOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = firstResult(partialResults)
            if (t.isNotBlank() && session.srPartial(t)) heardWhileSpeaking = false // it is the user, not echo
        }

        override fun onResults(results: Bundle?) {
            session.srFinal(firstResult(results), heardWhileSpeaking)
            restart(50)
        }

        override fun onError(error: Int) {
            listening = false
            session.srIdle()
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    session.srError("마이크 권한이 필요해요")
                    running = false
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    create()
                    restart(300)
                }
                else -> {
                    errorStreak++
                    if (errorStreak == 1 || errorStreak % 5 == 0) {
                        session.srError("Android 음성 인식 오류 ($error)")
                    }
                    if (errorStreak >= 3) create()
                    restart(minOf(5000L, 500L * errorStreak))
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
