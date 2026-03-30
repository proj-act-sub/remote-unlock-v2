package com.remoteunlock.crypto

import com.appmattus.crypto.Algorithm
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Noise_IK_25519_ChaChaPoly_BLAKE3 — Initiator (Android) side.
 *
 * The initiator's static keypair lives in AndroidKeyStore (see KeyManager).
 * Ephemeral keys are generated fresh per session using BouncyCastle X25519.
 */
class NoiseHandshake(
    /** Responder (desktop) X25519 static public key — from the QR code. */
    private val responderStaticPub: ByteArray,
    /** One-time pairing token — from the QR code. Null for unlock sessions. */
    private val pairingToken: ByteArray?,
) {
    companion object {
        private const val PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_BLAKE3"
        private val PROLOGUE = "remoteunlock-v7".toByteArray()
        private const val HASH_LEN = 32

        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ── BLAKE3 helpers ────────────────────────────────────────────────────────
    private fun blake3Hash(data: ByteArray): ByteArray =
        Algorithm.Blake3.create().apply { update(data) }.digest()

    private fun blake3Keyed(key: ByteArray, data: ByteArray): ByteArray {
        return Algorithm.Blake3.create(key = key).apply {
            update(data)
        }.digest()
    }

    private fun mixHash(h: ByteArray, data: ByteArray): ByteArray {
        val digest = Algorithm.Blake3.create() // Changed to .create()
        digest.update(h)
        digest.update(data)
        return digest.digest()
    }

    private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val tmp = blake3Keyed(ck, ikm)
        val ck2 = blake3Keyed(tmp, byteArrayOf(0x01))
        val k = blake3Keyed(tmp, ck2 + byteArrayOf(0x02))
        return Pair(ck2, k)
    }

    // ── AEAD: ChaCha20-Poly1305 ───────────────────────────────────────────────

    private fun aesGcmEncrypt(key: ByteArray, nonce: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        // Android crypto provider: "ChaCha20-Poly1305" available from API 28.
        // For broader compat we fall through to BouncyCastle.
        return chachaPolyEncrypt(key, nonce, ad, plaintext)
    }

    private fun chachaPolyEncrypt(key: ByteArray, nonce: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
        val nonceBytes = ByteArray(12).also { n ->
            val nLe = nonce.toLittleEndian()
            System.arraycopy(nLe, 0, n, 4, 8)
        }
        val keySpec = SecretKeySpec(key, "ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonceBytes))
        cipher.updateAAD(ad)
        return cipher.doFinal(plaintext)
    }

    private fun chachaPolyDecrypt(key: ByteArray, nonce: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
        val nonceBytes = ByteArray(12).also { n ->
            val nLe = nonce.toLittleEndian()
            System.arraycopy(nLe, 0, n, 4, 8)
        }
        val keySpec = SecretKeySpec(key, "ChaCha20")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonceBytes))
        cipher.updateAAD(ad)
        return cipher.doFinal(ciphertext)
    }

    private fun Long.toLittleEndian(): ByteArray {
        val b = ByteArray(8)
        var v = this
        for (i in 0..7) { b[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return b
    }

    // ── X25519 ephemeral DH (BouncyCastle) ───────────────────────────────────

    data class EphemeralKeypair(val priv: ByteArray, val pub: ByteArray)

    private fun generateEphemeral(): EphemeralKeypair {
        val privBytes = Random.nextBytes(32)
        val privParam = X25519PrivateKeyParameters(privBytes, 0)
        val pubParam = privParam.generatePublicKey()
        return EphemeralKeypair(privParam.encoded, pubParam.encoded)
    }

    private fun dhEphemeral(privBytes: ByteArray, remotePub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privBytes, 0))
        val out = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(remotePub, 0), out, 0)
        return out
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    /**
     * Build msg1 and pre-compute the state needed to process msg2.
     * Returns: (msg1_bytes, HandshakeState)
     */
    data class IntermediateState(
        val ck: ByteArray,
        val hState: ByteArray,
        val ephPub: ByteArray,
    )

    fun buildMsg1(timestamp: Long): Pair<ByteArray, IntermediateState> {
        // ── Initialize ────────────────────────────────────────────────────────
        val hInit = blake3Hash(PROTOCOL_NAME.toByteArray())
        var ck = hInit.copyOf()
        var h = mixHash(hInit, PROLOGUE)
        // Pre-message: responder static pubkey
        h = mixHash(h, responderStaticPub)

        // ── Ephemeral keypair ─────────────────────────────────────────────────
        val eph = generateEphemeral()
        h = mixHash(h, eph.pub)

        // es: DH(e_priv, rs)
        val es = dhEphemeral(eph.priv, responderStaticPub)
        val (ck1, kEs) = hkdf2(ck, es)
        ck = ck1

        // Encrypt our static pubkey
        val ourStaticPub = KeyManager.getPublicKeyBytes()
        val encS = chachaPolyEncrypt(kEs, 0L, h, ourStaticPub)
        h = mixHash(h, encS)

        // ss: DH(s_priv, rs) — via AndroidKeyStore
        val rsKey = X25519PublicKeyParameters(responderStaticPub, 0)
        // Convert BouncyCastle public key to java.security.PublicKey for KeyManager
        val jceRsPub = bouncyCastlePubToJce(responderStaticPub)
        val ss = KeyManager.dh(jceRsPub)
        val (ck2, kSs) = hkdf2(ck, ss)
        ck = ck2

        // Payload: token (32 B) + timestamp (8 B)
        val tokenBytes = pairingToken ?: ByteArray(32) // zeros for unlock flow
        val tsBytes = timestamp.toLittleEndian()
        val payload = tokenBytes + tsBytes
        val encPayload = chachaPolyEncrypt(kSs, 0L, h, payload)
        h = mixHash(h, encPayload)

        val msg1 = eph.pub + encS + encPayload
        return Pair(msg1, IntermediateState(ck, h, eph.pub))
    }

    /**
     * Process msg2 from the responder, complete the handshake,
     * and return the resulting [TransportSession].
     */
    fun processMsg2(
        msg2: ByteArray,
        state: IntermediateState,
        ourEphPriv: ByteArray,
    ): TransportSession {
        if (msg2.size != 64) throw Exception("msg2 length mismatch")

        val eRPub = msg2.sliceArray(0..31)
        val encResp = msg2.sliceArray(32..63)

        var (ck, h) = state.ck to state.hState
        h = mixHash(h, eRPub)

        // ee: DH(e_priv_i, e_r_pub)
        val ee = dhEphemeral(ourEphPriv, eRPub)
        val (ck1, _) = hkdf2(ck, ee)
        ck = ck1

        // se: DH(s_priv_i, e_r_pub) — via AndroidKeyStore
        val jceERPub = bouncyCastlePubToJce(eRPub)
        val se = KeyManager.dh(jceERPub)
        val (ck2, _) = hkdf2(ck, se)
        ck = ck2

        val (ck3, kFinal) = hkdf2(ck, ByteArray(0))
        ck = ck3

        // Decrypt msg2 payload (session id)
        val sessionIdBytes = chachaPolyDecrypt(kFinal, 0L, h, encResp)
        val sessionId = sessionIdBytes.sliceArray(0..15)

        // Derive transport keys
        val (kInitSend, kRespSend) = hkdf2(ck, ByteArray(0))

        return TransportSession(
            kInitSend = kInitSend,
            kRespSend = kRespSend,
            sessionId = sessionId,
            peerStaticPub = responderStaticPub,
        )
    }

    private fun bouncyCastlePubToJce(rawX25519Pub: ByteArray): java.security.PublicKey {
        // Wrap raw 32-byte X25519 public key in SubjectPublicKeyInfo DER encoding
        // OID for X25519: 1.3.101.110
        val header = byteArrayOf(
            0x30, 0x2a, // SEQUENCE (42 bytes)
            0x30, 0x05, // SEQUENCE (5 bytes)
            0x06, 0x03, 0x2b, 0x65, 0x6e, // OID X25519
            0x03, 0x21, 0x00  // BIT STRING, 33 bytes, 0 unused bits
        )
        val encoded = header + rawX25519Pub
        val kf = java.security.KeyFactory.getInstance("XDH")
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return kf.generatePublic(spec)
    }
}

