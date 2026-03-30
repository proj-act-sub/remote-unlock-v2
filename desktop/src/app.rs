use gtk4::prelude::*;
use libadwaita::prelude::*;
use libadwaita as adw;
use gtk4 as gtk;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::crypto::keys::KeyStore;
use crate::server::tunnel::TunnelServer;
use crate::ui::window::MainWindow;

pub struct RemoteUnlockApp;

impl RemoteUnlockApp {
    pub fn new() -> Self {
        Self
    }

    pub fn run(&self) {
        // Resources compiled at build time
        let resource_bytes = include_bytes!(concat!(env!("OUT_DIR"), "/remoteunlock.gresource"));
        let resource_data = glib::Bytes::from_static(resource_bytes);
        let res = gio::Resource::from_data(&resource_data).expect("resource load");
        gio::resources_register(&res);

        let app = adw::Application::builder()
            .application_id("com.remoteunlock.Desktop")
            .flags(gio::ApplicationFlags::HANDLES_COMMAND_LINE)
            .build();

        app.connect_activate(|app| {
            glib::MainContext::default().spawn_local(async move {
                // Request background portal permission on first run
                if let Err(e) = crate::portal::background::request_background().await {
                    tracing::warn!("Background portal: {e}");
                }
            });
            MainWindow::new(app).present();
        });

        app.run();
    }
}
