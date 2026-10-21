package io.github.excludebarrier.orsikrelay.security.applock

/**
 * The single authentication gate of the app lock.
 *
 * Production is backed by Android's [androidx.biometric.BiometricPrompt];
 * tests inject a fake so no system prompt is ever shown. [canAuthenticate]
 * answers whether the device currently offers a usable biometric or device
 * credential; [authenticate] hands the result to [onResult] exactly once
 * (`true` only on a successful authentication).
 */
interface AppAuthenticator {
    fun canAuthenticate(): Boolean
    fun authenticate(onResult: (Boolean) -> Unit)

    /**
     * `true` only when the device has no screen lock at all. Unlike a failed
     * [canAuthenticate] (which also covers a busy or briefly unavailable
     * sensor), this is the one state in which the app lock may switch itself
     * off.
     */
    fun deviceLockMissing(): Boolean = !canAuthenticate()
}
