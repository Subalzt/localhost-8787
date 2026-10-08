//! One clipboard for this computer and the phone: what is copied here goes to the phone, and what is
//! copied on the phone (or another computer) lands here. Text and pictures; only what is copied from
//! now on goes over, not what was on the clipboard when the helper started.

use crate::http::quote;
use crate::phone;
use crate::util::{log, say};
use arboard::{Clipboard, ImageData};
use std::borrow::Cow;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Mutex;
use std::thread::sleep;
use std::time::Duration;

pub static SYNC: AtomicBool = AtomicBool::new(true);
/// The version of the shared clipboard last taken or sent, so it is not taken twice.
pub static LAST_V: AtomicI64 = AtomicI64::new(-1);
static LAST_TEXT: Mutex<Option<String>> = Mutex::new(None);
static LAST_PIC: Mutex<u64> = Mutex::new(0);
static QUEUE: Mutex<Vec<Incoming>> = Mutex::new(Vec::new());
static SENDING: AtomicBool = AtomicBool::new(false);
const TEXT_MAX: usize = 256 * 1024;

pub enum Incoming {
    Text(String),
    Picture(Vec<u8>),
    Clear,
}

/// From the phone: put on this computer's clipboard by the clipboard's own thread.
pub fn incoming(i: Incoming) {
    if let Ok(mut q) = QUEUE.lock() {
        q.push(i);
    }
}

/// The phone's text clipboard arrived ("clipboard" event); the first is the phone's state on connecting.
pub fn phone_text(d: &str, first: bool) {
    let mut last = LAST_TEXT.lock().unwrap();
    if first {
        if last.is_none() { *last = Some(d.to_string()); }
        return;
    }
    if SYNC.load(Ordering::Relaxed) && !d.is_empty() && last.as_deref() != Some(d) {
        *last = Some(d.to_string());
        drop(last);
        incoming(Incoming::Text(d.to_string()));
    }
}

/// The phone's clipboard changed to a picture or a file ("clip" event).
pub fn phone_clip(json: &str, first: bool) {
    let c: serde_json::Value = serde_json::from_str(json).unwrap_or_default();
    let v = c["v"].as_i64().unwrap_or(-1);
    if first || SENDING.load(Ordering::Relaxed) {
        LAST_V.store(v, Ordering::Relaxed);
        return;
    }
    if !SYNC.load(Ordering::Relaxed) || v == LAST_V.load(Ordering::Relaxed) { return; }
    LAST_V.store(v, Ordering::Relaxed);
    match c["kind"].as_str().unwrap_or("") {
        "image" => {
            if let Ok(r) = phone::request("GET", &format!("/api/clipboard/blob?v={}", v), None, &[], 30_000) {
                if r.status == 200 { incoming(Incoming::Picture(r.body)); }
            }
        }
        "empty" => incoming(Incoming::Clear),
        _ => {}
    }
}

fn hash(b: &[u8]) -> u64 {
    // FNV-1a: enough to tell one picture from the next.
    let mut h: u64 = 0xcbf29ce484222325;
    for x in b.iter().step_by(7) { h ^= *x as u64; h = h.wrapping_mul(0x100000001b3); }
    h ^ b.len() as u64
}

fn png_of(img: &ImageData) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    {
        let mut e = png::Encoder::new(&mut out, img.width as u32, img.height as u32);
        e.set_color(png::ColorType::Rgba);
        e.set_depth(png::BitDepth::Eight);
        let mut w = e.write_header().ok()?;
        w.write_image_data(&img.bytes).ok()?;
    }
    Some(out)
}

