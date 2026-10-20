package com.voicecompanion.android

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.voicecompanion.android.core.CharacterStore
import com.voicecompanion.android.core.Config
import com.voicecompanion.android.core.ConvEvent
import com.voicecompanion.android.core.Conversation
import com.voicecompanion.android.core.bool
import com.voicecompanion.android.core.str
import com.voicecompanion.android.llm.LlmClient
import com.voicecompanion.android.voice.TtsEngine
import com.voicecompanion.android.voice.VoiceEvent
import com.voicecompanion.android.voice.VoiceSession
import com.voicecompanion.android.voice.WhisperApiTranscriber
import com.voicecompanion.android.voice.createTts
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class App : Application() {
    lateinit var runtime: Runtime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = Runtime(this)
    }

    companion object {
        fun runtime(ctx: Context): Runtime = (ctx.applicationContext as App).runtime
    }
}

/** Everything the UI talks to (desktop ``server.Runtime`` + the WebSocket events). */
sealed class UiEvent {
    class Conv(val ev: ConvEvent) : UiEvent()
    class Voice(val ev: VoiceEvent) : UiEvent()
    class Status(val status: RuntimeStatus) : UiEvent()
    object PersonasChanged : UiEvent()
}

data class Check(val ok: Boolean?, val message: String) // ok == null: checking

data class RuntimeStatus(val llm: Check, val stt: Check, val tts: Check, val ttsMode: String)

class Runtime(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(UiEvent) -> Unit>()

    val config = Config(File(ctx.filesDir, "config.json"))
    private val historyDir = File(ctx.filesDir, "history")
    val characters = CharacterStore(ctx.assets, File(ctx.filesDir, "characters"), historyDir)

    @Volatile
    var llm = LlmClient(config.data)
        private set
    @Volatile
    private var tts: TtsEngine = createTts(config.data)

    val voice = VoiceSession(ctx, config.data, tts) { ev -> onVoiceEvent(ev) }

    val conversation = Conversation(
        config = { config.data },
        llm = { llm },
        characters = characters,
        historyDir = historyDir,
        emit = { dispatch(UiEvent.Conv(it)) },
        voiceProvider = { _ -> voiceForReply() },
    )

    @Volatile
    var speakReplies: Boolean = config.data.bool("ui.speak_replies", true)

    @Volatile
    var status = RuntimeStatus(Check(null, "확인 중…"), Check(null, "확인 중…"), Check(null, "확인 중…"),
        config.data.str("tts.mode", "cloud"))
        private set

    fun addListener(l: (UiEvent) -> Unit) = listeners.add(l)
    fun removeListener(l: (UiEvent) -> Unit) = listeners.remove(l)

    /** Always delivered on the main thread. */
    fun dispatch(ev: UiEvent) {
        main.post { for (l in listeners) l(ev) }
    }

    private fun voiceForReply(): VoiceSession? {
        if (config.data.str("tts.mode", "cloud") == "off") return null
        return if (voice.active || speakReplies) voice else null
    }

    private fun onVoiceEvent(ev: VoiceEvent) {
        when (ev) {
            is VoiceEvent.UserFinal -> conversation.onUserVoice(ev.text)
            is VoiceEvent.BargeIn -> conversation.interrupt()
            else -> {}
        }
        dispatch(UiEvent.Voice(ev))
    }

    /** After the settings screen saved: rebuild clients that depend on config. */
    fun applyConfig() {
        val c = config.data
        llm = LlmClient(c)
        tts = createTts(c)
        voice.tts = tts
        voice.updateConfig(c)
        speakReplies = c.bool("ui.speak_replies", true)
        refreshStatus()
    }

    fun setPersona(pid: String) {
        conversation.setPersona(pid)
        config.update(JSONObject().put("ui", JSONObject().put("default_persona", pid)))
    }

    fun personasChanged() = dispatch(UiEvent.PersonasChanged)

    fun refreshStatus() {
        val c = config.data
        val mode = c.str("tts.mode", "cloud")
        status = status.copy(llm = Check(null, "확인 중…"), tts = Check(null, "확인 중…"), ttsMode = mode)
        dispatch(UiEvent.Status(status))
        val llmNow = llm
        val ttsNow = tts
        Thread {
            val l = runCatching { llmNow.check() }.getOrElse { false to (it.message ?: "오류") }
            val t = runCatching { ttsNow.check() }.getOrElse { false to (it.message ?: "오류") }
            val s = if (c.str("stt.mode", "android") == "whisper") {
                WhisperApiTranscriber(c).check()
            } else {
                if (SpeechRecognizer.isRecognitionAvailable(ctx)) true to "Android 음성 인식"
                else false to "이 기기에서 Android 음성 인식을 쓸 수 없어요"
            }
            status = RuntimeStatus(Check(l.first, l.second), Check(s.first, s.second), Check(t.first, t.second), mode)
            dispatch(UiEvent.Status(status))
        }.apply { isDaemon = true; start() }
    }
}
