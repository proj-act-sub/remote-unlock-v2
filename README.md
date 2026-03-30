# RemoteUnlock v7

A secure, biometric-gated remote screen-unlock system.

| Component | Stack |
|-----------|-------|
| Linux desktop | Rust · GTK 4 · libadwaita 1.7 · Flatpak |
| Android | Kotlin · Jetpack Compose · Material You |
| Crypto | Noise_IK_25519_ChaChaPoly_BLAKE3 |
| Background (Linux) | XDG Background Portal (no systemd) |
| Pairing | QR over mobile hotspot — zero network discovery |

## Quick Start

### Desktop (Flatpak)
```bash
cd desktop
flatpak-builder --user --install --force-clean _build data/com.remoteunlock.Desktop.flatpak.yml
flatpak run com.remoteunlock.Desktop
```

### Android
```bash
cd android
./gradlew installDebug
```

## Pairing Flow
1. Android device creates a **mobile hotspot**.
2. Connect the Linux PC to that hotspot.
3. Open the desktop app → **Pair** tab shows a QR code.
   - QR encodes: hotspot IP, port, X25519 static pubkey, one-time token.
4. In the Android app tap the QR scanner icon → scan the code.
5. The app performs a **Noise IK** handshake; pairing completes in < 500 ms.

## Unlock Flow
1. Tap the big unlock button in the Android app.
2. **BiometricPrompt** appears (fingerprint / face / PIN).
3. On success a new Noise IK session is opened; the desktop verifies the
   initiator's stored static key and runs the unlock chain.
4. GNOME screen unlocks in ~1 s on LAN.

## Security Properties
- **Forward secrecy** via ephemeral X25519 DH in every session.
- **Mutual authentication** — both sides verify each other's static keys.
- **BLAKE3** replaces SHA-256/BLAKE2s everywhere (KDF, MAC, hashing).
- **ChaCha20-Poly1305** for all AEAD; no AES.
- **One-time pairing token** prevents replay of the pairing QR.
- **No network discovery** — no mDNS, no multicast UDP.
- **Hotspot-only** — the QR IP is the device's hotspot interface address.
- **Flatpak Background Portal** — no persistent systemd unit needed.
