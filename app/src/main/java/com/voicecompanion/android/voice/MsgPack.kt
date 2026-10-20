package com.voicecompanion.android.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Minimal MessagePack codec for the Fish Audio live protocol and fish-speech requests. */
object MsgPack {

    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(o: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> o.write(0xc0)
            is Boolean -> o.write(if (v) 0xc3 else 0xc2)
            is Int -> writeLong(o, v.toLong())
            is Long -> writeLong(o, v)
            is Short -> writeLong(o, v.toLong())
            is Float -> { o.write(0xca); o.write(ByteBuffer.allocate(4).putFloat(v).array()) }
            is Double -> { o.write(0xcb); o.write(ByteBuffer.allocate(8).putDouble(v).array()) }
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                when {
                    b.size < 32 -> o.write(0xa0 or b.size)
                    b.size < 0x100 -> { o.write(0xd9); o.write(b.size) }
                    b.size < 0x10000 -> { o.write(0xda); u16(o, b.size) }
                    else -> { o.write(0xdb); u32(o, b.size.toLong()) }
                }
                o.write(b)
            }
            is ByteArray -> {
                when {
                    v.size < 0x100 -> { o.write(0xc4); o.write(v.size) }
                    v.size < 0x10000 -> { o.write(0xc5); u16(o, v.size) }
                    else -> { o.write(0xc6); u32(o, v.size.toLong()) }
                }
                o.write(v)
            }
            is Map<*, *> -> {
                val n = v.size
                when {
                    n < 16 -> o.write(0x80 or n)
                    n < 0x10000 -> { o.write(0xde); u16(o, n) }
                    else -> { o.write(0xdf); u32(o, n.toLong()) }
                }
                for ((k, value) in v) {
                    write(o, k)
                    write(o, value)
                }
            }
            is List<*> -> {
                val n = v.size
                when {
                    n < 16 -> o.write(0x90 or n)
                    n < 0x10000 -> { o.write(0xdc); u16(o, n) }
                    else -> { o.write(0xdd); u32(o, n.toLong()) }
                }
                for (x in v) write(o, x)
            }
            is Number -> write(o, v.toDouble())
            else -> write(o, v.toString())
        }
    }

    private fun writeLong(o: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..127 -> o.write(v.toInt())
            v in -32..-1 -> o.write((v.toInt() and 0xff))
            v in 0..0xff -> { o.write(0xcc); o.write(v.toInt()) }
            v in 0..0xffff -> { o.write(0xcd); u16(o, v.toInt()) }
            v in 0..0xffffffffL -> { o.write(0xce); u32(o, v) }
            v >= 0 -> { o.write(0xcf); o.write(ByteBuffer.allocate(8).putLong(v).array()) }
            v >= -128 -> { o.write(0xd0); o.write(v.toInt() and 0xff) }
            v >= -32768 -> { o.write(0xd1); u16(o, v.toInt() and 0xffff) }
            v >= Int.MIN_VALUE -> { o.write(0xd2); o.write(ByteBuffer.allocate(4).putInt(v.toInt()).array()) }
            else -> { o.write(0xd3); o.write(ByteBuffer.allocate(8).putLong(v).array()) }
        }
    }

    private fun u16(o: ByteArrayOutputStream, v: Int) {
        o.write((v shr 8) and 0xff); o.write(v and 0xff)
    }

    private fun u32(o: ByteArrayOutputStream, v: Long) {
        o.write(((v shr 24) and 0xff).toInt()); o.write(((v shr 16) and 0xff).toInt())
        o.write(((v shr 8) and 0xff).toInt()); o.write((v and 0xff).toInt())
    }

    // ------------------------------------------------------------------ decode
    fun unpack(data: ByteArray): Any? = Reader(ByteBuffer.wrap(data)).read()

    private class Reader(val b: ByteBuffer) {
        private fun u8() = b.get().toInt() and 0xff
        private fun u16() = b.short.toInt() and 0xffff
        private fun u32() = b.int.toLong() and 0xffffffffL
        private fun bytes(n: Int): ByteArray = ByteArray(n).also { b.get(it) }
        private fun str(n: Int) = String(bytes(n), Charsets.UTF_8)
        private fun map(n: Int): Map<Any?, Any?> {
            val m = LinkedHashMap<Any?, Any?>()
            repeat(n) { val k = read(); m[k] = read() }
            return m
        }
        private fun arr(n: Int): List<Any?> = List(n) { read() }

        fun read(): Any? {
            val t = u8()
            return when {
                t <= 0x7f -> t.toLong()
                t in 0x80..0x8f -> map(t and 0x0f)
                t in 0x90..0x9f -> arr(t and 0x0f)
                t in 0xa0..0xbf -> str(t and 0x1f)
                t >= 0xe0 -> (t - 256).toLong()
                else -> when (t) {
                    0xc0 -> null
                    0xc2 -> false
                    0xc3 -> true
                    0xc4 -> bytes(u8())
                    0xc5 -> bytes(u16())
                    0xc6 -> bytes(u32().toInt())
                    0xc7 -> { val n = u8(); b.get(); bytes(n); null }
                    0xc8 -> { val n = u16(); b.get(); bytes(n); null }
                    0xc9 -> { val n = u32().toInt(); b.get(); bytes(n); null }
                    0xca -> b.float.toDouble()
                    0xcb -> b.double
                    0xcc -> u8().toLong()
                    0xcd -> u16().toLong()
                    0xce -> u32()
                    0xcf -> b.long
                    0xd0 -> b.get().toLong()
                    0xd1 -> b.short.toLong()
                    0xd2 -> b.int.toLong()
                    0xd3 -> b.long
                    0xd4 -> { b.get(); bytes(1); null }
                    0xd5 -> { b.get(); bytes(2); null }
                    0xd6 -> { b.get(); bytes(4); null }
                    0xd7 -> { b.get(); bytes(8); null }
                    0xd8 -> { b.get(); bytes(16); null }
                    0xd9 -> str(u8())
                    0xda -> str(u16())
                    0xdb -> str(u32().toInt())
                    0xdc -> arr(u16())
                    0xdd -> arr(u32().toInt())
                    0xde -> map(u16())
                    0xdf -> map(u32().toInt())
                    else -> throw IllegalArgumentException("msgpack: bad type byte $t")
                }
            }
        }
    }
}
