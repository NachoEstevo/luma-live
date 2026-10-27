package com.lumalive.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

fun framePreview(source: ByteArray, width: Int, height: Int): Bitmap {
    val nv21 = i420ToNv21(source, width, height)
    val jpeg = ByteArrayOutputStream()
    check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), 65, jpeg))
    val bytes = jpeg.toByteArray()
    return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
}
