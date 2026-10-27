package com.lumalive.probe

import kotlinx.coroutines.*

/** Intent and SDK action launches share a request whose owner outlives an Activity. Main-thread use. */
class VoiceLaunchCoordinator(private val owner: CoroutineScope, private val start: suspend () -> Boolean) {
    private var pending: Deferred<Boolean>? = null
    suspend fun request(): Boolean {
        val shared = pending?.takeIf { it.isActive } ?: owner.async { start() }.also { pending = it }
        return try { shared.await() }
        catch (e: CancellationException) { currentCoroutineContext().ensureActive(); false }
    }
    fun cancelPending() { pending?.cancel(); pending = null }
}
