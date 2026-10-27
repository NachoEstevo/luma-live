package com.lumalive.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class VisitIntentInterpreterTest {
    private fun assertCommands(command: VisitCommand, vararg phrases: String, hasArtwork: Boolean = true) {
        phrases.forEach { phrase ->
            assertEquals(phrase, VisitIntent(command), interpretVisitIntent(phrase, hasArtwork))
        }
    }

    @Test fun explicitCaptureFamiliesAcceptNaturalSpanishAndAsrNormalization() {
        assertCommands(
            VisitCommand.CAPTURE,
            "quiero que mires otro cuadro",
            "¿Me describís lo que tengo delante?",
            "ahora pasemos a la siguiente obra",
            "podés sacar una foto nueva",
            "¿Podrías describirme esta pintura, por favor?",
            "LUMA, MIRÁ ESTA OBRA, POR FAVOR!!!",
            "Che, Luma, observá esta obra",
            "Otra obra",
            "Otro cuadro",
            "¿Qué cuadro es este?",
            "¿Qué obra es esta?",
            "¿Qué hay delante?",
            "¿Qué estoy viendo?",
            "Mirá lo que estoy viendo",
            "Contame qué ves",
            "¿Qué ves?",
            "Hablame de este cuadro",
            "Explicame esta obra",
            "¿Me contás sobre este cuadro?",
            "Captura",
            "Quisiera ver otra obra"
        )
    }

    @Test fun aFirstPhotoStillRequiresAnExplicitCaptureRequest() {
        assertCommands(
            VisitCommand.CAPTURE,
            "Describime este cuadro",
            "¿Qué tengo delante?",
            "¿Qué estoy viendo?",
            "Contame qué ves",
            "Hablame de este cuadro",
            "Quiero que mires otra obra",
            hasArtwork = false
        )
        assertCommands(
            VisitCommand.CLARIFY,
            "¿Por qué usó ese color?",
            "Qué hay a la izquierda del personaje",
            "Contame más",
            "Veo algo interesante",
            "Seguimos",
            "Hola",
            "",
            hasArtwork = false
        )
    }

    @Test fun selectedArtworkQuestionsAndResponseAdjustmentsKeepOriginalText() {
        listOf(
            "¿Qué hay a la izquierda del personaje?",
            "¿Por qué usó ese color?",
            "Contame más",
            "Más corto",
            "No tanto detalle",
            "Seguí",
            "Describime la expresión del personaje",
            "Me interesa la figura del fondo",
            "No entiendo por qué eligió azul",
            "¿Por qué no aparece la otra obra?"
        ).forEach { phrase ->
            assertEquals(VisitIntent(VisitCommand.QUESTION, phrase), interpretVisitIntent(phrase, true))
        }
    }

    @Test fun repeatFamiliesIncludeLocalMissedSpeechRequests() {
        assertCommands(
            VisitCommand.REPEAT,
            "Repetí",
            "Repetilo",
            "Otra vez",
            "Repite la respuesta",
            "No escuché bien",
            "No te oí bien",
            "¿Podés repetirme eso?",
            "Podrías repetírmelo",
            "Podés repetir la respuesta",
            "Volvé a decirlo",
            "Podrías volver a decirme lo último",
            "No entendí lo último"
        )
    }

    @Test fun stoppingSpeechAndPausingListeningHaveDifferentIntentions() {
        assertCommands(
            VisitCommand.QUIET,
            "¡Pará!",
            "Dejá de hablar",
            "Basta",
            "Silencio",
            "Podés dejar de hablar",
            "No hables"
        )
        assertCommands(
            VisitCommand.PAUSE,
            "Esperame un momento",
            "Quiero contemplar en silencio",
            "No escuches",
            "Pausá",
            "Podés pausar",
            "Esperá un segundo",
            "Quiero mirar el cuadro tranquilo",
            "Dejá de escuchar",
            "Dame un momento",
            "Dejame contemplarlo un rato",
            "Dejame mirar el cuadro tranquilo"
        )
    }

    @Test fun finishingRequiresAnExplicitVisitOrAppExit() {
        assertCommands(
            VisitCommand.FINISH,
            "Terminemos la visita",
            "Terminá la visita",
            "Terminar visita",
            "Cerrá Luma",
            "Quiero cerrar la visita",
            "Hasta luego",
            "Chau Luma"
        )
        assertCommands(VisitCommand.CLARIFY, "Terminá", "Terminar", "Terminemos", "Terminamos")
    }

    @Test fun controlWordsInsideArtworkQuestionsAreNeverCommands() {
        listOf(
            "¿Para qué usó ese color?",
            "¿Por qué hay silencio en esta obra?",
            "¿Cómo termina esta escena?",
            "¿Qué quiso capturar el pintor?",
            "¿Qué significa cerrar los ojos en esta obra?",
            "¿Me repetís el nombre del pintor?",
            "¿El personaje quiere que lo dejen de escuchar?",
            "¿Dónde está la otra obra que mencionaste?",
            "¿Qué vemos al fondo?",
            "¿Qué ves en el fondo de la obra?",
            "El cuadro termina con una figura oscura"
        ).forEach { phrase ->
            assertEquals(VisitIntent(VisitCommand.QUESTION, phrase), interpretVisitIntent(phrase, true))
        }
    }

    @Test fun negatedActionsDoNotCaptureExitOrConsumeAQuestion() {
        assertCommands(
            VisitCommand.QUIET,
            "No quiero otra foto",
            "No quiero otra foto por ahora",
            "No quiero nueva foto",
            "No terminemos todavía",
            "Todavía no terminemos la visita",
            "No quiero terminar la visita",
            "No saques una foto nueva",
            "No me describas otra obra",
            "No, no quiero otra foto",
            "Podés no sacar otra foto",
            "Mejor no cierres Luma"
        )
    }

    @Test fun vagueChangesAndBareConfirmationsNeedClarification() {
        assertCommands(
            VisitCommand.CLARIFY,
            "Sí",
            "No",
            "Dale",
            "Claro",
            "Sí por favor",
            "Otra",
            "Cambiemos",
            "Cambiá",
            "Cambiemos de obra",
            "Podés cambiar de cuadro",
            "¿Lo vemos?",
            "¿Lo miramos?",
            "Vemos eso",
            "Otra cosa",
            "Pasemos a la siguiente",
            "Mirá eso"
        )
    }

    @Test fun confirmationOnlyAppliesToAPendingClarification() {
        listOf("Sí", "Dale", "Claro", "De acuerdo", "Sí, por favor").forEach { phrase ->
            assertEquals(phrase, VisitIntent(VisitCommand.CAPTURE), interpretVisitIntent(phrase, true, true))
        }
        listOf("No", "No gracias", "Mejor no", "Todavía no").forEach { phrase ->
            assertEquals(phrase, VisitIntent(VisitCommand.QUIET), interpretVisitIntent(phrase, true, true))
        }
        listOf("¿Por qué usó azul?", "Sí, pero contame más del personaje", "No, explicame el fondo").forEach { phrase ->
            assertEquals(VisitIntent(VisitCommand.QUESTION, phrase), interpretVisitIntent(phrase, true, true))
        }
        assertEquals(VisitIntent(VisitCommand.PAUSE), interpretVisitIntent("Esperame un momento", true, true))
        assertEquals(VisitIntent(VisitCommand.FINISH), interpretVisitIntent("Terminemos la visita", true, true))
        assertEquals(VisitIntent(VisitCommand.CLARIFY), interpretVisitIntent("Por qué usó azul", false, true))
        assertEquals(VisitIntent(VisitCommand.CLARIFY), interpretVisitIntent("¿Lo vemos?", true))
        assertEquals(VisitIntent(VisitCommand.QUIET), interpretVisitIntent("No", true, true))
    }

    @Test fun anInterruptionCanCarryAQuestionInTheSameUtterance() {
        val phrase = "Pará, quería preguntarte qué hay detrás del personaje"
        assertEquals(VisitIntent(VisitCommand.QUESTION, phrase), interpretVisitIntent(phrase, true))
        assertEquals(VisitIntent(VisitCommand.CLARIFY), interpretVisitIntent(phrase, false))
    }

    @Test fun captureRequestsWithExtraQualifiersNeedConfirmationBeforeUsingAnyImage() {
        assertCommands(
            VisitCommand.CLARIFY,
            "Quiero que mires otro cuadro que tengo enfrente",
            "Mirá esta obra que está acá",
            "Podés sacar una foto nueva pero no ahora",
            "Quiero que mires otro cuadro pero no saques foto",
            "Contame qué ves pero no saques una foto",
            "Explicame esta obra que tengo enfrente"
        )
        assertCommands(
            VisitCommand.QUIET,
            "No quiero que mires otro cuadro que tengo enfrente",
            "Quiero que no mires otro cuadro",
            "No me cuentes qué ves",
            "No me expliques esta obra",
            "No me hables de este cuadro"
        )
        val artQuestion = "¿Dónde está la otra obra que mencionaste?"
        assertEquals(VisitIntent(VisitCommand.QUESTION, artQuestion), interpretVisitIntent(artQuestion, true))
    }

    @Test fun helpStaysLocalWithOrWithoutAnArtwork() {
        assertCommands(VisitCommand.HELP, "Ayuda", "Qué puedo decir", "Cómo funciona")
        assertCommands(VisitCommand.HELP, "Ayudame", "Qué comandos puedo usar", hasArtwork = false)
    }
}
