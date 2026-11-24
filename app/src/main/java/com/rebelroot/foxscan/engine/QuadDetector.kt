package com.rebelroot.foxscan.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin document quad detector (no OpenCV, no ML Kit).
 * Pipeline on the Y plane (downscaled to ~160x120):
 *   Sobel gradient -> hysteresis threshold -> connected edge mask ->
 *   4 extreme corners of the dominant edge mass -> convexity/area validation.
 * Noisiness is expected; QuadStreamAnalyzer smooths it.
 */
object QuadDetector {

    const val DW = 160
    const val DH = 120

    /** luma: Y plane bytes (rowStride may exceed width). rotation: 0/90/180/270. */
    fun detect(luma: ByteArray, rowStride: Int, srcW: Int, srcH: Int, rotation: Int): Quad? {
        val sw = srcW / 2
        val sh = srcH / 2
        if (sw < 8 || sh < 8) return null
        // sample grid to DW x DH (portrait-adjusted below via rotation)
        val gw = DW; val gh = DH
        val g = FloatArray(gw * gh)
        var i = 0
        for (gy in 0 until gh) {
            val sy = (gy * sh) / gh
            for (gx in 0 until gw) {
                val sx = (gx * sw) / gw
                g[i++] = luma[sy * rowStride + sx].toInt().and(0xFF).toFloat()
            }
        }
        // rotate grid to upright orientation if needed (90/270 swap dims)
        var w = gw; var h = gh
        var p = g
        when (rotation) {
            90 -> { p = rot90(g, gw, gh, false); w = gh; h = gw }
            180 -> { p = rot180(g); }
            270 -> { p = rot90(g, gw, gh, true); w = gh; h = gw }
        }
        // Sobel
        val mag = FloatArray(w * h)
        var sum = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val o = y * w + x
                val gx = (p[o - w + 1] + 2f * p[o + 1] + p[o + w + 1]) -
                    (p[o - w - 1] + 2f * p[o - 1] + p[o + w - 1])
                val gy = (p[o + w - 1] + 2f * p[o + w] + p[o + w + 1]) -
                    (p[o - w - 1] + 2f * p[o - w] + p[o - w + 1])
                val m = sqrt(gx * gx + gy * gy)
                mag[o] = m; sum += m
            }
        }
        val mean = sum / ((w - 2) * (h - 2))
        val strong = max(2.5f * mean, 40f)
        val weak = strong * 0.4f
        val edge = ByteArray(w * h)
        for (o in mag.indices) {
            edge[o] = when {
                mag[o] >= strong -> 2
                mag[o] >= weak -> 1
                else -> 0
            }
        }
        // hysteresis: keep weak pixels connected to strong (1 pass, cheap)
        val grown = edge.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val o = y * w + x
            if (grown[o].toInt() == 1) {
                if (edge[o - 1] == 2.toByte() || edge[o + 1] == 2.toByte() ||
                    edge[o - w] == 2.toByte() || edge[o + w] == 2.toByte()
                ) grown[o] = 2
            }
        }
        // find the largest connected 8-connected component of grown==2
        val label = IntArray(w * h)
        var best = -1; var bestSize = 0; var nextLabel = 1
        val stack = IntArray(w * h)
        for (o0 in grown.indices) {
            if (grown[o0] == 2.toByte() && label[o0] == 0) {
                var sp = 0; stack[sp++] = o0; label[o0] = nextLabel
                var size = 0
                while (sp > 0) {
                    val o = stack[--sp]; size++
                    val x = o % w; val y = o / w
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val no = ny * w + nx
                        if (grown[no] == 2.toByte() && label[no] == 0) {
                            label[no] = nextLabel; stack[sp++] = no
                        }
                    }
                }
                if (size > bestSize) { bestSize = size; best = nextLabel }
                nextLabel++
            }
        }
        if (best <= 0 || bestSize < (w * h) / 40) return null
        // extreme corners of the component: min/max of (x+y) and (x-y)
        var minSumX = 0; var minSumY = 0; var maxSumX = 0; var maxSumY = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE
        var minDifX = 0; var minDifY = 0; var maxDifX = 0; var maxDifY = 0
        var minDif = Int.MAX_VALUE; var maxDif = Int.MIN_VALUE
        for (o in label.indices) {
            if (label[o] != best) continue
            val x = o % w; val y = o / w
            val s = x + y; val d = x - y
            if (s < minSum) { minSum = s; minSumX = x; minSumY = y }
            if (s > maxSum) { maxSum = s; maxSumX = x; maxSumY = y }
            if (d < minDif) { minDif = d; minDifX = x; minDifY = y }
            if (d > maxDif) { maxDif = d; maxDifX = x; maxDifY = y }
        }
        // order TL,TR,BR,BL
        val cands = listOf(
            minSumX to minSumY,   // top-left-ish
            minDifX to minDifY,   // top-right-ish
            maxSumX to maxSumY,   // bottom-right-ish
            maxDifX to maxDifY,   // bottom-left-ish
        )
        val tl = cands[0]; val tr = cands[1]; val br = cands[2]; val bl = cands[3]
        val q = Quad(
            tl.first / w.toFloat(), tl.second / h.toFloat(),
            tr.first / w.toFloat(), tr.second / h.toFloat(),
            br.first / w.toFloat(), br.second / h.toFloat(),
            bl.first / w.toFloat(), bl.second / h.toFloat(),
        )
        if (!q.isValid || !q.convex()) return null
        if (q.area() < 0.08f) return null
        // side sanity: each edge at least 8% of the smaller dimension
        val pts = q.points()
        val dims = listOf(w, h)
        val minDim = min(dims[0], dims[1]).toFloat()
        for (i in 0 until 4) {
            val a = pts[i]; val b = pts[(i + 1) % 4]
            val dx = (a.first - b.first) * w
            val dy = (a.second - b.second) * h
            if (sqrt(dx * dx + dy * dy) < 0.08f * minDim) return null
        }
        return q
    }

    private fun rot90(src: FloatArray, w: Int, h: Int, ccw: Boolean): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = src[y * w + x]
            if (ccw) out[(w - 1 - x) * h + y] = v else out[x * h + (h - 1 - y)] = v
        }
        return out
    }

    private fun rot180(src: FloatArray): FloatArray {
        val out = FloatArray(src.size)
        val n = src.size
        for (i in 0 until n) out[i] = src[n - 1 - i]
        return out
    }
}
