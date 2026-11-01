package io.github.excludebarrier.orsikrelay.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRulesTest {

    @Test
    fun blockGapsFollowTheDesign() {
        assertEquals(0, blockGapBefore(null, BlockKind.Heading))
        assertEquals(0, blockGapBefore(null, BlockKind.Other))
        assertEquals(18, blockGapBefore(BlockKind.Other, BlockKind.Heading))
        assertEquals(18, blockGapBefore(BlockKind.Heading, BlockKind.Heading))
        assertEquals(6, blockGapBefore(BlockKind.Heading, BlockKind.Other))
        assertEquals(12, blockGapBefore(BlockKind.Other, BlockKind.Other))
    }

    @Test
    fun shellLanguagesAreRecognized() {
        listOf("bash", "sh", "shell", "zsh", " BASH ").forEach { assertTrue(it, isShellLanguage(it)) }
        listOf(null, "", "log", "python", "console").forEach { assertFalse("$it", isShellLanguage(it)) }
    }

    @Test
    fun commandLinesAreChainedWithAnd() {
        assertEquals(
            "cd /srv/stacks/paperless && docker compose pull && docker compose up -d",
            terminalCommandFrom("cd /srv/stacks/paperless\ndocker compose pull\ndocker compose up -d\n")
        )
    }

    @Test
    fun aSingleCommandStaysAsItIs() {
        assertEquals("df -h /srv", terminalCommandFrom("df -h /srv"))
    }

    @Test
    fun promptsCommentsAndBlankLinesAreDropped() {
        assertEquals("ls -la && pwd", terminalCommandFrom("# list\n$ ls -la\n\n$ pwd"))
    }

    @Test
    fun continuationLinesAreJoined() {
        assertEquals("docker run --rm -it alpine sh", terminalCommandFrom("docker run --rm \\\n  -it alpine sh"))
    }

    @Test
    fun outputLinesAreNotTakenAndOnlyTheFirstCommandGoesOver() {
        val block = """
            df -h
            Filesystem      Size  Used Avail Use% Mounted on
            /dev/nvme0n1p2  468G  411G   34G  93% /
        """.trimIndent()
        assertEquals("df -h", terminalCommandFrom(block))
    }

    @Test
    fun tableHeadsCountAsOutput() {
        assertTrue(looksLikeOutput("NAME      STATUS    AGE"))
        assertTrue(looksLikeOutput("CONTAINER ID   IMAGE     COMMAND"))
        assertTrue(looksLikeOutput("✔ broker Pulled"))
        assertTrue(looksLikeOutput("/dev/sda1  100G"))
        assertFalse(looksLikeOutput("docker ps -a"))
        assertFalse(looksLikeOutput("sudo systemctl status nginx"))
    }

    @Test
    fun multiLineConstructsSendOnlyTheFirstLine() {
        assertEquals("for f in *.log; do", terminalCommandFrom("for f in *.log; do\n  gzip \"\$f\"\ndone"))
        assertEquals("cat > a.txt <<EOF", terminalCommandFrom("cat > a.txt <<EOF\nhallo\nEOF"))
    }

    @Test
    fun controlCharactersAreRemovedSoNothingCanPressEnter() {
        val command = terminalCommandFrom("echo a\u001b[2Jb\r")
        assertEquals("echo a[2Jb", command)
        assertFalse(command!!.any { it.isISOControl() })
    }

    @Test
    fun onlyOutputGivesNothing() {
        assertNull(terminalCommandFrom("Filesystem  Size\n/dev/sda1  10G"))
        assertNull(terminalCommandFrom("   \n# just a comment"))
    }

    @Test
    fun linkDomainIsTheHostWithoutWww() {
        assertEquals("github.com", linkDomain("https://github.com/x/y/releases"))
        assertEquals("example.org", linkDomain("https://www.example.org"))
        assertEquals("docs.example.org", linkDomain("https://user@docs.example.org:8443/a?b#c"))
        assertNull(linkDomain("https://"))
        assertNull(linkDomain("relative/path"))
    }
}
