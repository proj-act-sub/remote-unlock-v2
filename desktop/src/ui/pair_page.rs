//! Pair page: displays a QR code encoding the Noise IK pairing payload.
//! The QR is regenerated on each app launch / "Rotate token" press.

use gtk4::prelude::*;
use libadwaita::prelude::*;
use libadwaita as adw;
use gtk4 as gtk;

pub fn build_pair_page() -> gtk::Widget {
    let page_box = gtk::Box::new(gtk::Orientation::Vertical, 24);
    page_box.set_margin_top(24);
    page_box.set_margin_bottom(24);
    page_box.set_margin_start(24);
    page_box.set_margin_end(24);
    page_box.set_halign(gtk::Align::Center);
    page_box.set_valign(gtk::Align::Center);

    // Status page for empty / loading state
    let status = adw::StatusPage::builder()
        .icon_name("qrscanner-symbolic")
        .title("Scan with RemoteUnlock on Android")
        .description("Make sure your PC is connected to the phone's mobile hotspot")
        .build();

    // QR image placeholder (filled async)
    let picture = gtk::Picture::new();
    picture.set_size_request(280, 280);
    picture.set_can_shrink(false);
    picture.add_css_class("card");

    // Alias label
    let alias_label = gtk::Label::builder()
        .label("Loading QR…")
        .css_classes(["dim-label"])
        .build();

    // Rotate token button
    let rotate_btn = gtk::Button::builder()
        .label("Rotate Token")
        .css_classes(["pill"])
        .halign(gtk::Align::Center)
        .build();

    let picture_clone = picture.clone();
    let label_clone = alias_label.clone();
    rotate_btn.connect_clicked(move |_| {
        regenerate_qr(&picture_clone, &label_clone);
    });

    // Initial QR generation (deferred to idle so window shows first)
    let picture2 = picture.clone();
    let label2 = alias_label.clone();
    glib::MainContext::default().spawn_local(async move {
        regenerate_qr(&picture2, &label2);
    });

    page_box.append(&status);
    page_box.append(&picture);
    page_box.append(&alias_label);
    page_box.append(&rotate_btn);

    page_box.upcast()
}

fn regenerate_qr(picture: &gtk::Picture, label: &gtk::Label) {
    // In a real app this pulls from the shared KeyStore; here we show the pattern.
    // Static placeholder pubkey for UI testing:
    let fake_pub = [0u8; 32];
    let alias = glib::hostname_get()
        .map(|h| h.to_string())
        .unwrap_or_else(|| "Linux PC".into());

    match crate::qr::generator::QrPayload::new(&fake_pub, &alias) {
        Ok((payload, _token)) => {
            match payload.render_png() {
                Ok(png_bytes) => {
                    let bytes = glib::Bytes::from(&png_bytes);
                    let stream = gio::MemoryInputStream::from_bytes(&bytes);
                    if let Ok(pixbuf) = gdk_pixbuf::Pixbuf::from_stream(&stream, gio::Cancellable::NONE) {
                        let texture = gdk4::Texture::for_pixbuf(&pixbuf);
                        picture.set_paintable(Some(&texture));
                        label.set_text(&format!("{} — scan once only", payload.name));
                    }
                }
                Err(e) => label.set_text(&format!("QR error: {e}")),
            }
        }
        Err(e) => label.set_text(&format!("Key error: {e}")),
    }
}
