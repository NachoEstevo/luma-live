package com.lumalive.probe

enum class VisitPhase { IDLE, CONNECTING, LISTENING, CAPTURING, THINKING, SPEAKING, PAUSING, PAUSED, ENDING }
enum class VisitCue { READY, PAUSED, CAPTURED }

data class VisitState(
    val phase: VisitPhase = VisitPhase.IDLE,
    val listening: Boolean = false,
    val status: String = "Una obra, una conversación. Llamame cuando quieras empezar.",
    val turns: Int = 0,
    val hasArtwork: Boolean = false,
    val idleSeconds: Int = 25,
) { val active: Boolean get() = phase != VisitPhase.IDLE }

/** Concrete effects at the boundary between the visit's decisions and Android/SDK resources. */
interface VisitExperience {
    val readyForVisit: Boolean
    val hasArtwork: Boolean
    val artworkFresh: Boolean
    val lastAnswer: String
    fun ownVisit(owned: Boolean)
    suspend fun openListening(onText: (String, Boolean, Float) -> Unit, onFailure: (String) -> Unit)
    suspend fun closeListening()
    fun stopListening()
    suspend fun selectArtwork()
    suspend fun describeArtwork(): String
    suspend fun askArtwork(question: String): String
    suspend fun say(text: String)
    suspend fun cue(cue: VisitCue)
    fun interrupt()
    fun forget()
}
