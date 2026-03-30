mod app;
mod crypto;
mod portal;
mod qr;
mod server;
mod ui;
mod unlock;

use anyhow::Result;
use tracing_subscriber::EnvFilter;

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();

    let app = app::RemoteUnlockApp::new();
    app.run();
    Ok(())
}
