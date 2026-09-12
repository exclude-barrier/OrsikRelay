package io.github.excludebarrier.orsikrelay.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Connected proof that legacy capability metadata cannot enable protocol v1. */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesRunApprovalCapabilityStoreTest {
    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var store: SharedPreferencesRunApprovalCapabilityStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "hermes_approval_capability_test_${UUID.randomUUID()}"
        store = SharedPreferencesRunApprovalCapabilityStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun legacyV1TrueIsIgnoredFailClosed() {
        assertTrue(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("run_approval.v1:$PROFILE_ID", true)
                .commit()
        )

        assertFalse(store.read(PROFILE_ID))
    }

    @Test
    fun confirmedV2ValueSurvivesRecreatedStore() {
        store.write(PROFILE_ID, true)

        val recreated = SharedPreferencesRunApprovalCapabilityStore(context, preferencesName)

        assertTrue(recreated.read(PROFILE_ID))
    }

    @Test
    fun missingAndClearedValuesStayFalse() {
        assertFalse(store.read(PROFILE_ID))
        store.write(PROFILE_ID, true)
        store.clear(PROFILE_ID)
        assertFalse(store.read(PROFILE_ID))
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
