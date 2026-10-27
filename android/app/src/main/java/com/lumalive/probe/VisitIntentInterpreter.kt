package com.lumalive.probe

data class VisitIntent(val command: VisitCommand, val question: String = "")

/** Local, deliberately bounded intent families. Uncertain changes need confirmation before a photo. */
fun interpretVisitIntent(
    text: String,
    hasArtwork: Boolean,
    awaitingCaptureConfirmation: Boolean = false
): VisitIntent {
    val key = VisitIntentPhrases.key(text)
    fun command(command: VisitCommand) = VisitIntent(command)
    fun question() = if (hasArtwork) VisitIntent(VisitCommand.QUESTION, text) else command(VisitCommand.CLARIFY)

    if (key.isBlank()) return command(VisitCommand.CLARIFY)
    if (VisitIntentPhrases.affirmative.matches(key)) {
        return command(if (awaitingCaptureConfirmation) VisitCommand.CAPTURE else VisitCommand.CLARIFY)
    }
    if (VisitIntentPhrases.negative.matches(key)) {
        return command(if (awaitingCaptureConfirmation) VisitCommand.QUIET else VisitCommand.CLARIFY)
    }

    // A wearer can interrupt speech and ask in the same utterance; keep their complete question.
    if (VisitIntentPhrases.interruptedQuestion.matches(key)) return question()
    if (VisitIntentPhrases.repeat.matches(key)) return command(VisitCommand.REPEAT)
    if (VisitIntentPhrases.pause.matches(key)) return command(VisitCommand.PAUSE)
    if (VisitIntentPhrases.quiet.matches(key)) return command(VisitCommand.QUIET)
    if (VisitIntentPhrases.help.matches(key)) return command(VisitCommand.HELP)

    // Negation is checked before action families, including requests with extra trailing words.
    if (VisitIntentPhrases.negatedAction.matches(key)) return command(VisitCommand.QUIET)
    if (VisitIntentPhrases.finish.matches(key)) return command(VisitCommand.FINISH)
    if (VisitIntentPhrases.capture.matches(key)) return command(VisitCommand.CAPTURE)
    if (VisitIntentPhrases.uncertainCapture.containsMatchIn(key)) return command(VisitCommand.CLARIFY)
    if (VisitIntentPhrases.ambiguousChange.matches(key)) return command(VisitCommand.CLARIFY)

    // Free-form discussion is useful only while an image is selected. Never infer a first capture.
    return question()
}

private object VisitIntentPhrases {
    private const val REQUEST = "(?:(?:podes|podrias|puedes|puede|podria)(?: vos)? |(?:quiero|quisiera|necesito|me gustaria)(?: que)? )?"
    private const val ARTWORK = "(?:esta|este|esa|ese|la|el|otra|otro|una|un) (?:obra|cuadro|pintura)"
    private val leadingCourtesy = Regex("^(?:luma|che|bueno|dale|ahora|por favor|porfa) ")
    private val trailingCourtesy = Regex(" (?:por favor|porfa|gracias)$")

    fun key(text: String): String {
        var result = spokenKey(text)
        // Repeated courtesy tokens are harmless, but aren't accepted in the middle of a command.
        while (true) {
            val next = result.replace(leadingCourtesy, "").replace(trailingCourtesy, "").trim()
            if (next == result) return result
            result = next
        }
    }

