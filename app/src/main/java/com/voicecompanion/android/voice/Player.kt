package com.voicecompanion.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock

/** Streaming int16 mono PCM playback (desktop ``audio_io.Player``). */
class Player {
    @Volatile
    private var track: AudioTrack? = null
    @Volatile
    private var interrupted = false

    @Volatile
    var isPlaying = false
        private set

    /** Blocking. Plays every chunk [next] returns until it returns null. False if interrupted. */
    fun play(next: () -> ByteArray?, sampleRate: Int): Boolean {
        interrupted = false
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sampleRate * 2 / 5) // ~200 ms
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        isPlaying = true
        var framesWritten = 0L
        try {
            t.play()
            while (!interrupted) {
                val chunk = next() ?: break
                var off = 0
                while (off < chunk.size && !interrupted) {
                    val n = t.write(chunk, off, minOf(4096, chunk.size - off), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                    framesWritten += n / 2
                }
            }
            if (!interrupted) {
                // let the buffered tail play out
                val deadline = SystemClock.elapsedRealtime() + framesWritten * 1000 / sampleRate + 1000
                while (!interrupted && SystemClock.elapsedRealtime() < deadline) {
                    val head = t.playbackHeadPosition.toLong() and 0xffffffffL
                    if (head >= framesWritten) break
                    Thread.sleep(15)
                }
            }
            return !interrupted
        } finally {
            isPlaying = false
            track = null
            runCatching { t.pause(); t.flush() }
            runCatching { t.stop() }
            t.release()
        }
    }

    fun interrupt() {
        interrupted = true
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
        }
    }
}
