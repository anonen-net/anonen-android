package net.anonen.app.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryTrash(
    private val scope: CoroutineScope,
    private val delete: suspend (Long) -> Unit,
    private val holdMs: Long = HOLD_MS,
) {
    private val _pending = MutableStateFlow<Set<Long>>(emptySet())

    val pending: StateFlow<Set<Long>> = _pending

    private val jobs = mutableMapOf<Long, Job>()

    @Synchronized
    fun stage(id: Long) {
        jobs.remove(id)?.cancel()
        _pending.value = _pending.value + id
        jobs[id] =
            scope.launch {
                delay(holdMs)
                commit(id)
            }
    }

    @Synchronized
    fun undo(id: Long) {
        jobs.remove(id)?.cancel()
        _pending.value = _pending.value - id
    }

    private suspend fun commit(id: Long) {
        synchronized(this) {
            if (id !in _pending.value) return
            jobs.remove(id)
        }
        runCatching { delete(id) }
        synchronized(this) { _pending.value = _pending.value - id }
    }

    companion object {
        const val HOLD_MS = 6_000L
    }
}
