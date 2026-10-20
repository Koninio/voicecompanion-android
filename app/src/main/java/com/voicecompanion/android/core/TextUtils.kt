package com.voicecompanion.android.core

import org.json.JSONObject

/**
 * Text helpers — port of desktop ``app/text_utils.py``: streaming sentence chunking
 * for TTS and TTS text cleanup.
 *
 * Korean-aware: sentence enders (``다.`` ``요.`` ``까?`` ``~`` ``…``) are split when
 * followed by whitespace (or at flush); decimals like ``3.5`` and ``안녕~반가워`` are
 * not split. Text inside ``*actions*`` / ``(stage directions)`` is never split, so
 * cleanForTts can remove those spans whole.
 */
object TextUtils {
    private const val ENDERS = ".!?…~\n。！？"
    private const val CLOSERS = "\"'”’)]}」』）"
    private const val COMMAS = ",，、"
    private const val OPEN_PARENS = "(（"
    private const val CLOSE_PARENS = ")）"

    // (?U): Unicode \w \s \d like Python 3's re
    private val WORD = Regex("(?U)\\w")
    private val WS_RUN = Regex("(?U)\\s+")

    // Fish Audio Drama 3 / S2 scoped tags (affect only the enclosed words)
    private val SCOPED_TAGS = listOf("emphasis", "whisper")
    private val SCOPED_TAG = Regex("<(/?)(?:" + SCOPED_TAGS.joinToString("|") + ")>", RegexOption.IGNORE_CASE)

    fun hasWord(s: String): Boolean = WORD.containsMatchIn(s)

    private fun isSpace(c: Char) = c.isWhitespace() || c == ' '

    /** Feed LLM deltas, get speakable chunks (never empty/whitespace-only). */
    class SentenceChunker(private val earlyCommaChars: Int = 25, private val maxChars: Int = 220) {
        private var buf = StringBuilder()
        private var carry = ""      // punctuation-only fragments, prepended to the next chunk
        private var emitted = 0

        fun feed(delta: String): List<String> {
            if (delta.isEmpty()) return emptyList()
            buf.append(delta)
            return drain(final = false)
        }

        fun flush(): List<String> {
            val out = drain(final = true).toMutableList()
            val rest = WS_RUN.replace(carry + buf, " ").trim()
            buf = StringBuilder()
            carry = ""
            if (rest.isNotEmpty() && hasWord(rest)) {
                out.add(rest)
                emitted++
            }
            return out
        }

        fun reset() {
            buf = StringBuilder()
            carry = ""
            emitted = 0
        }

        private fun drain(final: Boolean): List<String> {
            val out = ArrayList<String>()
            while (true) {
                val s = buf.toString()
                val idx = findSplit(s, final) ?: break
                val piece = s.substring(0, idx)
                buf = StringBuilder(s.substring(idx))
                val chunk = WS_RUN.replace(carry + piece, " ").trim()
                carry = ""
                if (chunk.isEmpty()) continue
                if (!hasWord(chunk)) {
                    carry = "$chunk "
                    continue
                }
                out.add(chunk)
                emitted++
            }
            return out
        }

        private fun findSplit(buf: String, final: Boolean): Int? {
            val n = buf.length
            if (n == 0) return null
            var paren = 0
            var bracket = 0
            var scoped = false   // inside <emphasis>…</emphasis> / <whisper>…</whisper>
            var starOpen = false
            var start = 0 // first non-space char
            while (start < n && isSpace(buf[start]) && buf[start] != '\n') start++
            var i = 0
            while (i < n) {
                val ch = buf[i]
                if (ch == '*') {
                    if (buf.startsWith("**", i)) { // bold marker: ignore
                        i += 2
                        continue
                    }
                    starOpen = !starOpen
                } else if (ch in OPEN_PARENS) {
                    paren++
                } else if (ch in CLOSE_PARENS && paren > 0) {
                    paren--
                } else if (ch == '[') {
                    bracket++
                } else if (ch == ']' && bracket > 0) {
                    bracket--
                } else if (ch == '<') {
                    val m = SCOPED_TAG.matchAt(buf, i)
                    if (m != null) {
                        scoped = m.groupValues[1].isEmpty()
                        i = m.range.last + 1
                        continue
                    }
                }
                val inside = starOpen || paren > 0 || bracket > 0 || scoped

                if (!inside && ch == '\n') return i + 1
                if (!inside && ch in ENDERS) {
                    var j = i + 1
                    while (j < n) {
                        j = if (buf[j] in ENDERS && buf[j] != '\n') j + 1
                        else if (buf[j] in CLOSERS) j + 1
                        else if (buf.startsWith("**", j)) j + 2
                        else break
                    }
                    val segment = buf.substring(start.coerceAtMost(i), i).trim()
                    val listMarker = ch == '.' && segment.isNotEmpty() && segment.all { it.isDigit() }
                    if (j >= n) {
                        if (final && !listMarker) return n
                        return null // need the next char to decide
                    }
                    if (isSpace(buf[j]) && !listMarker) return j
                    i = j
                    continue
                }
                if (!inside && ch in COMMAS && emitted == 0 &&
                    buf.substring(start.coerceAtMost(i + 1), i + 1).trim().length >= earlyCommaChars) {
                    if (i + 1 < n && isSpace(buf[i + 1])) return i + 1
                    if (i + 1 >= n && !final) return null
                }
                i++
            }
            if (n > maxChars) {
                val cut = buf.lastIndexOf(' ', maxChars - 1)
                return if (cut > 0) cut else maxChars
            }
            return if (final) n else null
        }
    }

