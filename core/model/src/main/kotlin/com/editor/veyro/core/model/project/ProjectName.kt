package com.editor.veyro.core.model.project

/** Rules for user-entered project names, shared by every place that creates or renames a project. */
public object ProjectName {
    public const val MAX_LENGTH: Int = 80

    /**
     * Trims, collapses runs of whitespace (including newlines) into single
     * spaces, and truncates to [MAX_LENGTH] code points. Returns `null` when
     * nothing usable is left.
     */
    public fun normalize(raw: String): String? {
        val collapsed = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        if (collapsed.isEmpty()) return null
        val codePoints = collapsed.codePointCount(0, collapsed.length)
        if (codePoints <= MAX_LENGTH) return collapsed
        val end = collapsed.offsetByCodePoints(0, MAX_LENGTH)
        return collapsed.substring(0, end).trimEnd()
    }

    private val WHITESPACE = Regex("\\s+")
}
