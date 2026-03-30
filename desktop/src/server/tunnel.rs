//! TCP server that accepts Noise IK connections from paired Android devices.
//! Each connection performs a fresh handshake (forward secrecy) and then
//! receives a JSON command (e.g. {"cmd":"unlock"}).

use anyhow::Result;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::{broadcast, RwLock};
use tracing::{info, warn, error};

use crate::crypto::keys::KeyStore;
use crate::crypto::noise::{ResponderHandshake, MSG1_LEN};
use crate::unlock::strategies::UnlockChain;
use crate::qr::generator::PORT;

pub struct TunnelServer {
    keystore: Arc<RwLock<KeyStore>>,
    /// Current one-time pairing token (if a QR is displayed, else None)
    pairing_token: Arc<RwLock<Option<[u8; 32]>>>,
    shutdown: broadcast::Sender<()>,
}

impl TunnelServer {
    pub fn new(keystore: Arc<RwLock<KeyStore>>) -> Self {
        let (shutdown, _) = broadcast::channel(1);
        Self {
            keystore,
            pairing_token: Arc::new(RwLock::new(None)),
            shutdown,
        }
    }

    pub fn pairing_token(&self) -> Arc<RwLock<Option<[u8; 32]>>> {
        Arc::clone(&self.pairing_token)
    }

    pub fn shutdown_sender(&self) -> broadcast::Sender<()> {
        self.shutdown.clone()
    }

    pub async fn run(&self) -> Result<()> {
        let addr = format!("0.0.0.0:{PORT}");
        let listener = TcpListener::bind(&addr).await?;
        info!("Tunnel server listening on {addr}");

        let mut shutdown_rx = self.shutdown.subscribe();

        loop {
            tokio::select! {
                Ok((stream, peer_addr)) = listener.accept() => {
                    info!("Connection from {peer_addr}");
                    let ks = Arc::clone(&self.keystore);
                    let pt = Arc::clone(&self.pairing_token);
                    tokio::spawn(async move {
                        if let Err(e) = handle_connection(stream, ks, pt).await {
                            warn!("Connection error from {peer_addr}: {e}");
                        }
                    });
                }
                _ = shutdown_rx.recv() => {
                    info!("Tunnel server shutting down");
                    break;
                }
            }
        }
        Ok(())
    }
}

async fn handle_connection(
    mut stream: tokio::net::TcpStream,
    keystore: Arc<RwLock<KeyStore>>,
    pairing_token: Arc<RwLock<Option<[u8; 32]>>>,
) -> Result<()> {
    // ── Read msg1 ────────────────────────────────────────────────────────────
    let mut msg1 = vec![0u8; crate::crypto::noise::MSG1_LEN];
    stream.read_exact(&mut msg1).await?;

    let (static_secret, expected_token) = {
        let ks = keystore.read().await;
        let pt = pairing_token.read().await;
        (ks.static_secret.clone(), *pt)
    };

    let hs = ResponderHandshake::new(static_secret, expected_token);
    let (msg2, mut session) = hs.process_msg1(&msg1)?;

    // ── Send msg2 ────────────────────────────────────────────────────────────
    stream.write_all(&msg2).await?;

    // ── Check peer ───────────────────────────────────────────────────────────
    let is_pairing = expected_token.is_some();
    {
        let ks = keystore.read().await;
        if !is_pairing && !ks.is_known_peer(&session.peer_static) {
            return Err(anyhow::anyhow!("Unknown peer — not in peer list"));
        }
    }

    if is_pairing {
        // Consume the one-time token
        let mut pt = pairing_token.write().await;
        *pt = None;
        info!("Pairing completed with peer {:?}", hex::encode(&session.peer_static[..8]));
        // Peer will be added via the UI after this returns
    } else {
        info!("Unlock request from known peer {:?}", hex::encode(&session.peer_static[..8]));
        // ── Read encrypted command ────────────────────────────────────────────
        let mut len_buf = [0u8; 4];
        stream.read_exact(&mut len_buf).await?;
        let len = u32::from_le_bytes(len_buf) as usize;
        if len > 4096 {
            return Err(anyhow::anyhow!("Message too large"));
        }
        let mut enc_cmd = vec![0u8; len];
        stream.read_exact(&mut enc_cmd).await?;

        let cmd_bytes = session.decrypt_as_responder(&enc_cmd)?;
        let cmd: serde_json::Value = serde_json::from_slice(&cmd_bytes)?;

        let ok = if cmd["cmd"].as_str() == Some("unlock") {
            match UnlockChain::execute().await {
                Ok(_) => {
                    info!("Screen unlocked successfully");
                    true
                }
                Err(e) => {
                    error!("Unlock failed: {e}");
                    false
                }
            }
        } else {
            false
        };

        let resp = session.encrypt_as_responder(
            serde_json::json!({"ok": ok}).to_string().as_bytes()
        );
        let len = (resp.len() as u32).to_le_bytes();
        stream.write_all(&len).await?;
        stream.write_all(&resp).await?;
    }

    Ok(())
}
