package com.remoteunlock.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wrapper around the standard **androidx.biometric.BiometricPrompt** API.
 *
 * Supports fingerprint, face, and PIN/password fallback.
 * Class-3 (strong) biometrics are preferred where available.
 */
object BiometricHelper {

    /** Check whether the device can authenticate with any supported method. */
    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        // Prefer BIOMETRIC_STRONG; fall back to DEVICE_CREDENTIAL
        val result = bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Show the biometric prompt.
     *
     * The context **must** be a [FragmentActivity] (Compose's [ComponentActivity] qualifies).
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String = "Confirm your identity to unlock",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val activity = context as? FragmentActivity
            ?: throw IllegalArgumentException("Context must be FragmentActivity")

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // Called when a biometric is presented but not recognised.
                // The prompt stays open; no action needed here.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            // NOTE: setNegativeButtonText is mutually exclusive with DEVICE_CREDENTIAL.
            .build()

        prompt.authenticate(info)
    }
}
