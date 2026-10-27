package com.lumalive.probe

import org.junit.Assert.*
import org.junit.Test

class NarratorVoicePolicyTest {
    @Test fun `only installed offline Spanish voices qualify`() {
        val profiles = NarratorVoicePolicy.profiles(listOf(
            NarratorVoice("online", "es-AR", 500, networkRequired = true),
            NarratorVoice("missing", "es-MX", 500, installed = false),
            NarratorVoice("english", "en-US", 500),
            NarratorVoice("local", "es-ES", 300),
        ))
        assertEquals(listOf("local"), profiles.map { it.name })
    }

    @Test fun `Latin America wins then best quality within a distinct locale`() {
        val profiles = NarratorVoicePolicy.profiles(listOf(
            NarratorVoice("spain", "es-ES", 500),
            NarratorVoice("mexico-low", "es-MX", 200),
            NarratorVoice("mexico-high", "es-MX", 400),
            NarratorVoice("argentina", "es-AR", 300),
            NarratorVoice("us", "es-US", 500),
        ))
        assertEquals(listOf("mexico-high", "argentina", "us", "spain"), profiles.map { it.name })
        assertEquals(profiles.size, profiles.map { it.localeTag }.distinct().size)
    }

    @Test fun `equal quality prefers Argentina and input order cannot change selection`() {
        val voices = listOf(NarratorVoice("mexico", "es-MX", 400), NarratorVoice("argentina", "es-AR", 400))
        assertEquals("argentina", NarratorVoicePolicy.profiles(voices).first().name)
        assertEquals(NarratorVoicePolicy.profiles(voices), NarratorVoicePolicy.profiles(voices.reversed()))
        assertTrue(NarratorVoicePolicy.label(voices[1]).contains("Argentina"))
    }

    @Test fun `no eligible voices remains an explicit empty selection`() {
        assertTrue(NarratorVoicePolicy.profiles(emptyList()).isEmpty())
        assertTrue(NarratorVoicePolicy.profiles(listOf(NarratorVoice("cloud", "es-AR", 500, networkRequired = true))).isEmpty())
    }
}
