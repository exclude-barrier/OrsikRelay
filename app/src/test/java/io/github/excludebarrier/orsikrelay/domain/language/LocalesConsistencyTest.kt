package io.github.excludebarrier.orsikrelay.domain.language

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the three language registries in sync:
 *
 * 1. [SupportedLanguages.all],
 * 2. `res/xml/locales_config.xml`,
 * 3. the `values` / `values-<tag>` resource directories.
 *
 * Adding a language to only one of them fails this test.
 */
class LocalesConsistencyTest {

    private val resDir: File = sequenceOf(
        File("src/main/res"),
        File("app/src/main/res")
    ).firstOrNull { it.isDirectory }
        ?: error("Android resource directory not found from ${File(".").absolutePath}")

    @Test
    fun everySupportedLanguageHasResourcesAndALocaleConfigEntry() {
        val configured = localeConfigTags()
        val resourceTags = languageResourceTags()

        SupportedLanguages.all.forEach { language ->
            if (language.tag == SupportedLanguages.BASE_TAG) {
                assertTrue(
                    "Missing base values/ directory for ${language.tag}",
                    File(resDir, "values").isDirectory
                )
            } else {
                assertTrue(
                    "Missing values-${language.tag}/ directory",
                    File(resDir, "values-${language.tag}").isDirectory
                )
            }
            assertTrue(
                "Missing ${language.tag} in locales_config.xml",
                configured.contains(language.tag)
            )
            assertTrue(
                "Missing resource directory for configured tag ${language.tag}",
                language.tag == SupportedLanguages.BASE_TAG || resourceTags.contains(language.tag)
            )
        }
    }

    @Test
    fun localeConfigAndResourceDirectoriesOnlyContainSupportedTags() {
        val supported = SupportedLanguages.all.map { it.tag }.toSet()
        val configured = localeConfigTags()
        val resourceTags = languageResourceTags()

        assertEquals(supported, configured)
        assertEquals(supported - SupportedLanguages.BASE_TAG, resourceTags)
    }

    private fun localeConfigTags(): Set<String> {
        val file = File(resDir, "xml/locales_config.xml")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("locale")
        return buildSet {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index)
                val attributes = element.attributes
                val name = attributes.getNamedItem("android:name")?.nodeValue
                    ?: attributes.getNamedItem("name")?.nodeValue
                if (!name.isNullOrBlank()) add(name)
            }
        }
    }

    private fun languageResourceTags(): Set<String> {
        val languageDir = Regex("^values-([a-z]{2,3}(-r[A-Z]{2})?)$")
        return resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { languageDir.matchEntire(it.name)?.groupValues?.get(1) }
            .toSet()
    }
}
