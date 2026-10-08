//! The control stream: the phone's trackpad, keys and media keys, one short line each, held open
//! for as long as the helper runs; and reconnecting when the phone moves or drops off for a moment.

use crate::input::Pointer;
use crate::phone::{self, find_phone, pair, ping};
use crate::util::{log, read_conf, say, PHONE_PORT};
use crate::http;
use std::io::BufRead;
use std::thread::sleep;
use std::time::Duration;

fn unescape(s: &str) -> String {
    // %XX escapes, as the page writes typed text.
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn handle(p: &mut Option<Box<dyn Pointer>>, line: &str) {
    let a: Vec<&str> = line.split(' ').collect();
    let Some(p) = p.as_mut() else { return };
    let num = |i: usize| a.get(i).and_then(|v| v.parse::<i32>().ok()).unwrap_or(0);
    match a[0] {
        "m" => p.move_by(num(1), num(2)),
        "b" => p.button(a.get(1).copied().unwrap_or("l"), a.get(2) == Some(&"d")),
        "c" => { let w = a.get(1).copied().unwrap_or("l"); p.button(w, true); p.button(w, false); }
        "w" => p.scroll(num(1), num(2)),
        "z" => { p.key("ctrl", true); p.scroll(120 * num(1), 0); p.key("ctrl", false); }
        "kd" => if let Some(k) = a.get(1) { p.key(k, true) },
        "ku" => if let Some(k) = a.get(1) { p.key(k, false) },
        "k" => if let Some(k) = a.get(1) { p.key(k, true); p.key(k, false) },
        "h" => if let Some(ks) = a.get(1) {
            let keys: Vec<&str> = ks.split('+').collect();
            for k in &keys { p.key(k, true); }
            for k in keys.iter().rev() { p.key(k, false); }
        },
        "t" if line.len() > 2 => p.type_text(&unescape(&line[2..])),
        _ => {} // "p" (keep-alive), and what this helper does not do yet (the volume, pointing at a spot)
    }
}

pub fn control_loop(no_browser: bool) {
    let mut pointer = crate::input::backend();
    if pointer.is_none() {
        say("The phone's trackpad and keyboard cannot reach this computer's input here (on Linux: X11, or Wayland with libei).");
    }
    let mut cookie = read_conf("session.txt");
    let mut announced = false;
    loop {
        let at = phone::phone();
        let result: std::io::Result<()> = (|| {
            if cookie.is_none() {
                cookie = Some(pair());
            }
            phone::set_session(cookie.clone());
            let host = at.clone().unwrap_or_default();
            let mut heads: Vec<(&str, String)> = vec![("Cookie", cookie.clone().unwrap_or_default()), ("Bridge-Heartbeat", "slow".into())];
            if let Some(a) = phone::own_addr() {
                heads.push(("Bridge-Addrs", a));
            }
            // The phone says something at least every 25 s; 40 s of silence is the phone gone.
            let mut st = http::open(&host, PHONE_PORT, "GET", "/api/control/stream", &heads, &[], Duration::from_secs(5), Some(Duration::from_secs(40)))?;
            if st.status == 401 {
                say("The phone no longer knows this computer; asking again.");
                cookie = None;
                let _ = std::fs::remove_file(crate::util::conf_dir().join("session.txt"));
                return Ok(());
            }
            if st.status != 200 {
                return Err(std::io::Error::other(format!("the phone answered {}", st.status)));
            }
            if !announced {
                say(if pointer.is_some() { "Ready. The phone's Control tab now drives this computer." } else { "Ready." });
                crate::relay::opened(no_browser);
                announced = true;
            } else {
                say("Reconnected.");
            }
            let mut line = String::new();
            loop {
                line.clear();
                if st.body.read_line(&mut line)? == 0 {
                    break;
                }
                handle(&mut pointer, line.trim_end_matches(['\r', '\n']));
            }
            if phone::phone() == at {
                say("The phone closed the connection.");
            }
            Ok(())
        })();
        if let Err(e) = result {
            if phone::phone() == at {
                say(&format!("Lost the phone ({}).", e));
            } else {
                log(&format!("Control stream moved: {}", e));
            }
        }
        sleep(Duration::from_millis(800));
        match phone::phone() {
            Some(p) if ping(&p) => {}
            _ => find_phone(false, None),
        }
    }
}
