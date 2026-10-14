package net.anonen.app.cloud

import okhttp3.Call
import java.util.concurrent.CopyOnWriteArraySet

class TranscribeCancelController {
    enum class Reason {
        USER,

        LOST_ACCESS,
    }

    @Volatile
    private var reason: Reason? = null

    private val inFlight = CopyOnWriteArraySet<Call>()

    val isCancelled: Boolean get() = reason != null

    val cancelReason: Reason? get() = reason

    fun begin() {
        reason = null
        inFlight.clear()
    }

    fun requestCancel(reason: Reason = Reason.USER) {
        if (this.reason != Reason.LOST_ACCESS) this.reason = reason
        inFlight.forEach { runCatching { it.cancel() } }
    }

    fun register(call: Call) {
        inFlight.add(call)
        if (isCancelled) runCatching { call.cancel() }
    }

    fun unregister(call: Call) {
        inFlight.remove(call)
    }
}
