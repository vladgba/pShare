package x.vladgba.sync.core.util

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/** A thread-safe observable value (stand-in for StateFlow). Listeners run on the thread that changed it. */
class Observable<T>(initial: T) {
    @Volatile
    var value: T = initial
        private set

    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    fun set(v: T) {
        synchronized(this) {
            if (v == value) return
            value = v
        }
        notifyAll(v)
    }

    fun update(f: (T) -> T) {
        val next: T
        synchronized(this) {
            next = f(value)
            if (next == value) return
            value = next
        }
        notifyAll(next)
    }

    /** Calls [listener] now and on every change. Close the result to unsubscribe. */
    fun observe(listener: (T) -> Unit): Closeable {
        listeners += listener
        listener(value)
        return Closeable { listeners -= listener }
    }

    private fun notifyAll(v: T) {
        for (l in listeners) {
            try {
                l(v)
            } catch (_: Exception) {
            }
        }
    }
}
