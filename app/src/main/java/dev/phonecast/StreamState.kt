package dev.phonecast

import android.os.Handler
import android.os.Looper

/** Tiny in-process state holder so the UI can follow what CastService is doing. */
object StreamState {
    enum class Phase { IDLE, CONNECTING, LIVE }
    data class State(val phase: Phase, val target: String?, val message: String?)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(State) -> Unit>()

    @Volatile
    var current = State(Phase.IDLE, null, null)
        private set

    fun update(phase: Phase, target: String?, message: String?) {
        val s = State(phase, target, message)
        current = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    /** Main thread only. Immediately delivers the current state. */
    fun observe(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun remove(listener: (State) -> Unit) {
        listeners.remove(listener)
    }
}
