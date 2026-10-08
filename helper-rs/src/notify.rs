//! What the phone wants this computer to show: a call ringing on the phone (it rings here too, the
//! page open or not) and movement seen by one of the phones in camera mode.
//!
//! Windows: a toast, through PowerShell (which reaches Windows' notifications), under the name
//! "Localhost 8787" kept in the user's registry, as Windows asks of an app without a shortcut; a
//! call's toast rings until the call stops ringing. Linux: `notify-send`. macOS: the Notification
//! Centre.

use crate::util::{conf_dir, log, stamp};
use base64::Engine;
use serde_json::Value;
use std::sync::Mutex;

static RINGING: Mutex<Option<String>> = Mutex::new(None);

fn page(tab: &str) -> String {
    format!("http://localhost:{}/#{}", crate::phone::port(), tab)
}

/// The call as the phone tells it ("call", the page's view of it): ring for one coming in, stop after.
pub fn call_event(d: &str) {
    let c: Option<Value> = serde_json::from_str(d).ok().filter(|v: &Value| v.is_object());
    let mut ringing = RINGING.lock().unwrap();
    let incoming = c.as_ref().filter(|c| c["phase"] == "ringing" && c["outgoing"] != true);
    if let Some(c) = incoming {
        let id = c["id"].as_str().unwrap_or("").to_string();
        if ringing.as_deref() == Some(id.as_str()) {
            return;
        }
        *ringing = Some(id);
        let names: Vec<&str> = c["members"].as_array().into_iter().flatten().filter_map(|m| m["name"].as_str()).collect();
        let who = if names.is_empty() { "A phone".to_string() } else { names.join(", ") };
        let kind = if c["video"] == true { "Video call" } else { "Voice call" };
        os::ring(&who, kind, &page("phones"));
        log(&format!("Ringing on this computer: {}", who));
    } else if ringing.take().is_some() {
        os::stop_ringing();
    }
}

/// Movement seen by one of your phones in camera mode ("camalert"): a notification with what it
/// saw, which opens the page's Cameras. The pictures are kept beside the helper's other files, the
/// last few only.
pub fn cam_alert(d: &str) {
    let Ok(a) = serde_json::from_str::<Value>(d) else { return };
    let camera = a["camera"].as_str().filter(|s| !s.is_empty()).unwrap_or("A camera").to_string();
    let mut pic = None;
    if let Some(j) = a["jpeg"].as_str().filter(|s| !s.is_empty()) {
        if let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(j) {
            let dir = conf_dir();
            let p = dir.join(format!("cam-alert-{}.jpg", crate::util::now_ms()));
            if std::fs::write(&p, bytes).is_ok() {
                let mut old: Vec<_> = std::fs::read_dir(&dir).into_iter().flatten().flatten().map(|e| e.path())
                    .filter(|p| p.file_name().and_then(|n| n.to_str()).map_or(false, |n| n.starts_with("cam-alert-") && n.ends_with(".jpg")))
                    .collect();
                old.sort();
                let n = old.len();
                for f in old.iter().take(n.saturating_sub(5)) {
                    let _ = std::fs::remove_file(f);
                }
                pic = Some(p);
            }
        }
    }
    os::movement(&camera, &stamp(), &page("cams"), pic.as_deref());
    log(&format!("Movement: {}", camera));
}

#[cfg(windows)]
mod os {
    use base64::Engine;
    use std::os::windows::process::CommandExt;
    use std::path::Path;
    use std::process::Command;

    const APP: &str = "Localhost8787.Calls";

    fn xml(s: &str) -> String {
        s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
    }

    /// A few lines of PowerShell with Windows' notifications at hand, run apart (this helper does not wait).
    fn toasts(script: &str) {
        let full = format!(
            "$ErrorActionPreference = 'SilentlyContinue'; $k = 'HKCU:\\Software\\Classes\\AppUserModelId\\{APP}'; \
             if (-not (Test-Path $k)) {{ New-Item $k -Force | Out-Null; Set-ItemProperty $k DisplayName 'Localhost 8787' }}; \
             [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; \
             [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime] | Out-Null; {script}"
        );
        let utf16: Vec<u8> = full.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        let enc = base64::engine::general_purpose::STANDARD.encode(utf16);
        if let Err(e) = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", &enc])
            .creation_flags(0x0800_0000)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
        {
            super::log(&format!("Could not ring here: {}", e));
        }
    }

