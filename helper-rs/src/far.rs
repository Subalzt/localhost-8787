//! Reaching the phone from another network: its tunnel keys and addresses, kept while it is close
//! (GET /api/tunnel); when no local path answers, every saved IPv6 address dialled at once with the
//! ones its website name points to now, the first tunnel up served on a loopback address.

use crate::tunnel::{self, Tunnel};
use crate::util::{conf_dir, log, read_conf, say, write_conf};
use base64::Engine;
use std::net::{TcpListener, ToSocketAddrs};
use std::sync::mpsc;
use std::sync::{Mutex, RwLock};
use std::thread::{sleep, spawn};
use std::time::{Duration, Instant, SystemTime};

static TUNNEL: RwLock<Option<Tunnel>> = RwLock::new(None);
static LOCAL: Mutex<Option<(String, u16)>> = Mutex::new(None);
static NEXT_TRY: Mutex<Option<Instant>> = Mutex::new(None);
static FAILS: Mutex<u32> = Mutex::new(0);

pub fn current() -> Option<Tunnel> {
    TUNNEL.read().ok().and_then(|t| t.clone())
}

/// Where the tunnel is served here, once it has been.
pub fn local() -> Option<(String, u16)> {
    LOCAL.lock().ok().and_then(|l| l.clone())
}

pub fn conf() -> Option<serde_json::Value> {
    read_conf("tunnel.json").and_then(|t| serde_json::from_str(&t).ok()).filter(|v: &serde_json::Value| v["id"].is_string() && v["key"].is_string())
}

/// The phone's addresses changed (its HELLO or ADDR): kept for next time.
pub fn save_addrs(a: &serde_json::Value) {
    if let (Some(mut c), Some(list)) = (conf(), a.as_array()) {
        if !list.is_empty() && c["addrs"] != *a {
            c["addrs"] = serde_json::Value::Array(list.clone());
            write_conf("tunnel.json", &c.to_string());
        }
    }
}

/// While the phone is close: its tunnel keys and addresses, for when it is not.
pub fn learn() {
    if let Ok(r) = crate::phone::request("GET", "/api/tunnel", None, &[], 5000) {
        if r.status == 200 {
            if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&r.body) {
                if v["key"].is_string() && v["id"].is_string() {
                    write_conf("tunnel.json", &v.to_string());
                    if let Some(n) = v["site"]["name"].as_str() {
                        write_conf("site.txt", n);
                    }
                }
            }
        }
    }
}

/// The keys are older than the session (from an earlier pairing): out of date.
fn stale() -> bool {
    let m = |n: &str| std::fs::metadata(conf_dir().join(n)).and_then(|m| m.modified()).ok();
    match (m("session.txt"), m("tunnel.json")) {
        (Some(s), Some(t)) => t < s,
        (Some(_), None) => true,
        _ => false,
    }
}

/// The phone's website name looked up: its IPv6 addresses now (it keeps the name pointing at itself).
fn site_addrs(c: &serde_json::Value) -> Vec<String> {
    let name = c["site"]["name"].as_str().map(|s| s.to_string()).or_else(|| read_conf("site.txt"));
    let Some(name) = name else { return Vec::new() };
    let Ok(found) = (name.as_str(), 0u16).to_socket_addrs() else { return Vec::new() };
    let mut out = Vec::new();
    for a in found {
        if let std::net::IpAddr::V6(v6) = a.ip() {
            let s = v6.to_string();
            if !s.starts_with("fe80") && !out.contains(&s) {
                out.push(s);
            }
        }
    }
    out
}

fn bind_local() -> Option<(String, u16)> {
    if let Some(l) = local() {
        return Some(l);
    }
    // Windows: an address of its own on the page's port, as the earlier helper; elsewhere 127.0.0.1
    // (a Mac answers only there) on a port of its own.
    let tries: Vec<(&str, u16)> = if cfg!(windows) { vec![("127.0.0.3", 8787), ("127.0.0.1", 18789)] } else { vec![("127.0.0.1", 18789), ("127.0.0.1", 18799), ("127.0.0.1", 18809)] };
    for (h, p) in tries {
        if let Ok(l) = TcpListener::bind((h, p)) {
            tunnel::serve(l, current);
            let at = (h.to_string(), p);
            *LOCAL.lock().unwrap() = Some(at.clone());
            return Some(at);
        }
    }
    say("Could not serve the tunnel on this computer: its loopback ports are taken.");
    None
}

