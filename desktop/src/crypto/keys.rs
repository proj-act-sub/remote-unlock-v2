//! Key management: persist X25519 static keypair and peer list via
//! libsecret (GNOME Keyring) and a JSON sidecar file.

use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, path::PathBuf};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

const SERVICE: &str = "com.remoteunlock.Desktop";
const LABEL_STATIC_KEY: &str = "RemoteUnlock static private key";
const ATTR_ACCOUNT: &str = "static-key-v1";

/// Stored peer (Android device)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Peer {
    pub name: String,
    /// base64url X25519 static public key
    pub x25519_pub: String,
    pub added_at: u64,
}

pub struct KeyStore {
    pub static_secret: StaticSecret,
    pub static_public: PublicKey,
    peers: HashMap<String, Peer>, // key = hex(pubkey[..8])
    peers_path: PathBuf,
}

impl KeyStore {
    pub async fn load_or_create() -> Result<Self> {
        let config_dir = dirs::config_dir()
            .ok_or_else(|| anyhow!("no config dir"))?
            .join("remoteunlock");
        std::fs::create_dir_all(&config_dir)?;
        let peers_path = config_dir.join("peers.json");

        let static_secret = Self::load_or_create_static_key().await?;
        let static_public = PublicKey::from(&static_secret);

        let peers = if peers_path.exists() {
            let bytes = std::fs::read(&peers_path)?;
            serde_json::from_slice(&bytes).unwrap_or_default()
        } else {
            HashMap::new()
        };

        Ok(Self { static_secret, static_public, peers, peers_path })
    }

    async fn load_or_create_static_key() -> Result<StaticSecret> {
        use secret_service::{EncryptionType, SecretService};

        let ss = SecretService::connect(EncryptionType::Dh).await?;
        let collection = ss.get_default_collection().await?;

        // Try to load existing key
        let attrs = vec![("account", ATTR_ACCOUNT)];
        let items = collection.search_items(attrs.clone()).await?;

        if let Some(item) = items.first() {
            let secret = item.get_secret().await?;
            if secret.len() == 32 {
                let arr: [u8; 32] = secret.try_into().map_err(|_| anyhow!("bad key"))?;
                return Ok(StaticSecret::from(arr));
            }
        }

        // Generate new key
        let mut raw = [0u8; 32];
        rand::RngCore::fill_bytes(&mut OsRng, &mut raw);
        let secret = StaticSecret::from(raw);
        raw.zeroize();

        collection
            .create_item(
                LABEL_STATIC_KEY,
                attrs,
                secret.as_bytes(),
                true,
                "application/octet-stream",
            )
            .await?;

        Ok(secret)
    }

    pub fn add_peer(&mut self, peer: Peer) -> Result<()> {
        let pub_bytes = URL_SAFE_NO_PAD.decode(&peer.x25519_pub)?;
        let id = hex::encode(&pub_bytes[..8]);
        self.peers.insert(id, peer);
        self.save_peers()
    }

    pub fn remove_peer(&mut self, id: &str) -> Result<()> {
        self.peers.remove(id);
        self.save_peers()
    }

    pub fn peers(&self) -> &HashMap<String, Peer> {
        &self.peers
    }

    pub fn peer_by_pubkey(&self, pubkey: &[u8; 32]) -> Option<&Peer> {
        let id = hex::encode(&pubkey[..8]);
        self.peers.get(&id)
    }

    pub fn is_known_peer(&self, pubkey: &[u8; 32]) -> bool {
        self.peer_by_pubkey(pubkey).is_some()
    }

    fn save_peers(&self) -> Result<()> {
        let bytes = serde_json::to_vec_pretty(&self.peers)?;
        std::fs::write(&self.peers_path, bytes)?;
        Ok(())
    }
}