    // -----------------------------------------------------------------------
    // clean_for_tts
    // -----------------------------------------------------------------------
    private val ML = setOf(RegexOption.MULTILINE)
    private val SPECIAL = Regex("<\\|?/?[A-Za-z_]+\\|?>")
    private val CODE_BLOCK = Regex("```.*?(?:```|$)", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val URL = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val HTML = Regex("</?[A-Za-z][^>]*>")
    private val HEADER = Regex("^[ \\t]{0,3}#{1,6}[ \\t]*", ML)
    private val BULLET = Regex("^[ \\t]*(?:[-*+•]|\\d+[.)])[ \\t]+", ML)
    private val QUOTE = Regex("^[ \\t]*>[ \\t]?", ML)
    private val BOLD = Regex("(\\*\\*|__)(.+?)\\1", RegexOption.DOT_MATCHES_ALL)
    private val STRIKE = Regex("~~(.+?)~~", RegexOption.DOT_MATCHES_ALL)
    private val ACTION = Regex("\\*[^*\\n]{1,160}\\*")
    private val PAREN = Regex("[(（][^()（）]{0,200}[)）]")
    private val BRACKET = Regex("\\[[^\\[\\]]{0,200}\\]")
    private val EMOTICON = Regex("(?U)\\^[_\\-oO.]?\\^+|(?<!\\w)[:;]-?[()DPp](?!\\w)")
    private val JAMO_RUN = Regex("[ㄱ-ㅎㅏ-ㅣ]{2,}")
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{E0020}-\\x{E007F}\\u2190-\\u21FF\\u2300-\\u23FF\\u25A0-\\u27BF" +
            "\\u2B00-\\u2BFF\\uFE00-\\uFE0F\\u200D\\u20E3\\u3030\\u303D\\u3297\\u3299\\u2122\\u2139]+")
    private val STRAY = Regex("[*#`|_]+")
    private val SPACE_BEFORE_PUNCT = Regex("(?U)\\s+([.,!?…~。！？])")
    private val WS = Regex("(?U)\\s+")

