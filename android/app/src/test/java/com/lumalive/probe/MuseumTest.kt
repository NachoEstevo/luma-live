package com.lumalive.probe

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MuseumTest {
    @Test fun wavIsBoundedMonoPcmWithActualPayloadLength() {
        val bytes = wav16k(ByteArray(32000))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals(32036, buffer.getInt(4))
        assertEquals(1, buffer.getShort(22).toInt()); assertEquals(16000, buffer.getInt(24)); assertEquals(32000, buffer.getInt(40))
    }
    @Test(expected = IllegalArgumentException::class) fun tooLongVoiceIsRejected() { wav16k(ByteArray(384002)) }
    @Test fun newArtworkCannotInheritPreviousConversation() {
        val history = MuseumHistory()
        repeat(4) { history.remember("Pregunta $it", "Respuesta $it") }
        assertEquals(4, history.snapshot().size)
        assertEquals("Pregunta 2", history.snapshot().first().text)
        history.clear(); assertTrue(history.snapshot().isEmpty())
    }
}
