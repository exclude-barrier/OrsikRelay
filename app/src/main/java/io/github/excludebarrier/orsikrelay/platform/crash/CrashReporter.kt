package io.github.excludebarrier.orsikrelay.platform.crash

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.excludebarrier.orsikrelay.BuildConfig
import io.github.excludebarrier.orsikrelay.domain.crash.CrashReport
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps the report of the last crash in the app's private no-backup storage.
 *
 * Nothing is sent anywhere: on the next start the app offers to report,
 * share or discard it (see [io.github.excludebarrier.orsikrelay.ui.crash.CrashReportPrompt]).
 * [pending] and [clear] do file I/O and must run off the main thread.
 */
object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val TEMP_FILE_NAME = "$FILE_NAME.tmp"

    /** Guards the record writes and the one-time install decision. */
    private val lock = Any()
    private var installed = false

    /**
     * Records an uncaught exception, then hands it to the previous handler.
     * Idempotent: a second call in the same process is a no-op, so the
     * report is never written twice by stacked handlers.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (installed) return
            installed = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { record(appContext, throwable) }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    fun environment(): String = CrashReport.environment(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}"
    )

    internal fun record(context: Context, throwable: Throwable) {
        val text = CrashReport.format(throwable, environment())
        val target = file(context)
        synchronized(lock) {
            // Write-then-rename: a crash, a second writer or a killed process
            // can never leave a truncated report in place. The temporary file
            // lives in the same directory, so renameTo is an atomic replace.
            val temp = File(context.noBackupFilesDir, TEMP_FILE_NAME)
            try {
                temp.writeText(text)
                if (!temp.renameTo(target)) {
                    target.writeText(text)
                }
            } finally {
                temp.delete()
            }
        }
    }

    fun pending(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)
}
