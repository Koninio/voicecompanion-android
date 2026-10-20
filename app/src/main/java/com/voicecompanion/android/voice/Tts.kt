package com.voicecompanion.android.voice

import com.voicecompanion.android.core.Cancel
import com.voicecompanion.android.core.Character
import com.voicecompanion.android.core.int
import com.voicecompanion.android.core.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Text-to-speech engines — port of desktop ``app/voice/tts.py``.
 *
 * Every engine delivers int16 mono little-endian PCM at ``sampleRate``. For
 * [FishLocalTts] the real rate is read from the streamed WAV header, so read
 * ``sampleRate`` after the first chunk has been delivered.
 *
 * Fish Audio cloud: ``POST {base}/v1/tts`` (format=pcm, chunked) or the live
 * WebSocket ``wss://api.fish.audio/v1/tts/live`` (msgpack start/text/flush/stop →
 * audio…/finish). fish-speech local server: ``POST {url}/v1/tts`` with a msgpack
 * body, streaming WAV.
 */
class TtsError(message: String, val status: Int? = null) : RuntimeException(message)

/** Blocking source of text chunks; null = end of the reply. */
fun interface ChunkSource {
    fun next(): String?
}

interface TtsEngine {
    val sampleRate: Int
    /** One live session per reply (text streamed in while it is generated). */
    val textStreaming: Boolean get() = false

    fun synthesizeStream(text: String, persona: Character, cancel: Cancel, onPcm: (ByteArray) -> Unit)

    fun synthesizeTextStream(chunks: ChunkSource, persona: Character, cancel: Cancel, onPcm: (ByteArray) -> Unit) {
        while (!cancel.isSet) {
            val t = chunks.next() ?: return
            if (t.isNotBlank()) synthesizeStream(t, persona, cancel, onPcm)
        }
    }

    fun check(): Pair<Boolean, String>
}

/** Re-chunks a byte stream so every delivered piece holds whole samples. */
private class Aligner(private val align: Int = 2) {
    private var carry = ByteArray(0)
    fun feed(chunk: ByteArray, n: Int = chunk.size): ByteArray? {
        if (n == 0) return null
        val data = ByteArray(carry.size + n)
        System.arraycopy(carry, 0, data, 0, carry.size)
        System.arraycopy(chunk, 0, data, carry.size, n)
        val cut = data.size - data.size % align
        carry = data.copyOfRange(cut, data.size)
        return if (cut > 0) data.copyOfRange(0, cut) else null
    }
}

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

private fun errorText(resp: Response): String = try {
    val body = resp.body?.string() ?: ""
    try {
        val o = JSONObject(body)
        o.optString("message").ifEmpty { o.optString("detail").ifEmpty { body.take(500) } }
    } catch (e: Exception) {
        body.take(500)
    }
} catch (e: Exception) {
    ""
}

private fun hint(code: Int): String = when (code) {
    401 -> "API 키가 올바르지 않습니다"
    402 -> "Fish Audio 크레딧이 부족합니다"
    404 -> "보이스(reference_id)를 찾을 수 없습니다"
    422 -> "요청 형식 오류"
    else -> "TTS 요청 실패"
}

// ---------------------------------------------------------------------------
class FishCloudTts(cfg: JSONObject) : TtsEngine {
    private val apiKey = cfg.str("tts.cloud.api_key").trim()
    private val model = cfg.str("tts.cloud.model", "drama-3").ifEmpty { "drama-3" }
    // "websocket": one /v1/tts/live session per reply (directions carry across
    // sentences, lowest latency); "http": one POST /v1/tts per sentence.
    private val transport = cfg.str("tts.cloud.transport", "websocket").lowercase()
    private val latency = cfg.str("tts.cloud.latency", "low").ifEmpty { "low" }
    private val baseUrl = cfg.str("tts.cloud.base_url", "https://api.fish.audio").ifEmpty { "https://api.fish.audio" }.trimEnd('/')
    private val language = cfg.str("tts.cloud.language")
    override val sampleRate = cfg.int("tts.sample_rate", 44100)
    override val textStreaming: Boolean get() = transport == "websocket"

