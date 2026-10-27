package com.lumalive.probe

import org.junit.Assert.*
import org.junit.Test

class ProbeTest {
    @Test fun planarChromaBecomesVuWithoutChangingSource() {
        val source = byteArrayOf(1, 2, 3, 4, 10, 20)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 20, 10), i420ToNv21(source, 2, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 10, 20), source)
    }
    @Test(expected = IllegalArgumentException::class) fun unexpectedLayoutIsRejected() {
        i420ToNv21(ByteArray(8), 2, 2)
    }
    @Test fun timingFreezesAfterStopAndNextRunIsIndependent() {
        val metrics = ProbeMetrics(100)
        metrics.frame(350, 6, 2, 2)
        metrics.frame(600, 6, 2, 2)
        assertEquals(250L, metrics.firstFrameMs)
        assertEquals(4.0, metrics.fps(600), 0.001)
        metrics.stop(1100)
        metrics.frame(1200, 6, 2, 2)
        assertEquals(1000L, metrics.elapsed(2000))
        assertEquals(2L, metrics.frames)
        assertEquals(12L, metrics.bytes)
        assertNull(ProbeMetrics(2000).firstFrameMs)
        assertFalse(metrics.csv(9000).contains("null"))
    }
    @Test fun stopRequestIsNotReportedAsDeviceConfirmation() {
        val metrics = ProbeMetrics(0, 78)
        metrics.stop(1000, 77)
        assertFalse(metrics.stopConfirmed)
        assertTrue(metrics.csv(1000).endsWith(",78,77,false\n"))
        metrics.confirmStopped()
        assertTrue(metrics.stopConfirmed)
        assertTrue(metrics.csv(2000).endsWith(",78,77,true\n"))
    }
}
