package com.junglebell.mobile.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Downloads meal post images for the meal widget and stores downscaled JPEG
 * copies in the app files directory.
 *
 * Source images are multi-megapixel photos (3000px+, 300~500KB); the widget
 * process must never decode them at full size, so every image is reduced to a
 * ~720px JPEG once at download time. Files are named by the media sha (or the
 * sha-256 of the url) so they are immutable and never re-downloaded.
 */
object MealImageStore {

    private const val MAX_DIMENSION = 720
    private const val JPEG_QUALITY = 80

    private fun dir(context: Context): File =
        File(context.getApplicationContext().filesDir, "meal-images").apply { mkdirs() }

    /** Deterministic local file for the image; may not exist yet. */
    fun fileFor(context: Context, image: MealImage): File? {
        if (image.url.isBlank()) return null
        val name = image.sha?.takeIf { it.length >= 8 } ?: sha256(image.url)
        val ext = image.extension?.trim()?.takeIf { it.isNotBlank() } ?: "jpg"
        return File(dir(context), "$name.$ext")
    }

    /**
     * Returns the local file for [image], downloading + downscaling it when it
     * is not cached yet. Best-effort: any failure yields null and leaves the
     * widget on its text-only fallback.
     */
    suspend fun ensureDownloaded(context: Context, image: MealImage): File? {
        val file = fileFor(context, image) ?: return null
        if (file.exists() && file.length() > 0) return file
        return try {
            withContext(Dispatchers.IO) {
                val response = PublicApiClient.httpClient
                    .newCall(Request.Builder().url(image.url).build())
                    .execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext null
                }
                val bytes = response.body?.bytes()
                response.close()
                bytes ?: return@withContext null
                val scaled = downscale(bytes) ?: return@withContext null
                val tmp = File(file.parentFile, file.name + ".tmp")
                FileOutputStream(tmp).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                scaled.recycle()
                if (!tmp.renameTo(file)) {
                    // Concurrent run finished first; fall back to a copy.
                    if (!file.exists()) {
                        tmp.copyTo(file, overwrite = true)
                    }
                    tmp.delete()
                }
                file
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downscale(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
