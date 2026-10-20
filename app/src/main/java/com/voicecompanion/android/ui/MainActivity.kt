package com.voicecompanion.android.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voicecompanion.android.App
import com.voicecompanion.android.Check
import com.voicecompanion.android.Runtime
import com.voicecompanion.android.UiEvent
import com.voicecompanion.android.core.Character
import com.voicecompanion.android.core.ConvEvent
import com.voicecompanion.android.core.HistoryEntry
import com.voicecompanion.android.core.str
import com.voicecompanion.android.voice.VoiceEvent

class MainActivity : Activity() {
    private lateinit var rt: Runtime
    private lateinit var pal: Palette

    private lateinit var headerEmoji: TextView
    private lateinit var headerName: TextView
    private lateinit var headerTagline: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var personaStrip: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var micBtn: TextView

    // voice panel
    private lateinit var voicePanel: FrameLayout
    private lateinit var orb: OrbView
    private lateinit var voiceTitle: TextView
    private lateinit var voiceState: TextView
    private lateinit var vtUser: TextView
    private lateinit var vtBot: TextView
    private lateinit var muteBtn: TextView

    private var persona: Character? = null
    private val streaming = HashMap<String, Pair<TextView, StringBuilder>>()
    private var replyActive = false
    private var voiceStateNow = "idle"
    private var paused = false
    private var needsReload = false

    private val listener: (UiEvent) -> Unit = { onUiEvent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = App.runtime(this)
        pal = Palette.of(this)
        applySystemBars(pal)
        setContentView(buildUi())
        rt.addListener(listener)
        loadPersona(rt.conversation.personaId)
        rt.refreshStatus()
    }

