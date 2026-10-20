package com.voicecompanion.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.voicecompanion.android.core.Character
import com.voicecompanion.android.core.CharacterError
import com.voicecompanion.android.core.Characters
import org.json.JSONArray
import org.json.JSONObject

/** Character editor (desktop ``#charModal``): profile, scene, rules, examples, tags, voice. */
class CharacterEditorActivity : Activity() {
    private lateinit var rt: Runtime
    private lateinit var pal: Palette
    private var original: Character? = null

    private lateinit var nameKo: EditText
    private lateinit var name: EditText
    private lateinit var tagline: EditText
    private lateinit var emoji: EditText
    private lateinit var color: EditText
    private lateinit var avatarPreview: TextView
    private lateinit var charName: EditText
    private lateinit var userName: EditText
    private lateinit var personality: EditText
    private lateinit var speaking: EditText
    private lateinit var relationship: EditText
    private lateinit var bgStory: EditText
    private lateinit var scenario: EditText
    private lateinit var rulesBox: LinearLayout
    private lateinit var examplesBox: LinearLayout
    private lateinit var greeting: EditText
    private lateinit var tags: EditText
    private lateinit var cloudRef: EditText
    private lateinit var localRef: EditText
    private var speed = 1.0
    private var temperature = 0.9
    private lateinit var overrideToggle: CheckBox
    private lateinit var overrideText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = App.runtime(this)
        pal = Palette.of(this)
        applySystemBars(pal)
        original = intent.getStringExtra("id")?.let { runCatching { rt.characters.get(it) }.getOrNull() }
        setContentView(build())
    }

    private fun build(): View {
        val c = original
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        val title = when {
            c == null -> "캐릭터 만들기"
            c.builtin -> "${c.nameKo} 편집 (기본 캐릭터${if (c.overridden) " · 수정됨" else ""})"
            else -> "${c.nameKo} 편집"
        }
        col.addView(topBar(title) { finish() }, lp(MATCH, WRAP))
        val f = Form(this, pal)

        // -- basic ------------------------------------------------------------------
        f.section("기본")
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        avatarPreview = label(c?.avatarEmoji ?: "🙂", 28f, pal.text).apply { gravity = Gravity.CENTER }
        head.addView(avatarPreview, lp(dp(56), dp(56)))
        f.add(head)
        nameKo = f.field("이름 *", textInput(pal, "예: 하린")).apply { setText(c?.nameKo ?: "") }
        name = f.field("영문 이름", textInput(pal, "예: Harin")).apply { setText(c?.name ?: "") }
        tagline = f.field("한 줄 소개", textInput(pal, "예: 다정하고 장난기 많은 소꿉친구")).apply { setText(c?.tagline ?: "") }
        emoji = f.field("이모지", textInput(pal, "🙂")).apply {
            setText(c?.avatarEmoji ?: "🙂")
            addTextChangedListener(watcher { updatePreview() })
        }
        f.add(pickRow(listOf("🙂", "🌹", "🌀", "🙄", "😺", "🦊", "🌙", "☕", "🎧", "🗡️", "👑", "🤖").map { it to it }) { v ->
            emoji.setText(v)
        })
        color = f.field("색", textInput(pal, "#c96442")).apply {
            setText(c?.color ?: "#c96442")
            addTextChangedListener(watcher { updatePreview() })
        }
        f.add(pickRow(listOf("#e86a92", "#9b5cff", "#3fb8a8", "#c96442", "#4f8fe8", "#d6a13a", "#5aa05a", "#8a8f98")
            .map { it to "●" }, colored = true) { v -> color.setText(v) })

        // -- character --------------------------------------------------------------
        val p = c?.profile
        f.section("캐릭터")
        charName = f.field("캐릭터 자기 이름", textInput(pal, "비워두면 이름과 같아요")).apply { setText(p?.charName ?: "") }
        userName = f.field("나를 부르는 호칭", textInput(pal, "비워두면 '너'")).apply { setText(p?.userName ?: "") }
        personality = f.field("성격", textInput(pal, "예: 겉으로는 새침하지만 속은 여리고 다정하다. 질투가 많다.", multiLine = true, lines = 3))
            .apply { setText(p?.personality ?: "") }
        speaking = f.field("말투", textInput(pal, "예: 반말. 짧고 툭툭 던지듯 말하다가 당황하면 말을 더듬는다.", multiLine = true, lines = 2))
            .apply { setText(p?.speakingStyle ?: "") }
        relationship = f.field("사용자와의 관계", textInput(pal, "예: 10년 지기 소꿉친구. 최근 들어 서로를 의식하기 시작했다.", multiLine = true, lines = 2))
            .apply { setText(p?.relationship ?: "") }
        bgStory = f.field("배경 / 세계관", textInput(pal, "예: 서울의 평범한 대학가. 하린은 카페에서 아르바이트를 한다.", multiLine = true, lines = 3))
            .apply { setText(p?.background ?: "") }

        // -- scene ------------------------------------------------------------------
        f.section("장면", "지금 이 대화가 벌어지는 상황이에요. 캐릭터는 이 장면 안에서만 행동해요.")
        scenario = f.field("현재 상황", textInput(pal, "예: 비 오는 밤, 막차를 놓친 두 사람이 버스 정류장 지붕 아래에 나란히 서 있다.",
            multiLine = true, lines = 4)).apply { setText(p?.scenario ?: "") }

        // -- rules ------------------------------------------------------------------
        f.section("규칙", "캐릭터가 반드시 지킬 것 / 하지 말 것 (최대 ${Characters.MAX_RULES}개)")
        rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.add(rulesBox)
        (p?.rules ?: emptyList()).forEach { addRule(it) }
        f.add(pillButton("+ 규칙 추가", pal) { addRule("") }, lp(WRAP, WRAP).apply { topMargin = dp(8) })

        // -- examples ---------------------------------------------------------------
        f.section("예시 대화", "말투와 분위기를 보여주는 대사 쌍 (최대 ${Characters.MAX_EXAMPLES}개)")
        examplesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.add(examplesBox)
        (p?.exampleDialogues ?: emptyList()).forEach { addExample(it.user, it.char) }
        f.add(pillButton("+ 예시 추가", pal) { addExample("", "") }, lp(WRAP, WRAP).apply { topMargin = dp(8) })

        // -- greeting / tags --------------------------------------------------------
        f.section("첫 인사")
        greeting = f.field("새 대화를 시작할 때 캐릭터가 먼저 하는 말", textInput(pal, "예: [반가운 듯] 왔구나. 오늘은 좀 늦었네?",
            multiLine = true, lines = 2)).apply { setText(c?.greeting ?: "") }
        f.section("감정 태그", "Fish Audio drama-3 / s2 목소리가 연기하는 지시예요. 쉼표로 구분해요.")
        tags = f.field("어울리는 태그", textInput(pal, "예: 수줍게 웃으며, 장난스럽게, 작게 웃음", multiLine = true, lines = 2))
            .apply { setText((p?.emotionTags ?: emptyList()).joinToString(", ") { it.trim('[', ']') }) }

        // -- voice ------------------------------------------------------------------
        f.section("목소리")
        cloudRef = f.field("클라우드 보이스 ID", textInput(pal, "fish.audio reference_id")).apply { setText(c?.voice?.cloudReferenceId ?: "") }
        localRef = f.field("로컬 보이스 ID", textInput(pal, "reference_id")).apply { setText(c?.voice?.localReferenceId ?: "") }
        speed = c?.voice?.speed ?: 1.0
        val speedLabel = labelView("말하기 속도  ${"%.2f".format(speed)}×")
        f.add(speedLabel)
        f.add(SeekBar(this).apply {
            max = 30
            progress = ((speed - 0.5) / 0.05).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(seekListener { v ->
                speed = 0.5 + v * 0.05
                speedLabel.text = "말하기 속도  ${"%.2f".format(speed)}×"
            })
        })

        // -- advanced ---------------------------------------------------------------
        f.section("고급")
        temperature = c?.sampling?.get("temperature")?.toDouble() ?: 0.9
        val tempLabel = labelView("창의성 (temperature)  ${"%.2f".format(temperature)}")
        f.add(tempLabel)
        f.add(SeekBar(this).apply {
            max = 40
            progress = (temperature / 0.05).toInt().coerceIn(0, 40)
            setOnSeekBarChangeListener(seekListener { v ->
                temperature = v * 0.05
                tempLabel.text = "창의성 (temperature)  ${"%.2f".format(temperature)}"
            })
        })
        overrideToggle = CheckBox(this).apply {
            text = "시스템 프롬프트 직접 쓰기"
            textSize = 14.5f
            setTextColor(pal.text)
            isChecked = !c?.systemPromptOverride.isNullOrBlank()
            setPadding(dp(4), dp(10), 0, dp(6))
            setOnCheckedChangeListener { _, on -> overrideText.visibility = if (on) View.VISIBLE else View.GONE }
        }
        f.add(overrideToggle)
        overrideText = textInput(pal, "여기에 쓴 내용이 그대로 시스템 프롬프트로 쓰여요. 위의 캐릭터·장면 항목은 무시돼요.",
            multiLine = true, lines = 8).apply {
            setText(c?.systemPromptOverride ?: "")
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            visibility = if (overrideToggle.isChecked) View.VISIBLE else View.GONE
        }
        f.add(overrideText)

        col.addView(ScrollView(this).apply { addView(f.root) }, lp(MATCH, 0, 1f))

        // -- bottom bar -------------------------------------------------------------
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.sidebar)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val barRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (c != null) {
            if (!c.builtin) barRow.addView(pillButton("삭제", pal, danger = true) { confirmDelete() }, btnLp())
            else if (c.overridden) barRow.addView(pillButton("기본값으로", pal, danger = true) { confirmDelete() }, btnLp())
            barRow.addView(pillButton("복제", pal) { duplicate() }, btnLp())
        }
        barRow.addView(pillButton("프롬프트 미리보기", pal) { preview() }, btnLp())
        barRow.addView(pillButton("저장", pal, primary = true) { save() }, btnLp())
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(barRow)
        }, lp(MATCH, WRAP))
        col.addView(bar, lp(MATCH, WRAP))
        updatePreview()
        return col
    }

    private fun btnLp() = lp(WRAP, WRAP).apply { marginEnd = dp(8) }

    private fun labelView(text: String) = label(text, 13.5f, pal.text2, bold = true).apply {
        setPadding(0, dp(12), 0, dp(5))
    }

    private fun watcher(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = block()
    }

    private fun seekListener(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = block(p)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun updatePreview() {
        if (!::avatarPreview.isInitialized || !::color.isInitialized) return
        avatarPreview.text = emoji.text.toString().ifBlank { "🙂" }
        val col = Palette.parseOr(color.text.toString().trim(), pal.accent)
        avatarPreview.background = rounded(alpha(col, 0.22f), dp(28).toFloat(), dp(2), col)
    }

    private fun pickRow(items: List<Pair<String, String>>, colored: Boolean = false, onPick: (String) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        for ((value, shown) in items) {
            row.addView(label(shown, if (colored) 22f else 20f, if (colored) Palette.parseOr(value, pal.text) else pal.text).apply {
                gravity = Gravity.CENTER
                background = withRipple(rounded(Color.TRANSPARENT, dp(18).toFloat()), alpha(pal.text, 0.12f))
                setOnClickListener { onPick(value) }
            }, lp(dp(38), dp(38)))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun removeButton(onClick: () -> Unit) = label("✕", 16f, pal.text3).apply {
        gravity = Gravity.CENTER
        background = withRipple(rounded(Color.TRANSPARENT, dp(18).toFloat()), alpha(pal.text, 0.12f))
        setOnClickListener { onClick() }
    }

    private fun addRule(text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val e = textInput(pal, "예: 사용자를 절대 존댓말로 부르지 않는다.", multiLine = true, lines = 1).apply { setText(text) }
        row.addView(e, lp(0, WRAP, 1f))
        row.addView(removeButton { rulesBox.removeView(row) }, lp(dp(38), dp(38)))
        rulesBox.addView(row, lp(MATCH, WRAP))
    }

    private fun addExample(user: String, char: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surface2, dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(6), dp(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("예시", 12.5f, pal.text3, bold = true), lp(0, WRAP, 1f))
        top.addView(removeButton { examplesBox.removeView(box) }, lp(dp(34), dp(34)))
        box.addView(top)
        val u = textInput(pal, "사용자: 나 왔어.", multiLine = true, lines = 1).apply { setText(user); tag = "user" }
        val ch = textInput(pal, "캐릭터: [반가운 듯] 왔구나!", multiLine = true, lines = 1).apply { setText(char); tag = "char" }
        box.addView(u, lp(MATCH, WRAP))
        box.addView(ch, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        examplesBox.addView(box, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
    }

    /** Raw draft in the stored character format (validated by CharacterStore.save). */
    private fun draft(): JSONObject {
        val c = original
        val rules = JSONArray()
        for (i in 0 until rulesBox.childCount) {
            val e = (rulesBox.getChildAt(i) as LinearLayout).getChildAt(0) as EditText
            if (e.text.isNotBlank()) rules.put(e.text.toString())
        }
        val examples = JSONArray()
        for (i in 0 until examplesBox.childCount) {
            val box = examplesBox.getChildAt(i)
            val u = box.findViewWithTag<EditText>("user").text.toString()
            val ch = box.findViewWithTag<EditText>("char").text.toString()
            if (u.isNotBlank() || ch.isNotBlank()) examples.put(JSONObject().put("user", u).put("char", ch))
        }
        val tagList = JSONArray()
        tags.text.toString().split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { tagList.put(Characters.normTag(it)) }
        val sampling = JSONObject()
        c?.sampling?.forEach { (k, v) -> sampling.put(k, v) }
        sampling.put("temperature", Math.round(temperature * 100) / 100.0)
        val o = JSONObject()
        if (c != null) o.put("id", c.id)
        o.put("name_ko", nameKo.text.toString())
        o.put("name", name.text.toString())
        o.put("tagline", tagline.text.toString())
        o.put("avatar_emoji", emoji.text.toString())
        o.put("color", color.text.toString().trim())
        o.put("greeting", greeting.text.toString())
        o.put("profile", JSONObject()
            .put("char_name", charName.text.toString())
            .put("user_name", userName.text.toString())
            .put("personality", personality.text.toString())
            .put("speaking_style", speaking.text.toString())
            .put("relationship", relationship.text.toString())
            .put("background", bgStory.text.toString())
            .put("scenario", scenario.text.toString())
            .put("rules", rules)
            .put("example_dialogues", examples)
            .put("emotion_tags", tagList))
        o.put("system_prompt_override", if (overrideToggle.isChecked) overrideText.text.toString() else "")
        o.put("sampling", sampling)
        o.put("voice", JSONObject()
            .put("cloud_reference_id", cloudRef.text.toString())
            .put("local_reference_id", localRef.text.toString())
            .put("speed", Math.round(speed * 100) / 100.0))
        return o
    }

    private fun save() {
        val saved = try {
            rt.characters.save(draft())
        } catch (e: CharacterError) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        rt.personasChanged()
        val before = original
        val sceneChanged = before != null &&
            (before.profile.scenario != saved.profile.scenario || before.greeting != saved.greeting)
        if (before == null) {
            rt.setPersona(saved.id) // start talking to the new character
            finish()
        } else if (sceneChanged && rt.conversation.hasHistory(saved.id)) {
            AlertDialog.Builder(this)
                .setTitle("장면이 바뀌었어요")
                .setMessage("새 장면으로 대화를 새로 시작할까요?")
                .setPositiveButton("새로 시작") { _, _ ->
                    rt.conversation.clearHistory(saved.id)
                    finish()
                }
                .setNegativeButton("이어서 하기") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } else {
            finish()
        }
    }

    private fun preview() {
        val c = try {
            Characters.normalize(draft(), strict = false)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }
        val tv = label("[시스템 프롬프트]\n\n${c.systemPrompt}\n\n[매 메시지에 붙는 연기 지시]\n\n${c.reminder}", 13f, pal.text).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("프롬프트 미리보기")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun duplicate() {
        val c = original ?: return
        try {
            val copy = rt.characters.duplicate(c.id)
            rt.personasChanged()
            Toast.makeText(this, "'${copy.nameKo}'(으)로 복제했어요", Toast.LENGTH_SHORT).show()
            finish()
            startActivity(intent.putExtra("id", copy.id))
        } catch (e: Exception) {
            Toast.makeText(this, "복제 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val c = original ?: return
        val reset = c.builtin
        AlertDialog.Builder(this)
            .setTitle(if (reset) "기본값으로 되돌리기" else "캐릭터 삭제")
            .setMessage(if (reset) "${c.nameKo}의 수정 내용을 지우고 기본값으로 되돌릴까요?"
                else "${c.nameKo}와(과) 대화 기록을 삭제할까요? 되돌릴 수 없어요.")
            .setPositiveButton(if (reset) "되돌리기" else "삭제") { _, _ ->
                try {
                    if (!reset) rt.conversation.forgetPersona(c.id, rt.characters.list().first().id.let {
                        if (it == c.id) "romantic" else it
                    })
                    rt.characters.delete(c.id)
                    if (!reset) rt.setPersona(rt.conversation.personaId)
                    rt.personasChanged()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this, "실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
