package net.anonen.app.core

object EmailFormat {
    fun isValid(email: String): Boolean {
        val e = email.trim()
        if (e.isEmpty() || e.any { it.isWhitespace() }) return false
        val at = e.indexOf('@')

        if (at <= 0) return false

        if (e.indexOf('@', at + 1) != -1) return false
        val domain = e.substring(at + 1)

        val dot = domain.lastIndexOf('.')
        return dot > 0 && dot < domain.length - 1
    }
}
