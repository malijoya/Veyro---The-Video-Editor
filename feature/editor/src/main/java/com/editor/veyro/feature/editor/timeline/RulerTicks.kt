package com.editor.veyro.feature.editor.timeline

/**
 * Tick spacing for the time ruler at a given zoom.
 *
 * @property majorUs time between labelled ticks.
 * @property minorPerMajor how many intervals the minor ticks split each major interval into.
 */
public data class RulerTicks(val majorUs: Long, val minorPerMajor: Int) {
    val minorUs: Long get() = majorUs / minorPerMajor

    public companion object {
        /** Readable steps for major ticks, from 0.1 s up to 30 min, each with a natural subdivision. */
        private val STEPS: List<RulerTicks> = listOf(
            RulerTicks(100_000, 2),
            RulerTicks(250_000, 5),
            RulerTicks(500_000, 5),
            RulerTicks(1_000_000, 4),
            RulerTicks(2_000_000, 4),
            RulerTicks(5_000_000, 5),
            RulerTicks(10_000_000, 5),
            RulerTicks(15_000_000, 3),
            RulerTicks(30_000_000, 3),
            RulerTicks(60_000_000, 4),
            RulerTicks(120_000_000, 4),
            RulerTicks(300_000_000, 5),
            RulerTicks(600_000_000, 5),
            RulerTicks(900_000_000, 3),
            RulerTicks(1_800_000_000, 3),
        )

        /**
         * The finest step whose labelled ticks are at least [minMajorSpacingPx]
         * apart at [scale], so labels never overlap. At extreme zoom-out the
         * largest step is used.
         */
        public fun choose(scale: TimelineScale, minMajorSpacingPx: Float): RulerTicks =
            STEPS.firstOrNull { scale.usToPx(it.majorUs) >= minMajorSpacingPx } ?: STEPS.last()

        /**
         * Labels for a tick at [timeUs]:
         * - whole seconds as `m:ss` (`1:05`)
         * - sub-second ticks as seconds with one decimal (`1.5s`), and as
         *   `m:ss.d` from one minute on (`1:05.5`)
         */
        public fun label(timeUs: Long): String {
            val totalTenths = timeUs / 100_000
            val tenths = totalTenths % 10
            val totalSeconds = totalTenths / 10
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return when {
                tenths != 0L && minutes == 0L -> "$seconds.${tenths}s"
                tenths != 0L -> "$minutes:${seconds.toString().padStart(2, '0')}.$tenths"
                else -> "$minutes:${seconds.toString().padStart(2, '0')}"
            }
        }
    }
}
