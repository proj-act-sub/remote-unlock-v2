package com.remoteunlock.qr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QrPayload(
    /** Version: must be 1 */
    val v: Int,
    /** base64url X25519 static public key of the desktop (32 bytes) */
    val pk: String,
    /** "ip:port" endpoint on the hotspot interface */
    val ep: String,
    /** base64url 32-byte one-time pairing token */
    val tok: String,
    /** Human-readable name of the desktop */
    val name: String,
)
