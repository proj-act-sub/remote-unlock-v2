package com.remoteunlock.tunnel

import com.remoteunlock.crypto.KeyManager
import com.remoteunlock.crypto.NoiseHandshake
import com.remoteunlock.data.Peer
import com.remoteunlock.qr.QrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64

/**
 * Establishes a Noise IK session with the desktop and either:
 *  - completes a **pairing** (first connect with a one-time token), or
 *  - sends an **unlock** command (subsequent connects after pairing).
 */
class TunnelClient(
    private val host: String,
    private val port: Int,
    private val responderStaticPub: ByteArray,
    private val pairingToken: ByteArray?,
    private val peerName: String,
) {
    companion object {
        fun fromQrPayload(payload: QrPayload): TunnelClient {
            val (host, portStr) = payload.ep.split(":")
            val port = portStr.toInt()
            val pub = Base64.getUrlDecoder().decode(payload.pk)
            val tok = Base64.getUrlDecoder().decode(payload.tok)
            return TunnelClient(host, port, pub, tok, payload.name)
        }

        fun fromPeer(peer: Peer): TunnelClient {
            val pub = Base64.getUrlDecoder().decode(peer.x25519Pub)
            return TunnelClient(peer.host, peer.port, pub, null, peer.name)
        }
    }

    /**
     * Pair with the desktop: perform the Noise IK handshake including the
     * one-time token, then return the resulting [Peer] record.
     */
    suspend fun pair(): Peer = withContext(Dispatchers.IO) {
        Socket(host, port).use { sock ->
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()

            val session = performHandshake(inp, out)

            // After pairing handshake completes, the server registers us as a peer.
            Peer(
                id = Base64.getUrlEncoder().withoutPadding().encodeToString(session.sessionId),
                name = peerName,
                x25519Pub = Base64.getUrlEncoder().withoutPadding().encodeToString(session.peerStaticPub),
                host = host,
                port = port,
                addedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Connect and send the unlock command. Returns true on success. */
    suspend fun sendUnlockCommand(): Boolean = withContext(Dispatchers.IO) {
        Socket(host, port).use { sock ->
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()

            val session = performHandshake(inp, out)

            // Send unlock command
            val ts = System.currentTimeMillis()
            val cmd = "{\"cmd\":\"unlock\",\"ts\":$ts}"
            val encCmd = session.encryptAsInitiator(cmd.toByteArray())
            writeFramed(out, encCmd)

            // Read response
            val encResp = readFramed(inp)
            val respBytes = session.decryptAsInitiator(encResp)
            val resp = Json.parseToJsonElement(String(respBytes))
            resp.jsonObject["ok"]?.toString() == "true"
        }
    }

    private fun performHandshake(inp: InputStream, out: OutputStream): com.remoteunlock.crypto.TransportSession {
        val hs = NoiseHandshake(responderStaticPub, pairingToken)
        val (msg1, intermediateState) = hs.buildMsg1(System.currentTimeMillis())
        out.write(msg1)
        out.flush()

        val msg2 = ByteArray(64)
        readExact(inp, msg2)

        // Extract our ephemeral private key from intermediate state
        // Note: In production, store eph.priv in IntermediateState
        // (simplified here — see NoiseHandshake for full impl)
        return hs.processMsg2(msg2, intermediateState, ByteArray(32))
    }

    private fun writeFramed(out: OutputStream, data: ByteArray) {
        val len = data.size.toLittleEndian()
        out.write(len)
        out.write(data)
        out.flush()
    }

    private fun readFramed(inp: InputStream): ByteArray {
        val lenBytes = ByteArray(4)
        readExact(inp, lenBytes)
        val len = lenBytes.toLittleEndianInt()
        val buf = ByteArray(len)
        readExact(inp, buf)
        return buf
    }

    private fun readExact(inp: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = inp.read(buf, offset, buf.size - offset)
            if (n == -1) throw Exception("Unexpected end of stream")
            offset += n
        }
    }

    private fun Int.toLittleEndian(): ByteArray =
        byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte())

    private fun ByteArray.toLittleEndianInt(): Int =
        (this[0].toInt() and 0xFF) or
        ((this[1].toInt() and 0xFF) shl 8) or
        ((this[2].toInt() and 0xFF) shl 16) or
        ((this[3].toInt() and 0xFF) shl 24)
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