    private fun body(text: String, persona: Character): LinkedHashMap<String, Any?> {
        val v = persona.voice
        val body = linkedMapOf<String, Any?>(
            "text" to text,
            "format" to "pcm",
            "sample_rate" to sampleRate,
            "latency" to latency,
            "normalize" to true,
            "prosody" to linkedMapOf("speed" to v.speed, "volume" to 0),
        )
        val ref = v.cloudReferenceId.trim()
        if (ref.isNotEmpty()) body["reference_id"] = ref
        if (language.isNotEmpty()) body["language"] = language
        return body
    }

    private fun jsonBody(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, if (v is Map<*, *>) JSONObject(v) else v)
        return o
    }

    override fun synthesizeStream(text: String, persona: Character, cancel: Cancel, onPcm: (ByteArray) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (apiKey.isEmpty()) throw TtsError("Fish Audio API 키가 설정되지 않았습니다", 401)
        val req = Request.Builder().url("$baseUrl/v1/tts")
            .header("Authorization", "Bearer $apiKey")
            .header("model", model)
            .post(jsonBody(body(t, persona)).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = httpClient.newCall(req)
        cancel.onCancel { call.cancel() }
        try {
            call.execute().use { resp ->
                if (resp.code != 200) {
                    throw TtsError("Fish Audio ${resp.code}: ${hint(resp.code)} - ${errorText(resp)}", resp.code)
                }
                val input = resp.body?.byteStream() ?: return
                val buf = ByteArray(8192)
                val al = Aligner()
                while (!cancel.isSet) {
                    val n = input.read(buf)
                    if (n < 0) break
                    al.feed(buf, n)?.let(onPcm)
                }
            }
        } catch (e: IOException) {
            if (cancel.isSet) return
            throw TtsError("Fish Audio 연결 실패: ${e.message}")
        }
    }

    /** Whole reply in one live session while its text is still arriving. */
    override fun synthesizeTextStream(chunks: ChunkSource, persona: Character, cancel: Cancel,
                                      onPcm: (ByteArray) -> Unit) {
        if (!textStreaming) return super.synthesizeTextStream(chunks, persona, cancel, onPcm)
        if (apiKey.isEmpty()) throw TtsError("Fish Audio API 키가 설정되지 않았습니다", 401)
        val url = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/tts/live"

        val events = LinkedBlockingQueue<Any>() // ByteArray frame | Throwable | CLOSED
        val closed = Any()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("model", model)
            .build()
        val ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.put(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                events.put(closed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.put(closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                events.put(
                    if (code != null && code != 101) TtsError("Fish Audio $code: ${hint(code)}", code)
                    else TtsError("Fish Audio 연결 실패: ${t.message}")
                )
            }
        })
        cancel.onCancel { ws.cancel() }

        val request = body("", persona)
        val failed = arrayOfNulls<Throwable>(1)

        val sender = Thread({
            var sending = ws.send(MsgPack.pack(mapOf("event" to "start", "request" to request)).toByteString())
            var first = true
            while (true) {
                val text = chunks.next() ?: break // always drain
                if (!sending || cancel.isSet) continue
                val t = text.trim()
                if (t.isEmpty()) continue
                sending = ws.send(MsgPack.pack(mapOf("event" to "text", "text" to "$t ")).toByteString())
                if (sending && first) { // speak the first sentence now instead of buffering
                    sending = ws.send(MsgPack.pack(mapOf("event" to "flush")).toByteString())
                    first = false
                }
                if (!sending) failed[0] = TtsError("Fish Audio 전송 실패")
            }
            if (sending && !cancel.isSet) ws.send(MsgPack.pack(mapOf("event" to "stop")).toByteString())
        }, "fish-ws-send")
        sender.isDaemon = true
        sender.start()

        val al = Aligner()
        try {
            while (true) {
                if (cancel.isSet) return
                val ev = events.poll(100, TimeUnit.MILLISECONDS)
                if (ev == null) {
                    failed[0]?.let { throw it }
                    continue
                }
                if (ev === closed) {
                    failed[0]?.let { throw it }
                    throw TtsError("Fish Audio 연결이 끊겼습니다")
                }
                if (ev is Throwable) throw ev
                val data = runCatching { MsgPack.unpack(ev as ByteArray) }.getOrNull() as? Map<*, *> ?: continue
                when (data["event"]) {
                    "audio" -> {
                        val audio = data["audio"] as? ByteArray ?: continue
                        al.feed(audio)?.let(onPcm)
                    }
                    "finish" -> {
                        if (data["reason"] == "error") {
                            throw TtsError("Fish Audio 합성 실패: ${data["message"] ?: data}")
                        }
                        return
                    }
                }
            }
        } finally {
            ws.close(1000, null)
        }
    }

    override fun check(): Pair<Boolean, String> {
        if (apiKey.isEmpty()) return false to "Fish Audio API 키가 설정되지 않았습니다"
        return try {
            val req = Request.Builder().url("$baseUrl/model?page_size=1&self=true")
                .header("Authorization", "Bearer $apiKey").get().build()
            httpClient.newBuilder().readTimeout(5, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                when (r.code) {
                    200 -> true to "Fish Audio 연결됨 ($model)"
                    401 -> false to "Fish Audio API 키가 올바르지 않습니다 (401)"
                    402 -> false to "Fish Audio 크레딧이 부족합니다 (402)"
                    else -> false to "Fish Audio 응답 오류 ${r.code}: ${errorText(r)}"
                }
            }
        } catch (e: IOException) {
            false to "Fish Audio 연결 실패: ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
/** Parsed (streaming) WAV header: rate, channels, bits, offset of the data chunk. */
data class WavHeader(val rate: Int, val channels: Int, val bits: Int, val dataOffset: Int)

/** null if more bytes are needed; throws [TtsError] if the data is not WAV. */
fun parseWavHeader(data: ByteArray): WavHeader? {
    if (data.size < 12) return null
    if (String(data, 0, 4, Charsets.US_ASCII) != "RIFF" || String(data, 8, 4, Charsets.US_ASCII) != "WAVE") {
        throw TtsError("fish-speech 응답이 WAV 형식이 아닙니다")
    }
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12
    var fmt: Triple<Int, Int, Int>? = null
    while (true) {
        if (data.size < pos + 8) return null
        val cid = String(data, pos, 4, Charsets.US_ASCII)
        val size = bb.getInt(pos + 4).toLong() and 0xffffffffL
        val body = pos + 8
        if (cid == "data") {
            val f = fmt ?: throw TtsError("WAV header has no fmt chunk")
            return WavHeader(f.first, f.second, f.third, body)
        }
        if (data.size < body + size) return null
        if (cid == "fmt ") {
            val audioFmt = bb.getShort(body).toInt() and 0xffff
            val channels = bb.getShort(body + 2).toInt() and 0xffff
            val rate = bb.getInt(body + 4)
            val bits = bb.getShort(body + 14).toInt() and 0xffff
            if (audioFmt != 1 && audioFmt != 0xFFFE) throw TtsError("unsupported WAV encoding $audioFmt")
            fmt = Triple(rate, channels, bits)
        }
        pos = (body + size + (size and 1)).toInt()
    }
}

private fun downmix(pcm: ByteArray, channels: Int): ByteArray {
    if (channels == 1) return pcm
    val inp = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val frames = pcm.size / (2 * channels)
    val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) {
        var sum = 0
        repeat(channels) { sum += inp.short.toInt() }
        out.putShort((sum / channels).toShort())
    }
    return out.array()
}

/** Self-hosted fish-speech ``tools/api_server.py`` (msgpack request, streamed WAV). */
class FishLocalTts(cfg: JSONObject) : TtsEngine {
    private val url = cfg.str("tts.local.url").trim().trimEnd('/')
    private val latency = cfg.str("tts.local.latency", "normal").let { if (it == "normal" || it == "balanced") it else "normal" }
    private val chunkLength = cfg.int("tts.local.chunk_length", 200)
    @Volatile
    override var sampleRate = cfg.int("tts.sample_rate", 44100) // replaced by the WAV header
        private set

    private fun body(text: String, persona: Character): Map<String, Any?> = linkedMapOf(
        "text" to text,
        "chunk_length" to chunkLength,
        "format" to "wav",
        "references" to emptyList<Any>(),
        "reference_id" to persona.voice.localReferenceId.trim().ifEmpty { null },
        "seed" to null,
        "use_memory_cache" to "on",
        "normalize" to true,
        "streaming" to true,
        "latency" to latency,
    )

    override fun synthesizeStream(text: String, persona: Character, cancel: Cancel, onPcm: (ByteArray) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (url.isEmpty()) throw TtsError("fish-speech 서버 주소가 설정되지 않았습니다")
        val req = Request.Builder().url("$url/v1/tts")
            .post(MsgPack.pack(body(t, persona)).toRequestBody("application/msgpack".toMediaType()))
            .build()
        val call = httpClient.newCall(req)
        cancel.onCancel { call.cancel() }
        try {
            call.execute().use { resp ->
                if (resp.code != 200) throw TtsError("fish-speech ${resp.code}: ${errorText(resp)}", resp.code)
                val input = resp.body?.byteStream() ?: return
                val buf = ByteArray(8192)
                var head = ByteArray(0)
                var channels = 1
                var headerDone = false
                var al = Aligner(2)
                while (!cancel.isSet) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!headerDone) {
                        head += buf.copyOfRange(0, n)
                        if (head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) != "RIFF") {
                            headerDone = true // raw PCM (no header): play at configured rate
                        } else {
                            val h = parseWavHeader(head) ?: continue
                            if (h.bits != 16) throw TtsError("fish-speech WAV는 16-bit 여야 합니다 (got ${h.bits})")
                            sampleRate = h.rate
                            channels = h.channels
                            al = Aligner(2 * channels)
                            head = head.copyOfRange(h.dataOffset, head.size)
                            headerDone = true
                        }
                        if (head.isNotEmpty()) al.feed(head)?.let { onPcm(downmix(it, channels)) }
                        continue
                    }
                    al.feed(buf, n)?.let { onPcm(downmix(it, channels)) }
                }
            }
        } catch (e: IOException) {
            if (cancel.isSet) return
            throw TtsError("fish-speech 서버 연결 실패 ($url): ${e.message}")
        }
    }

    override fun check(): Pair<Boolean, String> {
        if (url.isEmpty()) return false to "fish-speech 서버 주소가 설정되지 않았습니다"
        return try {
            httpClient.newBuilder().readTimeout(3, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$url/v1/health").get().build()).execute().use { r ->
                    if (r.code == 200) true to "fish-speech 서버 연결됨 ($url)"
                    else false to "fish-speech 서버 응답 오류 ${r.code}"
                }
        } catch (e: IOException) {
            false to "fish-speech 서버에 연결할 수 없습니다 ($url): ${e.message}"
        }
    }
}

// ---------------------------------------------------------------------------
/** mode "off": produces no audio. */
class NullTts : TtsEngine {
    override val sampleRate = 44100
    override fun synthesizeStream(text: String, persona: Character, cancel: Cancel, onPcm: (ByteArray) -> Unit) {}
    override fun check() = true to "음성 출력 꺼짐"
}

fun createTts(cfg: JSONObject): TtsEngine = when (cfg.str("tts.mode", "cloud").lowercase()) {
    "cloud" -> FishCloudTts(cfg)
    "local" -> FishLocalTts(cfg)
    else -> NullTts()
}
