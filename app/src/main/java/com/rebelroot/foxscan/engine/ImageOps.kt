package com.rebelroot.foxscan.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageOps {

    enum class Filter { ORIGINAL, MAGIC, GRAY, BW }

    /** 4-point perspective warp: src quad (normalized 0..1 on bitmap) -> full bitmap rect. */
    fun warp(src: Bitmap, q: Quad, inset: Float = 1.0f): Bitmap {
        val qq = q.inset(inset)
        val w = src.width; val h = src.height
        val outW = max(
            16,
            ((dist(qq.tlX * w, qq.tlY * h, qq.trX * w, qq.trY * h) +
                dist(qq.blX * w, qq.blY * h, qq.brX * w, qq.brY * h)) / 2f).toInt(),
        )
        val outH = max(
            16,
            ((dist(qq.tlX * w, qq.tlY * h, qq.blX * w, qq.blY * h) +
                dist(qq.trX * w, qq.trY * h, qq.brX * w, qq.brY * h)) / 2f).toInt(),
        )
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(
                qq.tlX * w, qq.tlY * h,
                qq.trX * w, qq.trY * h,
                qq.brX * w, qq.brY * h,
                qq.blX * w, qq.blY * h,
            ),
            0,
            floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()),
            0, 4,
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, m, paint)
        return out
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        max(1f, kotlin.math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)))

    fun applyFilter(src: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.ORIGINAL) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val paint = Paint()
        when (filter) {
            Filter.GRAY, Filter.MAGIC -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(cm)
            }
            Filter.BW -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                // stretch contrast around the midpoint, then hard threshold via color matrix
                val cm2 = ColorMatrix(
                    floatArrayOf(
                        2.4f, 0f, 0f, 0f, -255f * 0.72f,
                        0f, 2.4f, 0f, 0f, -255f * 0.72f,
                        0f, 0f, 2.4f, 0f, -255f * 0.72f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                cm2.setConcat(cm2, cm)
                paint.colorFilter = ColorMatrixColorFilter(cm2)
            }
            Filter.ORIGINAL -> {}
        }
        c.drawBitmap(out, 0f, 0f, paint)
        if (filter == Filter.MAGIC) autoContrast(out)
        return out
    }

    /** Histogram stretch (1st..99th percentile) on luminance -> "magic" cleanup. */
    private fun autoContrast(b: Bitmap) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (p in px) {
            val l = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)).toInt().coerceIn(0, 255)
            hist[l]++
        }
        val total = px.size
        var lo = 0; var hi = 255; var acc = 0
        for (i in 0..255) { acc += hist[i]; if (acc >= total * 0.01f) { lo = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc >= total * 0.01f) { hi = i; break } }
        if (hi <= lo) return
        val lut = IntArray(256)
        for (i in 0..255) lut[i] = ((i - lo) * 255f / (hi - lo)).coerceIn(0f, 255f).toInt()
        for (i in px.indices) {
            val p = px[i]
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val bch = lut[p and 0xFF]
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bch
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun saveJpeg(b: Bitmap, file: File, quality: Int = 90): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    /** Pages -> single A4 PDF. Returns the output file. */
    fun exportPdf(pages: List<Bitmap>, out: File): File {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842
        pages.forEachIndexed { i, bmp ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create())
            val canvas = page.canvas
            val margin = 24f
            val availW = pageW - 2 * margin; val availH = pageH - 2 * margin
            val scale = min(availW / bmp.width, availH / bmp.height)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            val dx = (pageW - dw) / 2f; val dy = (pageH - dh) / 2f
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), paint)
            doc.finishPage(page)
        }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Run the detector on a full-res bitmap (downsampled internally). */
    fun detectQuadOnBitmap(src: Bitmap): Quad? {
        val maxSide = 320
        val s = min(1f, maxSide / max(src.width, src.height).toFloat())
        val small = Bitmap.createScaledBitmap(
            src, max(1, (src.width * s).toInt()), max(1, (src.height * s).toInt()), true,
        )
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val luma = ByteArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            luma[i] = ((0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)).toInt()).toByte()
        }
        val q = QuadDetector.detect(luma, w, w, h, 0) ?: return null
        return q
    }
}
