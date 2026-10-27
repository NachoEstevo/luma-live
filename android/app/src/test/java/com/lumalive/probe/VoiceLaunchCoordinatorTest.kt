package com.lumalive.probe

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceLaunchCoordinatorTest {
    @Test fun rotationCannotCancelApplicationOwnedStartOrCreateDuplicateVisit() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connected = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = VoiceLaunchCoordinator(owner) { starts++; connected.await(); true }
        try {
            val oldActivity = launch { coordinator.request() }
            yield(); assertEquals(1, starts)
            oldActivity.cancelAndJoin()
            val replacement = async { coordinator.request() }
            yield(); assertEquals(1, starts)
            connected.complete(Unit)
            assertTrue(replacement.await())
        } finally { owner.cancel() }
    }
    @Test fun revokedConsentCancelsPendingStartAndFailedPreflightCanBeRetried() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connected = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = VoiceLaunchCoordinator(owner) { starts++; connected.await(); false }
        try {
            val waiting = async { coordinator.request() }
            yield(); coordinator.cancelPending()
            assertFalse(waiting.await())
            connected.complete(Unit)
            assertFalse(coordinator.request())
            assertEquals(2, starts)
        } finally { owner.cancel() }
    }
    @Test fun actionConsumerSurvivesRevokedRequestAndHandlesTheNextAction() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connected = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = VoiceLaunchCoordinator(owner) { starts++; connected.await(); true }
        val actions = kotlinx.coroutines.channels.Channel<Unit>(2)
        val outcomes = mutableListOf<Boolean>()
        try {
            val consumer = launch { for (action in actions) outcomes.add(coordinator.request()) }
            actions.send(Unit); yield(); coordinator.cancelPending(); yield()
            assertTrue(consumer.isActive)
            actions.send(Unit); yield(); connected.complete(Unit); actions.close(); consumer.join()
            assertEquals(listOf(false, true), outcomes)
            assertEquals(2, starts)
        } finally { owner.cancel() }
    }
}
