package io.github.excludebarrier.orsikrelay.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportedLanguagesTest {

    @Test
    fun everyLanguageHasATagAndItsOwnName() {
        assertEquals(
            listOf("en", "de"),
            SupportedLanguages.all.map { it.tag }
        )
        assertEquals(
            listOf("English", "Deutsch"),
            SupportedLanguages.all.map { it.displayName }
        )
        assertEquals("en", SupportedLanguages.base.tag)
    }

    @Test
    fun tagsAreNormalizedCaseInsensitively() {
        assertEquals("de", SupportedLanguages.normalizeTag("DE"))
        assertEquals("en", SupportedLanguages.normalizeTag("en"))
    }

    @Test
    fun systemAndUnknownValuesResolveToNoSupportedTag() {
        assertNull(SupportedLanguages.normalizeTag(null))
        assertNull(SupportedLanguages.normalizeTag(""))
        assertNull(SupportedLanguages.normalizeTag("fr"))
    }
}
