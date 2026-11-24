package com.rebelroot.foxscan.engine

/**
 * Temporal auto-capture state machine.
 *
 *  - Ring buffer of the last 90 timed quadrangles (3 s at 30 fps).
 *  - Deviation = RMS distance of each of the 8 corner coords from the mean quad,
 *    in normalized frame units; a window is "stable" when ALL 8 are within threshold.
 *  - Window thresholds: 3-frame 0.05, 20/40-frame 0.025.
 *  - State timing:
 *      count < 3                                  -> SEARCHING
 *      last-3 stable (0.05)                       -> ABOUT_TO_TRIGGER
 *      count < 20                                 -> stay ABOUT_TO_TRIGGER
 *      last-20 unstable (0.025) or newest quad
 *      invalid or time-in-state < 1.501 s         -> stay ABOUT_TO_TRIGGER
 *      count < 40                                 -> TRIGGER
 *      last-40 unstable (0.025)                   -> TRIGGER
 *      time-in-TRIGGER < 1.001 s                  -> TRIGGER
 *      otherwise                                  -> STABLE (post-capture hold)
 */
class QuadStreamAnalyzer {

    enum class State { SEARCHING, ABOUT_TO_TRIGGER, TRIGGER, STABLE }

    companion object {
        const val QUEUE_SIZE = 90
        const val T3 = 0.05f          // last-3 window threshold
        const val T20 = 0.025f        // last-20/40 window threshold
        const val MIN_FRAMES_3 = 3
        const val MIN_FRAMES_20 = 20
        const val MIN_FRAMES_40 = 40
        const val ABOUT_TO_TRIGGER_HOLD_US = 1_501_000L // 1.5 s + 1 ms slack
        const val TRIGGER_HOLD_US = 1_001_000L          // 1.0 s + 1 ms slack
        const val STABILITY_MS = 1000L                  // stability anchor (ms)
    }

    private val xs = FloatArray(8 * QUEUE_SIZE)
    private val ts = LongArray(QUEUE_SIZE)
    private var start = 0
    private var newest = -1
    private var count = 0
    private var lastStatus = -1
    private var tAbout = Long.MIN_VALUE
    private var tTrigger = Long.MIN_VALUE

    /** Mean quad of the valid (non-empty) frames, for display. */
    var displayQuad: Quad = Quad(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        private set

    fun reset() {
        start = 0; newest = -1; count = 0; lastStatus = -1
        tAbout = Long.MIN_VALUE; tTrigger = Long.MIN_VALUE
        displayQuad = Quad(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    /** Add one frame's quad (may be null = detection failed). Returns current state. */
    fun addQuad(quad: Quad?, nowUs: Long): State {
        val q = quad ?: Quad(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        newest = (newest + 1) % QUEUE_SIZE
        val w = newest * 8
        xs[w] = q.tlX; xs[w + 1] = q.tlY; xs[w + 2] = q.trX; xs[w + 3] = q.trY
        xs[w + 4] = q.brX; xs[w + 5] = q.brY; xs[w + 6] = q.blX; xs[w + 7] = q.blY
        ts[newest] = nowUs
        if (count < QUEUE_SIZE) count++

        val status = internalStatus(nowUs)
        if (status != lastStatus) {
            when (status) {
                0 -> { tAbout = Long.MIN_VALUE; tTrigger = Long.MIN_VALUE }
                1 -> { tAbout = nowUs; tTrigger = Long.MIN_VALUE }
                2 -> tTrigger = nowUs
            }
            lastStatus = status
        }
        displayQuad = if (status >= 1) mean(count) else displayQuad
        return State.entries[status]
    }

    private fun internalStatus(nowUs: Long): Int {
        if (count < MIN_FRAMES_3) return 0
        if (!stable(count, MIN_FRAMES_3, T3)) return 0
        // last-3 stable -> ABOUT_TO_TRIGGER; mean for display over all valid frames
        if (count < MIN_FRAMES_20) return 1
        if (!stable(count, MIN_FRAMES_20, T20)) return 1
        // newest frame must be a valid quad
        if (newest < 0) return 1
        val nw = newest * 8
        var any = false
        for (i in 0 until 8) if (xs[nw + i] != 0f) { any = true; break }
        if (!any) return 1
        if (tAbout != Long.MIN_VALUE && nowUs - tAbout < ABOUT_TO_TRIGGER_HOLD_US) return 1
        if (count < MIN_FRAMES_40) return 2
        if (!stable(count, MIN_FRAMES_40, T20)) return 2
        if (tTrigger != Long.MIN_VALUE && nowUs - tTrigger < TRIGGER_HOLD_US) return 2
        return 3
    }

    /** Mean over the newest n frames, skipping all-zero (invalid) quads. */
    private fun mean(n: Int): Quad {
        val nEff = minOf(count, n)
        val acc = FloatArray(8)
        var valid = 0
        var idx = newest
        repeat(nEff) {
            if (idx >= 0) {
                val w = idx * 8
                var nz = false
                for (i in 0 until 8) if (xs[w + i] != 0f) { nz = true; break }
                if (nz) {
                    for (i in 0 until 8) acc[i] += xs[w + i]
                    valid++
                }
            }
            idx = (idx - 1 + QUEUE_SIZE) % QUEUE_SIZE
        }
        if (valid == 0) return Quad(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val f = 1f / valid
        return Quad(acc[0] * f, acc[1] * f, acc[2] * f, acc[3] * f,
            acc[4] * f, acc[5] * f, acc[6] * f, acc[7] * f)
    }

    /** All 8 per-corner RMS deviations within threshold. */
    private fun stable(fromCount: Int, n: Int, threshold: Float): Boolean {
        val nEff = minOf(count, minOf(fromCount, n))
        if (nEff < 1) return false
        val m = mean(nEff)
        val mv = floatArrayOf(m.tlX, m.tlY, m.trX, m.trY, m.brX, m.brY, m.blX, m.blY)
        val sq = FloatArray(8)
        var idx = newest
        repeat(nEff) {
            val w = idx * 8
            var nz = false
            for (i in 0 until 8) if (xs[w + i] != 0f) { nz = true; break }
            if (nz) {
                for (i in 0 until 8) {
                    val d = xs[w + i] - mv[i]
                    sq[i] += d * d
                }
            }
            idx = (idx - 1 + QUEUE_SIZE) % QUEUE_SIZE
        }
        for (i in 0 until 8) {
            if (kotlin.math.sqrt(sq[i] / nEff) > threshold) return false
        }
        return true
    }
}
