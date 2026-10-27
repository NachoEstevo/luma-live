package com.lumalive.probe

import org.junit.Assert.*
import org.junit.Test

class VisitVoicePolicyTest {
    @Test fun commandsAreExactAndAccentInsensitive() {
        assertEquals(VisitCommand.CAPTURE, visitCommand("Luma, mirá esta obra, por favor"))
        assertEquals(VisitCommand.QUIET, visitCommand("¡Pará!"))
        assertEquals(VisitCommand.QUESTION, visitCommand("¿Para qué usó ese color?"))
        assertEquals(VisitCommand.QUESTION, visitCommand("¿Por qué hay silencio en esta obra?"))
        assertEquals(VisitCommand.FINISH, visitCommand("Terminá la visita"))
        assertEquals(VisitCommand.CAPTURE, visitCommand("¿Qué cuadro es este?"))
        assertEquals(VisitCommand.QUESTION, visitCommand("¿Cómo termina esta escena?"))
    }
    @Test fun duplicateFinalsEchoAndUnreliableSpeechDoNotTriggerPaidWork() {
        val policy = VisitVoicePolicy(0)
        assertTrue(policy.admit("¿Qué colores hay?", 10, -1f))
        assertFalse(policy.admit("Que colores hay", 20, 0.95f))
        assertTrue(policy.admit("Que colores hay", 3000, 0.95f))
        assertFalse(policy.admit("Hay un cielo azul", 4000, 1f, "Hay un cielo azul y una montaña"))
        assertFalse(policy.admit("Otra obra", 5000, 0.1f))
        assertTrue(policy.admit("Terminar visita", 6000, 1f, "Podés decir terminar visita cuando quieras"))
    }
    @Test fun failedQueriesStillConsumeFiniteVisitBudget() {
        val policy = VisitVoicePolicy(0, 2)
        assertTrue(policy.modelTurn(1)); assertTrue(policy.modelTurn(2)); assertFalse(policy.modelTurn(3))
        assertFalse(VisitVoicePolicy(0).admit("ayuda", 300_000, 1f))
        assertFalse(VisitVoicePolicy(0).modelTurn(300_000))
    }
}