    override fun onDestroy() {
        rt.removeListener(listener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (needsReload) {
            needsReload = false
            loadPersona(rt.conversation.personaId)
        }
    }

    override fun onStop() {
        super.onStop()
        // the mic cannot keep running in the background without a foreground service
        if (!isChangingConfigurations && rt.voice.active) endVoice()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (voicePanel.visibility == View.VISIBLE) endVoice() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ layout
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.sidebar)
            setPadding(dp(14), dp(10), dp(8), dp(6))
        }
        headerEmoji = label("💬", 22f, pal.text).apply {
            gravity = Gravity.CENTER
            background = rounded(pal.surface2, dp(20).toFloat())
        }
        header.addView(headerEmoji, lp(dp(40), dp(40)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener { persona?.let { openEditor(it.id) } }
        }
        headerName = label("VoiceCompanion", 19f, pal.text).apply {
            typeface = Typeface.create(SERIF, Typeface.BOLD)
            maxLines = 1
        }
        headerTagline = label("", 12.5f, pal.text3).apply { maxLines = 1 }
        titles.addView(headerName)
        titles.addView(headerTagline)
        header.addView(titles, lp(0, WRAP, 1f))
        header.addView(iconButton("↺", "새 대화") { confirmNewChat() }, lp(dp(42), dp(42)))
        header.addView(iconButton("⚙", "설정") { openSettings() }, lp(dp(42), dp(42)))
        col.addView(header, lp(MATCH, WRAP))

        // status row
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.sidebar)
            setPadding(dp(16), 0, dp(16), dp(6))
            setOnClickListener { showStatusDialog() }
        }
        col.addView(statusRow, lp(MATCH, WRAP))

        // persona strip
        personaStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(pal.sidebar)
            addView(personaStrip)
        }
        col.addView(hs, lp(MATCH, WRAP))
        col.addView(View(this).apply { setBackgroundColor(pal.border) }, lp(MATCH, dp(1)))

        // messages
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messages)
        }
        col.addView(scroll, lp(MATCH, 0, 1f))

        // composer
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = rounded(pal.surface, dp(18).toFloat(), dp(1), pal.borderStrong)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        input = EditText(this).apply {
            hint = "메시지를 입력하세요…"
            textSize = 15.5f
            setTextColor(pal.text)
            setHintTextColor(pal.text3)
            background = null
            maxLines = 5
            setPadding(dp(10), dp(10), dp(6), dp(10))
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = updateSendBtn()
            })
        }
        composer.addView(input, lp(0, WRAP, 1f))
        micBtn = roundButton("🎙", pal.surface2, pal.text) { startVoice() }
        composer.addView(micBtn, lp(dp(42), dp(42)).apply { setMargins(dp(2), 0, dp(4), dp(1)) })
        sendBtn = roundButton("↑", pal.accent, Color.WHITE) { send() }
        composer.addView(sendBtn, lp(dp(42), dp(42)).apply { bottomMargin = dp(1) })
        val composerWrap = LinearLayout(this).apply {
            setPadding(dp(12), dp(6), dp(12), dp(10))
            addView(composer, lp(MATCH, WRAP))
        }
        col.addView(composerWrap, lp(MATCH, WRAP))
        updateSendBtn()

        voicePanel = buildVoicePanel()
        root.addView(voicePanel, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun iconButton(glyph: String, desc: String, onClick: () -> Unit) = label(glyph, 20f, pal.text2).apply {
        gravity = Gravity.CENTER
        contentDescription = desc
        background = withRipple(rounded(Color.TRANSPARENT, dp(21).toFloat()), alpha(pal.text, 0.12f))
        setOnClickListener { onClick() }
    }

    private fun roundButton(glyph: String, bg: Int, fg: Int, onClick: () -> Unit) = label(glyph, 18f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        background = withRipple(rounded(bg, dp(21).toFloat()), alpha(Color.BLACK, 0.15f))
        setOnClickListener { onClick() }
    }

    private fun updateSendBtn() {
        val enabled = input.text.isNotBlank() || replyActive
        sendBtn.text = if (replyActive && input.text.isBlank()) "■" else "↑"
        sendBtn.alpha = if (enabled) 1f else 0.4f
    }

    private fun buildVoicePanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            setBackgroundColor(pal.bg)
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        panel.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        voiceTitle = label("음성 대화", 17f, pal.text, bold = true)
        top.addView(voiceTitle, lp(0, WRAP, 1f))
        top.addView(iconButton("✕", "닫기") { endVoice() }, lp(dp(42), dp(42)))
        col.addView(top, lp(MATCH, WRAP))

        orb = OrbView(this)
        col.addView(orb, lp(MATCH, 0, 1.2f))
        voiceState = label("연결 중…", 16f, pal.text2).apply { gravity = Gravity.CENTER }
        col.addView(voiceState, lp(MATCH, WRAP))

        val transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(8))
        }
        vtUser = label("", 14.5f, pal.text3).apply { gravity = Gravity.CENTER; maxLines = 3 }
        vtBot = label("", 16f, pal.text).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }
        transcript.addView(vtUser, lp(MATCH, WRAP))
        transcript.addView(vtBot, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val ts = ScrollView(this).apply { addView(transcript) }
        col.addView(ts, lp(MATCH, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        muteBtn = roundButton("❚❚", pal.surface2, pal.text) { togglePause() }
        buttons.addView(captioned(muteBtn, "일시정지", pal.surface2))
        val endBtn = roundButton("✆", pal.err, Color.WHITE) { endVoice() }
        buttons.addView(captioned(endBtn, "통화 종료", pal.err))
        col.addView(buttons, lp(MATCH, WRAP))
        return panel
    }

    private fun captioned(btn: TextView, caption: String, bg: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18), 0, dp(18), 0)
        addView(btn, lp(dp(64), dp(64)))
        btn.textSize = 22f
        btn.background = withRipple(rounded(bg, dp(32).toFloat()), alpha(Color.BLACK, 0.15f))
        addView(label(caption, 12.5f, pal.text2).apply {
            gravity = Gravity.CENTER
            tag = "caption"
        }, lp(WRAP, WRAP).apply { topMargin = dp(6) })
    }

    // ------------------------------------------------------------------ personas
    private fun renderPersonas() {
        personaStrip.removeAllViews()
        val list = rt.characters.list()
        for (c in list) {
            val selected = c.id == persona?.id
            val color = Palette.parseOr(c.color, pal.accent)
            val chip = label("${c.avatarEmoji}  ${c.nameKo}", 14f, if (selected) pal.text else pal.text2, bold = selected).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = withRipple(
                    rounded(if (selected) alpha(color, 0.18f) else pal.surface, dp(18).toFloat(), dp(1),
                        if (selected) color else pal.border), alpha(color, 0.25f))
                setOnClickListener { if (c.id != persona?.id) switchPersona(c.id) }
                setOnLongClickListener { openEditor(c.id); true }
            }
            personaStrip.addView(chip, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        val add = label("＋ 캐릭터 만들기", 14f, pal.text2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = withRipple(rounded(Color.TRANSPARENT, dp(18).toFloat(), dp(1), pal.borderStrong),
                alpha(pal.text, 0.12f))
            setOnClickListener { openEditor(null) }
        }
        personaStrip.addView(add, lp(WRAP, WRAP))
    }

    private fun switchPersona(pid: String) {
        if (rt.voice.active) rt.voice.interrupt()
        rt.setPersona(pid)
        loadPersona(pid)
    }

    private fun loadPersona(pid0: String) {
        val p = runCatching { rt.characters.get(pid0) }.getOrNull()
            ?: rt.characters.list().first().also { rt.setPersona(it.id) }
        if (p.id != rt.conversation.personaId) rt.conversation.setPersona(p.id)
        persona = p
        headerEmoji.text = p.avatarEmoji
        headerEmoji.background = rounded(alpha(Palette.parseOr(p.color, pal.accent), 0.2f), dp(20).toFloat())
        headerName.text = p.nameKo
        headerTagline.text = p.tagline
        headerTagline.visibility = if (p.tagline.isEmpty()) View.GONE else View.VISIBLE
        orb.color = Palette.parseOr(p.color, pal.accent)
        voiceTitle.text = "${p.avatarEmoji}  ${p.nameKo}"
        renderPersonas()
        renderHistory()
    }

    // ------------------------------------------------------------------ messages
    private fun renderHistory() {
        messages.removeAllViews()
        streaming.clear()
        val p = persona ?: return
        if (rt.config.data.str("llm.base_url").isBlank()) messages.addView(setupCard())
        val hist = runCatching { rt.conversation.history(p.id) }.getOrDefault(emptyList())
        for (m in hist) addMessage(m)
        scrollToBottom(force = true)
    }

    private fun setupCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.surface, dp(14).toFloat(), dp(1), pal.border)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(label("시작하기 전에", 15.5f, pal.text, bold = true))
        addView(label("대화를 하려면 언어 모델 서버(OpenAI 호환)를 설정해야 해요. PC의 llama-server 주소" +
            "(예: http://192.168.0.10:8081)나 OpenRouter 같은 API 주소를 입력하세요. 목소리를 들으려면 " +
            "Fish Audio API 키도 넣어 주세요.", 13.5f, pal.text2).apply { setPadding(0, dp(6), 0, dp(10)) })
        addView(pillButton("설정 열기", pal, primary = true) { openSettings() }, lp(WRAP, WRAP))
        layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(14) }
    }

    private fun addMessage(m: HistoryEntry): TextView =
        if (m.role == "user") addUserBubble(m.content) else addAssistant(m.content, m.interrupted)

    private fun addUserBubble(text: String): TextView {
        val row = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(dp(48), dp(6), 0, dp(6))
        }
        val tv = label(text, 15.5f, pal.text).apply {
            background = rounded(pal.userBubble, dp(16).toFloat())
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextIsSelectable(false)
            setLineSpacing(0f, 1.12f)
            setOnLongClickListener { copy(text); true }
        }
        row.addView(tv, lp(WRAP, WRAP))
        messages.addView(row, lp(MATCH, WRAP))
        return tv
    }

    private fun addAssistant(text: String, interrupted: Boolean = false): TextView {
        val p = persona
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), dp(24), dp(8))
        }
        val avatar = label(p?.avatarEmoji ?: "💬", 15f, pal.text).apply {
            gravity = Gravity.CENTER
            background = rounded(alpha(Palette.parseOr(p?.color ?: "", pal.accent), 0.2f), dp(15).toFloat())
        }
        row.addView(avatar, lp(dp(30), dp(30)).apply { topMargin = dp(2) })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val tv = label(MessageFormat.render(text, pal, resources.displayMetrics.density), 15.5f, pal.text).apply {
            setLineSpacing(0f, 1.18f)
            setOnLongClickListener { copy(this.text.toString()); true }
        }
        col.addView(tv, lp(MATCH, WRAP))
        if (interrupted) col.addView(label("중단됨", 11.5f, pal.text3).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, lp(0, WRAP, 1f))
        messages.addView(row, lp(MATCH, WRAP))
        return tv
    }

    private fun addError(text: String) {
        val tv = label(text, 13f, pal.err).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(alpha(pal.err, 0.08f), dp(10).toFloat())
        }
        messages.addView(tv, lp(MATCH, WRAP).apply { setMargins(0, dp(6), 0, dp(6)) })
        scrollToBottom()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show()
    }

    private fun scrollToBottom(force: Boolean = false) {
        val child = scroll.getChildAt(0) ?: return
        val nearBottom = child.bottom - (scroll.height + scroll.scrollY) < dp(160)
        if (force || nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN); input.requestFocus() }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            if (replyActive) rt.conversation.interrupt()
            return
        }
        input.setText("")
        try {
            rt.conversation.submitUser(persona?.id, text, "text")
        } catch (e: Exception) {
            addError(e.message ?: "보내기 실패")
        }
    }

    // ------------------------------------------------------------------ events
    private fun onUiEvent(ev: UiEvent) {
        when (ev) {
            is UiEvent.Conv -> onConv(ev.ev)
            is UiEvent.Voice -> onVoice(ev.ev)
            is UiEvent.Status -> renderStatus()
            is UiEvent.PersonasChanged -> needsReload = true
        }
    }

    private fun onConv(ev: ConvEvent) {
        when (ev) {
            is ConvEvent.UserMessage -> {
                addUserBubble(ev.text)
                if (ev.source == "voice") vtUser.text = ev.text
                vtBot.text = ""
                scrollToBottom(force = true)
            }
            is ConvEvent.AssistantStart -> {
                replyActive = true
                updateSendBtn()
                updateVoiceLabel()
                if (ev.persona == persona?.id) streaming[ev.id] = addAssistant("") to StringBuilder()
                scrollToBottom()
            }
            is ConvEvent.AssistantDelta -> {
                val s = streaming[ev.id] ?: return
                s.second.append(ev.text)
                s.first.text = MessageFormat.render(s.second.toString(), pal, resources.displayMetrics.density)
                vtBot.text = s.first.text
                scrollToBottom()
            }
            is ConvEvent.AssistantEnd -> {
                replyActive = false
                updateSendBtn()
                updateVoiceLabel()
                val s = streaming.remove(ev.id) ?: return
                if (ev.text.isEmpty()) {
                    (s.first.parent?.parent as? View)?.let { messages.removeView(it) }
                } else if (ev.interrupted) {
                    (s.first.parent as? LinearLayout)?.addView(label("중단됨", 11.5f, pal.text3).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            }
            is ConvEvent.Error -> addError(ev.message)
        }
    }

    private fun onVoice(ev: VoiceEvent) {
        when (ev) {
            is VoiceEvent.State -> {
                voiceStateNow = ev.state
                orb.state = ev.state
                updateVoiceLabel()
            }
            is VoiceEvent.Level -> orb.setLevel(ev.rms)
            is VoiceEvent.BargeIn -> {}
            is VoiceEvent.UserFinal -> {}
            is VoiceEvent.Error -> {
                if (voicePanel.visibility == View.VISIBLE) voiceState.text = ev.message
                addError(if (ev.where.isNotEmpty()) "[${ev.where}] ${ev.message}" else ev.message)
            }
        }
    }

    private fun updateVoiceLabel() {
        if (voicePanel.visibility != View.VISIBLE) return
        voiceState.text = when {
            paused && voiceStateNow != "speaking" -> "일시정지됨"
            voiceStateNow == "speaking" -> "말하는 중"
            voiceStateNow == "user_speaking" -> "듣는 중…"
            voiceStateNow == "transcribing" -> "알아듣는 중…"
            replyActive -> "생각 중…"
            voiceStateNow == "listening" -> "듣고 있어요. 말해 보세요"
            else -> "대기 중"
        }
    }

    private fun renderStatus() {
        val s = rt.status
        statusRow.removeAllViews()
        fun item(name: String, c: Check, off: Boolean = false) {
            val color = when {
                off -> pal.off
                c.ok == null -> pal.warn
                c.ok -> pal.ok
                else -> pal.err
            }
            statusRow.addView(label("●", 10f, color).apply { setPadding(0, 0, dp(4), 0) })
            statusRow.addView(label(name, 12f, pal.text3).apply { setPadding(0, 0, dp(14), 0) })
        }
        item("언어 모델", s.llm)
        item("음성 인식", s.stt)
        item("목소리", s.tts, off = s.ttsMode == "off")
    }

    private fun showStatusDialog() {
        val s = rt.status
        fun line(name: String, c: Check) = "$name: " + when (c.ok) {
            null -> "확인 중…"
            true -> "✓ ${c.message}"
            false -> "✕ ${c.message}"
        }
        AlertDialog.Builder(this)
            .setTitle("연결 상태")
            .setMessage(listOf(line("언어 모델", s.llm), line("음성 인식", s.stt), line("목소리", s.tts)).joinToString("\n\n"))
            .setPositiveButton("설정") { _, _ -> openSettings() }
            .setNeutralButton("다시 확인") { _, _ -> rt.refreshStatus() }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------------ actions
    private fun confirmNewChat() {
        val p = persona ?: return
        AlertDialog.Builder(this)
            .setTitle("새 대화")
            .setMessage("${p.nameKo}와(과)의 대화 기록을 지우고 새로 시작할까요?")
            .setPositiveButton("새로 시작") { _, _ ->
                rt.conversation.clearHistory(p.id)
                renderHistory()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openSettings() {
        needsReload = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openEditor(id: String?) {
        needsReload = true
        startActivity(Intent(this, CharacterEditorActivity::class.java).apply { if (id != null) putExtra("id", id) })
    }

    // ------------------------------------------------------------------ voice
    private fun startVoice() {
        if (rt.config.data.str("llm.base_url").isBlank()) {
            Toast.makeText(this, "먼저 설정에서 언어 모델 서버를 입력해 주세요", Toast.LENGTH_LONG).show()
            openSettings()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        paused = false
        vtUser.text = ""
        vtBot.text = ""
        muteBtn.text = "❚❚"
        setCaption(muteBtn, "일시정지")
        voicePanel.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orb.invalidate()
        rt.voice.start()
        updateVoiceLabel()
    }

    private fun endVoice() {
        rt.voice.stop()
        rt.conversation.interrupt()
        voicePanel.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            rt.voice.stop()
            muteBtn.text = "▶"
            setCaption(muteBtn, "다시 듣기")
        } else {
            rt.voice.start()
            muteBtn.text = "❚❚"
            setCaption(muteBtn, "일시정지")
        }
        updateVoiceLabel()
    }

    private fun setCaption(btn: View, text: String) {
        ((btn.parent as? LinearLayout)?.findViewWithTag<TextView>("caption"))?.text = text
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
            else Toast.makeText(this, "음성 대화를 하려면 마이크 권한이 필요해요", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_MIC = 1
    }
}
