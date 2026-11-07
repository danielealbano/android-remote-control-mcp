package com.danielealbano.androidremotecontrolmcp.services.shell

object CommandPatterns {
    private const val WILDCARD = '*'
    private const val ALLOWLIST_WILDCARD_EXCLUDED = ";&|`\$()<>\r\n"

    fun firstBlocklistMatch(
        patterns: List<String>,
        command: String,
    ): String? = patterns.firstOrNull { matches(it, command) { true } }

    fun firstAllowlistMatch(
        patterns: List<String>,
        command: String,
    ): String? = patterns.firstOrNull { matches(it, command) { char -> char !in ALLOWLIST_WILDCARD_EXCLUDED } }

    private fun matches(
        pattern: String,
        command: String,
        wildcardAllows: (Char) -> Boolean,
    ): Boolean {
        val trimmed = pattern.trim()
        val text = command.trim()
        val parts = trimmed.split(WILDCARD)
        return when {
            trimmed.isEmpty() -> false
            parts.size == 1 -> text == trimmed
            else -> matchesParts(parts, text, wildcardAllows)
        }
    }

    private fun matchesParts(
        parts: List<String>,
        text: String,
        wildcardAllows: (Char) -> Boolean,
    ): Boolean {
        val first = parts.first()
        val last = parts.last()
        val end = text.length - last.length
        if (end < first.length || !text.startsWith(first) || !text.endsWith(last)) return false
        var position = first.length
        var matched = true
        for (part in parts.subList(1, parts.size - 1)) {
            val found = text.indexOf(part, position)
            matched = found >= 0 && found + part.length <= end && gapAllowed(text, position, found, wildcardAllows)
            if (!matched) break
            position = found + part.length
        }
        return matched && gapAllowed(text, position, end, wildcardAllows)
    }

    private fun gapAllowed(
        text: String,
        from: Int,
        until: Int,
        wildcardAllows: (Char) -> Boolean,
    ): Boolean = (from until until).all { wildcardAllows(text[it]) }
}
