package com.voicecompanion.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.voicecompanion.android.App
import com.voicecompanion.android.Runtime
import com.voicecompanion.android.core.Config
import com.voicecompanion.android.core.bool
import com.voicecompanion.android.core.int
import com.voicecompanion.android.core.putPath
import com.voicecompanion.android.core.str
import com.voicecompanion.android.llm.LlmClient
import com.voicecompanion.android.voice.WhisperApiTranscriber
import com.voicecompanion.android.voice.createTts
import org.json.JSONObject

/** Row of selectable pills (the desktop's ``.segmented`` control). */
class Segmented(ctx: Context, private val pal: Palette, options: List<Pair<String, String>>, initial: String,
                private val onChange: (String) -> Unit = {}) : HorizontalScrollView(ctx) {
    var value: String = initial
        private set
    private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val chips = LinkedHashMap<String, TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        addView(row)
        for ((v, label) in options) {
            val chip = TextView(ctx).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
                setOnClickListener { select(v) }
            }
            chips[v] = chip
            row.addView(chip, lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
        }
        if (value !in chips) value = options.first().first
        restyle()
    }

    fun select(v: String) {
        if (v == value) return
        value = v
        restyle()
        onChange(v)
    }

    private fun restyle() {
        for ((v, chip) in chips) {
            val sel = v == value
            chip.setTextColor(if (sel) Color.WHITE else pal.text2)
            chip.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            chip.background = if (sel) rounded(pal.accent, context.dp(18).toFloat())
            else rounded(pal.surface, context.dp(18).toFloat(), context.dp(1), pal.border)
        }
    }
}

class SettingsActivity : Activity() {
    private lateinit var rt: Runtime
    private lateinit var pal: Palette
    private lateinit var form: Form

    private lateinit var llmUrl: EditText
    private lateinit var llmKey: EditText
    private lateinit var llmModel: EditText
    private lateinit var historyTurns: EditText
    private lateinit var llamaParams: CheckBox

    private lateinit var ttsMode: Segmented
    private lateinit var fishKey: EditText
    private lateinit var fishModel: Segmented
    private lateinit var fishTransport: Segmented
    private lateinit var fishLatency: Segmented
    private lateinit var emotionTags: CheckBox
    private lateinit var localUrl: EditText
    private val cloudRefs = LinkedHashMap<String, EditText>()
    private val localRefs = LinkedHashMap<String, EditText>()
    private lateinit var speakReplies: CheckBox
    private lateinit var cloudGroup: List<View>
    private lateinit var localGroup: List<View>

    private lateinit var sttMode: Segmented
    private lateinit var sttLang: Segmented
    private lateinit var whisperUrl: EditText
    private lateinit var whisperKey: EditText
    private lateinit var whisperModel: EditText
    private lateinit var whisperGroup: List<View>

