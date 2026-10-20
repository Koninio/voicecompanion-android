package com.voicecompanion.android.voice

import com.voicecompanion.android.core.Config
import com.voicecompanion.android.core.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Whisper hallucination filter for Korean — port of desktop ``app/voice/stt.py``. */
object Hallucination {
    private val PATTERNS = listOf(
        "시청\\s*해\\s*주\\s*셔서\\s*(정말\\s*)?(감사|고맙)\\s*합니다",
        "시청\\s*해\\s*주셔서\\s*(감사|고마)",
        "시청\\s*(감사|고맙)",
        "끝까지\\s*(시청|봐\\s*주셔서)",
        "구독\\s*(과|와|이랑|,)?\\s*좋아요",
        "좋아요\\s*(와|과|,)?\\s*구독",
        "구독\\s*(부탁|해\\s*주세요|하기)",
        "알림\\s*설정",
        "(MBC|KBS|SBS|JTBC|YTN|TV조선|채널A|MBN|연합)\\s*뉴스(\\s*[가-힣]{2,4}\\s*입니다)?",
        "[가-힣]{2,4}\\s*(기자|앵커)\\s*입니다",
        "자막\\s*(제공|제작|협찬|편집)",
        "(한글\\s*)?자막\\s*(by|BY|By)",
        "한글\\s*자막",
        "다음\\s*(영상|시간|편)에서\\s*(만나요|뵙겠습니다|만나겠습니다|봬요)",
        "thank(s| you)\\s*(so much\\s*)?for\\s*watching",
        "subtitles?\\s*(by|provided)",
        "amara\\.org",
        "ご視聴ありがとうございました",
    ).map { Regex("(?U)$it", RegexOption.IGNORE_CASE) }

    private val FILLER = Regex(
        "(?U)(부탁\\s*(드립니다|드려요|드릴게요|해요|합니다)|눌러\\s*주세요|해\\s*주세요|" +
            "(?<![가-힣])(및|그리고|와|과|감사합니다|고맙습니다)(?![가-힣]))")
    private val PUNCT = Regex("(?U)[\\s.,!?~\\-…·\"'`“”‘’()\\[\\]{}<>♪♫♬\\x{1F3B5}*_/\\\\|:;。、！？]+")
    private val MULTI_SPACE = Regex("(?U)\\s{2,}")

    /** "" for well-known Whisper hallucinations on (near-)silence, otherwise the text
     *  with those phrases removed. */
    fun filter(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        var cleaned = t
        for (p in PATTERNS) cleaned = p.replace(cleaned, " ")
        var meaningful = PUNCT.replace(cleaned, "")
        if (meaningful.isEmpty()) return ""
        if (cleaned != t) {
            // a hallucination phrase was present; drop leftover filler, keep substantial remainders
            cleaned = FILLER.replace(cleaned, " ")
            meaningful = PUNCT.replace(cleaned, "")
            if (meaningful.length < 4) return ""
            return MULTI_SPACE.replace(cleaned, " ").trim(' ', ',', '.')
        }
        // lone repeated character / filler like "음..." or "아" is noise
        if (meaningful.toSet().size == 1 && meaningful.length <= 2 && meaningful in setOf("음", "어", "아", "으", "흠")) {
            return ""
        }
        return t
    }
}

/** 16 kHz int16 mono PCM → text, through an OpenAI-compatible
 *  ``/v1/audio/transcriptions`` endpoint (Groq, OpenAI, faster-whisper-server, …). */
class WhisperApiTranscriber(cfg: JSONObject) {
    private val baseUrl = cfg.str("stt.whisper.base_url").trim()
    private val apiKey = cfg.str("stt.whisper.api_key").trim()
    private val model = cfg.str("stt.whisper.model", "whisper-large-v3-turbo").ifEmpty { "whisper-large-v3-turbo" }
    private val language = cfg.str("stt.language", "ko")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun check(): Pair<Boolean, String> = when {
        baseUrl.isEmpty() -> false to "Whisper 서버 주소가 설정되지 않았어요"
        else -> true to "Whisper API ($model)"
    }

    fun transcribe(pcm16k: ByteArray): String {
        if (pcm16k.size < 3200) return "" // < 100 ms
        val sb = ByteBuffer.wrap(pcm16k).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        val n = pcm16k.size / 2
        for (i in 0 until n) {
            val v = sb.getShort(i * 2) / 32768.0
            sum += v * v
        }
        if (sqrt(sum / n) < MIN_RMS) return ""
        if (baseUrl.isEmpty()) throw IOException("Whisper 서버 주소가 설정되지 않았어요 (설정 → 음성 인식)")

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav(pcm16k).toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (language.isNotEmpty()) addFormDataPart("language", language) }
            .build()
        val req = Request.Builder().url(Config.apiUrl(baseUrl, "audio/transcriptions")).post(body)
        if (apiKey.isNotEmpty()) req.header("Authorization", "Bearer $apiKey")
        client.newCall(req.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = runCatching {
                    val o = JSONObject(text)
                    o.optJSONObject("error")?.optString("message") ?: o.optString("detail")
                }.getOrNull()
                throw IOException("Whisper ${r.code}: ${msg?.ifEmpty { null } ?: text.take(200)}")
            }
            val out = runCatching { JSONObject(text).optString("text") }.getOrElse { text }
            return Hallucination.filter(out)
        }
    }

    companion object {
        const val MIN_RMS = 0.002 // utterances quieter than this are treated as silence

        fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
            val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
            b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
            return b.array()
        }
    }
}
