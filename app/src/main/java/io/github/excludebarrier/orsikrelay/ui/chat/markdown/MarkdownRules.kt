package io.github.excludebarrier.orsikrelay.ui.chat.markdown

/**
 * Pure rules of the chat Markdown layout, kept free of Compose so they can
 * be unit-tested: block spacing, the shell lines a code block hands to the
 * terminal, and the domain shown behind a link.
 */
internal enum class BlockKind { Heading, Other }

/** Space in dp above a top-level block: 18 before a heading, 6 after one, 12 otherwise. */
internal fun blockGapBefore(previous: BlockKind?, current: BlockKind): Int = when {
    previous == null -> 0
    current == BlockKind.Heading -> 18
    previous == BlockKind.Heading -> 6
    else -> 12
}

/** A code block collapses from this many lines on. */
internal const val CODE_COLLAPSE_LINES = 12

/** Lines a collapsed code block still shows (the last one fades out). */
internal const val CODE_COLLAPSED_VISIBLE_LINES = 10

private val SHELL_LANGUAGES = setOf("bash", "sh", "shell", "zsh")

internal fun isShellLanguage(language: String?): Boolean =
    language?.trim()?.lowercase() in SHELL_LANGUAGES

/** Words that open or close a multi-line shell construct; such blocks are not chained. */
private val BLOCK_WORDS = setOf("then", "do", "done", "fi", "else", "elif", "esac", "{", "}", "EOF")

private const val MAX_TERMINAL_COMMAND = 2_000

/**
 * The command line a shell code block puts into the terminal input (never
 * run; Enter stays with the user). Command lines are joined with ` && `.
 * Lines that look like output (`/dev/…`, `Filesystem`, table heads, check
 * marks …), comments and prompts are dropped. When the block looks like
 * output mixed with commands or holds a multi-line construct, only the first
 * command goes over. Control characters are removed, so nothing can carry an
 * Enter. Null when nothing usable is left.
 */
internal fun terminalCommandFrom(code: String): String? {
    val lines = joinContinuations(code.lines())
        .map { it.trim().removePrefix("$ ").trim() }
        .filter { it.isNotEmpty() }
    val commands = lines.filter { !it.startsWith("#") && !looksLikeOutput(it) }
    if (commands.isEmpty()) return null
    val doubtful = commands.size != lines.count { !it.startsWith("#") } ||
        commands.any { line -> line.split(Regex("\\s+")).any { it in BLOCK_WORDS } || line.contains("<<") }
    val chosen = if (doubtful) commands.take(1) else commands
    val joined = chosen.joinToString(" && ") { line -> line.filterNot { it.isISOControl() } }.trim()
    return joined.takeIf { it.isNotEmpty() && it.length <= MAX_TERMINAL_COMMAND }
}

private fun joinContinuations(lines: List<String>): List<String> {
    val out = mutableListOf<String>()
    val pending = StringBuilder()
    for (line in lines) {
        val trimmed = line.trimEnd()
        val part = if (pending.isEmpty()) trimmed else trimmed.trimStart()
        if (part.endsWith("\\")) {
            pending.append(part.dropLast(1).trimEnd()).append(' ')
        } else {
            out += pending.append(part).toString()
            pending.clear()
        }
    }
    if (pending.isNotEmpty()) out += pending.toString()
    return out
}

private val OUTPUT_PREFIXES = listOf(
    "/dev/", "Filesystem", "total ", "[+]", "✔", "✓", "✗", "→", "...", "drwx", "-rw", "lrwx"
)

/** A table head like `NAME   STATUS   AGE` or `CONTAINER ID   IMAGE`. */
private val TABLE_HEAD = Regex("^[A-Z][A-Z0-9_ ]*[A-Z0-9_]\\s{2,}[A-Z]")

internal fun looksLikeOutput(line: String): Boolean =
    OUTPUT_PREFIXES.any { line.startsWith(it) } ||
        TABLE_HEAD.containsMatchIn(line) ||
        line.firstOrNull()?.isDigit() == true ||
        line.contains(Regex("\\s{3,}\\S+\\s{3,}"))

/** The host shown after an https link ("github.com"), without `www.`; null when none can be read. */
internal fun linkDomain(url: String): String? {
    val rest = url.trim().substringAfter("://", missingDelimiterValue = "")
    if (rest.isEmpty()) return null
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase()
    return host.removePrefix("www.").takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
}
