//! Screen-unlock strategy chain for GNOME 50 / Wayland.
//! Tries D-Bus methods first (most reliable), then fallbacks.

use anyhow::{anyhow, Result};
use tokio::process::Command;
use zbus::Connection;

pub struct UnlockChain;

impl UnlockChain {
    pub async fn execute() -> Result<()> {
        // Strategy 1: GNOME ScreenSaver via D-Bus (GNOME 40+)
        if gnome_screensaver_dbus().await.is_ok() {
            return Ok(());
        }
        // Strategy 2: loginctl unlock-sessions (systemd-logind, any DE)
        if loginctl_unlock().await.is_ok() {
            return Ok(());
        }
        // Strategy 3: gdbus command-line (fallback)
        if gdbus_unlock().await.is_ok() {
            return Ok(());
        }
        Err(anyhow!("All unlock strategies failed"))
    }
}

async fn gnome_screensaver_dbus() -> Result<()> {
    let conn = Connection::session().await?;
    let proxy = zbus::ProxyBuilder::new(&conn)
        .interface("org.gnome.ScreenSaver")?
        .path("/org/gnome/ScreenSaver")?
        .destination("org.gnome.ScreenSaver")?
        .build::<zbus::Proxy>()
        .await?;

    proxy.call::<_, _, ()>("SetActive", &(false,)).await?;
    Ok(())
}

async fn loginctl_unlock() -> Result<()> {
    let status = Command::new("loginctl")
        .args(["unlock-sessions"])
        .status()
        .await?;
    if status.success() { Ok(()) } else { Err(anyhow!("loginctl failed")) }
}

async fn gdbus_unlock() -> Result<()> {
    let status = Command::new("gdbus")
        .args([
            "call", "--session",
            "--dest", "org.gnome.ScreenSaver",
            "--object-path", "/org/gnome/ScreenSaver",
            "--method", "org.gnome.ScreenSaver.SetActive",
            "false",
        ])
        .status()
        .await?;
    if status.success() { Ok(()) } else { Err(anyhow!("gdbus failed")) }
}