    val affirmative = Regex("(?:si|si quiero|si quiero otra|dale|bueno|claro|de acuerdo|adelante|si por favor)")
    val negative = Regex("(?:no|no gracias|mejor no|todavia no|aun no|por ahora no|no por ahora|no todavia)")
    val interruptedQuestion = Regex(
        "(?:para|basta|silencio|deja de hablar) (?:que )?(?:queria|quiero|necesito) (?:preguntarte|preguntar|saber) .+"
    )
    val repeat = Regex(
        REQUEST + "(?:repeti(?:lo|me|melo)?(?: (?:eso|la respuesta|lo ultimo))?|repetir(?:lo|me|melo)?(?: (?:eso|la respuesta|lo ultimo))?|" +
            "repite(?: (?:eso|la respuesta))?|otra vez|decilo (?:de nuevo|otra vez)|" +
            "(?:volve|volver) a decir(?:lo|me|melo)(?: (?:eso|lo ultimo))?|" +
            "no (?:te )?(?:escuche|oi)(?: bien| lo ultimo| la respuesta)?|no entendi (?:lo ultimo|la respuesta))"
    )
    val quiet = Regex(REQUEST + "(?:para(?: un segundo)?|basta|silencio|callate|no hables|deja(?:r)? de hablar|dejes de hablar)")
    val pause = Regex(
        REQUEST + "(?:pausa|pausar|pauses|espera(?:me)?(?: (?:un|unos) (?:momento|momentos|segundo|segundos|minuto|minutos))?|" +
            "dame (?:un|unos) (?:momento|momentos|segundo|segundos|minuto|minutos)|" +
            "dejame (?:contemplar(?:lo|la)?|mirar(?:lo|la)?)(?: (?:la obra|el cuadro))? (?:un rato|en silencio|tranquilo|tranquila)|" +
            "no escuches(?: mas)?|deja(?:r)? de escuchar|dejes de escuchar|" +
            "(?:quiero )?(?:contemplar|mirar|quedarme|estar)(?: (?:la obra|el cuadro))? (?:en silencio|tranquilo|tranquila))"
    )
    val help = Regex(REQUEST + "(?:ayuda|ayudame|que puedo decir|como funciona|que comandos (?:hay|puedo usar))")
    val negatedAction = Regex(
        REQUEST + "(?:(?:todavia|aun|mejor|por ahora) )?(?:no )+" +
            "(?:(?:quiero|queremos|necesito|me gustaria|hace falta|vayas a|vamos a)(?: que)? )?" +
            "(?:(?:me|nos) )?(?:" +
            "(?:otra|otro|una|un|mas|nueva|nuevo) (?:foto|captura|obra|cuadro)|" +
            "(?:saques|sacar|tomes|tomar|hagas|hacer|captures|capturar|mires|mirar|describas|describir|cuentes|contar|hables|hablar|expliques|explicar)\\b.*|" +
            "(?:terminemos|termines|terminar|terminar la visita|finalices|finalizar|cierres|cerrar)\\b.*)(?: .*)?"
    )
    val finish = Regex(
        REQUEST + "(?:(?:termina|terminar|terminemos|terminamos|finaliza|finalizar|finalicemos|cerra|cerrar|cerremos) (?:la )?visita|" +
            "(?:cerra|cerrar|cierres) luma|chau luma|adios luma|hasta luego)"
    )
    private val captureRequest =
        REQUEST + "(?:me )?(?:" +
            "(?:mira|mirar|mires|ver|veas|observa|observar|observes|describi|describime|describir|describirme|describis|describas) $ARTWORK|" +
            "(?:describi|describime|describir|describirme|describis|describas|mira|mirar|mires) lo que (?:tengo|hay) (?:delante|enfrente)|" +
            "que estoy viendo|(?:mira|mirar|mires) lo que estoy viendo|" +
            "(?:contame )?que ves|" +
            "(?:hablame de|explicame|contame sobre|me contas sobre) (?:este cuadro|esta obra|esta pintura)|" +
            "(?:que (?:tengo|hay) (?:delante|enfrente)|que (?:cuadro|obra) es (?:este|esta))|" +
            "(?:otra|otro) (?:obra|cuadro|pintura)|captura|" +
            "(?:saca|sacar|saques|toma|tomar|tomes|hace|hacer|hagas) (?:una |otra |la )?(?:foto|captura)(?: nueva)?|" +
            "(?:pasemos|pasa|pasar|vamos) a (?:la siguiente|otra|la proxima) (?:obra|pintura)|" +
            "(?:pasemos|pasa|pasar|vamos) a (?:el siguiente|otro|el proximo) cuadro)"
    val capture = Regex(captureRequest)
    // Extra qualifiers can contain a contradiction or a new intention. Confirm instead of guessing.
    // Keep detail questions about the selected picture out of the fresh-view prefix fallback.
    val uncertainCapture = Regex("^(?!(?:que ves|contame que ves) en (?:el|la) )(?:$captureRequest) ")
    val ambiguousChange = Regex(
        REQUEST + "(?:si|no|tal vez|quizas|hola(?: luma)?|luma|gracias|" +
            "(?:cambia(?:lo|r)?|cambiemos)(?: de (?:obra|cuadro))?|otra|otro|otra cosa|algo mas|" +
            "(?:vamos|pasemos|pasa) (?:a otra cosa|al siguiente|a la siguiente)|" +
            "(?:(?:lo|la) )?(?:vemos|miramos|veamos|miremos)(?: (?:esto|eso|otra vez))?|" +
            "(?:quiero |podes )?(?:seguir|cambiar|termina|terminar|terminemos|terminamos|finalizar)|" +
            "(?:mira|mirar|describi|describime)(?: esto| eso)?)"
    )
}
