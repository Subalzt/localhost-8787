//! The Localhost 8787 laptop helper, in Rust: one program for Windows, Linux and macOS, in place of
//! the Windows .bat (C#) and the Python helper. It finds the phone (the cable, its hotspot, the same
//! Wi-Fi), pairs once, and then lets the phone drive this computer: the trackpad and keys, one
//! clipboard, this computer's files, its health, and the page at http://localhost:8787.
//!
//! From another network it reaches the phone through the L87 tunnel over IPv6 (tunnel.rs, far.rs).
//!
//! Windows-only here, so far: the second screen with the computer's sound, the phone as the
//! computer's webcam, the location on the map, and Wi-Fi joining of the phone's direct link by
//! netsh. On Linux and macOS the Python helper still does the second screen and the webcam.

mod awake;
mod clip;
mod control;
mod direct;
#[cfg(windows)]
mod displays;
mod far;
mod events;
mod files;
mod handoff;
mod health;
mod http;
mod https;
mod input;
mod link;
#[cfg(windows)]
mod loopback;
mod lyrics;
mod mirror;
mod notify;
mod phone;
mod punch;
mod relay;
#[cfg(windows)]
mod screen;
mod tunnel;
mod util;
mod volume;
mod webcam;
mod where_at;

use std::thread::spawn;
use util::say;

fn single_instance() -> bool {
    #[cfg(windows)]
    {
        use windows_sys::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
        use windows_sys::Win32::System::Threading::CreateMutexW;
        // The same name as the earlier helper's, so only one of them drives the computer at a time.
        let name: Vec<u16> = "Local\\BlazeItLaptopHelper\0".encode_utf16().collect();
        let h = unsafe { CreateMutexW(std::ptr::null(), 1, name.as_ptr()) };
        if h.is_null() || unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
            return false;
        }

    }
    true
}

#[cfg(windows)]
fn on_close() {
    use windows_sys::Win32::System::Console::SetConsoleCtrlHandler;
    unsafe extern "system" fn handler(kind: u32) -> i32 {
        // Ctrl+C, Ctrl+Break, the window closed: tell the phone this is on purpose.
        if kind <= 2 {
            phone::bye();
        }
        // Close, log off, shut down: the phone's screen is taken off the desktop, not left there.
        if kind == 2 || kind == 5 || kind == 6 {
            screen::on_exit();
        }
        0
    }
    unsafe { SetConsoleCtrlHandler(Some(handler), 1) };
}

#[cfg(not(windows))]
fn on_close() {}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let no_browser = args.iter().any(|a| a == "--no-browser");
    let typed = args.iter().position(|a| a == "--phone").and_then(|i| args.get(i + 1)).cloned();
    if args.iter().any(|a| a == "--cable-check") {
        phone::cable_report();
        return;
    }
    if args.first().map_or(false, |a| a == "--seal" || a == "--unseal") {
        punch::seal_check(&args);
        return;
    }
    if args.iter().any(|a| a == "--punch-selftest") {
        std::process::exit(if punch::selftest() { 0 } else { 1 });
    }
    if args.iter().any(|a| a == "--ring-test") {
        // A call ringing here for three seconds, then stopped: to see the notification works.
        notify::call_event(r#"{"id":"test","phase":"ringing","outgoing":false,"video":false,"members":[{"name":"Test"}]}"#);
        std::thread::sleep(std::time::Duration::from_secs(3));
        notify::call_event("null");
        std::thread::sleep(std::time::Duration::from_secs(1));
        return;
    }
    if let Some(i) = args.iter().position(|a| a == "--link-check") {
        // --link-check HOST: what the Monitor tab would be told of the link to HOST.
        println!("{}", link::report(args.get(i + 1).map(|s| s.as_str()).unwrap_or("192.168.1.1"), 8787));
        return;
    }
    #[cfg(windows)]
    if let Some(i) = args.iter().position(|a| a == "--screen-selftest") {
        screen::selftest(args.get(i + 1).and_then(|p| p.parse().ok()).unwrap_or(18799));
        return;
    }
    #[cfg(windows)]
    if args.iter().any(|a| a == "--screen-check") {
        screen::check();
        return;
    }
    if args.iter().any(|a| a == "--webcam-check") {
        webcam::check();
        return;
    }
    if args.iter().any(|a| a == "--handoff") {
        println!("{}", handoff::snapshot());
        return;
    }
    if args.iter().any(|a| a == "--health") {
        println!("{}", health::snapshot());
        return;
    }
    if let Some(i) = args.iter().position(|a| a == "--volume") {
        // --volume reports the level; --volume 0.4 sets it first (a check, as --health is).
        if let Some(l) = args.get(i + 1).and_then(|v| v.parse::<f32>().ok()) {
            volume::set(l);
        }
        match volume::get() {
            Some((l, m)) => println!("{{\"level\":{},\"muted\":{}}}", l, m),
            None => println!("no volume control here"),
        }
        return;
    }
    if !single_instance() {
        say("Another Localhost 8787 helper is running here. Close it, then start this one again.");
        std::thread::sleep(std::time::Duration::from_secs(5));
        return;
    }
    on_close();
    say(&format!("Localhost 8787 laptop helper (Rust {}) for {}. Keep this window open; close it to stop.", env!("CARGO_PKG_VERSION"), util::machine_name()));
    #[cfg(windows)]
    screen::recover();
    phone::find_phone(true, typed.as_deref());
    spawn(relay::relay_loop);
    spawn(relay::call_relay_loop);
    spawn(events::events_loop);
    spawn(clip::clip_loop);
    spawn(far::tunnel_loop);
    spawn(volume::volume_loop);
    spawn(awake::awake_loop);
    spawn(link::link_loop);
    spawn(lyrics::lyrics_loop);
    spawn(webcam::webcam_loop);
    spawn(direct::direct_loop);
    spawn(where_at::where_loop);
    spawn(phone::follow_cable);
    #[cfg(windows)]
    spawn(handoff::hotkey_loop);
    control::control_loop(no_browser);
}
