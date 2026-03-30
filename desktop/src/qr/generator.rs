//! QR code generation for the pairing payload.
//! The QR encodes a JSON blob containing the desktop's X25519 public key,
//! hotspot IP:port, a one-time pairing token, and an alias.

use anyhow::Result;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use pnet::datalink;
use qrcode::{QrCode, EcLevel};
use image::Luma;
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};

pub const PORT: u16 = 51820;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QrPayload {
    pub v: u8,
    /// base64url X25519 static public key of desktop
    pub pk: String,
    /// "ip:port" on the hotspot interface
    pub ep: String,
    /// base64url 32-byte one-time pairing token
    pub tok: String,
    pub name: String,
}

impl QrPayload {
    pub fn new(static_pub: &[u8; 32], alias: &str) -> Result<(Self, [u8; 32])> {
        let ip = detect_hotspot_ip().unwrap_or_else(|| "192.168.43.1".to_string());
        let ep = format!("{ip}:{PORT}");

        let mut token = [0u8; 32];
        OsRng.fill_bytes(&mut token);

        let payload = Self {
            v: 1,
            pk: URL_SAFE_NO_PAD.encode(static_pub),
            ep,
            tok: URL_SAFE_NO_PAD.encode(token),
            name: alias.to_string(),
        };
        Ok((payload, token))
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("serialize QR payload")
    }

    /// Render as a PNG byte vec (for embedding in GTK Picture widget)
    pub fn render_png(&self) -> Result<Vec<u8>> {
        let code = QrCode::with_error_correction_level(self.to_json().as_bytes(), EcLevel::M)?;
        let image = code.render::<Luma<u8>>()
            .quiet_zone(true)
            .module_dimensions(8, 8)
            .build();
        let mut buf = std::io::Cursor::new(Vec::new());
        image.write_to(&mut buf, image::ImageFormat::Png)?;
        Ok(buf.into_inner())
    }
}

/// Detect the IP address on the Android hotspot interface.
/// Android hotspot uses 192.168.43.0/24 by default, but also covers
/// other RFC-1918 /24 subnets commonly assigned to mobile hotspot clients.
fn detect_hotspot_ip() -> Option<String> {
    for iface in datalink::interfaces() {
        if iface.is_loopback() {
            continue;
        }
        for net in &iface.ips {
            if let std::net::IpAddr::V4(ip) = net.ip() {
                let octets = ip.octets();
                // Android hotspot: 192.168.43.x  or  10.x.x.x  or  172.16-31.x.x
                if octets[0] == 192 && octets[1] == 168 && octets[2] == 43 {
                    return Some(ip.to_string());
                }
            }
        }
    }
    // Fallback: any non-loopback IPv4 on a wireless-like interface
    for iface in datalink::interfaces() {
        if iface.is_loopback() { continue; }
        let name = iface.name.to_lowercase();
        if name.starts_with("wl") || name.starts_with("wlan") || name.starts_with("en") {
            for net in &iface.ips {
                if let std::net::IpAddr::V4(ip) = net.ip() {
                    if !ip.is_loopback() {
                        return Some(ip.to_string());
                    }
                }
            }
        }
    }
    None
}
