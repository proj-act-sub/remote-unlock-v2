package com.remoteunlock.data

import kotlinx.serialization.Serializable

@Serializable
data class Peer(
    val id: String,
    val name: String,
    /** base64url X25519 static public key of the desktop. */
    val x25519Pub: String,
    val host: String,
    val port: Int,
    val addedAt: Long,
) {
    /** Short fingerprint for display: first 8 chars of base64-encoded pub. */
    val fingerprintShort: String
        get() = x25519Pub.take(8) + "…"
}
