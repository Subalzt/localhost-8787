//! The phone's direct link and hotspot. When the phone offers a fast link for computers, this
//! computer joins it: either the phone's direct link (its own offline network, fastest) or, in
//! hotspot mode, the phone's ordinary hotspot, which shares the phone's internet so this computer
//! stays online. The page keeps working because it talks to localhost, and the helper follows the
//! phone to its address on the new network. When the link goes away, this computer goes back to the
//! Wi-Fi it was on. A link started only for a phone-to-phone send says so ("laptop":false), and
//! this computer stays where it is.
//!
//! Windows: netsh. Linux: NetworkManager (nmcli). A Mac joins it by hand from the Wi-Fi menu; the
//! helper follows the phone there.

use crate::phone;
use crate::util::{conf_dir, log, read_conf, say, write_conf, PHONE_PORT};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::thread::sleep;
use std::time::{Duration, Instant};

/// The phone said its direct link changed: asked at once.
pub static POKED: AtomicBool = AtomicBool::new(true);

#[derive(Default)]
struct State {
    /// The direct link's network name while this computer is on it.
    ssid: Option<String>,
    /// The Wi-Fi to go back to.
    home: Option<String>,
    /// True when this helper created the profile it joined, so it may delete it afterwards.
    added: bool,
    /// A network that could not be joined, so it is not retried every few seconds.
    gave_up_on: Option<String>,
}

static STATE: Mutex<State> = Mutex::new(State { ssid: None, home: None, added: false, gave_up_on: None });

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let mut c = Command::new(cmd);
    c.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000);
    }
    let o = c.output().ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
}

fn say_once(key: &str, text: &str) {
    static SAID: Mutex<Vec<String>> = Mutex::new(Vec::new());
    let mut s = SAID.lock().unwrap();
    if !s.iter().any(|k| k == key) {
        s.push(key.to_string());
        say(text);
    }
}

// ---- what the system's Wi-Fi can do

#[cfg(windows)]
mod os {
    use super::run;

    pub fn usable() -> Result<(), String> {
        Ok(())
    }

    pub fn current() -> Option<String> {
        crate::link::wifi().get("Profile").cloned().filter(|p| !p.is_empty())
    }

    pub fn known(ssid: &str) -> bool {
        !run("netsh", &["wlan", "show", "profile", &format!("name={}", ssid)]).unwrap_or_else(|| "is not found".into()).contains("is not found")
    }

    fn xml(s: &str) -> String {
        s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
    }

    /// Joins a network already saved; a new one is saved first (for this user only).
    pub fn join(ssid: &str, pass: &str, security: &str, known: bool) -> bool {
        if !known {
            let profile = format!(
                "<?xml version=\"1.0\"?><WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\">\
                 <name>{n}</name><SSIDConfig><SSID><name>{n}</name></SSID></SSIDConfig>\
                 <connectionType>ESS</connectionType><connectionMode>manual</connectionMode><MSM><security>\
                 <authEncryption><authentication>{auth}</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>\
                 <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{k}</keyMaterial></sharedKey>\
                 </security></MSM></WLANProfile>",
                n = xml(ssid), k = xml(pass), auth = if security == "WPA3" { "WPA3SAE" } else { "WPA2PSK" }
            );
            let file = std::env::temp_dir().join("blazeit-direct.xml");
            if std::fs::write(&file, profile).is_err() {
                return false;
            }
            let added = run("netsh", &["wlan", "add", "profile", &format!("filename={}", file.display()), "user=current"]).is_some();
            let _ = std::fs::remove_file(&file);
            if !added {
                return false;
            }
        }
        run("netsh", &["wlan", "connect", &format!("name={}", ssid), &format!("ssid={}", ssid)]);
        true
    }

    pub fn back_to(home: &str) {
        run("netsh", &["wlan", "connect", &format!("name={}", home)]);
    }

    pub fn delete(ssid: &str) {
        run("netsh", &["wlan", "delete", "profile", &format!("name={}", ssid)]);
    }
}

#[cfg(all(unix, not(target_os = "macos")))]
mod os {
    use super::run;
    use std::thread::sleep;
    use std::time::Duration;

