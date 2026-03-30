# RemoteUnlock Wire Protocol

## Cryptographic Primitives

| Role | Algorithm |
|------|-----------|
| DH | X25519 |
| AEAD | ChaCha20-Poly1305 (96-bit nonce, 128-bit tag) |
| Hash / KDF | BLAKE3 |
| Noise pattern | IK |

Protocol name string: `Noise_IK_25519_ChaChaPoly_BLAKE3`

## BLAKE3 HKDF

Standard Noise HKDF uses HMAC-HASH(key, data). We substitute:

```
BLAKE3_PRF(key[32], data) -> [32]  =  blake3::keyed_hash(key, data)

HKDF2(ck, ikm) -> (ck', k):
  tmp = BLAKE3_PRF(ck, ikm)
  ck' = BLAKE3_PRF(tmp, [0x01])
  k   = BLAKE3_PRF(tmp, ck' || [0x02])

HKDF3(ck, ikm) -> (ck', k, k2):
  tmp = BLAKE3_PRF(ck, ikm)
  ck' = BLAKE3_PRF(tmp, [0x01])
  k   = BLAKE3_PRF(tmp, ck' || [0x02])
  k2  = BLAKE3_PRF(tmp, k   || [0x03])
```

## Noise IK Handshake

Prologue: `b"remoteunlock-v7"`

### Initiator (Android) → Responder (Desktop): msg1

```
e           32 B   ephemeral pubkey
enc_s       48 B   AEAD(k_es, n=0, h, s_pub_i)   -- 32 plaintext + 16 tag
enc_payload 56 B   AEAD(k_ss, n=0, h, token||ts)  -- 40 plaintext + 16 tag

h  = BLAKE3(h || e)
h  = BLAKE3(h || enc_s)
h  = BLAKE3(h || enc_payload)
```

After es: `(ck, k_es) = HKDF2(ck, DH(e_priv, s_pub_r))`
After ss: `(ck, k_ss) = HKDF2(ck, DH(s_priv_i, s_pub_r))`

### Responder → Initiator: msg2

```
e_r         32 B   responder ephemeral pubkey
enc_resp    32 B   AEAD(k_final, n=0, h, empty)   -- 0 + 16 tag + 16 session_id

h  = BLAKE3(h || e_r)
h  = BLAKE3(h || enc_resp)
```

After ee: `(ck, k_ee) = HKDF2(ck, DH(e_r_priv, e_pub_i))`
After se: `(ck, k_se) = HKDF2(ck, DH(e_r_priv, s_pub_i))`
After se: `(ck, k_final) = HKDF2(ck, [])`

### Transport Keys

After handshake:
```
(k_send_i, k_recv_i) = HKDF2(ck, [])   // initiator perspective
k_send_i == k_recv_r,  k_recv_i == k_send_r
```

Transport messages use a 64-bit little-endian counter nonce (bytes 4-11, bytes 0-3 zero).

## QR Payload (JSON)

```json
{
  "v": 1,
  "pk": "<base64url X25519 static pubkey of desktop, 32 B>",
  "ep": "<hotspot-ip>:<port>",
  "tok": "<base64url 32-byte one-time pairing nonce>",
  "name": "My PC"
}
```

The pairing token is verified in the handshake payload and is single-use.

## Unlock Request (after transport keys established)

```json
{ "cmd": "unlock", "sid": "<session-id>", "ts": <unix-ms> }
```

Response: `{ "ok": true }` or `{ "ok": false, "err": "..." }`
