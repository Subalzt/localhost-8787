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