fn failed() {
    let mut f = FAILS.lock().unwrap();
    *f += 1;
    let wait = (15u64 << (*f - 1).min(6)).min(600);
    *NEXT_TRY.lock().unwrap() = Some(Instant::now() + Duration::from_secs(wait));
    log(&format!("From afar: trying again in {} s.", wait));
}

/// The last way in, from another network. Where the tunnel is served here, or None.
pub fn path() -> Option<(String, u16)> {
    if let Some(t) = current() {
        if t.alive() {
            return local();
        }
    }
    let c = conf()?;
    if NEXT_TRY.lock().unwrap().map(|t| Instant::now() < t).unwrap_or(false) {
        return None;
    }
    let at = bind_local()?;
    let b64 = base64::engine::general_purpose::STANDARD;
    let tid: Vec<u8> = (0..16).filter_map(|i| u8::from_str_radix(c["id"].as_str()?.get(i * 2..i * 2 + 2)?, 16).ok()).collect();
    let psk = b64.decode(c["key"].as_str().unwrap_or("")).ok()?;
    if tid.len() != 16 {
        return None;
    }
    let port = c["port"].as_u64().unwrap_or(8789) as u16;
    let mut addrs: Vec<String> = c["addrs"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
    // Its website name first, in case its IPv6 changed while the two were apart.
    for a in site_addrs(&c).into_iter().rev() {
        if !addrs.contains(&a) {
            log(&format!("The phone's website points to {}", a));
            addrs.insert(0, a);
        }
    }
    if addrs.is_empty() {
        return None;
    }
    // Every address at once; the first tunnel up wins, the others are closed.
    let (tx, rx) = mpsc::channel::<(String, std::io::Result<Tunnel>)>();
    for a in &addrs {
        let (a, tid, psk, tx) = (a.clone(), tid.clone(), psk.clone(), tx.clone());
        spawn(move || {
            let r = tunnel::dial(&a, port, &tid, &psk, Duration::from_secs(4));
            let _ = tx.send((a, r));
        });
    }
    drop(tx);
    let mut won: Option<Tunnel> = None;
    while let Ok((a, r)) = rx.recv_timeout(Duration::from_secs(20)) {
        match r {
            Ok(t) if won.is_none() => {
                log(&format!("Tunnel to [{}]:{}", a, port));
                won = Some(t);
            }
            Ok(t) => t.close("another address answered first"),
            Err(e) => say(&format!("Could not reach the phone at {} over the internet ({}).", a, e)),
        }
    }
    match won {
        Some(t) => {
            *TUNNEL.write().unwrap() = Some(t);
            *FAILS.lock().unwrap() = 0;
            Some(at)
        }
        None => {
            failed();
            None
        }
    }
}

/// Keeps the tunnel's details current, and leaves the tunnel as soon as a local path answers.
pub fn tunnel_loop() {
    let mut last_learn: Option<SystemTime> = None;
    loop {
        sleep(Duration::from_secs(5));
        if crate::phone::session().is_none() {
            continue;
        }
        let on_tunnel = local().map(|l| Some(l.0) == crate::phone::phone() && l.1 == crate::phone::port()).unwrap_or(false);
        if !on_tunnel {
            let due = last_learn.map(|t| t.elapsed().map(|e| e > Duration::from_secs(600)).unwrap_or(true)).unwrap_or(true)
                || (stale() && last_learn.map(|t| t.elapsed().map(|e| e > Duration::from_secs(30)).unwrap_or(true)).unwrap_or(true));
            if due && crate::phone::phone().is_some() {
                learn();
                last_learn = Some(SystemTime::now());
            }
            // Close by now: the tunnel is not needed any more.
            if let Some(t) = current() {
                if t.alive() {
                    t.close("not needed any more");
                    log("Tunnel closed: not needed any more");
                }
            }
            continue;
        }
        if let Some((c, p)) = crate::phone::local_candidate() {
            say(&format!("The phone is close again, {}.", crate::phone::link_name(&c, p)));
            crate::phone::move_to(&c, p);
            last_learn = None;
        }
    }
}
