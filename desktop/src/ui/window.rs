//! Main AdwApplicationWindow with AdwNavigationView.
//! Pages: Pair (QR display) and Devices (paired phone list).

use gtk4::prelude::*;
use libadwaita::prelude::*;
use libadwaita as adw;
use gtk4 as gtk;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::crypto::keys::KeyStore;
use crate::qr::generator::QrPayload;
use crate::server::tunnel::TunnelServer;

pub struct MainWindow;

impl MainWindow {
    pub fn new(app: &adw::Application) -> adw::ApplicationWindow {
        let window = adw::ApplicationWindow::builder()
            .application(app)
            .title("RemoteUnlock")
            .default_width(480)
            .default_height(640)
            .build();

        // ── Navigation view (Pair ↔ Devices) ─────────────────────────────────
        let nav_view = adw::NavigationView::new();

        // Pair page
        let pair_page_content = crate::ui::pair_page::build_pair_page();
        let pair_nav_page = adw::NavigationPage::builder()
            .title("Pair Phone")
            .tag("pair")
            .child(&pair_page_content)
            .build();

        // Devices page
        let devices_page_content = crate::ui::devices_page::build_devices_page();
        let devices_nav_page = adw::NavigationPage::builder()
            .title("Devices")
            .tag("devices")
            .child(&devices_page_content)
            .build();

        nav_view.add(&pair_nav_page);
        nav_view.add(&devices_nav_page);

        // ── Tab bar via AdwViewSwitcher ───────────────────────────────────────
        let view_stack = adw::ViewStack::new();
        view_stack.add_titled_with_icon(&pair_page_content,   Some("pair"),    "Pair",    "qrscanner-symbolic");
        view_stack.add_titled_with_icon(&devices_page_content, Some("devices"), "Devices", "phone-symbolic");

        let switcher = adw::ViewSwitcher::builder()
            .stack(&view_stack)
            .policy(adw::ViewSwitcherPolicy::Wide)
            .build();

        let header = adw::HeaderBar::builder()
            .title_widget(&switcher)
            .build();

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&view_stack));

        // ── Banner: hotspot reminder ──────────────────────────────────────────
        let banner = adw::Banner::builder()
            .title("Connect your PC to the phone's hotspot before scanning")
            .build();
        banner.set_revealed(true);

        let outer_box = gtk::Box::new(gtk::Orientation::Vertical, 0);
        outer_box.append(&banner);
        outer_box.append(&toolbar);

        window.set_content(Some(&outer_box));
        window
    }
}
