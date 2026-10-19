package io.github.excludebarrier.orsikrelay.domain.crash

import java.net.URLEncoder

/**
 * Plain-text crash and bug reports that are safe to show and to share.
 *
 * A crash report keeps only the exception types and the stack frames (class,
 * method, file, line) of the throwable and its causes. Exception messages are
 * dropped because they can carry URLs, file paths, content URIs or server
 * text; thread names are dropped because OkHttp names its threads after the
 * request URL. The user reviews every report before it leaves the device.
 */
object CrashReport {
    const val MAX_CAUSES = 5
    const val MAX_FRAMES = 40

    /** Upper bound of the report text put into a GitHub issue URL. */
    const val MAX_ISSUE_BODY_CHARS = 4_000

    /** App version, Android version and device model, one per line. */
    fun environment(appVersion: String, androidVersion: String, device: String): String =
        "App: $appVersion\nAndroid: $androidVersion\nDevice: $device\n"

    fun format(throwable: Throwable, environment: String): String = buildString {
        append(environment)
        appendLine()
        var current: Throwable? = throwable
        val seen = mutableListOf<Throwable>()
        while (current != null && seen.size < MAX_CAUSES && seen.none { it === current }) {
            if (seen.isNotEmpty()) append("Caused by: ")
            appendLine(current.javaClass.name)
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { appendLine("    at $it") }
            if (frames.size > MAX_FRAMES) appendLine("    ... ${frames.size - MAX_FRAMES} more")
            seen += current
            current = current.cause
        }
    }

    /** Issue title for a crash report: the type of the outermost exception. */
    fun crashTitle(report: String): String {
        val type = report.lineSequence()
            .dropWhile { it.isNotBlank() }
            .firstOrNull { it.isNotBlank() }
            ?.substringAfterLast('.')
            .orEmpty()
        return if (type.isEmpty()) "Crash" else "Crash: $type"
    }

    /**
     * A prefilled "new issue" page of [projectUrl]. [report] is cut to
     * [MAX_ISSUE_BODY_CHARS] so the URL stays within what browsers and GitHub
     * accept; the full text can still be shared.
     */
    fun issueUrl(projectUrl: String, title: String, report: String): String {
        val text = if (report.length > MAX_ISSUE_BODY_CHARS) {
            report.take(MAX_ISSUE_BODY_CHARS) + "\n..."
        } else {
            report
        }
        val body = "**What happened?**\n\n\n\n```\n${text.trimEnd()}\n```\n"
        return "$projectUrl/issues/new?title=${encode(title)}&body=${encode(body)}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
