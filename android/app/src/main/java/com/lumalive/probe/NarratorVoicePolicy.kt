package com.lumalive.probe

import java.util.Locale

/** Android-independent voice metadata: selection must never require cloud speech. */
data class NarratorVoice(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val latency: Int = 0,
    val networkRequired: Boolean = false,
    val installed: Boolean = true,
)

object NarratorVoicePolicy {
    private val latinAmerica = setOf("AR", "BO", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN", "MX", "NI", "PA", "PE", "PR", "PY", "SV", "UY", "VE")

    /** One best installed voice per locale makes cycling an audible locale choice. */
    fun profiles(voices: List<NarratorVoice>): List<NarratorVoice> = voices
        .filter { !it.networkRequired && it.installed && Locale.forLanguageTag(it.localeTag).language == "es" }
        .sortedWith(compareBy<NarratorVoice> { regionRank(it.localeTag) }
            .thenByDescending { it.quality }
            .thenBy { if (Locale.forLanguageTag(it.localeTag).country == "AR") 0 else 1 }
            .thenBy { it.latency }
            .thenBy { it.name })
        .distinctBy { Locale.forLanguageTag(it.localeTag).toLanguageTag() }

    private fun regionRank(tag: String): Int = when (Locale.forLanguageTag(tag).country) {
        in latinAmerica -> 0
        "US" -> 1
        else -> 2
    }

    fun label(voice: NarratorVoice): String {
        val locale = Locale.forLanguageTag(voice.localeTag)
        return locale.getDisplayName(Locale.forLanguageTag("es-AR")) + " · sin red"
    }
}
