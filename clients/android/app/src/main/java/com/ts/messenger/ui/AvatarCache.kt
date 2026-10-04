package com.ts.messenger.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap

/** Profile pictures already downloaded, by user id (kept in memory only, cleared on sign-out). */
object AvatarCache {
    val images = mutableStateMapOf<String, ImageBitmap>()

    /** The avatar URL each cached picture came from, so a changed picture is fetched again. */
    val urls = HashMap<String, String>()

    fun clear() {
        images.clear()
        urls.clear()
    }
}

/**
 * Decodes an image and returns a centre-cropped square of [size] pixels. Re-encoding like this also
 * drops metadata (location, camera model) before a picture is uploaded.
 */
fun decodeAvatar(bytes: ByteArray, size: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 20_000 || bounds.outHeight > 20_000) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    return Bitmap.createScaledBitmap(square, size, size, true)
}
