//! The Localhost 8787 laptop helper, in Rust: one program for Windows, Linux and macOS, in place of
//! the Windows .bat (C#) and the Python helper. It finds the phone (the cable, its hotspot, the same
//! Wi-Fi), pairs once, and then lets the phone drive this computer: the trackpad and keys, one
//! clipboard, this computer's files, its health, and the page at http://localhost:8787.
//!
//! From another network it reaches the phone through the L87 tunnel over IPv6 (tunnel.rs, far.rs).
//!
//! Not yet here (the earlier helpers still do them): punching through IPv4 NATs, the second screen,
//! the laptop's sound, the webcam, Handoff and calls.

mod clip;
mod control;
mod far;
mod events;
mod files;
mod health;
mod http;
mod input;
mod phone;
mod relay;
mod tunnel;
mod util;
mod volume;

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
    phone::find_phone(true, typed.as_deref());
    spawn(relay::relay_loop);
    spawn(events::events_loop);
    spawn(clip::clip_loop);
    spawn(far::tunnel_loop);
    spawn(volume::volume_loop);
    control::control_loop(no_browser);
}
