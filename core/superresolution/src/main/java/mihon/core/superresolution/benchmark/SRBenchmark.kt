package mihon.core.superresolution.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import mihon.core.superresolution.SuperResolutionManager

class SRBenchmark(private val manager: SuperResolutionManager) {

    suspend fun run(context: Context): BenchmarkResult {
        val synthetic = createSyntheticImage()
        val (ms, result, srDidRun) = runOnce(synthetic)
        synthetic.recycle()

        if (!srDidRun) {
            result.recycle()
            return BenchmarkResult(deviceTier = DeviceTier.UNKNOWN)
        }

        val finalMs = if (ms < 300) {
            val real = loadAssetImage(context, "benchmark_sample.png")
            val (ms2, result2, ok2) = runOnce(real)
            real.recycle()
            result.recycle()
            if (!ok2) {
                result2.recycle()
                return BenchmarkResult(deviceTier = DeviceTier.UNKNOWN)
            }
            result2.recycle()
            ms2
        } else {
            result.recycle()
            ms
        }

        return BenchmarkResult(
            inferenceMs = finalMs,
            deviceTier = classifyTier(finalMs),
            scale = manager.activeScale,
            modelKey = manager.activeModel?.key,
        )
    }

    private suspend fun runOnce(input: Bitmap): Triple<Long, Bitmap, Boolean> {
        val version = manager.currentModelVersion()
        val scale = manager.activeScale
        val startTime = System.nanoTime()
        val result = manager.process(input, version)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        val srDidRun = result.width == input.width * scale &&
            result.height == input.height * scale
        return Triple(elapsedMs, result, srDidRun)
    }

    private fun classifyTier(ms: Long): DeviceTier = when {
        ms < 3000 -> DeviceTier.FAST
        ms < 8000 -> DeviceTier.MID
        else -> DeviceTier.SLOW
    }

    private fun createSyntheticImage(width: Int = 800, height: Int = 1100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.LTGRAY, Color.DKGRAY, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        for (i in 1 until 10) {
            val x = i * width / 10f
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
        for (i in 1 until 10) {
            val y = i * height / 10f
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }
        return bitmap
    }

    private fun loadAssetImage(context: Context, filename: String): Bitmap {
        return context.assets.open(filename).use { BitmapFactory.decodeStream(it) }
    }
}