fn image_of(png_bytes: &[u8]) -> Option<ImageData<'static>> {
    let mut d = png::Decoder::new(png_bytes);
    d.set_transformations(png::Transformations::EXPAND | png::Transformations::ALPHA);
    let mut r = d.read_info().ok()?;
    let mut buf = vec![0; r.output_buffer_size()];
    let info = r.next_frame(&mut buf).ok()?;
    buf.truncate(info.buffer_size());
    let rgba = match info.color_type {
        png::ColorType::Rgba => buf,
        png::ColorType::GrayscaleAlpha => buf.chunks(2).flat_map(|p| [p[0], p[0], p[0], p[1]]).collect(),
        _ => return None,
    };
    Some(ImageData { width: info.width as usize, height: info.height as usize, bytes: Cow::Owned(rgba) })
}

fn send_text(t: &str) {
    let body = format!("{{\"text\":{}}}", crate::util::json_str(t));
    let host = phone::phone().unwrap_or_default();
    let h = vec![("Cookie", phone::session().unwrap_or_default()), ("Content-Type", "application/json".into()), ("Bridge-Auto", "1".into())];
    let _ = crate::http::request(&host, crate::util::PHONE_PORT, "POST", "/api/clipboard", &h, body.as_bytes(), Duration::from_secs(4));
}

fn send_picture(png: Vec<u8>) {
    SENDING.store(true, Ordering::Relaxed);
    let host = phone::phone().unwrap_or_default();
    let h = vec![("Cookie", phone::session().unwrap_or_default()), ("Content-Type", "image/png".into()), ("Bridge-Auto", "1".into())];
    let name = format!("Picture {}.png", crate::util::now_ms() / 1000);
    match crate::http::request(&host, crate::util::PHONE_PORT, "POST", &format!("/api/clipboard/blob?name={}", quote(&name)), &h, &png, Duration::from_secs(30)) {
        Ok(r) => {
            let v: serde_json::Value = serde_json::from_slice(&r.body).unwrap_or_default();
            if let Some(v) = v["v"].as_i64() { LAST_V.store(v, Ordering::Relaxed); }
            say("Picture copied here is on the phone's clipboard.");
        }
        Err(e) => say(&format!("Could not put it on the phone's clipboard ({}).", e)),
    }
    SENDING.store(false, Ordering::Relaxed);
}

pub fn clip_loop() {
    let mut cb = match Clipboard::new() {
        Ok(c) => c,
        Err(e) => { say(&format!("The clipboard stays unsynced here ({}).", e)); return; }
    };
    // What is on the clipboard already stays here.
    if let Ok(t) = cb.get_text() { *LAST_TEXT.lock().unwrap() = Some(t); }
    if let Ok(i) = cb.get_image() { *LAST_PIC.lock().unwrap() = hash(&i.bytes); }
    loop {
        sleep(Duration::from_millis(600));
        let pending = QUEUE.lock().ok().and_then(|mut q| if q.is_empty() { None } else { Some(q.remove(0)) });
        if let Some(p) = pending {
            match p {
                Incoming::Text(t) => { if let Err(e) = cb.set_text(t) { log(&format!("Clipboard: {}", e)); } }
                Incoming::Picture(b) => match image_of(&b) {
                    Some(img) => {
                        *LAST_PIC.lock().unwrap() = hash(&img.bytes);
                        if let Err(e) = cb.set_image(img) { log(&format!("Clipboard: {}", e)); }
                    }
                    None => log("Clipboard: a picture from the phone that is not a PNG"),
                },
                Incoming::Clear => { let _ = cb.clear(); }
            }
            continue;
        }
        if !SYNC.load(Ordering::Relaxed) || phone::session().is_none() || phone::phone().is_none() { continue; }
        if let Ok(t) = cb.get_text() {
            let mut last = LAST_TEXT.lock().unwrap();
            if !t.is_empty() && last.as_deref() != Some(t.as_str()) {
                *last = Some(t.clone());
                drop(last);
                if t.len() <= TEXT_MAX { send_text(&t); }
                continue;
            }
        }
        if let Ok(img) = cb.get_image() {
            let h = hash(&img.bytes);
            let mut last = LAST_PIC.lock().unwrap();
            if h != *last {
                *last = h;
                drop(last);
                if let Some(p) = png_of(&img) { send_picture(p); }
            }
        }
    }
}