    fn wifi_device() -> Option<String> {
        run("nmcli", &["-t", "-f", "DEVICE,TYPE,STATE", "dev"])?.lines().find_map(|l| {
            let p: Vec<&str> = l.split(':').collect();
            (p.len() >= 3 && p[1] == "wifi" && p[2] != "unavailable").then(|| p[0].to_string())
        })
    }

    pub fn usable() -> Result<(), String> {
        if run("nmcli", &["-v"]).is_none() || wifi_device().is_none() {
            return Err("this computer has no Wi-Fi that NetworkManager (nmcli) can use".into());
        }
        Ok(())
    }

    pub fn current() -> Option<String> {
        run("nmcli", &["-t", "-f", "NAME,TYPE", "con", "show", "--active"])?.lines().find_map(|l| {
            let (name, kind) = l.rsplit_once(':')?;
            (kind == "802-11-wireless").then(|| name.replace("\\:", ":"))
        })
    }

    pub fn known(ssid: &str) -> bool {
        run("nmcli", &["-t", "-f", "NAME", "con", "show"]).map_or(false, |o| o.lines().any(|l| l == ssid))
    }

    pub fn join(ssid: &str, pass: &str, _security: &str, known: bool) -> bool {
        // A network that has only just started is not in the last scan yet.
        run("nmcli", &["dev", "wifi", "rescan", "ssid", ssid]);
        for _ in 0..10 {
            if run("nmcli", &["-t", "-f", "SSID", "dev", "wifi", "list", "--rescan", "no"]).map_or(false, |o| o.lines().any(|l| l == ssid)) {
                break;
            }
            sleep(Duration::from_secs(1));
        }
        if known {
            run("nmcli", &["con", "up", "id", ssid]).is_some()
        } else {
            run("nmcli", &["dev", "wifi", "connect", ssid, "password", pass]).is_some()
        }
    }

    pub fn back_to(home: &str) {
        run("nmcli", &["con", "up", "id", home]);
    }

    pub fn delete(ssid: &str) {
        run("nmcli", &["con", "delete", "id", ssid]);
    }
}

#[cfg(target_os = "macos")]
mod os {
    pub fn usable() -> Result<(), String> {
        Err("on a Mac, join it from the Wi-Fi menu; the helper follows the phone there by itself".into())
    }
    pub fn current() -> Option<String> { None }
    pub fn known(_: &str) -> bool { false }
    pub fn join(_: &str, _: &str, _: &str, _: bool) -> bool { false }
    pub fn back_to(_: &str) {}
    pub fn delete(_: &str) {}
}

// ---- joining and leaving

fn direct_file() -> std::path::PathBuf {
    conf_dir().join("direct-wifi.txt")
}

fn join_direct(ssid: &str, pass: &str, host: &str, security: &str, hotspot: bool) {
    let what = if hotspot { "hotspot" } else { "direct link" };
    if let Err(e) = os::usable() {
        say_once("no-wifi", &format!("The phone offers its {} ({}), but {}; staying on the current link.", what, ssid, e));
        STATE.lock().unwrap().gave_up_on = Some(ssid.to_string());
        return;
    }
    // A network this computer already knows (the phone's hotspot, often) is joined with the saved
    // profile, which is left exactly as it was.
    let known = os::known(ssid);
    if !known && pass.is_empty() {
        say("The phone's hotspot is on, but this computer does not know its password. Add it in the phone's Settings (Laptop link).");
        STATE.lock().unwrap().gave_up_on = Some(ssid.to_string());
        return;
    }
    if let Some(cur) = os::current().filter(|c| c != ssid) {
        write_conf("home-wifi.txt", &cur);
        STATE.lock().unwrap().home = Some(cur);
    }
    say(if hotspot {
        "The phone's hotspot is on. Moving this computer onto it; the internet stays on through the phone."
    } else {
        "The phone started its direct link. Moving this computer onto it; there is no internet while on it."
    });
    {
        let mut s = STATE.lock().unwrap();
        s.added = !known;
        s.ssid = Some(ssid.to_string());
    }
    let _ = std::fs::write(direct_file(), format!("{}\n{}", ssid, if known { "saved" } else { "added" }));
    let joined = os::join(ssid, pass, security, known);
    for _ in 0..50 {
        if joined && phone::ping(host) {
            phone::move_to(host, PHONE_PORT);
            say(if hotspot {
                "On the phone's hotspot. The page carries on over it, and the internet works."
            } else {
                "On the direct link. The page carries on over it at full speed."
            });
            return;
        }
        sleep(Duration::from_millis(500));
    }
    say(&format!("Could not reach the phone on {}; going back.", if hotspot { "its hotspot" } else { "its direct link" }));
    STATE.lock().unwrap().gave_up_on = Some(ssid.to_string());
    leave_direct(false);
}

