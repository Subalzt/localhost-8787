//! The phone: where it is (the cable, the hotspot, the same Wi-Fi, a saved address), asking it to
//! let this computer in, and requests to it with the session.

use crate::http;
use crate::util::{json_str, log, read_conf, say, write_conf, DISCOVERY_PORT, PHONE_PORT};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::process::Command;
use std::sync::RwLock;
use std::thread::sleep;
use std::time::{Duration, Instant};

/// Where the phone is reached now: its address and port (the page's, or the tunnel's loopback here).
static PHONE: RwLock<Option<(String, u16)>> = RwLock::new(None);
static SESSION: RwLock<Option<String>> = RwLock::new(None);

pub fn phone() -> Option<String> {
    PHONE.read().ok().and_then(|p| p.as_ref().map(|p| p.0.clone()))
}

pub fn port() -> u16 {
    PHONE.read().ok().and_then(|p| p.as_ref().map(|p| p.1)).unwrap_or(PHONE_PORT)
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
    move_to(p, PHONE_PORT);
}

/// From now on the phone is at [host]:[port]; the streams on the old link reconnect on this one.
pub fn move_to(host: &str, port: u16) {
    let changed = match PHONE.write() {
        Ok(mut w) => {
            let changed = w.as_ref().map_or(true, |p| p.0 != host || p.1 != port);
            *w = Some((host.to_string(), port));
            changed
        }
        Err(_) => false,
    };
    if changed {
        cut_tracked();
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
    http::request(&host, port(), method, path, &h, body, Duration::from_millis(timeout_ms))
}

pub fn post_json(path: &str, json: &str) -> std::io::Result<http::Response> {
    request("POST", path, Some("application/json"), json.as_bytes(), 15_000)
}

pub fn ping(host: &str) -> bool {
    ping_at(host, PHONE_PORT)
}

pub fn ping_at(host: &str, port: u16) -> bool {
    http::request(host, port, "GET", "/api/ping", &[], &[], Duration::from_millis(1500))
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

/// Where adb is: the Android SDK's, scrcpy's beside the helper or where the earlier helper keeps it,
/// else the one on the PATH.
pub fn adb_exe() -> Option<std::path::PathBuf> {
    let exe = if cfg!(windows) { "adb.exe" } else { "adb" };
    let mut tries: Vec<std::path::PathBuf> = Vec::new();
    if cfg!(windows) {
        if let Ok(l) = std::env::var("LOCALAPPDATA") {
            let l = std::path::PathBuf::from(l);
            tries.push(l.join("Android/Sdk/platform-tools").join(exe));
            tries.push(l.join("BlazeIt/scrcpy").join(exe));
        }
    } else {
        let h = crate::util::home();
        tries.push(h.join("Android/Sdk/platform-tools").join(exe));
        tries.push(h.join("Library/Android/sdk/platform-tools").join(exe));
    }
    if let Some(d) = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf())) {
        tries.push(d.join("scrcpy").join(exe));
    }
    if let Some(p) = tries.into_iter().find(|p| p.is_file()) {
        return Some(p);
    }
    std::env::var_os("PATH").and_then(|paths| std::env::split_paths(&paths).map(|d| d.join(exe)).find(|p| p.is_file()))
}

/// The forward adb keeps to the phone's page: this computer's port.
pub const ADB_FORWARD_PORT: u16 = 18787;

/// The last way in: the cable with USB debugging on, through adb's port forward to the phone's page.
/// It needs no network at all. None when adb or a phone on it is not there.
fn adb_path() -> Option<(String, u16)> {
    // For trying the internet path with the cable still in: "no-cable.txt" in the helper's folder.
    if crate::util::conf_dir().join("no-cable.txt").exists() {
        return None;
    }
    let adb = adb_exe()?;
    let adb = adb.to_string_lossy();
    let devices = run(&adb, &["devices"]);
    if !devices.lines().skip(1).any(|l| l.split_whitespace().nth(1) == Some("device")) {
        return None;
    }
    let fwd = format!("tcp:{}", ADB_FORWARD_PORT);
    let to = format!("tcp:{}", PHONE_PORT);
    run(&adb, &["forward", &fwd, &to]);
    Some(("127.0.0.1".to_string(), ADB_FORWARD_PORT))
}

