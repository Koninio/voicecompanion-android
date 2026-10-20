package com.voicecompanion.android.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** threading.Event-like cancel flag; listeners run once when it is set (e.g. Call.cancel). */
class Cancel {
    private val flag = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isSet: Boolean get() = flag.get()

    fun set() {
        if (flag.compareAndSet(false, true)) {
            for (l in listeners) runCatching { l() }
        }
    }

    /** Runs [block] now if already cancelled. */
    fun onCancel(block: () -> Unit) {
        listeners.add(block)
        if (flag.get()) runCatching { block() }
    }
}