fn leave_direct(quiet: bool) {
    let (ssid, home, added) = {
        let mut s = STATE.lock().unwrap();
        let r = (s.ssid.take(), s.home.clone(), s.added);
        s.added = false;
        r
    };
    let _ = std::fs::remove_file(direct_file());
    let home = home.or_else(|| read_conf("home-wifi.txt"));
    if let Some(h) = &home {
        os::back_to(h);
    }
    // Only a network this helper added itself (the direct link's, never one you saved) goes.
    if let Some(s) = ssid.filter(|s| added && s.starts_with("AndroidShare")) {
        os::delete(&s);
    }
    if !quiet {
        say(&format!("The phone's link ended. Back on {}.", home.as_deref().unwrap_or("your Wi-Fi")));
        sleep(Duration::from_secs(3));
        phone::find_phone(false, None);
    }
}

/// A cable beats every Wi-Fi link: the direct link is left (quietly) when the cable is found.
pub fn leave_for_cable() {
    if STATE.lock().unwrap().ssid.is_some() {
        leave_direct(true);
    }
}

pub fn direct_loop() {
    if let Some(rec) = read_conf("direct-wifi.txt") {
        let mut l = rec.lines();
        let mut s = STATE.lock().unwrap();
        s.ssid = l.next().map(|x| x.trim().to_string()).filter(|x| !x.is_empty());
        // Only a profile this helper recorded as its own may be deleted; older helpers wrote one
        // line, and a profile of unknown origin is always kept.
        s.added = l.next().map(|x| x.trim() == "added").unwrap_or(false);
    }
    STATE.lock().unwrap().home = read_conf("home-wifi.txt");
    let (mut misses, mut polled): (u32, Option<Instant>) = (0, None);
    loop {
        sleep(Duration::from_millis(2500));
        if phone::session().is_none() || phone::phone().is_none() {
            continue;
        }
        // Asked when the phone says its direct link changed ("direct"), each 2.5 s only while on the
        // link (to notice it stopping), else once a minute, so an idle phone sleeps.
        let on_link = STATE.lock().unwrap().ssid.is_some();
        if !POKED.swap(false, Ordering::Relaxed) && !on_link && polled.map_or(false, |t| t.elapsed() < Duration::from_secs(60)) {
            continue;
        }
        polled = Some(Instant::now());
        let d: Option<serde_json::Value> = phone::request("GET", "/api/direct", None, &[], 3000)
            .ok()
            .filter(|r| r.status == 200)
            .and_then(|r| serde_json::from_slice(&r.body).ok());
        let Some(d) = d else {
            // On the link and the phone has gone quiet: the link was stopped.
            misses += 1;
            if on_link && misses >= 3 {
                leave_direct(false);
                misses = 0;
            }
            continue;
        };
        misses = 0;
        let on = d["state"] == "on" && d["laptop"] != false;
        let (ssid, host) = (d["ssid"].as_str().unwrap_or(""), d["host"].as_str().unwrap_or(""));
        let (current, gave_up) = {
            let s = STATE.lock().unwrap();
            (s.ssid.clone(), s.gave_up_on.clone())
        };
        if on && !ssid.is_empty() && !host.is_empty() && current.as_deref() != Some(ssid) && gave_up.as_deref() != Some(ssid) {
            join_direct(ssid, d["passphrase"].as_str().unwrap_or(""), host, d["security"].as_str().unwrap_or(""), d["kind"] == "hotspot");
        } else if !on && current.is_some() {
            leave_direct(false);
        }
        if !on {
            STATE.lock().unwrap().gave_up_on = None;
        }
        let _ = log;
    }
}
