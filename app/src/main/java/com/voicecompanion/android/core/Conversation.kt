package com.voicecompanion.android.core

import android.util.Log
import com.voicecompanion.android.llm.ChatMessage
import com.voicecompanion.android.llm.LlmClient
import com.voicecompanion.android.voice.ChunkSource
import com.voicecompanion.android.voice.VoiceSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class HistoryEntry(val role: String, val content: String, val ts: Double, val interrupted: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("role", role).put("content", content).put("ts", ts)
        .apply { if (interrupted) put("interrupted", true) }
}

sealed class ConvEvent {
    class UserMessage(val id: String, val text: String, val source: String) : ConvEvent()
    class AssistantStart(val id: String, val persona: String) : ConvEvent()
    class AssistantDelta(val id: String, val text: String) : ConvEvent()
    class AssistantEnd(val id: String, val text: String, val interrupted: Boolean) : ConvEvent()
    class Error(val message: String, val where: String = "") : ConvEvent()
}

/**
 * Turn orchestrator — port of desktop ``app/conversation.py``: history per persona,
 * LLM streaming, sentence chunking → TTS.
 *
 * One reply at a time. A new user turn, barge-in or [interrupt] cancels the current
 * reply; interrupted replies are saved with the partial text (``interrupted: true``).
 * Events are emitted from worker threads.
 */
