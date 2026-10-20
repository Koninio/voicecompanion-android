package com.voicecompanion.android.core

import org.json.JSONObject
import java.io.File

/**
 * App settings, the Android counterpart of desktop ``config.example.json``.
 *
 * Differences from the desktop: the LLM is any OpenAI-compatible server (the PC's
 * llama-server on the LAN, or a cloud API) instead of a child process, and STT is
 * either Android's speech recognizer or a Whisper-compatible HTTP API.
 */
class Config(private val file: File) {

    @Volatile
    var data: JSONObject = load()
        private set

    private fun load(): JSONObject {
        val stored = readJsonFile(file) as? JSONObject
        val merged = if (stored != null) deepMerge(defaults(), stored) else defaults()
        if (stored == null) save(merged)
        return merged
    }

    private fun save(obj: JSONObject) = writeTextAtomic(file, obj.toString(2))

    /** Partial deep-merge update (like PUT /api/config). */
    @Synchronized
    fun update(partial: JSONObject) {
        val next = deepMerge(data, partial)
        save(next)
        data = next
    }

    companion object {
        val CLOUD_MODELS = listOf("drama-3", "s2.1-pro", "s2.1-pro-free", "s2-pro")

        fun defaults(): JSONObject = JSONObject(
            """
            {
              "llm": {
                "base_url": "",
                "api_key": "",
                "model": "",
                "history_turns": 20,
                "llama_params": true
              },
              "stt": {
                "mode": "android",
                "language": "ko",
                "whisper": {
                  "base_url": "https://api.groq.com/openai",
                  "api_key": "",
                  "model": "whisper-large-v3-turbo"
                }
              },
              "vad": {
                "threshold": 0.5,
                "barge_in": true,
                "barge_in_threshold": 0.8,
                "end_silence_ms": 1000,
                "min_speech_ms": 250,
                "preroll_ms": 300
              },
              "tts": {
                "mode": "cloud",
                "sample_rate": 44100,
                "emotion_tags": true,
                "cloud": {
                  "api_key": "",
                  "model": "drama-3",
                  "transport": "websocket",
                  "latency": "low",
                  "base_url": "https://api.fish.audio"
                },
                "local": { "url": "" }
              },
              "personas_voice": {},
              "ui": { "default_persona": "romantic", "speak_replies": true }
            }
            """.trimIndent()
        )

        /** ``base`` + ``/v1/<path>`` unless the base already ends in ``/v1``. */
        fun apiUrl(base: String, path: String): String {
            val b = base.trim().trimEnd('/')
            val p = path.trimStart('/')
            return if (b.endsWith("/v1")) "$b/$p" else "$b/v1/$p"
        }
    }
}
