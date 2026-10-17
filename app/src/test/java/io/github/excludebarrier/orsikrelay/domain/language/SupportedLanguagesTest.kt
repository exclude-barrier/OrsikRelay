package io.github.excludebarrier.orsikrelay.domain.language

import java.util.Locale
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

    @Test
    fun explicitLanguageResolvesToItsLocale() {
        assertEquals(
            Locale.GERMAN,
            AppLanguage.resolveLocale("de", Locale.US)
        )
        assertEquals(
            Locale.ENGLISH,
            AppLanguage.resolveLocale("en", Locale.GERMANY)
        )
    }

    @Test
    fun systemSelectionResolvesToTheDeviceLocale() {
        assertEquals(
            Locale.US,
            AppLanguage.resolveLocale(null, Locale.US)
        )
    }

    @Test
    fun unknownSelectionBehavesLikeSystem() {
        assertEquals(
            Locale.US,
            AppLanguage.resolveLocale("fr", Locale.US)
        )
    }
}
