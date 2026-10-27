package com.lumalive.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun wav16k(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size <= 384000 && pcm.size % 2 == 0)
    return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(pcm.size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}