    fn show(toast_xml: &str, tag: &str, group: &str) {
        let tag = if tag.is_empty() { String::new() } else { format!(" $t.Tag = '{tag}';") };
        toasts(&format!(
            "$x = New-Object Windows.Data.Xml.Dom.XmlDocument; $x.LoadXml('{}'); \
             $t = [Windows.UI.Notifications.ToastNotification]::new($x);{tag} $t.Group = '{group}'; \
             [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('{APP}').Show($t)",
            toast_xml.replace('\'', "''")
        ));
    }

    pub fn ring(who: &str, kind: &str, url: &str) {
        let x = format!(
            "<toast scenario=\"incomingCall\" activationType=\"protocol\" launch=\"{u}\">\
             <visual><binding template=\"ToastGeneric\"><text>{a}</text><text>{b}</text></binding></visual>\
             <actions><action content=\"Answer on the laptop\" activationType=\"protocol\" arguments=\"{u}\"/></actions>\
             <audio src=\"ms-winsoundevent:Notification.Looping.Call\" loop=\"true\"/></toast>",
            u = xml(url),
            a = xml(&format!("{} is calling", who)),
            b = xml(&format!("{} on the phone \u{b7} answer on this laptop", kind)),
        );
        show(&x, "call", "calls");
    }

    pub fn stop_ringing() {
        toasts(&format!("[Windows.UI.Notifications.ToastNotificationManager]::History.Remove('call', 'calls', '{APP}')"));
    }

    pub fn movement(camera: &str, at: &str, url: &str, pic: Option<&Path>) {
        let hero = pic
            .map(|p| format!("<image placement=\"hero\" src=\"{}\"/>", xml(&format!("file:///{}", p.to_string_lossy().replace('\\', "/").replace(' ', "%20")))))
            .unwrap_or_default();
        let x = format!(
            "<toast activationType=\"protocol\" launch=\"{u}\">\
             <visual><binding template=\"ToastGeneric\"><text>{a}</text><text>{b}</text>{hero}</binding></visual>\
             <actions><action content=\"Watch\" activationType=\"protocol\" arguments=\"{u}\"/></actions></toast>",
            u = xml(url),
            a = xml(&format!("Movement: {}", camera)),
            b = xml(&format!("Seen at {} \u{b7} click to watch", at)),
        );
        show(&x, "", "cams");
    }
}

#[cfg(target_os = "macos")]
mod os {
    use std::path::Path;
    use std::process::{Command, Stdio};

    fn banner(title: &str, body: &str) {
        let q = |s: &str| serde_json::Value::String(s.to_string()).to_string();
        let _ = Command::new("osascript")
            .args(["-e", &format!("display notification {} with title {} sound name \"Glass\"", q(body), q(title))])
            .stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    }

    pub fn ring(who: &str, kind: &str, url: &str) {
        banner(&format!("{} is calling", who), &format!("{} on the phone. Answer on this computer: {}", kind, url));
    }

    pub fn stop_ringing() {}

    pub fn movement(camera: &str, at: &str, url: &str, _pic: Option<&Path>) {
        banner(&format!("Movement: {}", camera), &format!("Seen at {}. Watch: {}", at, url));
    }
}

#[cfg(all(unix, not(target_os = "macos")))]
mod os {
    use std::path::Path;
    use std::process::{Command, Stdio};

    fn notify(icon: &str, ms: &str, title: &str, body: &str) {
        let _ = Command::new("notify-send")
            .args(["-a", "Localhost 8787", "-u", "critical", "-i", icon, "-t", ms, title, body])
            .stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    }

    pub fn ring(who: &str, kind: &str, url: &str) {
        notify("call-start", "45000", &format!("{} is calling", who), &format!("{} on the phone. Answer on this computer: {}", kind, url));
    }

    pub fn stop_ringing() {}

    pub fn movement(camera: &str, at: &str, url: &str, pic: Option<&Path>) {
        let icon = pic.map(|p| p.to_string_lossy().into_owned()).unwrap_or_else(|| "camera-web".into());
        notify(&icon, "30000", &format!("Movement: {}", camera), &format!("Seen at {}. Watch: {}", at, url));
    }
}