/// The first local way to the phone that answers: the saved address, the gateway (its hotspot, or USB
/// tethering), the network's answer to XOOSH?, and last the cable over USB debugging.
pub fn local_candidate() -> Option<(String, u16)> {
    if let Some(c) = candidates().into_iter().find(|c| !c.is_empty() && ping(c)) {
        return Some((c, PHONE_PORT));
    }
    adb_path().filter(|(h, p)| ping_at(h, *p))
}

/// What to call where the phone was found.
pub fn link_name(host: &str, port: u16) -> String {
    if port == ADB_FORWARD_PORT && host == "127.0.0.1" {
        "over the USB cable's debugging (turn on USB tethering on the phone for full speed)".into()
    } else if gateways().iter().any(|g| g == host) {
        "on its hotspot or the cable's USB tethering".into()
    } else {
        "over Wi-Fi".into()
    }
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
        if let Some((c, p)) = local_candidate() {
            let moved = phone().as_deref() != Some(c.as_str()) || port() != p;
            move_to(&c, p);
            if moved {
                say(&format!("Found the phone at {}, {}.", if p == PHONE_PORT { c.clone() } else { "the cable".into() }, link_name(&c, p)));
            }
            if p == PHONE_PORT {
                write_conf("phone.txt", &c);
                check_cable(&c);
            }
            return;
        }
        // From another network, through the tunnel to the phone's saved addresses.
        if let Some((h, p)) = crate::far::path() {
            if ping_at(&h, p) {
                let moved = phone().as_deref() != Some(h.as_str());
                move_to(&h, p);
                if moved {
                    say("Found the phone over the internet, through the tunnel.");
                }
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
        let r = match http::request(&host, port(), "POST", "/api/pair", &[], &[], Duration::from_secs(5)) {
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
            let Ok(r) = http::request(&host, port(), "GET", &format!("/api/pair/{}", id), &[], &[], Duration::from_secs(5)) else { continue };
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
    if host.starts_with("127.") {
        return None; // through the tunnel: the phone knows this computer by the tunnel's keys
    }
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

/// The adapter the phone is a gateway on, when it is a USB one: its description and speed (Mbit/s).
#[cfg(windows)]
fn usb_adapter(gateway: &str) -> Option<(String, u64)> {
    let script = format!(
        "Get-NetIPConfiguration | ? {{ $_.IPv4DefaultGateway.NextHop -eq '{}' }} | % {{ $_.NetAdapter.InterfaceDescription + '|' + $_.NetAdapter.Speed }}",
        gateway.replace('\'', "")
    );
    let out = run("powershell", &["-NoProfile", "-NonInteractive", "-Command", &script]);
    let line = out.lines().next()?.trim().to_string();
    let (desc, speed) = line.rsplit_once('|')?;
    let usb = ["NDIS", "USB", "Android"].iter().any(|k| desc.contains(k));
    usb.then(|| (desc.to_string(), speed.trim().parse::<u64>().unwrap_or(0) / 1_000_000))
}

/// On the cable's USB tethering at USB 2 speed: said once, with what to do about it.
pub fn check_cable(host: &str) {
    #[cfg(windows)]
    {
        static SAID: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);
        if SAID.lock().unwrap().as_deref() == Some(host) {
            return;
        }
        if let Some((_, mbps)) = usb_adapter(host) {
            *SAID.lock().unwrap() = Some(host.to_string());
            if mbps > 0 && mbps < 600 {
                say("The cable is running at USB 2 speed (about 40 MB/s). A USB 3 cable, in a USB-C port on this laptop, gives about 250 MB/s. Charging cables are usually USB 2.");
            }
        }
    }
    #[cfg(not(windows))]
    let _ = host;
}

/// --cable-check: what the helper sees of the phone on a cable.
pub fn cable_report() {
    match adb_exe() {
        Some(a) => {
            println!("adb: {}", a.display());
            print!("{}", run(&a.to_string_lossy(), &["devices"]));
        }
        None => println!("adb: not found (the Android SDK's platform-tools, or scrcpy beside the helper)"),
    }
    let gws = gateways();
    println!("gateways: {:?}", gws);
    println!("on a USB adapter: {:?}", usb_gateways());
    #[cfg(windows)]
    for g in &gws {
        if let Some((d, s)) = usb_adapter(g) {
            println!("  {} is on a USB adapter: {} at {} Mbit/s", g, d, s);
        }
    }
    match adb_path() {
        Some((h, p)) => println!("adb forward: {}:{} -> phone:{}, answers: {}", h, p, PHONE_PORT, ping_at(&h, p)),
        None => println!("adb forward: none (no phone on USB debugging)"),
    }
}

// ---- following the best way

static TRACKED: std::sync::Mutex<Vec<std::net::TcpStream>> = std::sync::Mutex::new(Vec::new());

/// Remembers a long-lived connection to the phone (the control and event streams), so moving to
/// another link cuts it and it reconnects there, rather than waiting out the old link's timeout.
pub fn track(s: &std::net::TcpStream) {
    if let Ok(c) = s.try_clone() {
        let mut t = TRACKED.lock().unwrap();
        t.retain(|x| x.peer_addr().is_ok());
        t.push(c);
    }
}

fn cut_tracked() {
    for s in TRACKED.lock().unwrap().drain(..) {
        let _ = s.shutdown(std::net::Shutdown::Both);
    }
}

#[cfg(all(unix, not(target_os = "macos")))]
fn linux_usb(gateway: &str) -> bool {
    let out = run("ip", &["-4", "route", "get", gateway]);
    let Some(dev) = out.split_whitespace().skip_while(|w| *w != "dev").nth(1) else { return false };
    std::fs::canonicalize(format!("/sys/class/net/{}", dev)).map(|p| p.to_string_lossy().contains("/usb")).unwrap_or(false)
}

/// Whether the gateway is on a USB network adapter (the phone's USB tethering). Looked up once a
/// minute for each: Windows answers through PowerShell, which is slow.
fn is_usb(gateway: &str) -> bool {
    static CACHE: std::sync::Mutex<Vec<(String, bool, Instant)>> = std::sync::Mutex::new(Vec::new());
    if let Some((_, usb, at)) = CACHE.lock().unwrap().iter().find(|c| c.0 == gateway) {
        if at.elapsed() < Duration::from_secs(60) {
            return *usb;
        }
    }
    #[cfg(windows)]
    let usb = usb_adapter(gateway).is_some();
    #[cfg(all(unix, not(target_os = "macos")))]
    let usb = linux_usb(gateway);
    #[cfg(target_os = "macos")]
    let usb = false;
    let mut c = CACHE.lock().unwrap();
    c.retain(|x| x.0 != gateway);
    c.push((gateway.to_string(), usb, Instant::now()));
    usb
}

/// The default gateways that are on a USB adapter now.
pub fn usb_gateways() -> Vec<String> {
    gateways().into_iter().filter(|g| is_usb(g)).collect()
}

/// A cable beats every Wi-Fi link: while connected some other way and the phone answers on the
/// cable's USB tethering, move to it; when the cable goes, look for the phone again.
pub fn follow_cable() {
    let mut misses = 0;
    let mut on_cable: Option<String> = None;
    loop {
        sleep(Duration::from_millis(2500));
        if session().is_none() || phone().is_none() {
            continue;
        }
        let usb = usb_gateways();
        if let Some(g) = usb.iter().find(|g| ping(g)) {
            misses = 0;
            if phone().as_deref() != Some(g.as_str()) || port() != PHONE_PORT {
                move_to(g, PHONE_PORT);
                write_conf("phone.txt", g);
                say("USB cable to the phone found: using it. It is several times faster than any Wi-Fi link.");
                check_cable(g);
            }
            on_cable = Some(g.clone());
            continue;
        }
        // Unplugged, or USB tethering switched off: a cable still listed gets one more look.
        if let Some(c) = &on_cable {
            if phone().as_deref() == Some(c.as_str()) && (!usb.contains(c) || { misses += 1; misses >= 2 }) {
                say("The USB cable is gone; looking for the phone over Wi-Fi.");
                on_cable = None;
                misses = 0;
                find_phone(false, None);
            } else if phone().as_deref() != Some(c.as_str()) {
                on_cable = None;
            }
        }
    }
}

/// The speed the phone's USB tethering adapter reports, in Mbit/s: about 426 on USB 2 (which moves
/// about 40 MB/s), 852 or more on USB 3 (225 to 270 MB/s). 0 when not known.
pub fn usb_mbps(gateway: &str) -> u64 {
    #[cfg(windows)]
    {
        usb_adapter(gateway).map(|a| a.1).unwrap_or(0)
    }
    #[cfg(not(windows))]
    {
        let _ = gateway;
        0
    }
}
