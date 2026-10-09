//! The link report, for the phone's Monitor tab: this computer's side of the link (the Wi-Fi's
//! signal, link rates, channel, band and generation on Windows), the round trip to the phone, and
//! the byte counters of the adapter that reaches it, so the phone can subtract its own traffic and
//! show what else shares the link. Every 2 s while someone has the monitor open, every 15 s
//! otherwise (which is only there to notice when someone opens it).

use crate::phone;
use crate::util::json_str;
use std::net::IpAddr;
use std::process::Command;
use std::time::{Duration, Instant};

/// The "Name : value" lines of netsh's description of the Wi-Fi connection.
#[cfg(windows)]
pub fn wifi() -> std::collections::HashMap<String, String> {
    use std::os::windows::process::CommandExt;
    let mut kv = std::collections::HashMap::new();
    let Ok(o) = Command::new("netsh").args(["wlan", "show", "interfaces"]).creation_flags(0x0800_0000).output() else { return kv };
    for l in String::from_utf8_lossy(&o.stdout).lines() {
        if let Some((k, v)) = l.split_once(" : ") {
            kv.entry(k.trim().to_string()).or_insert_with(|| v.trim().to_string());
        }
    }
    kv
}

#[cfg(not(windows))]
pub fn wifi() -> std::collections::HashMap<String, String> {
    Default::default()
}

/// The leading number in "87%" or "866.7".
fn num(v: &str) -> String {
    let n: String = v.chars().skip_while(|c| !c.is_ascii_digit()).take_while(|c| c.is_ascii_digit()).collect();
    if n.is_empty() { "0".into() } else { n }
}

fn radio(r: &str) -> String {
    if r.ends_with("be") { "Wi-Fi 7" } else if r.ends_with("ax") { "Wi-Fi 6" } else if r.ends_with("ac") { "Wi-Fi 5" } else if r.ends_with('n') { "Wi-Fi 4" } else { r }.to_string()
}

/// The adapter that reaches the phone: the one with an address on the phone's subnet. Its name and
/// byte counters, which include everything on that link, not only Localhost 8787.
fn adapter(host: &str) -> Option<(String, u64, u64)> {
    let IpAddr::V4(target) = host.parse::<IpAddr>().ok()? else { return None };
    let nets = sysinfo::Networks::new_with_refreshed_list();
    for (name, d) in nets.iter() {
        for n in d.ip_networks() {
            if let IpAddr::V4(ip) = n.addr {
                let mask = if n.prefix == 0 { 0 } else { u32::MAX << (32 - n.prefix.min(32) as u32) };
                if u32::from(ip) & mask == u32::from(target) & mask {
                    return Some((name.clone(), d.total_received(), d.total_transmitted()));
                }
            }
        }
    }
    None
}

/// What this computer reports of the link to `host`, as the phone's JSON.
pub fn report(host: &str, port: u16) -> String {
    let kv = wifi();
    let get = |k: &str| kv.get(k).cloned().unwrap_or_default();
    let t = Instant::now();
    let rtt = if phone::ping_at(host, port) { t.elapsed().as_millis() as i64 } else { -1 };
    let (iface, rx, tx) = adapter(host).unwrap_or_default();
    let usb = if phone::usb_gateways().iter().any(|g| g == host) { phone::usb_mbps(host) } else { 0 };
    format!(
        "{{\"ssid\":{},\"signalPercent\":{},\"rxMbps\":{},\"txMbps\":{},\"channel\":{},\"band\":{},\"radio\":{},\"rttMs\":{},\"usbMbps\":{},\"iface\":{},\"rxBytes\":{},\"txBytes\":{}}}",
        json_str(&get("SSID")), num(&get("Signal")), num(&get("Receive rate (Mbps)")), num(&get("Transmit rate (Mbps)")),
        json_str(&get("Channel")), json_str(&get("Band")), json_str(&radio(&get("Radio type"))), rtt, usb, json_str(&iface), rx, tx
    )
}

pub fn link_loop() {
    loop {
        let mut wait = 15;
        if let (Some(host), Some(_)) = (phone::phone(), phone::session()) {
            let body = report(&host, phone::port());
            if let Ok(r) = phone::request("POST", "/api/monitor/link", Some("application/json"), body.as_bytes(), 3000) {
                if r.text().contains("\"watch\"") {
                    wait = 2;
                }
            }
        }
        std::thread::sleep(Duration::from_secs(wait));
    }
}