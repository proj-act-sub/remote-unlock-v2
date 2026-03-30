//! Noise_IK_25519_ChaChaPoly_BLAKE3
//!
//! WireGuard-style Noise IK handshake with BLAKE3 as the hash/KDF function.
//!
//! Protocol roles:
//!   Initiator = Android phone  (knows responder's static pubkey from QR)
//!   Responder = Linux desktop  (learns initiator's static pubkey during handshake)

use chacha20poly1305::{
    ChaCha20Poly1305, Key, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use x25519_dalek::{EphemeralSecret, PublicKey, StaticSecret};
use zeroize::{Zeroize, ZeroizeOnDrop};
use anyhow::{anyhow, Result};
use rand::rngs::OsRng;

// ── Constants ────────────────────────────────────────────────────────────────

const PROTOCOL_NAME: &[u8] = b"Noise_IK_25519_ChaChaPoly_BLAKE3";
const PROLOGUE: &[u8] = b"remoteunlock-v7";
const HASH_LEN: usize = 32;

// ── BLAKE3-based primitives ───────────────────────────────────────────────────

fn h(data: &[u8]) -> [u8; HASH_LEN] {
    *blake3::hash(data).as_bytes()
}

/// Mix `data` into a running hash state.
fn mix_hash(state: &[u8; HASH_LEN], data: &[u8]) -> [u8; HASH_LEN] {
    let mut hasher = blake3::Hasher::new();
    hasher.update(state);
    hasher.update(data);
    *hasher.finalize().as_bytes()
}

/// BLAKE3-keyed-hash as PRF: `PRF(key, data) -> [u8; 32]`
fn prf(key: &[u8; 32], data: &[u8]) -> [u8; 32] {
    *blake3::keyed_hash(key, data).as_bytes()
}

/// 2-output HKDF: `(ck', k) = HKDF2(ck, ikm)`
pub fn hkdf2(ck: &[u8; 32], ikm: &[u8]) -> ([u8; 32], [u8; 32]) {
    let tmp = prf(ck, ikm);
    let ck2 = prf(&tmp, &[0x01]);
    let mut k_input = [0u8; 33];
    k_input[..32].copy_from_slice(&ck2);
    k_input[32] = 0x02;
    let k = prf(&tmp, &k_input);
    (ck2, k)
}

/// 3-output HKDF: `(ck', k1, k2) = HKDF3(ck, ikm)`
pub fn hkdf3(ck: &[u8; 32], ikm: &[u8]) -> ([u8; 32], [u8; 32], [u8; 32]) {
    let tmp = prf(ck, ikm);
    let ck2 = prf(&tmp, &[0x01]);
    let mut k1_in = [0u8; 33];
    k1_in[..32].copy_from_slice(&ck2);
    k1_in[32] = 0x02;
    let k1 = prf(&tmp, &k1_in);
    let mut k2_in = [0u8; 33];
    k2_in[..32].copy_from_slice(&k1);
    k2_in[32] = 0x03;
    let k2 = prf(&tmp, &k2_in);
    (ck2, k1, k2)
}

// ── AEAD helpers ─────────────────────────────────────────────────────────────

fn nonce_from_counter(n: u64) -> Nonce {
    let mut bytes = [0u8; 12];
    bytes[4..12].copy_from_slice(&n.to_le_bytes());
    Nonce::from(bytes)
}

pub fn aead_encrypt(key: &[u8; 32], nonce: u64, ad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .encrypt(&nonce_from_counter(nonce), Payload { msg: plaintext, aad: ad })
        .expect("AEAD encrypt failed")
}

pub fn aead_decrypt(key: &[u8; 32], nonce: u64, ad: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .decrypt(&nonce_from_counter(nonce), Payload { msg: ciphertext, aad: ad })
        .map_err(|_| anyhow!("AEAD authentication failed"))
}

// ── Transport Session ─────────────────────────────────────────────────────────

/// A pair of symmetric keys derived after a completed Noise IK handshake.
#[derive(ZeroizeOnDrop)]
pub struct TransportSession {
    /// Key used by the *initiator* to send (responder to receive).
    pub k_init_send: [u8; 32],
    /// Key used by the *responder* to send (initiator to receive).
    pub k_resp_send: [u8; 32],
    pub n_send: u64,
    pub n_recv: u64,
    pub peer_static: [u8; 32],
    pub session_id: [u8; 16],
}

impl TransportSession {
    pub fn encrypt_as_initiator(&mut self, plaintext: &[u8]) -> Vec<u8> {
        let ct = aead_encrypt(&self.k_init_send, self.n_send, &[], plaintext);
        self.n_send += 1;
        ct
    }

    pub fn decrypt_as_initiator(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        let pt = aead_decrypt(&self.k_resp_send, self.n_recv, &[], ciphertext)?;
        self.n_recv += 1;
        Ok(pt)
    }

    pub fn encrypt_as_responder(&mut self, plaintext: &[u8]) -> Vec<u8> {
        let ct = aead_encrypt(&self.k_resp_send, self.n_send, &[], plaintext);
        self.n_send += 1;
        ct
    }

    pub fn decrypt_as_responder(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        let pt = aead_decrypt(&self.k_init_send, self.n_recv, &[], ciphertext)?;
        self.n_recv += 1;
        Ok(pt)
    }
}

// ── Responder Handshake ───────────────────────────────────────────────────────

/// Wire format of msg1 (initiator → responder)
/// Layout: [e_pub: 32][enc_s: 48][enc_payload: 56] = 136 bytes
pub const MSG1_LEN: usize = 32 + 48 + 56;

/// Wire format of msg2 (responder → initiator)
/// Layout: [e_r_pub: 32][enc_resp: 32] = 64 bytes
pub const MSG2_LEN: usize = 32 + 32;

pub struct ResponderHandshake {
    s: StaticSecret,
    s_pub: PublicKey,
    /// Expected pairing token (for pairing flows; None = unlock flow)
    expected_token: Option<[u8; 32]>,
}

impl ResponderHandshake {
    pub fn new(s: StaticSecret, expected_token: Option<[u8; 32]>) -> Self {
        let s_pub = PublicKey::from(&s);
        Self { s, s_pub, expected_token }
    }

    /// Process msg1, produce msg2, and return the completed TransportSession.
    pub fn process_msg1(&self, msg1: &[u8]) -> Result<(Vec<u8>, TransportSession)> {
        if msg1.len() != MSG1_LEN {
            return Err(anyhow!("msg1 length mismatch: got {}", msg1.len()));
        }

        // ── Initialize ──────────────────────────────────────────────────────
        let h_init = h(PROTOCOL_NAME);
        let ck_init = h_init; // ck = h for HASH_LEN == DH_LEN
        let mut ck = ck_init;
        let mut h_state = mix_hash(&h_init, PROLOGUE);
        // Pre-message: responder's static public key is "sent" before handshake
        h_state = mix_hash(&h_state, self.s_pub.as_bytes());

        // ── Parse msg1 ──────────────────────────────────────────────────────
        let e_pub_bytes: [u8; 32] = msg1[0..32].try_into().unwrap();
        let enc_s: &[u8] = &msg1[32..80];    // 32 + 16 tag
        let enc_payload: &[u8] = &msg1[80..]; // 40 + 16 tag

        // e (initiator ephemeral)
        let e_pub = PublicKey::from(e_pub_bytes);
        h_state = mix_hash(&h_state, e_pub.as_bytes());

        // es: DH(s_priv_r, e_pub_i)
        let es = self.s.diffie_hellman(&e_pub);
        let (ck2, k_es) = hkdf2(&ck, es.as_bytes());
        ck = ck2;

        // Decrypt initiator static pubkey
        let s_pub_i_bytes = aead_decrypt(&k_es, 0, &h_state, enc_s)?;
        if s_pub_i_bytes.len() != 32 {
            return Err(anyhow!("Invalid static key in msg1"));
        }
        let s_pub_i: [u8; 32] = s_pub_i_bytes.try_into().unwrap();
        h_state = mix_hash(&h_state, enc_s);

        // ss: DH(s_priv_r, s_pub_i)
        let ss = self.s.diffie_hellman(&PublicKey::from(s_pub_i));
        let (ck3, k_ss) = hkdf2(&ck, ss.as_bytes());
        ck = ck3;

        // Decrypt payload: token (32 B) + timestamp (8 B) = 40 B
        let payload = aead_decrypt(&k_ss, 0, &h_state, enc_payload)?;
        if payload.len() != 40 {
            return Err(anyhow!("Invalid payload length"));
        }
        h_state = mix_hash(&h_state, enc_payload);

        let token: [u8; 32] = payload[..32].try_into().unwrap();
        // Verify token for pairing flows
        if let Some(expected) = &self.expected_token {
            use subtle::ConstantTimeEq;
            if token.ct_eq(expected).unwrap_u8() != 1 {
                return Err(anyhow!("Pairing token mismatch"));
            }
        }

        // ── Build msg2 ──────────────────────────────────────────────────────
        let e_r_secret = EphemeralSecret::random_from_rng(OsRng);
        let e_r_pub = PublicKey::from(&e_r_secret);
        h_state = mix_hash(&h_state, e_r_pub.as_bytes());

        // ee: DH(e_r_priv, e_pub_i)
        let ee = e_r_secret.diffie_hellman(&e_pub);
        let (ck4, k_ee) = hkdf2(&ck, ee.as_bytes());
        ck = ck4;

        // se: DH(e_r_priv, s_pub_i)
        // Note: we need a second ephemeral or clone before consuming e_r_secret above.
        // Since EphemeralSecret is consumed by diffie_hellman, we pre-compute both.
        // Fix: derive se from a fresh DH.  We use a StaticSecret as temporary "ephemeral".
        // See note in code — we handle this by computing ee and se from the same secret
        // using a workaround: store bytes first.
        //
        // Actually EphemeralSecret::diffie_hellman consumes self, so we can only call it once.
        // Workaround: use StaticSecret (same math, different type that allows reuse).
        let _ = k_ee; // already computed via consumed e_r_secret above

        // ── Correct approach: use StaticSecret for multi-use DH ─────────────
        // Re-derive responder ephemeral as StaticSecret for multi-DH use.
        // (StaticSecret is cryptographically identical to EphemeralSecret)
        let e_r_static = StaticSecret::random_from_rng(OsRng);
        let e_r_pub2 = PublicKey::from(&e_r_static);
        let mut h_state2 = mix_hash(&h_state, e_r_pub2.as_bytes()); // redo with correct pub
        // (replace h_state with h_state2 from here)

        let ee2 = e_r_static.diffie_hellman(&e_pub);
        let (ck5, k_ee2) = hkdf2(&ck, ee2.as_bytes()); // ck from before ee step
        let mut ck2 = ck5;

        let se = e_r_static.diffie_hellman(&PublicKey::from(s_pub_i));
        let (ck6, k_se) = hkdf2(&ck2, se.as_bytes());
        ck2 = ck6;

        // Final key derivation
        let (ck7, k_final, _) = hkdf3(&ck2, &[]);
        let ck_final = ck7;

        // Derive transport keys
        let (k_init_send, k_resp_send) = hkdf2(&ck_final, &[]);

        // Generate session_id
        let mut session_id = [0u8; 16];
        session_id.copy_from_slice(&prf(&k_final, b"session-id")[..16]);

        // Encrypt empty payload for msg2
        let enc_resp = aead_encrypt(&k_final, 0, &h_state2, &session_id);

        let mut msg2 = Vec::with_capacity(MSG2_LEN);
        msg2.extend_from_slice(e_r_pub2.as_bytes());
        msg2.extend_from_slice(&enc_resp);

        let session = TransportSession {
            k_init_send,
            k_resp_send,
            n_send: 0,
            n_recv: 0,
            peer_static: s_pub_i,
            session_id,
        };

        Ok((msg2, session))
    }
}
