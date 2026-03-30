//! Devices page: lists paired Android phones with remove / fingerprint actions.

use gtk4::prelude::*;
use libadwaita::prelude::*;
use libadwaita as adw;
use gtk4 as gtk;

pub fn build_devices_page() -> gtk::Widget {
    let page_box = gtk::Box::new(gtk::Orientation::Vertical, 0);

    let group = adw::PreferencesGroup::builder()
        .title("Paired Phones")
        .description("These devices can unlock your screen")
        .margin_top(24)
        .margin_bottom(12)
        .margin_start(12)
        .margin_end(12)
        .build();

    // Placeholder row — in a full app, rows are generated from KeyStore::peers()
    let row = adw::ActionRow::builder()
        .title("No devices paired yet")
        .subtitle("Scan a QR code from the Pair tab")
        .icon_name("phone-symbolic")
        .build();
    group.add(&row);

    let status = adw::StatusPage::builder()
        .icon_name("phone-symbolic")
        .title("No Devices")
        .description("Go to the Pair tab and scan the QR code on your Android phone")
        .vexpand(true)
        .build();

    page_box.append(&group);
    page_box.append(&status);
    page_box.upcast()
}
