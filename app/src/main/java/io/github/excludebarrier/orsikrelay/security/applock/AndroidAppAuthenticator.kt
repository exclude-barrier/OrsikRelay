package io.github.excludebarrier.orsikrelay.security.applock

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.excludebarrier.orsikrelay.R

/**
 * Production [AppAuthenticator] over [BiometricPrompt].
 *
 * Authenticator choice follows the androidx.biometric contract for the
 * running API level (verified against the `androidx.biometric:biometric:1.1.0`
 * sources, `AuthenticatorUtils.isSupportedCombination` and
 * `BiometricPrompt.Builder.setAllowedAuthenticators`):
 *
 * - API 30+: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` is supported.
 * - API 26-29: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` is unsupported on API
 *   28-29, so `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` is used — the variant the
 *   library explicitly supports on every API level and the documented
 *   replacement for the deprecated `setDeviceCredentialAllowed(true)`.
 *
 * Device credential is always part of the set, so the prompt needs no
 * negative button (setting one would throw in `PromptInfo.build()`).
 */
class AndroidAppAuthenticator(
    private val activity: FragmentActivity
) : AppAuthenticator {

    private val biometricManager = BiometricManager.from(activity)

    override fun canAuthenticate(): Boolean =
        biometricManager.canAuthenticate(allowedAuthenticators()) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override fun deviceLockMissing(): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == false

    override fun authenticate(onResult: (Boolean) -> Unit) =
        authenticate(activity.getString(R.string.app_lock_prompt_title), onResult)

    override fun authenticate(title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onResult(true)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    onResult(false)
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(allowedAuthenticators())
                .build()
        )
    }

    companion object {
        internal fun allowedAuthenticators(sdkInt: Int = Build.VERSION.SDK_INT): Int =
            if (sdkInt >= Build.VERSION_CODES.R) {
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            } else {
                Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
            }
    }
}
