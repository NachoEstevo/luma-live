package com.lumalive.probe

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkConversationTest {
    private class Effects : VisitExperience {
        override var readyForVisit = true
        override var hasArtwork = false
        override val artworkFresh get() = hasArtwork
        override var lastAnswer = ""
        var owned = false
        var mic = false
        var captures = 0
        var queries = 0
        var opens = 0
        var forgotten = 0
        var speech: ((String, Boolean, Float) -> Unit)? = null
        var closeGate: CompletableDeferred<Unit>? = null
        var modelGate: CompletableDeferred<Unit>? = null
        var speakGate: CompletableDeferred<Unit>? = null
        var readyGate: CompletableDeferred<Unit>? = null
        var pausedGate: CompletableDeferred<Unit>? = null
        var captureGate: CompletableDeferred<Unit>? = null
        var captureCleanupGate: CompletableDeferred<Unit>? = null
        var captureCleanupEntered = false
        var failingCue: VisitCue? = null
        var noticeFails = false
        var farewellFails = false
        val cues = mutableListOf<VisitCue>()
        val said = mutableListOf<String>()
        val saidWithMicOpen = mutableListOf<Boolean>()
        override fun ownVisit(owned: Boolean) { this.owned = owned }
        override suspend fun openListening(onText: (String, Boolean, Float) -> Unit, onFailure: (String) -> Unit) { check(!mic); mic = true; opens++; speech = onText }
        override suspend fun closeListening() { mic = false; closeGate?.await() }
        override fun stopListening() { mic = false }
        override suspend fun selectArtwork() {
            captures++
            try { captureGate?.await(); hasArtwork = true; lastAnswer = "" }
            finally { withContext(NonCancellable) { captureCleanupEntered = true; captureCleanupGate?.await() } }
        }
        override suspend fun describeArtwork(): String { queries++; modelGate?.await(); lastAnswer = "Una pintura con cielo azul."; return lastAnswer }
        override suspend fun askArtwork(question: String): String { queries++; modelGate?.await(); lastAnswer = "Al fondo hay árboles."; return lastAnswer }
        override suspend fun say(text: String) {
            said.add(text); saidWithMicOpen.add(mic)
            if (noticeFails && text.startsWith("No pude continuar")) error("Narración no disponible")
            if (farewellFails && text.startsWith("Terminamos la visita")) error("Despedida no disponible")
            speakGate?.await()
        }
        override suspend fun cue(cue: VisitCue) {
            cues.add(cue)
            check(cue != failingCue) { "Señal no disponible" }
            if (cue == VisitCue.READY) readyGate?.await()
            if (cue == VisitCue.PAUSED) pausedGate?.await()
        }
        override fun interrupt() { }
        override fun forget() { forgotten++; hasArtwork = false; lastAnswer = "" }
        fun hear(text: String, final: Boolean = true, confidence: Float = .95f) { speech?.invoke(text, final, confidence) }
    }
    private fun TestScope.guide(io: Effects, idle: Int = 25) = HandsFreeGuide(io, backgroundScope, { testScheduler.currentTime }, idle)

    @Test fun silenceClosesRecognitionAndHeyResumePreservesArtworkWithoutNewProviderCall() = runTest {
        val io = Effects(); val guide = guide(io); var endings = 0
        guide.start { endings++ }; runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        io.hear("quiero que mires otro cuadro"); runCurrent()
        assertEquals(1, io.captures); assertEquals(1, io.queries)
        advanceTimeBy(25_000); runCurrent()
        assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
        assertFalse(io.mic); assertTrue(io.hasArtwork); assertEquals(0, io.forgotten)
        assertEquals(VisitCue.PAUSED, io.cues.last())
        io.hear("quiero otra obra"); runCurrent() // delayed SDK delivery while closed cannot act
        assertEquals(1, io.captures)
        guide.start { endings++ }; runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        assertEquals(1, guide.state.value.turns); assertEquals(1, io.queries); assertEquals(1, io.captures)
        io.hear("¿Qué hay al fondo?"); runCurrent()
        assertEquals(2, io.queries); assertEquals(1, io.captures)
        io.hear("terminemos la visita"); runCurrent()
        assertFalse(guide.state.value.active); assertFalse(io.hasArtwork); assertEquals(1, endings)
    }

    @Test fun idleWindowStartsAfterReplyAndNeverExpiresWhileModelOrNarrationIsBusy() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        io.modelGate = CompletableDeferred()
        io.hear("mirá esta obra"); runCurrent()
        assertEquals(VisitPhase.THINKING, guide.state.value.phase)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(VisitPhase.THINKING, guide.state.value.phase)
        io.speakGate = CompletableDeferred()
        io.modelGate!!.complete(Unit); runCurrent()
        assertEquals(VisitPhase.SPEAKING, guide.state.value.phase)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(VisitPhase.SPEAKING, guide.state.value.phase)
        io.speakGate!!.complete(Unit); runCurrent()
        advanceTimeBy(24_999); runCurrent(); assertTrue(io.mic)
        advanceTimeBy(1); runCurrent(); assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
    }

    @Test fun partialSpeechExtendsSilenceWindowButOnlyFinalSpeechCanCallModel() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        advanceTimeBy(24_000); runCurrent()
        io.hear("quiero que mires", final = false); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertTrue(io.mic); assertEquals(0, io.queries)
        io.hear("quiero que mires otro cuadro"); runCurrent()
        assertEquals(1, io.queries)
    }

    @Test fun pauseCueWaitsForSdkCloseAndActivationDuringClosingResumesOnce() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        io.closeGate = CompletableDeferred()
        guide.pause(); runCurrent()
        assertEquals(VisitPhase.PAUSING, guide.state.value.phase)
        assertFalse(io.cues.contains(VisitCue.PAUSED))
        guide.start { }; runCurrent()
        io.closeGate!!.complete(Unit); runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        assertEquals(2, io.opens)
        assertEquals(0, io.captures); assertEquals(0, io.queries)
    }

    @Test fun uncertainRecognitionRepairsOnceThenPausesWithoutSpendingOrCapturing() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        io.hear("otra obra", confidence = .2f); runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        assertTrue(io.said.any { it.contains("No llegué a entenderte") })
        advanceTimeBy(3000)
        io.hear("mira este cuadro", confidence = .2f); runCurrent()
        assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
        assertEquals(0, io.queries); assertEquals(0, io.captures)
    }

    @Test fun adjustableWindowAndHardLifetimeKeepPausedContextFinite() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.configureIdleWindow(60)
        guide.start { }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        advanceTimeBy(59_999); runCurrent(); assertTrue(io.mic)
        advanceTimeBy(1); runCurrent(); assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
        advanceTimeBy(HandsFreeGuide.MAX_VISIT_MS - 60_000); runCurrent()
        assertFalse(guide.state.value.active); assertFalse(io.hasArtwork); assertFalse(io.owned)
    }

    @Test fun ambiguousRequestRequiresConfirmationAndNegativeAnswerKeepsExistingWork() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        io.hear("¿Lo vemos?"); runCurrent()
        assertEquals(1, io.captures)
        io.hear("no"); runCurrent()
        assertTrue(io.hasArtwork); assertEquals(1, io.captures); assertEquals(1, io.queries)
    }

    @Test fun explicitPauseAndRepeatedWakeDoNotResetQuotaOrReplayOldIdleTimer() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        io.hear("esperame un momento"); runCurrent()
        assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
        advanceTimeBy(10_000); runCurrent()
        guide.start { }; guide.start { }; runCurrent()
        assertEquals(1, guide.state.value.turns)
        advanceTimeBy(20_000); runCurrent(); assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        advanceTimeBy(5000); runCurrent(); assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
    }

    @Test fun speakingDuringReadySoundCannotCreateAnAlreadyExpiredIdleDeadline() = runTest {
        advanceTimeBy(1_000_000)
        val io = Effects().apply { readyGate = CompletableDeferred() }; val guide = guide(io)
        guide.start { }; runCurrent()
        io.hear("quiero que mires", final = false); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        io.readyGate!!.complete(Unit); runCurrent()
        assertTrue(io.mic)
    }

    @Test fun explicitPrivacyStopDoesNotDependOnAsrConfidence() = runTest {
        val io = Effects(); val guide = guide(io)
        guide.start { }; runCurrent()
        io.hear("no escuches", confidence = .2f); runCurrent()
        assertEquals(VisitPhase.PAUSED, guide.state.value.phase)
        assertFalse(io.mic); assertEquals(0, io.queries)
    }

    @Test fun pausingPublishesBeforeCancelledCaptureCleanupAndQueuesHeyDuringThatCleanup() = runTest {
        val io = Effects().apply { captureGate = CompletableDeferred(); captureCleanupGate = CompletableDeferred() }
        val guide = guide(io)
        guide.start { }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        assertEquals(VisitPhase.CAPTURING, guide.state.value.phase)
        guide.pause()
        assertEquals(VisitPhase.PAUSING, guide.state.value.phase)
        assertFalse(guide.state.value.listening)
        runCurrent()
        assertTrue(io.captureCleanupEntered)
        guide.start { }; runCurrent()
        assertEquals(VisitPhase.PAUSING, guide.state.value.phase)
        assertEquals(1, io.opens)
        assertFalse(io.cues.contains(VisitCue.PAUSED))
        io.captureCleanupGate!!.complete(Unit); runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        assertEquals(2, io.opens); assertEquals(1, io.captures); assertEquals(0, io.queries)
    }

    @Test fun pausedStateWaitsUntilClosedMicCueHasFinished() = runTest {
        val io = Effects().apply { pausedGate = CompletableDeferred() }; val guide = guide(io)
        guide.start { }; runCurrent()
        guide.pause(); runCurrent()
        assertFalse(io.mic)
        assertEquals(VisitCue.PAUSED, io.cues.last())
        assertEquals(VisitPhase.PAUSING, guide.state.value.phase)
        guide.start { }; runCurrent()
        assertEquals(1, io.opens)
        io.pausedGate!!.complete(Unit); runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        assertEquals(2, io.opens)
    }

    @Test fun firstHeyAfterElapsedExpiryStartsCleanWithoutCallingOldServiceStop() = runTest {
        var elapsed = 0L
        val io = Effects(); val guide = HandsFreeGuide(io, backgroundScope, { elapsed })
        var oldEndings = 0; var newEndings = 0
        guide.start { oldEndings++ }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        guide.pause(); runCurrent()
        assertTrue(io.hasArtwork); assertEquals(1, guide.state.value.turns)
        elapsed = HandsFreeGuide.MAX_VISIT_MS + 1 // deep sleep: scheduler has not advanced
        guide.start { newEndings++ }; runCurrent()
        assertEquals(VisitPhase.LISTENING, guide.state.value.phase)
        assertFalse(io.hasArtwork); assertEquals("", io.lastAnswer)
        assertEquals(0, guide.state.value.turns)
        assertEquals(0, oldEndings); assertEquals(0, newEndings)
        io.hear("mirá esta obra"); runCurrent()
        assertEquals(2, io.captures); assertEquals(2, io.queries); assertEquals(1, guide.state.value.turns)
        advanceTimeBy(HandsFreeGuide.MAX_VISIT_MS); runCurrent()
        assertTrue(guide.state.value.active); assertTrue(io.hasArtwork)
        assertEquals(0, oldEndings); assertEquals(0, newEndings)
    }

    @Test fun elapsedExpiryOnWearerInputPurgesBeforeAnotherModelQuestion() = runTest {
        var elapsed = 0L
        val io = Effects(); val guide = HandsFreeGuide(io, backgroundScope, { elapsed }); var endings = 0
        guide.start { endings++ }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        elapsed = HandsFreeGuide.MAX_VISIT_MS
        io.hear("Qué hay al fondo"); runCurrent()
        assertFalse(guide.state.value.active); assertFalse(io.hasArtwork); assertEquals("", io.lastAnswer)
        assertEquals(1, io.queries); assertEquals(1, endings)
    }

    @Test fun foregroundExpiryPurgesPausedContextEvenWhenDelayDidNotRun() = runTest {
        var elapsed = 0L
        val io = Effects(); val guide = HandsFreeGuide(io, backgroundScope, { elapsed }); var endings = 0
        guide.start { endings++ }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        guide.pause(); runCurrent()
        elapsed = HandsFreeGuide.MAX_VISIT_MS + 1
        assertTrue(guide.expireOverdueContext())
        assertFalse(guide.state.value.active); assertFalse(io.hasArtwork); assertFalse(io.owned)
        assertEquals(1, endings)
        assertFalse(guide.expireOverdueContext()); assertEquals(1, endings)
    }

    @Test fun failedReadyCueClosesMicAndGivesOneBoundedFailureNoticeWithoutPaidQuestion() = runTest {
        val io = Effects().apply { failingCue = VisitCue.READY }; val guide = guide(io); var endings = 0
        guide.start { endings++ }; runCurrent()
        assertFalse(guide.state.value.active); assertFalse(io.mic); assertFalse(io.hasArtwork)
        assertEquals(0, io.queries); assertEquals(0, io.captures); assertEquals(1, endings)
        assertEquals(1, io.said.count { it.startsWith("No pude continuar") })
        assertFalse(io.saidWithMicOpen.last())
        io.hear("Te escucho"); runCurrent(); assertEquals(0, io.queries)
    }

    @Test fun failureNoticeCannotRecurseAndDoesNotSpeakIfMicClosureIsUnconfirmed() = runTest {
        val io = Effects().apply { failingCue = VisitCue.READY; noticeFails = true }; val failingGuide = guide(io); var endings = 0
        failingGuide.start { endings++ }; runCurrent()
        assertFalse(failingGuide.state.value.active); assertEquals(1, endings)
        assertEquals(1, io.said.count { it.startsWith("No pude continuar") })
        val closing = Effects().apply { failingCue = VisitCue.READY; closeGate = CompletableDeferred() }
        val closingGuide = guide(closing)
        closingGuide.start { }; runCurrent()
        assertEquals(VisitPhase.ENDING, closingGuide.state.value.phase)
        advanceTimeBy(5000); runCurrent()
        assertFalse(closingGuide.state.value.active)
        assertFalse(closing.said.any { it.startsWith("No pude continuar") })
    }

    @Test fun aFailedFarewellStillReleasesContextAndEndsTheService() = runTest {
        val io = Effects().apply { farewellFails = true }; val guide = guide(io); var endings = 0
        guide.start { endings++ }; runCurrent()
        io.hear("mirá esta obra"); runCurrent()
        io.hear("terminemos la visita"); runCurrent()
        assertFalse(guide.state.value.active); assertFalse(io.mic); assertFalse(io.hasArtwork)
        assertEquals(1, endings)
    }
}