    private var silenceMs = 1000
    private lateinit var bargeIn: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = App.runtime(this)
        pal = Palette.of(this)
        applySystemBars(pal)
        setContentView(build())
    }

    private fun build(): View {
        val cfg = rt.config.data
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        col.addView(topBar("설정") { finish() }, lp(MATCH, WRAP))

        form = Form(this, pal)
        val f = form

        // -- LLM -------------------------------------------------------------------
        f.section("언어 모델", "OpenAI 호환 서버라면 무엇이든 돼요. PC의 llama-server를 쓰려면 " +
            "'--host 0.0.0.0'으로 실행하고 휴대폰을 같은 와이파이에 연결하세요.")
        llmUrl = f.field("서버 주소", textInput(pal, "예: http://192.168.0.10:8081  또는  https://openrouter.ai/api"),
            "'/v1'은 자동으로 붙어요.")
        llmUrl.setText(cfg.str("llm.base_url"))
        llmKey = f.field("API 키 (필요한 경우)", textInput(pal, "llama-server는 비워두세요", password = true))
        llmKey.setText(cfg.str("llm.api_key"))
        llmModel = f.field("모델 이름", textInput(pal, "비워두면 서버 기본 모델"))
        llmModel.setText(cfg.str("llm.model"))
        historyTurns = f.field("대화 기억 (턴 수)", textInput(pal, "20", number = true),
            "최근 몇 번의 주고받기를 모델에 보낼지 정해요.")
        historyTurns.setText(cfg.int("llm.history_turns", 20).toString())
        llamaParams = checkbox("llama.cpp 전용 옵션 보내기 (top_k, repeat_penalty, 프롬프트 캐시)",
            cfg.bool("llm.llama_params", true))
        f.add(llamaParams)
        f.help("OpenAI 등 다른 서비스가 알 수 없는 옵션이라고 거부하면 끄세요.")

        // -- TTS -------------------------------------------------------------------
        f.section("목소리 (TTS)")
        ttsMode = f.field("모드", Segmented(this, pal, listOf("cloud" to "클라우드 (Fish Audio)",
            "local" to "로컬 (fish-speech)", "off" to "끄기"), cfg.str("tts.mode", "cloud")) { updateGroups() })

        val cloud = ArrayList<View>()
        fun <T : View> c(v: T): T = v.also { cloud.add(it) }
        fishKey = textInput(pal, "API 키 입력", password = true).apply { setText(cfg.str("tts.cloud.api_key")) }
        cloud.add(labelView("Fish Audio API 키"))
        f.add(cloud.last())
        f.add(c(fishKey))
        c(helpView("fish.audio → API Keys 에서 발급할 수 있어요.")).also { f.add(it) }
        f.add(c(labelView("모델")))
        fishModel = c(Segmented(this, pal, Config.CLOUD_MODELS.map { it to it }, cfg.str("tts.cloud.model", "drama-3")))
        f.add(fishModel)
        f.add(c(helpView("drama-3·s2 계열은 [감정 태그]를 연기해요. s2.1-pro-free는 테스트용이에요.")))
        f.add(c(labelView("전송 방식")))
        fishTransport = c(Segmented(this, pal, listOf("websocket" to "실시간 (권장)", "http" to "문장별 HTTP"),
            cfg.str("tts.cloud.transport", "websocket")))
        f.add(fishTransport)
        f.add(c(labelView("지연 시간")))
        fishLatency = c(Segmented(this, pal, listOf("low" to "낮음", "balanced" to "균형", "normal" to "보통"),
            cfg.str("tts.cloud.latency", "low")))
        f.add(fishLatency)
        emotionTags = c(checkbox("감정 태그 사용 ([다정하게] 같은 연기 지시)", cfg.bool("tts.emotion_tags", true)))
        f.add(emotionTags)
        f.add(c(labelView("캐릭터별 클라우드 보이스 ID")))
        f.add(c(helpView("fish.audio 목소리 페이지 URL 끝의 ID를 붙여 넣으세요. " +
            "예: fish.audio/m/7f92f8…cb1 → 7f92f8…cb1. 비워두면 캐릭터 설정 값을 써요.")))
        val chars = rt.characters.list()
        for (ch in chars) {
            val e = textInput(pal, "${ch.voice.cloudReferenceId.ifEmpty { "reference_id" }}")
            e.setText(cfg.optJSONObject("personas_voice")?.optJSONObject(ch.id)?.optString("cloud_reference_id") ?: "")
            cloudRefs[ch.id] = e
            f.add(c(personaRow(ch.avatarEmoji + " " + ch.nameKo, e)))
        }
        cloudGroup = cloud

        val local = ArrayList<View>()
        fun <T : View> l(v: T): T = v.also { local.add(it) }
        f.add(l(labelView("fish-speech 서버 주소")))
        localUrl = l(textInput(pal, "예: http://192.168.0.10:8080")).apply { setText(cfg.str("tts.local.url")) }
        f.add(localUrl)
        f.add(l(labelView("캐릭터별 로컬 보이스 ID")))
        for (ch in chars) {
            val e = textInput(pal, ch.voice.localReferenceId.ifEmpty { "reference_id" })
            e.setText(cfg.optJSONObject("personas_voice")?.optJSONObject(ch.id)?.optString("local_reference_id") ?: "")
            localRefs[ch.id] = e
            f.add(l(personaRow(ch.avatarEmoji + " " + ch.nameKo, e)))
        }
        localGroup = local

        speakReplies = checkbox("입력한 메시지에도 목소리로 답하기", cfg.bool("ui.speak_replies", true))
        f.add(speakReplies)

        // -- STT -------------------------------------------------------------------
        f.section("음성 인식")
        sttMode = f.field("방식", Segmented(this, pal, listOf("android" to "Android 기본", "whisper" to "Whisper API"),
            cfg.str("stt.mode", "android")) { updateGroups() })
        f.help("Android 기본: 설정 없이 바로 쓸 수 있어요. Whisper API: 데스크톱처럼 Silero VAD로 말 끝을 " +
            "감지하고 Whisper로 받아 적어요 (Groq 무료 키로도 돼요).")
        sttLang = f.field("언어", Segmented(this, pal, listOf("ko" to "한국어", "en" to "English", "ja" to "日本語"),
            cfg.str("stt.language", "ko")))
        val wg = ArrayList<View>()
        fun <T : View> w(v: T): T = v.also { wg.add(it) }
        f.add(w(labelView("Whisper 서버 주소")))
        whisperUrl = w(textInput(pal, "https://api.groq.com/openai")).apply { setText(cfg.str("stt.whisper.base_url")) }
        f.add(whisperUrl)
        f.add(w(labelView("Whisper API 키")))
        whisperKey = w(textInput(pal, "API 키", password = true)).apply { setText(cfg.str("stt.whisper.api_key")) }
        f.add(whisperKey)
        f.add(w(labelView("Whisper 모델")))
        whisperModel = w(textInput(pal, "whisper-large-v3-turbo")).apply { setText(cfg.str("stt.whisper.model")) }
        f.add(whisperModel)
        whisperGroup = wg

        // -- turn taking -----------------------------------------------------------
        f.section("대화 감지")
        silenceMs = cfg.int("vad.end_silence_ms", 1000)
        val silenceLabel = labelView("말 끝 인식 대기 시간  ${silenceMs} ms")
        f.add(silenceLabel)
        val seek = SeekBar(this).apply {
            max = 15
            progress = ((silenceMs - 500) / 100).coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    silenceMs = 500 + p * 100
                    silenceLabel.text = "말 끝 인식 대기 시간  ${silenceMs} ms"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        f.add(seek)
        f.help("말을 멈추고 이 시간이 지나면 차례가 끝난 걸로 봐요.")
        bargeIn = checkbox("끼어들기 (봇이 말할 때 내가 말하면 멈추기)", cfg.bool("vad.barge_in", true))
        f.add(bargeIn)
        f.help("스피커로 들으면 봇이 자기 목소리에 멈출 수 있어요. 이어폰을 권장해요.")

        val scroll = ScrollView(this).apply { addView(f.root) }
        col.addView(scroll, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.sidebar)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bar.addView(pillButton("연결 테스트", pal) { testConnections() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        bar.addView(pillButton("취소", pal) { finish() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        bar.addView(pillButton("저장", pal, primary = true) { save() }, lp(WRAP, WRAP))
        col.addView(bar, lp(MATCH, WRAP))
        updateGroups()
        return col
    }

    private fun updateGroups() {
        val tm = ttsMode.value
        cloudGroup.forEach { it.visibility = if (tm == "cloud") View.VISIBLE else View.GONE }
        localGroup.forEach { it.visibility = if (tm == "local") View.VISIBLE else View.GONE }
        whisperGroup.forEach { it.visibility = if (sttMode.value == "whisper") View.VISIBLE else View.GONE }
    }

    private fun labelView(text: String) = label(text, 13.5f, pal.text2, bold = true).apply {
        setPadding(0, dp(12), 0, dp(5))
    }

    private fun helpView(text: String) = label(text, 12.5f, pal.text3).apply { setPadding(0, dp(2), 0, dp(4)) }

    private fun checkbox(text: String, checked: Boolean) = CheckBox(this).apply {
        this.text = text
        textSize = 14.5f
        setTextColor(pal.text)
        isChecked = checked
        setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun personaRow(name: String, edit: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(label(name, 14f, pal.text), lp(dp(110), WRAP))
        addView(edit, lp(0, WRAP, 1f))
    }

    private fun collect(): JSONObject {
        val o = JSONObject()
        o.putPath("llm.base_url", llmUrl.text.toString().trim())
        o.putPath("llm.api_key", llmKey.text.toString().trim())
        o.putPath("llm.model", llmModel.text.toString().trim())
        o.putPath("llm.history_turns", historyTurns.text.toString().trim().toIntOrNull()?.coerceIn(1, 200) ?: 20)
        o.putPath("llm.llama_params", llamaParams.isChecked)
        o.putPath("tts.mode", ttsMode.value)
        o.putPath("tts.emotion_tags", emotionTags.isChecked)
        o.putPath("tts.cloud.api_key", fishKey.text.toString().trim())
        o.putPath("tts.cloud.model", fishModel.value)
        o.putPath("tts.cloud.transport", fishTransport.value)
        o.putPath("tts.cloud.latency", fishLatency.value)
        o.putPath("tts.local.url", localUrl.text.toString().trim())
        val pv = JSONObject()
        for ((id, e) in cloudRefs) pv.putPath("$id.cloud_reference_id", e.text.toString().trim())
        for ((id, e) in localRefs) pv.putPath("$id.local_reference_id", e.text.toString().trim())
        o.put("personas_voice", pv)
        o.putPath("ui.speak_replies", speakReplies.isChecked)
        o.putPath("stt.mode", sttMode.value)
        o.putPath("stt.language", sttLang.value)
        o.putPath("stt.whisper.base_url", whisperUrl.text.toString().trim())
        o.putPath("stt.whisper.api_key", whisperKey.text.toString().trim())
        o.putPath("stt.whisper.model", whisperModel.text.toString().trim())
        o.putPath("vad.end_silence_ms", silenceMs)
        o.putPath("vad.barge_in", bargeIn.isChecked)
        return o
    }

    private fun save() {
        val wasVoice = rt.voice.active
        if (wasVoice) rt.voice.stop()
        rt.config.update(collect())
        rt.applyConfig()
        Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnections() {
        val cfg = com.voicecompanion.android.core.deepMerge(rt.config.data, collect())
        val dlg = AlertDialog.Builder(this).setTitle("연결 테스트").setMessage("확인 중…")
            .setPositiveButton("닫기", null).show()
        Thread {
            val l = runCatching { LlmClient(cfg).check() }.getOrElse { false to (it.message ?: "오류") }
            val t = runCatching { createTts(cfg).check() }.getOrElse { false to (it.message ?: "오류") }
            val s = if (cfg.str("stt.mode") == "whisper") WhisperApiTranscriber(cfg).check() else true to "Android 기본"
            fun line(n: String, r: Pair<Boolean, String>) = "$n: ${if (r.first) "✓" else "✕"} ${r.second}"
            val msg = listOf(line("언어 모델", l), line("목소리", t), line("음성 인식", s)).joinToString("\n\n")
            runOnUiThread { if (dlg.isShowing) dlg.setMessage(msg) }
        }.apply { isDaemon = true; start() }
    }
}

/** Shared top bar with a back arrow. */
fun Activity.topBar(title: String, onBack: () -> Unit): View {
    val pal = Palette.of(this)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(pal.sidebar)
        setPadding(dp(6), dp(8), dp(14), dp(8))
        addView(label("←", 21f, pal.text2).apply {
            gravity = Gravity.CENTER
            contentDescription = "뒤로"
            background = withRipple(rounded(Color.TRANSPARENT, dp(21).toFloat()), alpha(pal.text, 0.12f))
            setOnClickListener { onBack() }
        }, lp(dp(42), dp(42)))
        addView(label(title, 19f, pal.text).apply {
            typeface = Typeface.create(SERIF, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        }, lp(0, WRAP, 1f))
    }
}
