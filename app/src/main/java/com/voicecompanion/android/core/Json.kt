package com.voicecompanion.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small org.json helpers (the desktop app passes plain nested dicts around). */

fun JSONObject.at(path: String): Any? {
    var cur: Any? = this
    for (key in path.split('.')) {
        cur = (cur as? JSONObject)?.opt(key) ?: return null
        if (cur == JSONObject.NULL) return null
    }
    return cur
}

fun JSONObject.str(path: String, def: String = ""): String {
    val v = at(path) ?: return def
    return if (v is JSONObject || v is JSONArray) def else v.toString()
}

fun JSONObject.num(path: String, def: Double): Double = when (val v = at(path)) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: def
    else -> def
}

fun JSONObject.int(path: String, def: Int): Int = num(path, def.toDouble()).toInt()

fun JSONObject.bool(path: String, def: Boolean): Boolean = when (val v = at(path)) {
    is Boolean -> v
    is String -> v.equals("true", true)
    is Number -> v.toInt() != 0
    else -> def
}

/** Sets a dotted path, creating intermediate objects. */
fun JSONObject.putPath(path: String, value: Any?): JSONObject {
    val keys = path.split('.')
    var cur = this
    for (k in keys.dropLast(1)) {
        val next = cur.optJSONObject(k) ?: JSONObject().also { cur.put(k, it) }
        cur = next
    }
    cur.put(keys.last(), value ?: JSONObject.NULL)
    return this
}

fun deepCopy(o: JSONObject): JSONObject = JSONObject(o.toString())

/** Returns base with ``over`` merged in (objects recursively, everything else replaced). */
fun deepMerge(base: JSONObject, over: JSONObject): JSONObject {
    val out = deepCopy(base)
    for (k in over.keys()) {
        val v = over.get(k)
        val cur = out.opt(k)
        if (v is JSONObject && cur is JSONObject) out.put(k, deepMerge(cur, v))
        else out.put(k, if (v is JSONObject) deepCopy(v) else v)
    }
    return out
}

fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

fun readJsonFile(f: File): Any? = try {
    val text = f.readText(Charsets.UTF_8).removePrefix("﻿").trim()
    when {
        text.startsWith("{") -> JSONObject(text)
        text.startsWith("[") -> JSONArray(text)
        else -> null
    }
} catch (e: Exception) {
    null
}

/** Atomic write (tmp file + rename), like the desktop's os.replace. */
fun writeTextAtomic(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(f)) {
        f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
