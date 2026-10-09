//! The phone's live events (server-sent events): the clipboard, this computer's files, health,
//! links to open. The first events after connecting are the phone's state, not requests.

use crate::phone;
use crate::util::log;
use crate::{clip, files, handoff, health, http, notify};
use base64::Engine;
use std::io::BufRead;
use std::thread::{sleep, spawn};
use std::time::Duration;

fn b64(s: &str) -> String {
    base64::engine::general_purpose::STANDARD.decode(s.trim()).ok().map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default()
}

fn open_url(b: &str) {
    let url = b64(b);
    if url.starts_with("http://") || url.starts_with("https://") {
        match open::that(&url) {
            Ok(_) => log(&format!("Opened from the phone: {}", url.split('/').nth(2).unwrap_or(""))),
            Err(e) => log(&format!("Could not open it: {}", e)),
        }
    } else {
        log(&format!("Not a web address, not opened: {}", url));
    }
}

fn dispatch(ev: &str, d: &str, snapshot: &mut bool, clip_snapshot: &mut bool) {
    match ev {
        "clipsync" => clip::SYNC.store(d == "on", std::sync::atomic::Ordering::Relaxed),
        "clip" => {
            let first = *clip_snapshot;
            *clip_snapshot = false;
            let d = d.to_string();
            spawn(move || clip::phone_clip(&d, first));
        }
        "clipboard" => {
            // The first marks the end of what the phone sends on connecting.
            let first = *snapshot;
            *snapshot = false;
            clip::phone_text(d, first);
        }
        "health" if !*snapshot => { let id = d.trim().to_string(); spawn(move || health::answer(&id)); }
        "call" => notify::call_event(d),
        "camalert" => { let d = d.to_string(); spawn(move || notify::cam_alert(&d)); }
        "handoff" if !*snapshot => { let id = d.trim().to_string(); spawn(move || handoff::answer(&id)); }
        "openurl" if !*snapshot => open_url(d),
        "mirror" if !*snapshot => { spawn(crate::mirror::open_phone_screen); }
        "laptopfs" if !*snapshot => {
            let q: Vec<String> = d.split(' ').map(|s| s.to_string()).collect();
            if q.first().map(|s| s.as_str()) == Some("put") && q.len() >= 5 {
                let folder = b64(&q[2]);
                let name = b64(&q[3]);
                let size: i64 = q[4].parse().unwrap_or(-1);
                let drop = folder == ":drop";
                let folder = if drop { files::downloads_dir().to_string_lossy().into_owned() } else { folder };
                let rid = q[1].clone();
                spawn(move || files::receive(&rid, &folder, &name, size, drop));
            } else if q.len() >= 3 {
                let path = if q[2].is_empty() { String::new() } else { b64(&q[2]) };
                let (rid, get) = (q[1].clone(), q[0] == "get");
                spawn(move || if get { files::send(&rid, &path) } else { files::list(&rid, &path) });
            }
        }
        _ => {} // what this helper does not do yet: the second screen, sound, webcam, calls on this computer
    }
}

pub fn events_loop() {
    loop {
        let (Some(at), Some(cookie)) = (phone::phone(), phone::session()) else { sleep(Duration::from_secs(2)); continue };
        if let Ok(r) = phone::request("GET", "/api/state", None, &[], 5_000) {
            clip::SYNC.store(!r.text().contains("\"clipSync\":false"), std::sync::atomic::Ordering::Relaxed);
        }
        let h = vec![("Cookie", cookie), ("Accept", "text/event-stream".to_string())];
        match http::open(&at, crate::phone::port(), "GET", "/events", &h, &[], Duration::from_secs(5), Some(Duration::from_secs(40))) {
            Ok(mut st) if st.status == 200 => {
                phone::track(&st.socket);
                let (mut ev, mut data) = (String::new(), Vec::<String>::new());
                let (mut snapshot, mut clip_snapshot) = (true, true);
                let mut line = String::new();
                while phone::phone().as_deref() == Some(at.as_str()) {
                    line.clear();
                    match st.body.read_line(&mut line) {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {}
                    }
                    let l = line.trim_end_matches(['\r', '\n']);
                    if !l.is_empty() {
                        if let Some(v) = l.strip_prefix("event:") { ev = v.trim().to_string(); }
                        else if let Some(v) = l.strip_prefix("data:") { data.push(v.strip_prefix(' ').unwrap_or(v).to_string()); }
                        continue;
                    }
                    let d = data.join("\n");
                    dispatch(&ev, &d, &mut snapshot, &mut clip_snapshot);
                    ev.clear();
                    data.clear();
                }
            }
            Ok(st) => log(&format!("Events: the phone answered {}", st.status)),
            Err(e) => log(&format!("Events: {}", e)),
        }
        sleep(Duration::from_millis(1500));
    }
}
