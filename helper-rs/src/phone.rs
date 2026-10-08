//! The phone: where it is (the cable, the hotspot, the same Wi-Fi, a saved address), asking it to
//! let this computer in, and requests to it with the session.

use crate::http;
use crate::util::{json_str, log, read_conf, say, write_conf, DISCOVERY_PORT, PHONE_PORT};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::process::Command;
use std::sync::RwLock;
use std::thread::sleep;
use std::time::{Duration, Instant};

static PHONE: RwLock<Option<String>> = RwLock::new(None);
static SESSION: RwLock<Option<String>> = RwLock::new(None);

pub fn phone() -> Option<String> {
    PHONE.read().ok().and_then(|p| p.clone())
}

pub fn session() -> Option<String> {
    SESSION.read().ok().and_then(|s| s.clone())
}

pub fn set_session(s: Option<String>) {
    if let Ok(mut w) = SESSION.write() {
        *w = s;
    }
}

fn set_phone(p: &str) {
    if let Ok(mut w) = PHONE.write() {
        *w = Some(p.to_string());
    }
}

/// A request to the phone with the session.
pub fn request(method: &str, path: &str, content_type: Option<&str>, body: &[u8], timeout_ms: u64) -> std::io::Result<http::Response> {
    let host = phone().ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotConnected, "no phone yet"))?;
    let mut h: Vec<(&str, String)> = Vec::new();
    if let Some(c) = session() {
        h.push(("Cookie", c));
    }
    if let Some(t) = content_type {
        h.push(("Content-Type", t.to_string()));
    }
    http::request(&host, PHONE_PORT, method, path, &h, body, Duration::from_millis(timeout_ms))
}

pub fn post_json(path: &str, json: &str) -> std::io::Result<http::Response> {
    request("POST", path, Some("application/json"), json.as_bytes(), 15_000)
}

pub fn ping(host: &str) -> bool {
    http::request(host, PHONE_PORT, "GET", "/api/ping", &[], &[], Duration::from_millis(1500))
        .map(|r| r.status == 200 && r.text().contains("\"ok\":true"))
        .unwrap_or(false)
}

fn run(cmd: &str, args: &[&str]) -> String {
    let mut c = Command::new(cmd);
    c.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    c.output().ok().map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default()
}

/// The default gateways: on the phone's hotspot or USB tethering the phone is the gateway.
fn gateways() -> Vec<String> {
    let mut out = Vec::new();
    if cfg!(windows) {
        // "0.0.0.0  0.0.0.0  GATEWAY  INTERFACE  METRIC"
        for l in run("route", &["print", "-4", "0.0.0.0"]).lines() {
            let f: Vec<&str> = l.split_whitespace().collect();
            if f.len() >= 5 && f[0] == "0.0.0.0" && f[1] == "0.0.0.0" && f[2].parse::<Ipv4Addr>().is_ok() {
                out.push(f[2].to_string());
            }
        }
    } else if cfg!(target_os = "macos") {
        for l in run("netstat", &["-rn", "-f", "inet"]).lines() {
            let f: Vec<&str> = l.split_whitespace().collect();
            if f.len() >= 2 && f[0] == "default" && f[1].parse::<Ipv4Addr>().is_ok() {
                out.push(f[1].to_string());
            }
        }
    } else {
        for l in run("ip", &["-4", "route", "show", "default"]).lines() {
            if let Some(i) = l.split_whitespace().position(|w| w == "via") {
                if let Some(g) = l.split_whitespace().nth(i + 1) {
                    out.push(g.to_string());
                }
            }
        }
    }
    out.dedup();
    out
}

/// The phone answers "XOOSH?" on UDP 8788 from any network it is on.
fn discover(gws: &[String]) -> Vec<String> {
    let mut found = Vec::new();
    let Ok(u) = UdpSocket::bind("0.0.0.0:0") else { return found };
    let _ = u.set_broadcast(true);
    let ask = b"XOOSH?";
    let _ = u.send_to(ask, (Ipv4Addr::BROADCAST, DISCOVERY_PORT));
    // Each network's own broadcast too (some Wi-Fi drops the all-ones one), taken as a /24.
    for g in gws {
        if let Ok(IpAddr::V4(v4)) = g.parse::<IpAddr>() {
            let o = v4.octets();
            let _ = u.send_to(ask, (Ipv4Addr::new(o[0], o[1], o[2], 255), DISCOVERY_PORT));
        }
    }
    let _ = u.set_read_timeout(Some(Duration::from_millis(300)));
    let until = Instant::now() + Duration::from_millis(1200);
    let mut buf = [0u8; 512];
    while Instant::now() < until {
        if let Ok((n, from)) = u.recv_from(&mut buf) {
            if buf[..n].starts_with(b"XOOSH ") {
                let a = from.ip().to_string();
                if !found.contains(&a) {
                    found.push(a);
                }
            }
        }
    }
    found
}

