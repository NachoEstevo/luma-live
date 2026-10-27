package com.lumalive.probe

data class MuseumTurn(val role: String, val text: String)

class MuseumHistory {
    private val turns = mutableListOf<MuseumTurn>()
    fun remember(question: String, answer: String) {
        turns.add(MuseumTurn("user", question.take(1000)))
        turns.add(MuseumTurn("assistant", answer.take(1500)))
        while (turns.size > 4) turns.removeAt(0)
    }
    fun snapshot() = turns.toList()
    fun clear() = turns.clear()
}
