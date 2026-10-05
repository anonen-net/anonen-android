package net.anonen.app.overlay

class RecordingCancelGesture {
    var armed = false
        private set

    var fired = false
        private set

    fun onDown(
        pushToTalk: Boolean,
        recording: Boolean,
    ): Boolean {
        fired = false
        armed = longPressDeletes(pushToTalk) && recording
        return armed
    }

    fun onDrag() {
        armed = false
    }

    fun onLongPressTimeout(recording: Boolean): Boolean {
        if (!armed) return false
        armed = false
        if (!recording) return false
        fired = true
        return true
    }

    fun onUp(): Boolean {
        armed = false
        return fired
    }

    companion object {
        fun longPressDeletes(pushToTalk: Boolean): Boolean = !pushToTalk
    }
}
