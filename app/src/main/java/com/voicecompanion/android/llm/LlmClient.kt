package com.voicecompanion.android.llm

import com.voicecompanion.android.core.Cancel
import com.voicecompanion.android.core.Config
import com.voicecompanion.android.core.bool
import com.voicecompanion.android.core.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LlmError(message: String) : RuntimeException(message)

data class ChatMessage(val role: String, val content: String)

/**
 * OpenAI-compatible chat client (llama.cpp ``llama-server``, OpenRouter, OpenAI, …).
 * Replaces the desktop's LlamaServer child process: on the phone the model runs elsewhere.
 */
class LlmClient(cfg: JSONObject) {
    private val baseUrl = cfg.str("llm.base_url").trim()
    private val apiKey = cfg.str("llm.api_key").trim()
    private val model = cfg.str("llm.model").trim()
    /** llama.cpp sampling extras (top_k, repeat_penalty, min_p, cache_prompt). */
    private val llamaParams = cfg.bool("llm.llama_params", true)

    val configured: Boolean get() = baseUrl.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // a cold 26B prompt can take a while
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(Config.apiUrl(baseUrl, path))
        if (apiKey.isNotEmpty()) b.header("Authorization", "Bearer $apiKey")
        return b
    }

    fun buildBody(messages: List<ChatMessage>, sampling: Map<String, Number>): JSONObject {
        val body = JSONObject()
        body.put("model", model.ifEmpty { "local" })
        body.put("stream", true)
        body.put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
        sampling["temperature"]?.let { body.put("temperature", it.toDouble()) }
        sampling["top_p"]?.let { body.put("top_p", it.toDouble()) }
        sampling["max_tokens"]?.let { body.put("max_tokens", it.toInt()) }
        sampling["presence_penalty"]?.let { body.put("presence_penalty", it.toDouble()) }
        sampling["frequency_penalty"]?.let { body.put("frequency_penalty", it.toDouble()) }
        sampling["seed"]?.let { if (it.toInt() >= 0) body.put("seed", it.toInt()) }
        if (llamaParams) {
            sampling["top_k"]?.let { body.put("top_k", it.toInt()) }
            sampling["repeat_penalty"]?.let { body.put("repeat_penalty", it.toDouble()) }
            sampling["min_p"]?.let { body.put("min_p", it.toDouble()) }
            body.put("cache_prompt", true)
        }
        return body
    }

    /** Blocking; calls [onDelta] with each text delta until the reply ends or [cancel] is set. */
    fun streamChat(messages: List<ChatMessage>, sampling: Map<String, Number>, cancel: Cancel,
                   onDelta: (String) -> Unit) {
        if (!configured) throw LlmError("언어 모델 서버 주소가 설정되지 않았어요 (설정 → 언어 모델)")
        val req = request("chat/completions")
            .header("Accept", "text/event-stream")
            .post(buildBody(messages, sampling).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(req)
        cancel.onCancel { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string()?.let { errorText(it) } ?: ""
                    throw LlmError("LLM ${resp.code}: ${detail.ifEmpty { resp.message }}")
                }
                val source = resp.body?.source() ?: return
                while (!cancel.isSet) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val obj = try { JSONObject(data) } catch (e: Exception) { continue }
                    obj.optJSONObject("error")?.let { throw LlmError("LLM: " + (it.optString("message").ifEmpty { it.toString() })) }
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
                    val text = delta?.opt("content")
                    if (text is String && text.isNotEmpty()) onDelta(text)
                }
            }
        } catch (e: IOException) {
            if (cancel.isSet) return
            throw LlmError("언어 모델 서버에 연결할 수 없어요: ${e.message}")
        }
    }

    /** Cheap health check: (ok, message). */
    fun check(): Pair<Boolean, String> {
        if (!configured) return false to "서버 주소가 설정되지 않았어요"
        return try {
            client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
                .newCall(request("models").get().build()).execute().use { r ->
                    when {
                        r.isSuccessful -> {
                            val first = runCatching {
                                JSONObject(r.body?.string() ?: "").optJSONArray("data")
                                    ?.optJSONObject(0)?.optString("id")
                            }.getOrNull()
                            true to "연결됨" + (model.ifEmpty { first ?: "" }.let { if (it.isNotEmpty()) " ($it)" else "" })
                        }
                        r.code == 401 || r.code == 403 -> false to "API 키가 올바르지 않아요 (${r.code})"
                        else -> false to "응답 오류 ${r.code}"
                    }
                }
        } catch (e: IOException) {
            false to "연결할 수 없어요: ${e.message}"
        }
    }

    private fun errorText(body: String): String = try {
        val o = JSONObject(body)
        val err = o.opt("error")
        when (err) {
            is JSONObject -> err.optString("message").ifEmpty { err.toString() }
            is String -> err
            else -> o.optString("message").ifEmpty { o.optString("detail").ifEmpty { body.take(300) } }
        }
    } catch (e: Exception) {
        body.take(300)
    }
}
