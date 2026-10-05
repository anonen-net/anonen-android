package net.anonen.app.textproc

private val SOUNDEX_CODES =
    mapOf(
        'B' to '1', 'F' to '1', 'P' to '1', 'V' to '1',
        'C' to '2', 'G' to '2', 'J' to '2', 'K' to '2',
        'Q' to '2', 'S' to '2', 'X' to '2', 'Z' to '2',
        'D' to '3', 'T' to '3',
        'L' to '4',
        'M' to '5', 'N' to '5',
        'R' to '6',
    )

internal fun soundexCode(s: String): String {
    val letters = s.uppercase().filter { it in 'A'..'Z' }
    if (letters.isEmpty()) return ""
    val sb = StringBuilder().append(letters[0])
    var prev = SOUNDEX_CODES[letters[0]]
    for (c in letters.drop(1)) {
        val code = SOUNDEX_CODES[c]
        if (code != null) {
            if (code != prev) {
                sb.append(code)
                if (sb.length == 4) break
            }
            prev = code
        } else if (c != 'H' && c != 'W') {
            prev = null
        }
    }
    return sb.append("000").substring(0, 4)
}

internal fun soundexMatch(
    a: String,
    b: String,
): Boolean {
    val ca = soundexCode(a)
    return ca.isNotEmpty() && ca == soundexCode(b)
}