class Conversation(
    private val config: () -> JSONObject,
    private val llm: () -> LlmClient,
    private val characters: CharacterStore,
    private val historyDir: File,
    private val emit: (ConvEvent) -> Unit,
    /** Session that should speak this reply (voice active, or speak_replies on), else null. */
    private val voiceProvider: (source: String) -> VoiceSession?,
) {
    private inner class Turn(val persona: Character, val text: String, val source: String) {
        val id: String = newId()
        val cancel = Cancel()
        @Volatile
        var voice: VoiceSession? = null
    }

    private val histLock = ReentrantLock()
    private val turnLock = ReentrantLock()
    private val stateLock = Any()
    @Volatile
    private var current: Turn? = null

    @Volatile
    var personaId: String = config().str("ui.default_persona", "romantic").ifEmpty { "romantic" }

    val busy: Boolean get() = current != null

    private fun safeEmit(ev: ConvEvent) {
        try {
            emit(ev)
        } catch (e: Exception) {
            Log.e(TAG, "emit failed", e)
        }
    }

    // -- history ---------------------------------------------------------------
    private fun histFile(pid: String): File {
        val safe = pid.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "default" }
        return File(historyDir, "$safe.json")
    }

    private fun read(pid: String): MutableList<HistoryEntry>? {
        val f = histFile(pid)
        if (!f.exists()) return null
        val data = readJsonFile(f)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("messages") ?: JSONArray()
            else -> return null
        }
        return arr.objects()
            .filter { it.optString("role") == "user" || it.optString("role") == "assistant" }
            .map { HistoryEntry(it.optString("role"), it.optString("content"), it.optDouble("ts", 0.0), it.optBoolean("interrupted")) }
            .toMutableList()
    }

    private fun write(pid: String, msgs: List<HistoryEntry>) {
        val arr = JSONArray()
        for (m in msgs.takeLast(MAX_STORED_MESSAGES)) arr.put(m.toJson())
        writeTextAtomic(histFile(pid), arr.toString(1))
    }

    private fun load(pid: String): MutableList<HistoryEntry> = histLock.withLock {
        var msgs = read(pid)
        if (msgs.isNullOrEmpty()) {
            val persona = characters.get(pid)
            msgs = ArrayList()
            if (persona.greeting.isNotEmpty()) msgs.add(HistoryEntry("assistant", persona.greeting, now()))
            runCatching { write(pid, msgs) }.onFailure { Log.e(TAG, "cannot write history", it) }
        }
        msgs
    }

    private fun append(pid: String, entry: HistoryEntry) = histLock.withLock {
        val msgs = load(pid)
        msgs.add(entry)
        runCatching { write(pid, msgs) }.onFailure { Log.e(TAG, "cannot write history", it) }
    }

    /** Throws [UnknownCharacter] for an unknown persona. */
    fun history(pid: String): List<HistoryEntry> {
        characters.get(pid)
        return load(pid).toList()
    }

    fun hasHistory(pid: String): Boolean = read(pid)?.any { it.role == "user" } == true

    fun clearHistory(pid: String) {
        val cur = current
        if (cur != null && cur.persona.id == pid) interrupt()
        histLock.withLock { histFile(pid).delete() }
    }

    /** A character is being deleted: stop its reply and switch away from it. */
    fun forgetPersona(pid: String, fallback: String = "romantic") {
        val cur = current
        if (cur != null && cur.persona.id == pid) {
            interrupt()
            waitIdle(3000)
        }
        histLock.withLock { histFile(pid).delete() }
        if (personaId == pid) personaId = fallback
    }

    fun buildMessages(persona: Character): List<ChatMessage> {
        val hist = load(persona.id)
        val turns = config().int("llm.history_turns", 20)
        val n = maxOf(turns, 1) * 2
        // Drop old messages in blocks of n/2 instead of one per turn: the request
        // prefix then stays identical between turns, so llama-server's prompt cache
        // only processes the new message.
        val step = maxOf(n / 2, 2)
        val drop = if (hist.size <= n) 0 else ((hist.size - n + step - 1) / step) * step // ceil to a block
        val window = hist.drop(drop)
        val fromStart = drop == 0
        val merged = ArrayList<Pair<String, StringBuilder>>()
        for (m in window) {
            val content = m.content.trim()
            if (content.isEmpty()) continue
            if (merged.isNotEmpty() && merged.last().first == m.role) merged.last().second.append("\n").append(content)
            else merged.add(m.role to StringBuilder(content))
        }
        if (merged.isNotEmpty() && merged[0].first == "assistant") {
            if (fromStart) merged.add(0, "user" to StringBuilder(SYNTHETIC_OPENER))
            else merged.removeAt(0)
        }
        // Roleplay reminder: request-only (never stored / shown). It rides on every user
        // turn so earlier turns look the same in each request and stay in the prompt cache.
        val reminder = runCatching { persona.reminder }.getOrDefault("")
        val out = ArrayList<ChatMessage>()
        out.add(ChatMessage("system", persona.systemPrompt))
        for ((role, sb) in merged) {
            val content = if (role == "user" && reminder.isNotEmpty()) "$sb\n\n$reminder" else sb.toString()
            out.add(ChatMessage(role, content))
        }
        return out
    }

    // -- persona ----------------------------------------------------------------
    fun setPersona(pid: String) {
        characters.get(pid)
        if (pid != personaId && current != null) interrupt()
        personaId = pid
    }

    // -- turns ------------------------------------------------------------------
    /** Starts a reply to [text] (non-blocking); cancels any running reply.
     *  Returns the user message id, or null for empty text. */
    fun submitUser(pid: String?, text0: String, source: String = "text"): String? {
        val text = text0.trim()
        if (text.isEmpty()) return null
        val id = pid ?: personaId
        val persona = characters.get(id).withVoiceOverrides(config())
        personaId = id
        val turn = Turn(persona, text, source)
        synchronized(stateLock) {
            current?.let { cancelTurn(it) }
            current = turn
        }
        safeEmit(ConvEvent.UserMessage(turn.id, text, source))
        Thread({ runTurn(turn) }, "turn-${turn.id}").apply { isDaemon = true; start() }
        return turn.id
    }

    fun interrupt() {
        current?.let { cancelTurn(it) }
    }

    private fun cancelTurn(turn: Turn) {
        turn.cancel.set()
        turn.voice?.let { runCatching { it.interrupt() } }
    }

    fun waitIdle(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (current == null) return true
            Thread.sleep(50)
        }
        return false
    }

    /** Text of a voice turn (UserFinal) or a barge-in from the voice session. */
    fun onUserVoice(text: String) {
        if (text.isBlank()) return
        try {
            submitUser(personaId, text, "voice")
        } catch (e: UnknownCharacter) {
            safeEmit(ConvEvent.Error("unknown persona $personaId"))
        }
    }

    private fun runTurn(turn: Turn) {
        val pid = turn.persona.id
        try {
            turnLock.withLock {
                append(pid, HistoryEntry("user", turn.text, now()))
                if (turn.cancel.isSet) return // superseded before it started
                reply(turn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "turn failed", e)
            safeEmit(ConvEvent.Error("응답 생성 실패: ${e.message}"))
        } finally {
            synchronized(stateLock) { if (current === turn) current = null }
        }
    }

    private fun reply(turn: Turn) {
        val persona = turn.persona
        val pid = persona.id
        val messages = buildMessages(persona)
        safeEmit(ConvEvent.AssistantStart(turn.id, pid))

        val speaker = runCatching { voiceProvider(turn.source) }.getOrNull()
        var q: LinkedBlockingQueue<String>? = null
        var speakThread: Thread? = null
        val speakResult = arrayOfNulls<Boolean>(1)
        val endMark = "\u0000END"
        if (speaker != null) {
            turn.voice = speaker
            if (turn.cancel.isSet) cancelTurn(turn) // cancelled between checks: make sure audio stops
            val queue = LinkedBlockingQueue<String>()
            q = queue
            val source = ChunkSource {
                while (true) {
                    val item = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (item == null) {
                        if (turn.cancel.isSet) return@ChunkSource null
                        continue
                    }
                    if (item == endMark || turn.cancel.isSet) return@ChunkSource null
                    return@ChunkSource item
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            speakThread = Thread({
                try {
                    if (turn.cancel.isSet) {
                        speakResult[0] = false
                    } else {
                        speakResult[0] = speaker.speak(source, persona)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "speak failed", e) // TTS failure != user interruption
                    safeEmit(ConvEvent.Error("[tts] ${e.message}", "tts"))
                }
            }, "speak-${turn.id}").apply { isDaemon = true; start() }
        }

        val chunker = TextUtils.SentenceChunker()
        val parts = StringBuilder()
        var failed = false
        val keepTags = TextUtils.supportsEmotionTags(config())
        var carry = "" // tag-only chunk, prefixed to the next speakable one

        fun push(chunks: List<String>, final: Boolean = false) {
            val qq = q ?: return
            for (c0 in chunks) {
                var c = TextUtils.cleanForTts(c0, keepTags)
                if (c.isEmpty()) continue
                if (keepTags && !TextUtils.speakable(c)) {
                    carry = "$carry $c".trim()
                    continue
                }
                if (carry.isNotEmpty()) {
                    c = "$carry $c"
                    carry = ""
                }
                qq.put(c)
            }
            if (final) carry = ""
        }

        try {
            llm().streamChat(messages, persona.sampling, turn.cancel) { delta ->
                if (!turn.cancel.isSet && delta.isNotEmpty()) {
                    parts.append(delta)
                    safeEmit(ConvEvent.AssistantDelta(turn.id, delta))
                    push(chunker.feed(delta))
                }
            }
            if (!turn.cancel.isSet) push(chunker.flush(), final = true)
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "LLM stream failed: ${e.message}")
            safeEmit(ConvEvent.Error(e.message ?: e.toString(), "llm"))
        } finally {
            q?.put(endMark)
        }

        if (speakThread != null) {
            while (speakThread.isAlive) {
                speakThread.join(100)
                if (turn.cancel.isSet) {
                    speakThread.join(3000)
                    break
                }
            }
        }

        val text = parts.toString().trim()
        val interrupted = turn.cancel.isSet || speakResult[0] == false || (failed && text.isNotEmpty())
        if (text.isNotEmpty()) append(pid, HistoryEntry("assistant", text, now(), interrupted))
        safeEmit(ConvEvent.AssistantEnd(turn.id, text, interrupted))
    }

    companion object {
        private const val TAG = "Conversation"
        const val MAX_STORED_MESSAGES = 400 // persisted per persona (UI scrollback)
        const val SYNTHETIC_OPENER = "안녕" // keeps user/assistant alternation when history starts with the greeting

        fun now(): Double = System.currentTimeMillis() / 1000.0
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