    // -----------------------------------------------------------------------
    // Emotion / delivery tags (Fish Audio Drama 3 and S2 family)
    //   [direction]                 point tag / direction, applies from its position on
    //   <emphasis>…</emphasis>      scoped: only the enclosed words
    //   <whisper>…</whisper>
    // -----------------------------------------------------------------------
    val EMOTION_TAG_MODELS = setOf("drama-3", "s2.1-pro", "s2.1-pro-free", "s2-pro")
    private const val MAX_TAGS_PER_CHUNK = 3
    private val TAG = Regex("\\[([^\\[\\]\\n]{1,60})\\]")
    private val SCOPED_SPAN = Regex("<(" + SCOPED_TAGS.joinToString("|") + ")>(.*?)</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCOPED_MARK = Regex("</?(?:" + SCOPED_TAGS.joinToString("|") + ")>", RegexOption.IGNORE_CASE)
    private val ACTION_TO_TAG = Regex("(?<!\\*)\\*([^*\\n]{1,40})\\*(?!\\*)")      // *웃으며* -> [웃으며]
    private val PAREN_TO_TAG = Regex("(?U)[(（]([^()（）\\n\\d]{1,30})[)）]")       // (한숨) -> [한숨]
    private val HOLD = Regex("(\\d+)")
    // <words> that are not HTML/scoped tags (models sometimes write <강조> for emphasis)
    private val ANGLE_WORDS = Regex("<(?![A-Za-z/!|])([^<>\\n]{1,40})>")
    private val STRAY_ANGLE = Regex("[<>]")

    /** True when the configured TTS understands [direction] tags. */
    fun supportsEmotionTags(cfg: JSONObject): Boolean {
        if (!cfg.bool("tts.emotion_tags", true) || cfg.str("tts.mode").lowercase() != "cloud") return false
        return cfg.str("tts.cloud.model").lowercase() in EMOTION_TAG_MODELS
    }

    /** Remove [direction] tags and scoped-tag markers, keeping the spoken words. */
    fun stripTags(text: String): String {
        var t = TAG.replace(text, " ")
        t = SCOPED_MARK.replace(t, "")
        return WS.replace(t, " ").trim()
    }

    /** True when ``text`` contains words to say (tags alone are not speakable). */
    fun speakable(text: String): Boolean = hasWord(stripTags(text))

    private fun cleanTagged(text: String): String {
        val keep = ArrayList<String>()
        fun hold(s: String): String {
            keep.add(s)
            return " ${keep.size - 1} "
        }

        var t = ACTION_TO_TAG.replace(text) { "[" + it.groupValues[1] + "]" }
        t = PAREN_TO_TAG.replace(t) { "[" + it.groupValues[1] + "]" }
        t = SCOPED_SPAN.replace(t) { m ->
            val inner = cleanCore(m.groupValues[2])
            if (!hasWord(inner)) " " else {
                val name = m.groupValues[1].lowercase()
                hold("<$name>$inner</$name>")
            }
        }
        t = SCOPED_MARK.replace(t, " ")          // unbalanced leftovers
        t = ANGLE_WORDS.replace(t) { m ->       // model wrote <단어> meaning emphasis
            val inner = cleanCore(m.groupValues[1])
            if (hasWord(inner)) hold("<emphasis>$inner</emphasis>") else " "
        }
        var count = 0
        t = TAG.replace(t) { m ->
            val inner = WS.replace(EMOJI.replace(m.groupValues[1], " "), " ").trim(' ', '"', '\'', '.', ',')
            if (inner.isEmpty() || count >= MAX_TAGS_PER_CHUNK) " " else {
                count++
                hold("[$inner]")
            }
        }
        t = cleanCore(t)
        t = HOLD.replace(t) { keep[it.groupValues[1].toInt()] }
        t = WS.replace(t, " ")
        return SPACE_BEFORE_PUNCT.replace(t) { it.groupValues[1] }.trim()
    }

    /**
     * Strip markdown, emoji, *actions*, (stage directions), URLs → plain speakable text.
     *
     * With [keepTags] (TTS model understands them), [direction] tags and
     * <emphasis>/<whisper> spans are kept (max 3 tags per chunk) and *actions* /
     * short (stage directions) are turned into [tags] instead of being dropped.
     * The result may then consist of tags only — check it with [speakable].
     * Returns "" when nothing remains.
     */
    fun cleanForTts(text: String, keepTags: Boolean = false): String {
        if (text.isEmpty()) return ""
        val t = if (keepTags) cleanTagged(text) else cleanCore(text)
        return if (hasWord(t)) t else ""
    }

    private fun cleanCore(text: String): String {
        var t = SPECIAL.replace(text, " ")
        t = CODE_BLOCK.replace(t, " ")
        t = IMAGE.replace(t, " ")
        t = LINK.replace(t) { it.groupValues[1] }
        t = URL.replace(t, " ")
        t = INLINE_CODE.replace(t) { it.groupValues[1] }
        t = HTML.replace(t, " ")
        t = ANGLE_WORDS.replace(t) { it.groupValues[1] }
        t = STRAY_ANGLE.replace(t, " ")
        t = HEADER.replace(t, "")
        t = BULLET.replace(t, "")
        t = QUOTE.replace(t, "")
        t = BOLD.replace(t) { it.groupValues[2] }
        t = STRIKE.replace(t) { it.groupValues[1] }
        t = ACTION.replace(t, " ")
        var prev: String? = null
        while (prev != t) { // nested parentheses
            prev = t
            t = PAREN.replace(t, " ")
        }
        t = BRACKET.replace(t, " ")
        t = EMOJI.replace(t, " ")
        t = EMOTICON.replace(t, " ")
        t = JAMO_RUN.replace(t, " ")
        t = STRAY.replace(t, " ")
        t = WS.replace(t, " ")
        return SPACE_BEFORE_PUNCT.replace(t) { it.groupValues[1] }.trim()
    }
}
