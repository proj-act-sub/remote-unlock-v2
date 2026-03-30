package com.remoteunlock.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import javax.crypto.KeyAgreement

/**
 * Manages the X25519 static keypair in the AndroidKeyStore.
 *
 * Requirements: API 31+ (Android 12) — KeyProperties.PURPOSE_AGREE_KEY
 * and XDH (X25519) are available in the AndroidKeyStore from this version.
 *
 * The private key never leaves secure hardware. All DH operations are
 * performed inside the Keystore/StrongBox (if available).
 */
object KeyManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "remoteunlock_x25519_static_v1"

    fun init(context: Context) {
        if (!hasKey()) generateKey()
    }

    private fun hasKey(): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return ks.containsAlias(KEY_ALIAS)
    }

    private fun generateKey() {
        val kpg = KeyPairGenerator.getInstance("XDH", KEYSTORE_PROVIDER)
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(NamedParameterSpec.X25519)
                // No user auth required here — BiometricPrompt gates the *operation*, not the key.
                .setUserAuthenticationRequired(false)
                .build()
        )
        kpg.generateKeyPair()
    }

    /** Returns the raw 32-byte X25519 public key. */
    fun getPublicKeyBytes(): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val pub = ks.getCertificate(KEY_ALIAS).publicKey
        // XDH public keys are encoded as SubjectPublicKeyInfo; the raw key is the last 32 bytes.
        val encoded = pub.encoded
        return encoded.takeLast(32).toByteArray()
    }

    /** Returns the java.security.PublicKey for the static keypair. */
    fun getPublicKey(): PublicKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return ks.getCertificate(KEY_ALIAS).publicKey
    }

    /** Returns the java.security.PrivateKey (hardware-backed, non-extractable). */
    private fun getPrivateKey(): java.security.PrivateKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as java.security.PrivateKey
    }

    /**
     * Compute X25519 DH shared secret: DH(our_static_priv, remote_pub).
     * The operation executes inside the secure element.
     */
    fun dh(remotePubKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("XDH", KEYSTORE_PROVIDER)
        ka.init(getPrivateKey())
        ka.doPhase(remotePubKey, true)
        return ka.generateSecret()
    }
}
