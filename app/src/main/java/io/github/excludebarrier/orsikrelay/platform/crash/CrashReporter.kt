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

    /** Records an uncaught exception, then hands it to the previous handler. */
    fun install(context: Context) {
        val appContext = context.applicationContext
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

    fun environment(): String = CrashReport.environment(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}"
    )

    internal fun record(context: Context, throwable: Throwable) {
        file(context).writeText(CrashReport.format(throwable, environment()))
    }

    fun pending(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)
}
