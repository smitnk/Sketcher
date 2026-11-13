package com.smitnk.sketcher.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

object ImageLoader {

    /** Decodes a bundled asset, scaled so the longest side is <= [maxSide]. */
    fun loadAsset(context: Context, name: String, maxSide: Int): Bitmap? = try {
        val decoded = context.assets.open(name).use { BitmapFactory.decodeStream(it) }
        if (decoded == null) null else {
            val scale = minOf(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
            if (scale < 1f) Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true,
            ) else decoded
        }
    } catch (e: Exception) {
        null
    }

    /** Decodes [uri], applies EXIF rotation and scales so the longest side is <= [maxSide]. */
    fun load(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val rotation = try {
                resolver.openInputStream(uri)?.use { stream ->
                    when (ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                0f
            }

            val scale = minOf(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
            val matrix = Matrix()
            if (rotation != 0f) matrix.postRotate(rotation)
            if (scale < 1f) matrix.postScale(scale, scale)
            if (matrix.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } catch (e: Exception) {
            null
        }
    }
}
