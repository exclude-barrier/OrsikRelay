package io.github.excludebarrier.orsikrelay

import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesRunApprovalCapabilityStore
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Instrumented contract test for the process-scoped [AppDependenciesHolder]
 * (P4 Activity-recreation fix).
 *
 * The holder is the single production graph producer: it must hand out the
 * SAME graph for the whole live process - that is what lets a re-created chat
 * screen re-attach to the still-running turn instead of meeting an empty
 * controller - while a caller that injects its own stores must get a fresh,
 * uncached graph (test isolation, no shared mutable state).
 *
 * Real application context, real SharedPreferences-backed stores; no network,
 * no credentials.
 */
class AppDependenciesHolderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun productionGraphIsSharedAcrossRepeatedCallsInTheSameProcess() {
        val first = AppDependenciesHolder.get(context)
        val second = AppDependenciesHolder.get(context)
        assertSame(
            "the process-scoped graph must be reused: a recreation must not build a second controller",
            first,
            second
        )
        assertSame(first.runController, second.runController)
    }

    @Test
    fun injectedStoresAlwaysProduceAFreshUncachedGraph() {
        val cached = AppDependenciesHolder.get(context)
        val injected = AppDependenciesHolder.get(
            context,
            selectionStore = SharedPreferencesActiveServerSelectionStore(context),
            capabilityStore = SharedPreferencesRunApprovalCapabilityStore(context)
        )
        assertNotSame(
            "a caller with its own stores must never share the production graph",
            cached,
            injected
        )
        assertNotSame(
            "the injected graph must not reuse the production controller",
            cached.runController,
            injected.runController
        )
        // The production graph stays cached and unchanged by the injected call.
        assertSame(cached, AppDependenciesHolder.get(context))
    }
}
