package com.sovereignedge.pocketpantry.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** Recipe photos are stored and synced as JPEGs no larger than [MAX_EDGE] on the long side. */
object Photos {
    const val MAX_EDGE = 1280

    fun fromUri(context: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = context.contentResolver.openInputStream(uri)?.use { runCatching { ExifInterface(it).rotationDegrees }.getOrDefault(0) } ?: 0
        return encode(if (rotation != 0) bitmap.rotate(rotation) else bitmap)
    }

    fun fromBitmap(bitmap: Bitmap): ByteArray = encode(bitmap)

    private fun encode(source: Bitmap): ByteArray {
        val scale = MAX_EDGE.toFloat() / maxOf(source.width, source.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap = Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
