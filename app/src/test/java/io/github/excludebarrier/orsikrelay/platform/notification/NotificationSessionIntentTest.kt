package io.github.excludebarrier.orsikrelay.platform.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed coverage for the notification-tap routing identifier (P3). The
 * `isSafeId` gate is the only thing that decides whether an ID from an intent
 * extra may drive navigation; it is a strict, closed character-set + length
 * check, so a forged or corrupt extra is rejected.
 */
class NotificationSessionIntentTest {

    @Test
    fun `typical server ids are safe`() {
        assertTrue(NotificationSessionIntent.isSafeId("api_1790203929_be37fef8"))
        assertTrue(NotificationSessionIntent.isSafeId("run_deadbeefdeadbeefdeadbeefdeadbeef"))
        assertTrue(NotificationSessionIntent.isSafeId("p-123_456.x"))
    }

    @Test
    fun `empty id is not safe`() {
        assertFalse(NotificationSessionIntent.isSafeId(""))
    }

    @Test
    fun `oversize id is not safe`() {
        assertFalse(NotificationSessionIntent.isSafeId("a".repeat(129)))
    }

    @Test
    fun `id with a newline is not safe`() {
        assertFalse(NotificationSessionIntent.isSafeId("a\nb"))
    }

    @Test
    fun `id with a space is not safe`() {
        assertFalse(NotificationSessionIntent.isSafeId("a b"))
    }

    @Test
    fun `id with a NUL control character is not safe`() {
        assertFalse(NotificationSessionIntent.isSafeId("a\u0000b"))
    }

    @Test
    fun `id with a path separator is not safe`() {
        assertFalse(NotificationSessionIntent.isSafeId("a/b"))
    }
}
