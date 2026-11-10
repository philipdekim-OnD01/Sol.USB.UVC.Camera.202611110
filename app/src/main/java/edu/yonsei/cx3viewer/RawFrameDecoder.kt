package edu.yonsei.cx3viewer

import android.graphics.Bitmap
import android.graphics.Color

enum class PixelFormat(val bits: Int) { RAW8(8), RAW10(10), RAW12(12) }

/** Diagnostic fixed-size grayscale unpacker. Does not infer line headers or CX3 framing. */
object RawFrameDecoder {
    fun expectedBytes(width: Int, height: Int, format: PixelFormat): Int {
        require(width in 1..8192 && height in 1..8192)
        val pixels = width.toLong() * height
        val bytes = (pixels * format.bits + 7L) / 8L
        require(bytes <= 64L * 1024 * 1024)
        return bytes.toInt()
    }
    fun bitmap(data: ByteArray, width: Int, height: Int, format: PixelFormat): Bitmap {
        val pixels = width * height
        val argb = IntArray(pixels)
        when (format) {
            PixelFormat.RAW8 -> {
                require(data.size >= pixels)
                for (i in 0 until pixels) {
                    val v = data[i].toInt() and 255
                    argb[i] = Color.rgb(v, v, v)
                }
            }
            PixelFormat.RAW10 -> {
                require(data.size >= (pixels.toLong() * 10 + 7).toInt() / 8)
                var src = 0; var dst = 0
                while (dst + 3 < pixels && src + 4 < data.size) {
                    val low = data[src + 4].toInt() and 255
                    for (j in 0..3) {
                        val sample = ((data[src + j].toInt() and 255) shl 2) or ((low shr (j * 2)) and 3)
                        val v = sample shr 2
                        argb[dst++] = Color.rgb(v, v, v)
                    }
                    src += 5
                }
                require(dst == pixels) { "RAW10 requires width*height divisible by 4" }
            }
            PixelFormat.RAW12 -> {
                require(data.size >= ((pixels.toLong() * 12 + 7) / 8).toInt())
                var src = 0; var dst = 0
                while (dst + 1 < pixels && src + 2 < data.size) {
                    val lo = data[src + 2].toInt() and 255
                    val p0 = ((data[src].toInt() and 255) shl 4) or (lo and 15)
                    val p1 = ((data[src + 1].toInt() and 255) shl 4) or ((lo shr 4) and 15)
                    val v0 = p0 shr 4; val v1 = p1 shr 4
                    argb[dst++] = Color.rgb(v0, v0, v0)
                    argb[dst++] = Color.rgb(v1, v1, v1)
                    src += 3
                }
                require(dst == pixels) { "RAW12 requires width*height divisible by 2" }
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }
}
