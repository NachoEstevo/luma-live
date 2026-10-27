package com.lumalive.probe

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VoiceLauncher(private val startVisit: suspend () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoring: Job? = null
    private val mutable = MutableStateFlow("Apertura por voz pendiente de configuración")
    val state = mutable.asStateFlow()
    fun initialize() {
        if (monitoring?.isActive == true) return
        monitoring = scope.launch {
            Wearables.registrationState.collectLatest { registration ->
                if (registration != RegistrationState.REGISTERED) { mutable.value = "Hey Meta · primero vinculá Luma"; return@collectLatest }
                AutoDeviceSelector().activeDeviceFlow().collectLatest { device ->
                    if (device == null) { mutable.value = "Hey Meta · esperando lentes conectados"; return@collectLatest }
                    // The channel is not resumable after an error. Bounded SDK reconnects, no model calls.
                    repeat(3) { attempt ->
                        connectUntilError()
                        delay((attempt + 1) * 2000L)
                    }
                    mutable.value = "Hey Meta · reconectá los lentes o volvé a abrir Luma"
                }
            }
        }
    }

    private suspend fun connectUntilError() = coroutineScope {
        val current = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
        val failure = CompletableDeferred<Unit>()
        val workers = listOf(
            launch {
                var connected = false
                current.state.collect {
                    mutable.value = "Hey Meta · ${it.name}"; Log.i("LumaVisit", "invocations:state=${it.name}")
                    if (it.name == "STARTED") connected = true
                    if (connected && it.name == "STOPPED") failure.complete(Unit)
                }
            },
            launch { current.errors.collect { mutable.value = "Hey Meta · ${it.name}"; Log.i("LumaVisit", "invocations:error=${it.name}"); failure.complete(Unit) } },
            launch {
                current.invocations.collect { invocation ->
                    when (invocation) {
                        is LaunchApp -> {
                            val started = try { startVisit() }
                                catch (e: CancellationException) { currentCoroutineContext().ensureActive(); false }
                                catch (_: Exception) { false }
                            val delivered = if (started) invocation.responseHandle.sendSuccess(null)
                                else invocation.responseHandle.sendFailure("Abrí Luma y completá permisos y consentimiento antes de iniciar una visita.")
                            Log.i("LumaVisit", "invocation:ack,started=$started,delivered=$delivered")
                        }
                    }
                }
            },
        )
        try { failure.await() }
        finally { workers.forEach { it.cancel() }; current.close() }
    }
}
