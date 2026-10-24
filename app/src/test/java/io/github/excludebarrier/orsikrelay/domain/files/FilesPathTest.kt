package io.github.excludebarrier.orsikrelay.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesPathTest {

    @Test
    fun childPathJoinsWithOneSlashAndKeepsTheRootEmpty() {
        assertEquals("a", childPath("", "a"))
        assertEquals("a/b", childPath("a", "b"))
        assertEquals("a/b/c", childPath("a/b", "c"))
        assertEquals("a dir/ü.txt", childPath("a dir", "ü.txt"))
    }

    @Test
    fun parentPathIsNullOnlyAtTheRoot() {
        assertNull(parentPath(""))
        assertEquals("", parentPath("a"))
        assertEquals("a", parentPath("a/b"))
        assertEquals("a/b", parentPath("a/b/c"))
    }

    @Test
    fun childAndParentRoundTrip() {
        for (parent in listOf("", "a", "a/b")) {
            assertEquals(parent, parentPath(childPath(parent, "name")))
        }
    }
}
