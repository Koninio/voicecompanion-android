package com.voicecompanion.android.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Light markdown for chat bubbles — port of ``renderMarkdown`` in ``web/app.js``:
 * `code`, **bold**, *italic*, and the Drama 3 / S2 delivery tags rendered as in the
 * desktop UI ([direction] → small italic pill, <emphasis> → bold, <whisper> → faint italic).
 */
object MessageFormat {
    private sealed class Rule(val re: Regex) {
        class Code : Rule(Regex("`([^`\\n]+)`"))
        class Bold : Rule(Regex("\\*\\*([^*\\n]+?)\\*\\*|__([^_\\n]+?)__"))
        class Italic : Rule(Regex("(?<![*\\w])\\*([^*\\n]+?)\\*(?!\\*)"))
        class Tag : Rule(Regex("\\[([^\\[\\]\\n]{1,60})\\]"))
        class Emph : Rule(Regex("<emphasis>([\\s\\S]*?)</emphasis>", RegexOption.IGNORE_CASE))
        class Whisper : Rule(Regex("<whisper>([\\s\\S]*?)</whisper>", RegexOption.IGNORE_CASE))
        class AngleWord : Rule(Regex("<(?![A-Za-z/!])([^<>\\n]{1,40}?)>"))
        class StrayClose : Rule(Regex("</[^<>\\n]{0,20}>"))
    }

    private val RULES = listOf(Rule.Code(), Rule.Emph(), Rule.Whisper(), Rule.StrayClose(), Rule.Bold(),
        Rule.Italic(), Rule.Tag(), Rule.AngleWord())

    /** Pill-shaped tag (``.etag`` in styles.css). */
    private class TagSpan(private val fg: Int, private val bg: Int, private val pad: Float, private val scale: Float) :
        ReplacementSpan() {
        private fun paintFor(p: Paint): Paint = Paint(p).apply {
            textSize = p.textSize * scale
            typeface = Typeface.create(p.typeface, Typeface.ITALIC)
            color = fg
        }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val p = paintFor(paint)
            return (p.measureText(text, start, end) + pad * 2 + pad).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int,
                          bottom: Int, paint: Paint) {
            val p = paintFor(paint)
            val w = p.measureText(text, start, end)
            val fm = p.fontMetrics
            val h = fm.descent - fm.ascent
            val cy = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
            val rect = RectF(x + pad / 2, cy - h / 2 - 1, x + pad / 2 + w + pad * 2, cy + h / 2 + 1)
            val bgp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bgp)
            canvas.drawText(text, start, end, x + pad / 2 + pad, cy - (fm.ascent + fm.descent) / 2, p)
        }
    }

    fun render(src: String, pal: Palette, density: Float): CharSequence {
        val out = SpannableStringBuilder()
        append(out, src, pal, density)
        return out
    }

    private fun append(out: SpannableStringBuilder, s: String, pal: Palette, density: Float) {
        var pos = 0
        while (pos < s.length) {
            // earliest match among all rules (first rule wins on ties)
            var best: Pair<Rule, MatchResult>? = null
            for (r in RULES) {
                val m = r.re.find(s, pos) ?: continue
                if (best == null || m.range.first < best.second.range.first) best = r to m
            }
            if (best == null) {
                out.append(s.substring(pos))
                return
            }
            val (rule, m) = best
            out.append(s.substring(pos, m.range.first))
            val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            val start = out.length
            when (rule) {
                is Rule.Code -> {
                    out.append(inner)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Rule.Bold, is Rule.Emph, is Rule.AngleWord -> {
                    append(out, inner, pal, density)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Rule.Italic -> {
                    append(out, inner, pal, density)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Rule.Whisper -> {
                    append(out, inner, pal, density)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(alpha(pal.text, 0.6f)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Rule.Tag -> {
                    out.append(inner)
                    out.setSpan(TagSpan(pal.text2, alpha(pal.text2, 0.13f), 5f * density, 0.8f),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Rule.StrayClose -> {}
            }
            pos = m.range.last + 1
            if (m.range.isEmpty()) pos++ // safety: never loop on an empty match
        }
    }
}
