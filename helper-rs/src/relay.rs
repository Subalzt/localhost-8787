//! The page at http://localhost:8787: every connection the browser opens is relayed to the phone as
//! it is, so the page's own pairing, uploads and downloads work unchanged, at the link's full speed.

use crate::phone;
use crate::util::say;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicU16, Ordering};
use std::thread::spawn;
use std::time::Duration;

static PORT: AtomicU16 = AtomicU16::new(0);

fn pump(mut from: TcpStream, mut to: TcpStream) {
    let mut buf = vec![0u8; 1 << 16];
    loop {
        match from.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => if to.write_all(&buf[..n]).is_err() { break },
        }
    }
    let _ = to.shutdown(Shutdown::Write);
}

fn bridge(browser: TcpStream) {
    let Some(host) = phone::phone() else { return };
    let port = phone::port();
    let addr = if host.contains(':') { format!("[{}]:{}", host, port) } else { format!("{}:{}", host, port) };
    let Ok(a) = addr.parse() else { return };
    let Ok(to) = TcpStream::connect_timeout(&a, Duration::from_secs(10)) else { return };
    let _ = to.set_nodelay(true);
    let _ = browser.set_nodelay(true);
    let (Ok(b2), Ok(t2)) = (browser.try_clone(), to.try_clone()) else { return };
    let up = spawn(move || pump(b2, t2));
    pump(to, browser);
    let _ = up.join();
}

pub fn relay_loop() {
    for port in [8787u16, 8797, 8807] {
        if let Ok(l) = TcpListener::bind(("127.0.0.1", port)) {
            PORT.store(port, Ordering::Relaxed);
            for c in l.incoming().flatten() {
                spawn(move || bridge(c));
            }
            return;
        }
    }
    say("Could not serve the page on this computer (ports 8787, 8797 and 8807 are all busy). The trackpad still works.");
}

/// Ready: the page, opened once (unless asked not to, or a tab has it already).
pub fn opened(no_browser: bool) {
    for _ in 0..30 {
        if PORT.load(Ordering::Relaxed) != 0 { break; }
        std::thread::sleep(Duration::from_millis(100));
    }
    let port = PORT.load(Ordering::Relaxed);
    if port == 0 { return; }
    let url = format!("http://localhost:{}/", port);
    if no_browser {
        say(&format!("The page: {}", url));
    } else if crate::handoff::page_open(port) {
        say(&format!("The Localhost 8787 page is open at {}.", url));
    } else {
        say(&format!("Opening the Localhost 8787 page at {} for full-speed transfers.", url));
        let _ = open::that(&url);
    }
}

// ---- calls on the page, from afar
//
// A call on this computer's page while the phone is far away: the page's WebRTC may find no way
// straight to the phone (no IPv6 on one side, a network that lets nothing in). The phone then
// offers this relay (127.0.0.1:8790, on this computer alone, so nothing is open to the network) as
// a last way: WebRTC over TCP, which the browser opens to it and this helper carries to the phone
// (/api/call/pipe), through the sealed tunnel when the phone is far, as it does everything else.

const CALL_RELAY_PORT: u16 = 8790;

fn call_relay(browser: TcpStream) {
    let (Some(host), Some(cookie)) = (phone::phone(), phone::session()) else { return };
    let port = phone::port();
    let addr = if host.contains(':') { format!("[{}]:{}", host, port) } else { format!("{}:{}", host, port) };
    let Ok(a) = addr.parse() else { return };
    let Ok(mut up) = TcpStream::connect_timeout(&a, Duration::from_secs(10)) else { return };
    let _ = up.set_nodelay(true);
    let _ = browser.set_nodelay(true);
    let req = format!(
        "GET /api/call/pipe HTTP/1.1\r\nHost: phone\r\nUser-Agent: {}\r\nCookie: {}\r\nConnection: Upgrade\r\nUpgrade: l87-pipe\r\n\r\n",
        crate::util::user_agent(), cookie
    );
    if up.write_all(req.as_bytes()).is_err() { return; }
    // The answer's head, up to its blank line; what follows is the call's own.
    let mut head = Vec::new();
    let mut b = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        if up.read(&mut b).unwrap_or(0) == 0 || head.len() > 8192 { return; }
        head.push(b[0]);
    }
    let head = String::from_utf8_lossy(&head);
    if !head.starts_with("HTTP/1.1 101") {
        crate::util::log(&format!("Call relay: the phone said {}", head.lines().next().unwrap_or("")));
        return;
    }
    crate::util::log("A call on the page goes through the tunnel (WebRTC over TCP).");
    let (Ok(b2), Ok(u2)) = (browser.try_clone(), up.try_clone()) else { return };
    let t = spawn(move || pump(b2, u2));
    pump(up, browser);
    let _ = t.join();
}

pub fn call_relay_loop() {
    let l = match TcpListener::bind(("127.0.0.1", CALL_RELAY_PORT)) {
        Ok(l) => l,
        Err(e) => { crate::util::log(&format!("Call relay: {}", e)); return; }
    };
    for c in l.incoming().flatten() {
        spawn(move || call_relay(c));
    }
}
