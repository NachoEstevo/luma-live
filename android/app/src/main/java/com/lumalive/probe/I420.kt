package com.lumalive.probe

/** Converts packed planar Y/U/V to Android YuvImage's packed Y/VU. No shared SDK buffer mutation. */
fun i420ToNv21(source: ByteArray, width: Int, height: Int): ByteArray {
    require(width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0)
    val ySize = width * height
    val chroma = ySize / 4
    require(source.size == ySize + 2 * chroma) { "Unexpected decoded frame layout" }
    val output = ByteArray(source.size)
    source.copyInto(output, endIndex = ySize)
    for (i in 0 until chroma) {
        output[ySize + 2 * i] = source[ySize + chroma + i]
        output[ySize + 2 * i + 1] = source[ySize + i]
    }
    return output
}
