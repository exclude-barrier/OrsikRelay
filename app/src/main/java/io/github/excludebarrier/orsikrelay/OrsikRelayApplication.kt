package io.github.excludebarrier.orsikrelay

import android.app.Application
import io.github.excludebarrier.orsikrelay.platform.crash.CrashReporter

/** Installs the local crash recorder before any component starts. */
class OrsikRelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
