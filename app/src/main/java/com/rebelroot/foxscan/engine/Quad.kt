package com.rebelroot.foxscan.engine

/** Document quadrilateral in normalized frame coords (0..1), corners TL,TR,BR,BL. */
data class Quad(
    val tlX: Float, val tlY: Float,
    val trX: Float, val trY: Float,
    val brX: Float, val brY: Float,
    val blX: Float, val blY: Float,
) {
    val isValid: Boolean
        get() = listOf(tlX, tlY, trX, trY, brX, brY, blX, blY).all { it.isFinite() } &&
            !isEmpty()
    fun isEmpty() = tlX == 0f && tlY == 0f && trX == 0f && trY == 0f &&
        brX == 0f && brY == 0f && blX == 0f && blY == 0f

    fun area(): Float {
        fun ax(p1x: Float, p1y: Float, p2x: Float, p2y: Float) = p1x * p2y - p2x * p1y
        val s = ax(tlX, tlY, trX, trY) + ax(trX, trY, brX, brY) +
            ax(brX, brY, blX, blY) + ax(blX, blY, tlX, tlY)
        return kotlin.math.abs(s) / 2f
    }

    fun convex(): Boolean {
        val pts = listOf(tlX to tlY, trX to trY, brX to brY, blX to blY)
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
            val cross = (b.first - a.first) * (c.second - b.second) -
                (b.second - a.second) * (c.first - b.first)
            val s = if (cross > 1e-6f) 1 else if (cross < -1e-6f) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return sign != 0
    }

    /** Shrink toward the centroid by r (0..1) to avoid edge-hugging crops. */
    fun inset(r: Float): Quad {
        val cx = (tlX + trX + brX + blX) / 4f
        val cy = (tlY + trY + brY + blY) / 4f
        fun f(v: Float) = cx + (v - cx) * r
        fun g(v: Float) = cy + (v - cy) * r
        return Quad(f(tlX), g(tlY), f(trX), g(trY), f(brX), g(brY), f(blX), g(blY))
    }

    fun points(): List<Pair<Float, Float>> =
        listOf(tlX to tlY, trX to trY, brX to brY, blX to blY)
}
