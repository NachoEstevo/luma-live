package com.lumalive.probe

/** Times are monotonic phone times, not optical or network latency measurements. */
class ProbeMetrics(private val startedAtMs: Long, private val batteryStart: Int? = null) {
    var frames: Long = 0; private set
    var bytes: Long = 0; private set
    var firstFrameMs: Long? = null; private set
    var width: Int = 0; private set
    var height: Int = 0; private set
    private var endedAtMs: Long? = null
    private var batteryEnd: Int? = null
    var stopConfirmed: Boolean = false; private set

    fun frame(nowMs: Long, byteCount: Int, width: Int, height: Int) {
        if (endedAtMs != null) return
        if (firstFrameMs == null) firstFrameMs = (nowMs - startedAtMs).coerceAtLeast(0)
        frames++
        bytes += byteCount.coerceAtLeast(0)
        this.width = width; this.height = height
    }
    fun elapsed(nowMs: Long) = ((endedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0)
    fun fps(nowMs: Long): Double = if (elapsed(nowMs) > 0) frames * 1000.0 / elapsed(nowMs) else 0.0
    fun stop(nowMs: Long, battery: Int? = null) {
        if (endedAtMs == null) { endedAtMs = nowMs; batteryEnd = battery }
    }
    fun confirmStopped() { stopConfirmed = true }
    fun csv(nowMs: Long) = "sdk,quality,requested_fps,elapsed_to_stop_request_ms,received_frames,decoded_bytes,first_frame_ms,width,height,battery_start_pct,battery_at_stop_request_pct,stop_confirmed\n" +
        "1.0.1,LOW,7,${elapsed(nowMs)},$frames,$bytes,${firstFrameMs ?: ""},$width,$height,${batteryStart ?: ""},${batteryEnd ?: ""},$stopConfirmed\n"
}
