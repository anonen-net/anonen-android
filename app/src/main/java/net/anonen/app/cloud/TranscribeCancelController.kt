package net.anonen.app.cloud

import okhttp3.Call
import java.util.concurrent.CopyOnWriteArraySet

class TranscribeCancelController {
    @Volatile
    private var cancelled = false

    private val inFlight = CopyOnWriteArraySet<Call>()

    val isCancelled: Boolean get() = cancelled

    fun begin() {
        cancelled = false
        inFlight.clear()
    }

    fun requestCancel() {
        cancelled = true
        inFlight.forEach { runCatching { it.cancel() } }
    }

    fun register(call: Call) {
        inFlight.add(call)
        if (cancelled) runCatching { call.cancel() }
    }

    fun unregister(call: Call) {
        inFlight.remove(call)
    }
}
