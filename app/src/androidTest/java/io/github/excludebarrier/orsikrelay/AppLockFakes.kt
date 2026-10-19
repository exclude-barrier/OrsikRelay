package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator

/**
 * Records app-lock authentication requests without ever touching the system
 * prompt. [autoResult] answers a request immediately; otherwise the callback
 * stays pending until [complete] is called.
 */
internal class FakeAppAuthenticator(
    var available: Boolean = true,
    private val autoResult: Boolean? = null,
    private val lockMissing: Boolean = !available
) : AppAuthenticator {

    private var calls = 0
    private val pending = mutableListOf<(Boolean) -> Unit>()

    val callCount: Int
        get() = calls

    override fun canAuthenticate(): Boolean = available

    override fun deviceLockMissing(): Boolean = lockMissing

    override fun authenticate(onResult: (Boolean) -> Unit) {
        calls++
        if (autoResult != null) {
            onResult(autoResult)
        } else {
            pending += onResult
        }
    }

    fun complete(success: Boolean) {
        val callback = pending.removeAt(0)
        callback(success)
    }
}
