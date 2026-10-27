package com.lumalive.probe

import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class SpeechLifecycleTest {
    @Test fun newSessionWaitsForSdkStopConfirmation() = runBlocking {
        val state = MutableStateFlow(DeviceSessionState.STOPPING)
        val admission = async { confirmSpeechStopped(state); true }
        yield()
        assertFalse(admission.isCompleted)
        state.value = DeviceSessionState.STOPPED
        assertTrue(admission.await())
    }
    @Test fun unconfirmedStopFailsClosedInsteadOfAdmittingAnotherSession() = runBlocking {
        val state = MutableStateFlow(DeviceSessionState.STOPPING)
        try { confirmSpeechStopped(state, 30); fail("A closing session cannot admit new SDK work") }
        catch (_: TimeoutCancellationException) { assertEquals(DeviceSessionState.STOPPING, state.value) }
    }
}
