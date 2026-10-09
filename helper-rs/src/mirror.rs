//! The phone's screen on this computer, to use with the mouse and keyboard, sound included
//! (scrcpy, github.com/Genymobile/scrcpy, which talks to the phone over adb). On Windows scrcpy is
//! fetched from its official release the first time and checked against the release's own
//! checksum. The phone needs USB debugging on, once.

use crate::util::{conf_dir, say};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;

static OPEN: Mutex<bool> = Mutex::new(false);

fn exe_name() -> &'static str {
    if cfg!(windows) { "scrcpy.exe" } else { "scrcpy" }
}

fn home() -> PathBuf {
    if cfg!(windows) {
        PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_default()).join("BlazeIt").join("scrcpy")
    } else {
        conf_dir().join("scrcpy")
    }
}

fn find() -> Option<PathBuf> {
    let mut dirs = vec![home()];
    if let Some(d) = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join("scrcpy"))) {
        dirs.push(d);
    }
    if let Some(p) = dirs.into_iter().map(|d| d.join(exe_name())).find(|p| p.is_file()) {
        return Some(p);
    }
    std::env::var_os("PATH").and_then(|paths| std::env::split_paths(&paths).map(|d| d.join(exe_name())).find(|p| p.is_file()))
}

fn no_window(c: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000);
    }
    let _ = c;
}

/// Downloads the latest scrcpy for 64-bit Windows from its official GitHub release, checked.
#[cfg(windows)]
fn fetch() -> Option<PathBuf> {
    use sha2::{Digest, Sha256};
    use std::time::Duration;
    say("Getting scrcpy (about 11 MB), the piece that shows the phone's screen, from its official release...");
    let api = crate::https::request(
        "GET",
        "https://api.github.com/repos/Genymobile/scrcpy/releases/latest",
        &[("User-Agent", crate::util::user_agent()), ("Accept", "application/vnd.github+json".into())],
        None,
        Duration::from_secs(30),
    );
    let v: serde_json::Value = api.ok().and_then(|r| serde_json::from_str(&r.body).ok()).unwrap_or_default();
    let asset = v["assets"].as_array().into_iter().flatten().find(|a| {
        let n = a["name"].as_str().unwrap_or("");
        n.starts_with("scrcpy-win64-v") && n.ends_with(".zip")
    });
    let (Some(name), Some(sum), Some(url)) = (
        asset.and_then(|a| a["name"].as_str()),
        asset.and_then(|a| a["digest"].as_str()).and_then(|d| d.strip_prefix("sha256:")),
        asset.and_then(|a| a["browser_download_url"].as_str()),
    ) else {
        say(&format!("Could not find scrcpy's download. Get it from github.com/Genymobile/scrcpy and unzip it into {}", home().display()));
        return None;
    };
    let zip = std::env::temp_dir().join(name);
    if let Err(e) = crate::https::download(url, &zip, Duration::from_secs(300)) {
        say(&format!("Could not get scrcpy ({}).", e));
        return None;
    }
    let got: String = Sha256::digest(std::fs::read(&zip).ok()?).iter().map(|b| format!("{:02x}", b)).collect();
    if got != sum {
        let _ = std::fs::remove_file(&zip);
        say("scrcpy's download did not match its checksum; not using it.");
        return None;
    }
    let tmp = PathBuf::from(format!("{}.part", home().display()));
    let _ = std::fs::remove_dir_all(&tmp);
    let _ = std::fs::create_dir_all(&tmp);
    // Windows 10 and later carry tar, which reads zip files.
    let mut c = Command::new("tar");
    c.arg("-xf").arg(&zip).arg("-C").arg(&tmp);
    no_window(&mut c);
    let ok = c.status().map(|s| s.success()).unwrap_or(false);
    let _ = std::fs::remove_file(&zip);
    if !ok {
        say("Could not unpack scrcpy.");
        return None;
    }
    // The zip holds one folder (scrcpy-win64-vX.Y); that folder becomes scrcpy's home.
    let inner: Vec<PathBuf> = std::fs::read_dir(&tmp).ok()?.flatten().map(|e| e.path()).collect();
    let src = if inner.len() == 1 && inner[0].is_dir() && !tmp.join(exe_name()).exists() { inner[0].clone() } else { tmp.clone() };
    let _ = std::fs::remove_dir_all(home());
    let _ = std::fs::create_dir_all(home().parent()?);
    std::fs::rename(&src, home()).ok()?;
    let _ = std::fs::remove_dir_all(&tmp);
    say("scrcpy is ready.");
    Some(home().join(exe_name()))
}

#[cfg(not(windows))]
fn fetch() -> Option<PathBuf> {
    say("To see the phone's screen here, install scrcpy (Homebrew: brew install scrcpy; Linux: your package manager), with USB debugging on on the phone.");
    None
}

/// The phone's screen in a window of its own, until it is closed.
pub fn open_phone_screen() {
    {
        let mut open = OPEN.lock().unwrap();
        if *open {
            say("The phone's screen is already open.");
            return;
        }
        *open = true;
    }
    run();
    *OPEN.lock().unwrap() = false;
}

fn run() {
    let Some(exe) = find().or_else(fetch) else { return };
    // The Android SDK's adb when there is one (two different adbs fight over the same port), else scrcpy's own.
    let adb = crate::phone::adb_exe().unwrap_or_else(|| exe.with_file_name(if cfg!(windows) { "adb.exe" } else { "adb" }));
    let mut c = Command::new(&adb);
    c.arg("devices").stdin(Stdio::null());
    no_window(&mut c);
    let devices = c.output().map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    // The cable when there is one: it is the fastest and needs nothing else.
    let usb = devices
        .lines()
        .filter_map(|l| {
            let p: Vec<&str> = l.trim().split('\t').collect();
            (p.len() == 2 && p[1] == "device" && !p[0].contains(':')).then(|| p[0].to_string())
        })
        .next();
    let target: Vec<String> = match (&usb, crate::phone::phone()) {
        (Some(s), _) => vec!["-s".into(), s.clone()],
        (None, Some(p)) if !p.starts_with("127.") => vec![format!("--tcpip={}:5555", p)],
        _ => {
            say("Plug the phone in with a USB cable (USB debugging on), then try again.");
            return;
        }
    };
    let mut c = Command::new(&exe);
    c.args(&target).args(["--window-title=Phone (Localhost 8787)", "--stay-awake", "--video-bit-rate=16M", "--max-fps=60"]);
    c.env("ADB", &adb).current_dir(exe.parent().unwrap_or(std::path::Path::new("."))).stdin(Stdio::null());
    no_window(&mut c);
    say(if usb.is_some() { "Opening the phone's screen over the USB cable." } else { "Opening the phone's screen over Wi-Fi." });
    match c.output() {
        Ok(o) if !o.status.success() => {
            let err = format!("{}{}", String::from_utf8_lossy(&o.stderr), String::from_utf8_lossy(&o.stdout));
            if ["Could not find any ADB device", "failed to connect", "Could not connect"].iter().any(|k| err.contains(k)) {
                say("Could not reach the phone over adb. On the phone: Developer options, turn on USB debugging (and on Xiaomi, USB debugging (Security settings)); plug it in once, and allow this computer.");
            } else {
                say(&format!("The phone's screen closed: {}", err.trim().lines().last().unwrap_or("").trim()));
            }
        }
        Ok(_) => {}
        Err(e) => say(&format!("Could not open the phone's screen ({}).", e)),
    }
}
