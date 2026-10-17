package io.github.excludebarrier.orsikrelay.data.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.domain.run.RunLease
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [SharedPreferencesRunLeaseStore] against
 * the real SharedPreferences persistence: exact round-trip across store
 * instances (the process-death path re-creates the store), replacement,
 * idempotent clear and the "incomplete record is absent" rule.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesRunLeaseStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String

    private val lease = RunLease(
        profileId = "profile-1",
        sessionId = "session-1",
        runId = "run_" + "a".repeat(32)
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "run_lease_test_" + UUID.randomUUID()
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    private fun newStore() = SharedPreferencesRunLeaseStore(context, preferencesName)

    @Test
    fun emptyStoreHasNoLease() {
        assertNull(newStore().load())
    }

    @Test
    fun savedLeaseSurvivesANewStoreInstance() {
        newStore().save(lease)
        assertEquals(lease, newStore().load())
    }

    @Test
    fun saveReplacesThePreviousLease() {
        val store = newStore()
        store.save(lease)
        val next = RunLease("profile-2", "session-2", "run_" + "b".repeat(32))
        store.save(next)
        assertEquals(next, newStore().load())
    }

    @Test
    fun clearRemovesTheLeaseAndIsIdempotent() {
        val store = newStore()
        store.save(lease)
        store.clear()
        store.clear()
        assertNull(newStore().load())
    }

    @Test
    fun incompleteRecordIsTreatedAsAbsent() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("run.lease.v1.profile", "profile-1")
            .putString("run.lease.v1.session", "session-1")
            .putString("run.lease.v1.run", " ")
            .commit()
        assertNull(newStore().load())
    }
}