/** Symmetric keys for the transport phase after a completed Noise IK handshake. */
data class TransportSession(
    val kInitSend: ByteArray, // initiator → responder
    val kRespSend: ByteArray, // responder → initiator
    val sessionId: ByteArray,
    val peerStaticPub: ByteArray,
) {
    private var nSend = 0L
    private var nRecv = 0L

    fun encryptAsInitiator(plaintext: ByteArray): ByteArray =
        chachaPolyEncrypt(kInitSend, nSend++, ByteArray(0), plaintext)

    fun decryptAsInitiator(ciphertext: ByteArray): ByteArray =
        chachaPolyDecrypt(kRespSend, nRecv++, ByteArray(0), ciphertext)

    private fun Long.toLittleEndian(): ByteArray {
        val b = ByteArray(8); var v = this
        for (i in 0..7) { b[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return b
    }

    private fun chachaPolyEncrypt(key: ByteArray, nonce: Long, ad: ByteArray, pt: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
        val nb = ByteArray(12).also { System.arraycopy(nonce.toLittleEndian(), 0, it, 4, 8) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nb))
        cipher.updateAAD(ad)
        return cipher.doFinal(pt)
    }

    private fun chachaPolyDecrypt(key: ByteArray, nonce: Long, ad: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
        val nb = ByteArray(12).also { System.arraycopy(nonce.toLittleEndian(), 0, it, 4, 8) }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nb))
        cipher.updateAAD(ad)
        return cipher.doFinal(ct)
    }
}

private fun javax.crypto.Cipher.updateAAD(ad: ByteArray) { if (ad.isNotEmpty()) updateAAD(ad) }
