package com.voicecompanion.android.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Colors of the desktop web UI (``web/styles.css``), light and dark. */
class Palette(val dark: Boolean) {
    val bg = c(if (dark) "#262624" else "#faf9f5")
    val sidebar = c(if (dark) "#1f1e1d" else "#f3f1ea")
    val surface = c(if (dark) "#30302e" else "#ffffff")
    val surface2 = c(if (dark) "#3a3936" else "#f0eee6")
    val border = c(if (dark) "#3d3c38" else "#e5e2d8")
    val borderStrong = c(if (dark) "#4c4a45" else "#d6d2c4")
    val text = c(if (dark) "#ecebe4" else "#2f2c25")
    val text2 = c(if (dark) "#bdb9ad" else "#5f5b50")
    val text3 = c(if (dark) "#8c887d" else "#8f8a7c")
    val accent = c(if (dark) "#d97757" else "#c96442")
    val userBubble = c(if (dark) "#141413" else "#f0ede4")
    val ok = c("#4f9d69")
    val warn = c("#d6a13a")
    val err = c("#d0533f")
    val off = c("#a8a396")

    companion object {
        fun c(hex: String): Int = Color.parseColor(hex)

        fun of(ctx: Context) = Palette(
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)

        fun parseOr(hex: String, fallback: Int): Int = try {
            Color.parseColor(if (hex.length == 4 && hex.startsWith("#"))
                "#" + hex.drop(1).map { "$it$it" }.joinToString("") else hex)
        } catch (e: Exception) {
            fallback
        }
    }
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun withRipple(bg: GradientDrawable, rippleColor: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null)

fun alpha(color: Int, a: Float): Int =
    Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

val SERIF: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.label(text: CharSequence, size: Float = 15f, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Pill / rounded button in the desktop style. */
fun Context.pillButton(text: String, pal: Palette, primary: Boolean = false, danger: Boolean = false,
                       onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(16), dp(9), dp(16), dp(9))
    val fg = when {
        primary -> Color.WHITE
        danger -> pal.err
        else -> pal.text
    }
    setTextColor(fg)
    val bg = if (primary) rounded(pal.accent, dp(10).toFloat())
    else rounded(Color.TRANSPARENT, dp(10).toFloat(), dp(1), if (danger) alpha(pal.err, 0.5f) else pal.borderStrong)
    background = withRipple(bg, alpha(pal.text, 0.12f))
    setOnClickListener { onClick() }
}

fun Context.textInput(pal: Palette, hint: String = "", multiLine: Boolean = false, lines: Int = 1,
                      password: Boolean = false, number: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    textSize = 15f
    setTextColor(pal.text)
    setHintTextColor(pal.text3)
    background = rounded(pal.surface, dp(10).toFloat(), dp(1), pal.border)
    setPadding(dp(12), dp(10), dp(12), dp(10))
    inputType = when {
        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    if (multiLine) {
        minLines = lines
        gravity = Gravity.TOP or Gravity.START
        isSingleLine = false
    } else {
        isSingleLine = true
    }
}

/** Vertical form helpers shared by the settings and character screens. */
class Form(private val ctx: Context, val pal: Palette) {
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), ctx.dp(32))
    }

    fun section(title: String, help: String? = null) {
        root.addView(ctx.label(title, 17f, pal.text, bold = true).apply {
            typeface = Typeface.create(SERIF, Typeface.BOLD)
            setPadding(0, ctx.dp(22), 0, ctx.dp(4))
        })
        if (help != null) help(help)
        root.addView(View(ctx).apply { setBackgroundColor(pal.border) }, lp(MATCH, ctx.dp(1)).apply {
            bottomMargin = ctx.dp(6)
        })
    }

    fun help(text: String) {
        root.addView(ctx.label(text, 12.5f, pal.text3).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(4)) })
    }

    fun <T : View> field(label: String, view: T, help: String? = null): T {
        root.addView(ctx.label(label, 13.5f, pal.text2, bold = true).apply {
            setPadding(0, ctx.dp(12), 0, ctx.dp(5))
        })
        root.addView(view, lp(MATCH, WRAP))
        if (help != null) help(help)
        return view
    }

    fun add(view: View, params: LinearLayout.LayoutParams = lp(MATCH, WRAP)) = root.addView(view, params)
}

fun Activity.applySystemBars(pal: Palette) {
    window.statusBarColor = pal.sidebar
    window.navigationBarColor = pal.bg
}
