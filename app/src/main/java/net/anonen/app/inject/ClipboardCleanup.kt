package net.anonen.app.inject

internal class ClipboardCleanup<C : Any>(
    private val read: () -> C?,
    private val holds: (C, String) -> Boolean,
    private val restore: (C) -> Boolean,
    private val clear: () -> Unit,
    private val post: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    private class Pending<C>(val previous: C?, val text: String)

    private val lock = Any()
    private var pending: Pending<C>? = null

    fun beginPaste(): C? =
        synchronized(lock) {
            val current = read()
            val waiting = pending
            pending = null
            when {
                waiting == null -> current

                current == null || holds(current, waiting.text) -> waiting.previous

                else -> current
            }
        }

    fun afterPaste(
        previous: C?,
        text: String,
        pasted: Boolean,
    ) {
        if (!pasted) {
            synchronized(lock) { cleanUp(previous, text) }
            return
        }
        val mine = Pending(previous, text)
        synchronized(lock) { pending = mine }
        post(DELAY_MS) {
            synchronized(lock) {
                if (pending === mine) {
                    pending = null
                    cleanUp(previous, text)
                }
            }
        }
    }

    fun keep() {
        synchronized(lock) { pending = null }
    }

    private fun cleanUp(
        previous: C?,
        text: String,
    ) {
        val current = read()
        if (current != null && !holds(current, text)) return
        if (previous != null && restore(previous)) return
        clear()
    }

    companion object {
        const val DELAY_MS = 1200L
    }
}