fn candidates() -> Vec<String> {
    let mut list = Vec::new();
    if let Some(saved) = read_conf("phone.txt") {
        list.push(saved);
    }
    let gws = gateways();
    for g in &gws {
        if !list.contains(g) {
            list.push(g.clone());
        }
    }
    for d in discover(&gws) {
        if !list.contains(&d) {
            list.push(d);
        }
    }
    list
}

/// Looks for the phone until it answers; [first_time]: say so while waiting.
pub fn find_phone(first_time: bool, typed: Option<&str>) {
    if let Some(t) = typed {
        if ping(t) {
            set_phone(t);
            write_conf("phone.txt", t);
            say(&format!("Connected to {}.", t));
            return;
        }
    }
    let mut told = false;
    loop {
        for c in candidates() {
            if !c.is_empty() && ping(&c) {
                let moved = phone().as_deref() != Some(c.as_str());
                set_phone(&c);
                if moved {
                    say(&format!("Found the phone at {}.", c));
                }
                write_conf("phone.txt", &c);
                return;
            }
        }
        if !told {
            say(if first_time {
                "Waiting for the phone. Start Localhost 8787 on it, and put both on the same Wi-Fi (or its hotspot, or the cable)."
            } else {
                "Looking for the phone again."
            });
            told = true;
        }
        sleep(Duration::from_secs(2));
    }
}

/// Asks the phone to let this computer in; the session once someone taps Allow there.
pub fn pair() -> String {
    loop {
        let host = phone().unwrap_or_default();
        let r = match http::request(&host, PHONE_PORT, "POST", "/api/pair", &[], &[], Duration::from_secs(5)) {
            Ok(r) => r,
            Err(e) => {
                say(&format!("Could not reach the phone to pair ({}).", e));
                sleep(Duration::from_secs(3));
                find_phone(false, None);
                continue;
            }
        };
        if r.status == 429 {
            say("The phone is busy with other requests; trying again shortly.");
            sleep(Duration::from_secs(5));
            continue;
        }
        let v: serde_json::Value = serde_json::from_slice(&r.body).unwrap_or_default();
        let id = v["id"].as_str().unwrap_or("").to_string();
        let code = v["code"].as_str().unwrap_or("").to_string();
        say(&format!("On the phone, allow \"Laptop control on {}\". Code: {}", crate::util::machine_name(), code));
        for _ in 0..125 {
            sleep(Duration::from_secs(1));
            let Ok(r) = http::request(&host, PHONE_PORT, "GET", &format!("/api/pair/{}", id), &[], &[], Duration::from_secs(5)) else { continue };
            let t = r.text();
            if t.contains("APPROVED") {
                if let Some(c) = r.session_cookie() {
                    write_conf("session.txt", &c);
                    say("Allowed. This computer will not need to ask again.");
                    return c;
                }
                log("The phone approved but sent no session");
                break;
            }
            if t.contains("DENIED") {
                say("The phone said no. Asking again in a moment.");
                sleep(Duration::from_secs(5));
                break;
            }
            if t.contains("EXPIRED") {
                break;
            }
        }
    }
}

/// This computer's address on the link to the phone, for the phone to know its pages are on it.
pub fn own_addr() -> Option<String> {
    let host = phone()?;
    let target: SocketAddr = format!("{}:{}", host, PHONE_PORT).parse().ok()?;
    let u = UdpSocket::bind(if target.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }).ok()?;
    u.connect(target).ok()?;
    Some(u.local_addr().ok()?.ip().to_string())
}

/// Closing on purpose: the phone does not report this computer as gone offline.
pub fn bye() {
    if phone().is_some() && session().is_some() {
        let _ = request("POST", "/api/control/bye", None, &[], 1500);
    }
}

#[allow(dead_code)]
pub fn report(path: &str, json: &str) {
    if let Err(e) = post_json(path, json) {
        log(&format!("{}: {}", path, e));
    }
}

#[allow(dead_code)]
pub fn quoted(s: &str) -> String {
    json_str(s)
}
