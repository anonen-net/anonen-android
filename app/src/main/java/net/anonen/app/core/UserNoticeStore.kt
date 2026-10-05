package net.anonen.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UserNotice(
    val message: String,
    val timestampMillis: Long,
)

class UserNoticeStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _notices = MutableStateFlow<List<UserNotice>>(emptyList())

    val notices: StateFlow<List<UserNotice>> = _notices.asStateFlow()

    private val lock = Any()

    init {
        synchronized(lock) {
            _notices.value =
                runCatching {
                    file.takeIf { it.isFile }?.readText()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Json.decodeFromString(SERIALIZER, it) }
                }.getOrNull().orEmpty()
        }
    }

    fun post(message: String) {
        synchronized(lock) {
            if (_notices.value.firstOrNull()?.message == message) return
            val updated = (listOf(UserNotice(message, now())) + _notices.value).take(MAX)
            _notices.value = updated
            save(updated)
        }
    }

    fun clear() {
        synchronized(lock) {
            _notices.value = emptyList()
            runCatching { file.delete() }
        }
    }

    private fun save(list: List<UserNotice>) {
        runCatching { file.writeText(Json.encodeToString(SERIALIZER, list)) }
    }

    companion object {
        private const val MAX = 20
        private val SERIALIZER = ListSerializer(UserNotice.serializer())
    }
}
