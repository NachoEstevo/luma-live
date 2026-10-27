package com.lumalive.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.*

/** Original short sounds, media-routed like narration; no microphone, files or network. */
class VisitCues(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val ids = mutableMapOf<VisitCue, Int>()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()

    init {
        var loaded = 0
        pool.setOnLoadCompleteListener { _, _, status ->
            if (status != 0) ready.complete(false)
            else if (++loaded == 3) ready.complete(true)
        }
        ids[VisitCue.READY] = pool.load(context, R.raw.luma_ready, 1)
        ids[VisitCue.PAUSED] = pool.load(context, R.raw.luma_paused, 1)
        ids[VisitCue.CAPTURED] = pool.load(context, R.raw.luma_captured, 1)
    }

    suspend fun play(cue: VisitCue): Boolean {
        if (withTimeoutOrNull(1500) { ready.await() } != true) return false
        val stream = pool.play(ids.getValue(cue), .55f, .55f, 1, 0, 1f)
        if (stream == 0) return false
        try { delay(if (cue == VisitCue.CAPTURED) 120 else 230) }
        finally { pool.stop(stream) }
        return true
    }
}
