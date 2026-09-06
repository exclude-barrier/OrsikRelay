package io.github.excludebarrier.orsikrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User-facing brand contract: the launcher label (manifest `app_name`)
 * must be "Orsik Relay". The applicationId, package name and all
 * Hermes API types stay `io.github.excludebarrier.orsikrelay`; only the visible
 * brand changes. No Hermes/Nous logo or affiliation claim.
 */
@RunWith(AndroidJUnit4::class)
class AppBrandingUiTest {

    @Test
    fun launcherLabelIsOrsikRelay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.packageManager.getApplicationInfo(
            context.packageName,
            0
        ).loadLabel(context.packageManager).toString()
        assertEquals("Orsik Relay", label)
    }

    @Test
    fun applicationIdStaysHermesPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.excludebarrier.orsikrelay", context.packageName)
    }
}
