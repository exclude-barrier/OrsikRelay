package io.github.excludebarrier.orsikrelay.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.fail

/**
 * Contract of the synchronous [ActiveServerSelectionStore]
 * seam. The selection is private local metadata: exactly one profile ID,
 * never an API key, URL, draft or chat data.
 */
class ActiveServerSelectionStoreContractTest {

    @Test
    fun absentSelectionReadsNull() {
        assertNull(FakeSelectionStore().read())
    }

    @Test
    fun writePersistsTheExactIdAndReadsItBack() {
        val store = FakeSelectionStore()
        store.write("profile-a")
        assertEquals("profile-a", store.read())
    }

    @Test
    fun aSecondWriteReplacesThePreviousSelection() {
        val store = FakeSelectionStore()
        store.write("profile-a")
        store.write("profile-b")
        assertEquals("profile-b", store.read())
    }

    @Test
    fun clearRemovesTheSelectionAndIsIdempotent() {
        val store = FakeSelectionStore()
        store.write("profile-a")
        store.clear()
        assertNull(store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun aBlankIdIsRejectedWithoutWritingAnything() {
        val store = FakeSelectionStore()
        assertStoreFailure { store.write("   ") }
        assertNull(store.read())
        assertEquals(1, store.writeCalls)
    }

    @Test
    fun everyFailureIsTheClosedGenericBoundaryWithoutInputs() {
        val failing = FakeSelectionStore(failWrite = true)
        val error = assertStoreFailure { failing.write("profile-a") }
        assertEquals(
            ActiveServerSelectionStoreException.MESSAGE,
            error.message
        )
        assertFalseLeak(error, "profile-a")

        val failingRead = FakeSelectionStore(failRead = true)
        val readError = assertStoreFailure { failingRead.read() }
        assertEquals(
            ActiveServerSelectionStoreException.MESSAGE,
            readError.message
        )

        val failingClear = FakeSelectionStore(failClear = true)
        val clearError = assertStoreFailure { failingClear.clear() }
        assertEquals(
            ActiveServerSelectionStoreException.MESSAGE,
            clearError.message
        )
    }

    private fun assertStoreFailure(action: () -> Unit): ActiveServerSelectionStoreException {
        return try {
            action()
            fail("Expected ActiveServerSelectionStoreException")
            throw AssertionError("unreachable")
        } catch (error: ActiveServerSelectionStoreException) {
            error
        } catch (_: Throwable) {
            fail("Expected ActiveServerSelectionStoreException")
            throw AssertionError("unreachable")
        }
    }

    private fun assertFalseLeak(error: Throwable, forbidden: String) {
        org.junit.Assert.assertFalse(
            "the stored ID must not leak into the error: ${error.message}",
            error.toString().contains(forbidden)
        )
    }

    /**
     * The fake models the CLOSED seam contract: every failure surfaces as
     * the closed [ActiveServerSelectionStoreException] (the production
     * SharedPreferences implementation maps its raw failures to exactly
     * this type via its boundary wrapper).
     */
    private class FakeSelectionStore(
        private val failRead: Boolean = false,
        private val failWrite: Boolean = false,
        private val failClear: Boolean = false
    ) : ActiveServerSelectionStore {
        private var value: String? = null
        var writeCalls = 0

        override fun read(): String? {
            if (failRead) throw ActiveServerSelectionStoreException()
            return value
        }

        override fun write(profileId: String) {
            writeCalls++
            if (failWrite) throw ActiveServerSelectionStoreException()
            if (profileId.isBlank()) throw ActiveServerSelectionStoreException()
            value = profileId
        }

        override fun clear() {
            if (failClear) throw ActiveServerSelectionStoreException()
            value = null
        }
    }
}
