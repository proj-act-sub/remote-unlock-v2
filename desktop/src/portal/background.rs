//! XDG Background Portal — requests permission to run in the background
//! without a systemd service. Works correctly inside a Flatpak sandbox.

use anyhow::Result;
use zbus::Connection;
use std::collections::HashMap;

/// Call org.freedesktop.portal.Background.RequestBackground so the app can
/// keep running (and listening for unlock connections) after the window is
/// closed, without any systemd unit.
pub async fn request_background() -> Result<()> {
    let connection = Connection::session().await?;

    let proxy = zbus::ProxyBuilder::new(&connection)
        .interface("org.freedesktop.portal.Background")?
        .path("/org/freedesktop/portal/desktop")?
        .destination("org.freedesktop.portal.Desktop")?
        .build::<zbus::Proxy>()
        .await?;

    // Window handle: empty string is valid for non-Wayland fallback.
    let window_handle = "";

    let mut options: HashMap<&str, zbus::zvariant::Value<'_>> = HashMap::new();
    options.insert("handle_token", "ru_bg_1".into());
    options.insert(
        "reason",
        "RemoteUnlock needs to run in the background to receive unlock requests from your phone.".into(),
    );
    options.insert("autostart", false.into()); // Don't autostart; user launches manually.
    options.insert("dbus-activatable", false.into());

    let _response_path: zbus::zvariant::OwnedObjectPath = proxy
        .call("RequestBackground", &(window_handle, options))
        .await?;

    tracing::info!("Background portal request sent");
    Ok(())
}
