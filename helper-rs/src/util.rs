//! Saying and logging, where things are kept, and small helpers shared by everything.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub const PHONE_PORT: u16 = 8787;
pub const DISCOVERY_PORT: u16 = 8788;

static LOG_LOCK: Mutex<()> = Mutex::new(());

/// Where the helper keeps its pairing and notes: the same folder the earlier helpers used, so a
/// laptop already paired stays paired (Windows: %APPDATA%\Xoosh; Linux: ~/.config/localhost-8787;
/// macOS: ~/Library/Application Support/localhost-8787).
pub fn conf_dir() -> PathBuf {
    let d = if cfg!(windows) {
        PathBuf::from(std::env::var("APPDATA").unwrap_or_else(|_| ".".into())).join("Xoosh")
    } else if cfg!(target_os = "macos") {
        home().join("Library/Application Support/localhost-8787")
    } else {
        std::env::var("XDG_CONFIG_HOME").map(PathBuf::from).unwrap_or_else(|_| home().join(".config")).join("localhost-8787")
    };
    let _ = fs::create_dir_all(&d);
    d
}

pub fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").or_else(|_| std::env::var("USERPROFILE")).unwrap_or_else(|_| ".".into()))
}

pub fn read_conf(name: &str) -> Option<String> {
    fs::read_to_string(conf_dir().join(name)).ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

pub fn write_conf(name: &str, text: &str) {
    let p = conf_dir().join(name);
    let _ = fs::write(&p, text);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if name == "session.txt" {
            let _ = fs::set_permissions(&p, fs::Permissions::from_mode(0o600));
        }
    }
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn stamp() -> String {
    // Local time without a time-zone crate: what the clock says, as hours:minutes:seconds.
    let s = now_ms() / 1000 + local_offset_secs();
    format!("{:02}:{:02}:{:02}", (s / 3600) % 24, (s / 60) % 60, s % 60)
}

#[cfg(windows)]
fn local_offset_secs() -> u64 {
    use windows_sys::Win32::System::Time::{GetTimeZoneInformation, TIME_ZONE_INFORMATION};
    unsafe {
        let mut tz: TIME_ZONE_INFORMATION = std::mem::zeroed();
        let r = GetTimeZoneInformation(&mut tz);
        let bias = tz.Bias + if r == 2 { tz.DaylightBias } else { 0 };
        (-(bias as i64) * 60).rem_euclid(86_400) as u64
    }
}

#[cfg(not(windows))]
fn local_offset_secs() -> u64 {
    // The offset `date` reports (+0530), else none.
    std::process::Command::new("date").arg("+%z").output().ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .and_then(|z| {
            let z = z.trim();
            if z.len() != 5 { return None; }
            let sign: i64 = if z.starts_with('-') { -1 } else { 1 };
            let h: i64 = z[1..3].parse().ok()?;
            let m: i64 = z[3..5].parse().ok()?;
            Some((sign * (h * 3600 + m * 60)).rem_euclid(86_400) as u64)
        })
        .unwrap_or(0)
}

/// Into the helper's log file only (kept under 512 KB).
pub fn log(s: &str) {
    let _g = LOG_LOCK.lock();
    let p = conf_dir().join("helper.log");
    if fs::metadata(&p).map(|m| m.len() > 512 * 1024).unwrap_or(false) {
        let _ = fs::remove_file(&p);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&p) {
        let _ = writeln!(f, "{}  {}", stamp(), s);
    }
}

/// On the screen, and in the log.
pub fn say(s: &str) {
    println!("{}  {}", stamp(), s);
    log(s);
}

/// The name the phone shows for this computer.
pub fn machine_name() -> String {
    let n = std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_default();
    if !n.is_empty() {
        return n;
    }
    std::process::Command::new("hostname").output().ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().split('.').next().unwrap_or("").to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Computer".into())
}

/// How the phone knows a helper: "BlazeItPC/1 (NAME)" on Windows, "(NAME; Linux)" or "(NAME; Mac)"
/// elsewhere, shown on the phone as "Laptop control on NAME".
pub fn user_agent() -> String {
    if cfg!(windows) {
        format!("BlazeItPC/1 ({})", machine_name())
    } else if cfg!(target_os = "macos") {
        format!("BlazeItPC/1 ({}; Mac)", machine_name())
    } else {
        format!("BlazeItPC/1 ({}; Linux)", machine_name())
    }
}

/// A JSON string, quoted and escaped.
pub fn json_str(s: &str) -> String {
    serde_json::Value::String(s.to_string()).to_string()
}
