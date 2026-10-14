package com.editor.veyro.core.model.time

/** Display formatting for durations. Display only, never stored or parsed back. */
public object DurationFormat {
    /**
     * Clock-style duration such as `0:07`, `12:34` or `1:02:03`. Rounds down to
     * whole seconds, except that any positive duration under one second shows
     * as `0:01`, so short clips never read as empty.
     */
    public fun clock(durationUs: Long): String {
        require(durationUs >= 0) { "durationUs must not be negative: $durationUs" }
        val totalSeconds = when {
            durationUs == 0L -> 0L
            durationUs < TimeUs.PER_SECOND -> 1L
            else -> durationUs / TimeUs.PER_SECOND
        }
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
        } else {
            "$minutes:${seconds.twoDigits()}"
        }
    }

    private fun Long.twoDigits(): String = toString().padStart(2, '0')
}
