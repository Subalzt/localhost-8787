//! Handoff: what this computer is in the middle of, for the phone to carry on, and a page the
//! phone sends to open here (events.rs). Asked for by the phone ("handoff ID"), or sent with
//! Ctrl+Alt+P on Windows.
//!
//! Windows: Windows' media sessions and the browser's address bar, read by PowerShell (handoff.ps1,
//! the earlier helper's own script), the window in front, and Firefox's session file, which has the
//! page of every tab even in full screen. Linux: the media players' own word (MPRIS, through
//! `playerctl`). macOS: the front Safari or Chrome tab. What plays is paused here as it goes.

use crate::phone;
use crate::util::{log, machine_name};
use serde_json::{json, Map, Value};

fn escape(s: &str) -> String {
    s.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) { (b as char).to_string() } else { format!("%{:02X}", b) }).collect()
}

/// The phone asked ("handoff ID"): what is shown or playing here, posted back under that id.
pub fn answer(id: &str) {
    let body = std::panic::catch_unwind(|| now(true)).map(|v| v.to_string()).unwrap_or_else(|_| json!({"error": "could not read it"}).to_string());
    let _ = phone::post_json(&format!("/api/handoff/answer?id={}", escape(id)), &body);
}

/// What is shown or playing here, left playing (`--handoff`, to see what the phone would be told).
pub fn snapshot() -> String {
    now(false).to_string()
}

/// Ctrl+Alt+P: what is shown or playing here goes to the phone, as it is.
#[cfg(windows)]
fn push() {
    let v = now(true);
    match phone::post_json("/api/handoff/open", &v.to_string()) {
        Ok(_) => log(&format!("Sent to the phone: {}", v["title"].as_str().unwrap_or(""))),
        Err(e) => log(&format!("Could not send it to the phone: {}", e)),
    }
}

fn base(laptop: bool) -> Map<String, Value> {
    let mut m = Map::new();
    for k in ["url", "title", "app"] {
        m.insert(k.into(), json!(""));
    }
    if laptop {
        m.insert("laptop".into(), json!(machine_name()));
    }
    m
}

#[cfg(windows)]
pub use win::{hotkey_loop, page_open};

#[cfg(windows)]
fn now(pause: bool) -> Value {
    win::now(pause)
}

#[cfg(not(windows))]
pub fn page_open(_port: u16) -> bool {
    false
}

#[cfg(windows)]
mod win {
    use super::*;
    use base64::Engine;
    use std::os::windows::process::CommandExt;
    use std::path::PathBuf;
    use std::process::Command;
    use std::time::{Duration, SystemTime};
    use windows_sys::Win32::Foundation::{CloseHandle, HWND, LPARAM};
    use windows_sys::Win32::System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION};
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::RegisterHotKey;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;

    const SCRIPT: &str = include_str!("handoff.ps1");
    const FIREFOX_AUMID: &str = "308046B0AF4A39CB";

    fn run_script(pause: bool) -> String {
        let script = SCRIPT.replace("PAUSE", if pause { "$true" } else { "$false" });
        let utf16: Vec<u8> = script.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        let enc = base64::engine::general_purpose::STANDARD.encode(utf16);
        Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-EncodedCommand", &enc])
            .creation_flags(0x0800_0000) // no window
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default()
    }

    /// The window in front: its program's name and its title.
    fn foreground() -> Option<(String, String)> {
        unsafe {
            let h = GetForegroundWindow();
            if h.is_null() {
                return None;
            }
            let mut pid = 0u32;
            GetWindowThreadProcessId(h, &mut pid);
            let mut buf = [0u16; 512];
            let n = GetWindowTextW(h, buf.as_mut_ptr(), 512).max(0) as usize;
            let title = String::from_utf16_lossy(&buf[..n]);
            let p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if p.is_null() {
                return None;
            }
            let mut path = [0u16; 520];
            let mut len = path.len() as u32;
            let ok = QueryFullProcessImageNameW(p, 0, path.as_mut_ptr(), &mut len) != 0;
            CloseHandle(p);
            if !ok {
                return None;
            }
            let full = PathBuf::from(String::from_utf16_lossy(&path[..len as usize]));
            Some((full.file_stem()?.to_string_lossy().into_owned(), title))
        }
    }

    fn is_browser_app(aumid: &str) -> bool {
        let a = aumid.to_lowercase();
        a == "308046b0af4a39cb" || ["firefox", "chrome", "msedge", "brave", "opera", "vivaldi"].iter().any(|b| a.contains(b))
    }

    fn app_name(aumid: &str) -> String {
        let a = aumid.to_lowercase();
        for (k, v) in [("msedge", "Edge"), ("chrome", "Chrome"), ("brave", "Brave"), ("spotify", "Spotify")] {
            if a.contains(k) {
                return v.into();
            }
        }
        match aumid.find('!') {
            Some(i) if i > 0 => aumid[i + 1..].to_string(),
            _ => aumid.to_string(),
        }
    }

    fn rank(f: &[String]) -> i32 {
        (if f[2] == "Playing" { 2 } else { 0 }) + (if is_browser_app(&f[1]) { 1 } else { 0 })
    }

    /// "Page title — Mozilla Firefox" to "Page title".
    fn without_firefox(t: &str) -> String {
        if !t.ends_with("Mozilla Firefox") {
            return t.to_string();
        }
        let cut = [" \u{2014} ", " - "].iter().filter_map(|s| t.find(s)).min();
        cut.map(|i| t[..i].to_string()).unwrap_or_else(|| t.to_string())
    }

    /// "Page title - Site" to "Page title".
    fn without_site(t: &str) -> String {
        match t.rfind(" - ") {
            Some(i) if i + 3 < t.len() && !t[i + 3..].contains('-') => t[..i].to_string(),
            _ => t.to_string(),
        }
    }

    pub fn now(pause: bool) -> Value {
        let out = run_script(pause);
        let fg = foreground();
        let mut best: Option<Vec<String>> = None;
        let mut urls: Vec<Vec<String>> = Vec::new();
        for raw in out.split('\n') {
            let f: Vec<String> = raw.trim_end_matches('\r').split('\t').map(|s| s.to_string()).collect();
            if f[0] == "S" && f.len() >= 6 {
                // A browser's playing session first, then any playing one, then a paused browser one.
                if best.as_ref().map_or(true, |b| rank(&f) > rank(b)) {
                    best = Some(f);
                }
            } else if f[0] == "U" && f.len() >= 4 {
                urls.push(f);
            }
        }
        let app = best.as_ref().map(|b| b[1].clone()).or_else(|| fg.as_ref().map(|f| f.0.clone())).unwrap_or_default();
        let mut title = best.as_ref().map(|b| b[4].clone()).unwrap_or_default();
        let mut url = String::new();
        let firefox = app == FIREFOX_AUMID || app.to_lowercase().contains("firefox") || (best.is_none() && fg.as_ref().map_or(false, |f| f.0 == "firefox"));
        if firefox {
            let want = if !title.is_empty() { title.clone() } else { fg.as_ref().map(|f| without_firefox(&f.1)).unwrap_or_default() };
            // Its own address bar first, when its window shows what plays (not in full screen, where it hides).
            for u in &urls {
                if u[1] == "firefox" && (want.is_empty() || u[2].starts_with(&want)) {
                    url = if u[3].contains("://") { u[3].clone() } else { format!("https://{}", u[3]) };
                    break;
                }
            }
            let mut t2 = String::new();
            if url.is_empty() {
                let was = session_time();
                (url, t2) = firefox_tab(&want);
                // Not in the session yet (Firefox writes it every 15 s): once more after its next write.
                if !want.is_empty() && t2 != want && !t2.starts_with(&format!("{} ", want)) {
                    for _ in 0..34 {
                        if session_time() != was {
                            break;
                        }
                        std::thread::sleep(Duration::from_millis(500));
                    }
                    (url, t2) = firefox_tab(&want);
                }
            }
            if title.is_empty() {
                title = t2;
            }
        } else {
            // Chrome, Edge and the like: the address bar of the window whose title is what plays (or the one in front).
            for u in &urls {
                let in_front = fg.as_ref().map_or(false, |f| u[1] == f.0 && u[2] == f.1);
                if url.is_empty() || (!title.is_empty() && u[2].starts_with(&title)) || in_front {
                    url = u[3].clone();
                    if title.is_empty() {
                        title = without_site(&u[2]);
                    }
                }
            }
            if !url.is_empty() && !url.contains("://") {
                url = format!("https://{}", url);
            }
        }
        if title.is_empty() {
            if let Some(f) = &fg {
                title = f.1.clone();
            }
        }
        let mut m = base(true);
        m.insert("url".into(), json!(url));
        m.insert("title".into(), json!(title));
        m.insert("app".into(), json!(if firefox { "Firefox".to_string() } else { app_name(&app) }));
        if let Some(b) = &best {
            m.insert("artist".into(), json!(b[5]));
            m.insert("playing".into(), json!(b[2] == "Playing"));
            m.insert("pos".into(), json!(b[3].trim().parse::<f64>().unwrap_or(0.0)));
            m.insert("media".into(), json!(b[4]));
        }
        Value::Object(m)
    }

    // ---- Firefox's session file

    fn profile_sessions() -> Vec<(PathBuf, SystemTime)> {
        let root = PathBuf::from(std::env::var("APPDATA").unwrap_or_default()).join("Mozilla").join("Firefox").join("Profiles");
        let mut v: Vec<(PathBuf, SystemTime)> = std::fs::read_dir(root)
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|d| {
                let f = d.path().join("sessionstore-backups").join("recovery.jsonlz4");
                f.metadata().ok().and_then(|m| m.modified().ok()).map(|t| (f, t))
            })
            .collect();
        v.sort_by(|a, b| b.1.cmp(&a.1));
        v
    }

    /// When Firefox last wrote its session (the newest profile's).
    fn session_time() -> Option<SystemTime> {
        profile_sessions().first().map(|f| f.1)
    }

    /// An LZ4 block (Firefox's .jsonlz4 after its 12-byte header) to its `size` bytes.
    fn lz4_block(src: &[u8], mut at: usize, size: usize) -> Option<Vec<u8>> {
        let mut dst: Vec<u8> = Vec::with_capacity(size);
        while at < src.len() {
            let tok = src[at] as usize;
            at += 1;
            let mut lit = tok >> 4;
            if lit == 15 {
                loop {
                    let b = *src.get(at)? as usize;
                    at += 1;
                    lit += b;
                    if b != 255 {
                        break;
                    }
                }
            }
            dst.extend_from_slice(src.get(at..at + lit)?);
            at += lit;
            if at >= src.len() {
                break;
            }
            let off = *src.get(at)? as usize | (*src.get(at + 1)? as usize) << 8;
            at += 2;
            let mut ml = tok & 15;
            if ml == 15 {
                loop {
                    let b = *src.get(at)? as usize;
                    at += 1;
                    ml += b;
                    if b != 255 {
                        break;
                    }
                }
            }
            ml += 4;
            if off == 0 || off > dst.len() {
                return None;
            }
            for _ in 0..ml {
                dst.push(dst[dst.len() - off]);
            }
        }
        Some(dst)
    }

    fn session(path: &PathBuf) -> Option<Value> {
        let raw = std::fs::read(path).ok()?;
        if raw.len() < 12 || &raw[..8] != b"mozLz40\0" {
            return None;
        }
        let size = u32::from_le_bytes(raw[8..12].try_into().ok()?) as usize;
        serde_json::from_slice(&lz4_block(&raw, 12, size)?).ok()
    }

    /// The tab's page: its address and title, from the entry it is showing.
    fn tab_entry(tab: &Value) -> Option<(String, String)> {
        let es = tab["entries"].as_array().filter(|e| !e.is_empty())?;
        let idx = tab["index"].as_i64().map(|i| i - 1).unwrap_or(es.len() as i64 - 1).clamp(0, es.len() as i64 - 1) as usize;
        Some((es[idx]["url"].as_str().unwrap_or("").to_string(), es[idx]["title"].as_str().unwrap_or("").to_string()))
    }

    /// The address of the Firefox tab titled `title` (or the tab in front of the window used last),
    /// from its session file across every profile, the most recent first.
    fn firefox_tab(title: &str) -> (String, String) {
        for (f, _) in profile_sessions() {
            let Some(doc) = session(&f) else { continue };
            let Some(windows) = doc["windows"].as_array() else { continue };
            // The window used last first ("selectedWindow" counts from 1).
            let sw = doc["selectedWindow"].as_i64().map(|i| i - 1).unwrap_or(0);
            let (mut first, mut named): (Option<(String, String)>, Option<(String, String)>) = (None, None);
            for wi in -1..windows.len() as i64 {
                let w = if wi < 0 { sw } else { wi };
                if w < 0 || w >= windows.len() as i64 || (wi >= 0 && wi == sw) {
                    continue;
                }
                let win = &windows[w as usize];
                let Some(tabs) = win["tabs"].as_array() else { continue };
                let sel = win["selected"].as_i64().map(|i| i - 1).unwrap_or(0);
                for (ti, tab) in tabs.iter().enumerate() {
                    let Some((u, t)) = tab_entry(tab) else { continue };
                    if !title.is_empty() && t == title {
                        return (u, t);
                    }
                    // The page's title is the media's with the site after it ("... - YouTube").
                    if !title.is_empty() && named.is_none() && [" - ", " \u{2014} ", " | "].iter().any(|s| t.starts_with(&format!("{}{}", title, s))) {
                        named = Some((u.clone(), t.clone()));
                    }
                    if first.is_none() && ti as i64 == sel && u.starts_with("http") {
                        first = Some((u, t));
                    }
                }
            }
            // Nothing by its name: the tab in front of the window used last.
            if let Some(n) = named.or(first) {
                return n;
            }
        }
        (String::new(), String::new())
    }

    // ---- the page already open

    unsafe extern "system" fn title_of(h: HWND, lp: LPARAM) -> i32 {
        let mut buf = [0u16; 128];
        let n = GetWindowTextW(h, buf.as_mut_ptr(), 128).max(0) as usize;
        if String::from_utf16_lossy(&buf[..n]).starts_with("Localhost 8787") {
            *(lp as *mut bool) = true;
            return 0;
        }
        1
    }

    /// Whether a browser here already has the page open: a window titled with it (the tab in
    /// front), or a Firefox tab at it (its session file, when written lately).
    pub fn page_open(port: u16) -> bool {
        let mut found = false;
        unsafe { EnumWindows(Some(title_of), &mut found as *mut bool as LPARAM) };
        if found {
            return true;
        }
        let want = [format!("http://localhost:{}/", port), format!("http://127.0.0.1:{}/", port), format!("http://[::1]:{}/", port)];
        for (f, t) in profile_sessions() {
            // One left from long ago says nothing about now.
            if t.elapsed().map_or(true, |e| e > Duration::from_secs(600)) {
                continue;
            }
            let Some(doc) = session(&f) else { continue };
            for win in doc["windows"].as_array().into_iter().flatten() {
                for tab in win["tabs"].as_array().into_iter().flatten() {
                    if let Some((u, _)) = tab_entry(tab) {
                        if want.iter().any(|w| u.starts_with(w)) {
                            return true;
                        }
                    }
                }
            }
        }
        false
    }

    // ---- Ctrl+Alt+P

    /// Ctrl+Alt+P anywhere on this computer: handoff to the phone. Its own thread with a message loop.
    pub fn hotkey_loop() {
        unsafe {
            // MOD_ALT 1 | MOD_CONTROL 2 | MOD_NOREPEAT 0x4000, 'P'.
            if RegisterHotKey(std::ptr::null_mut(), 1, 0x4003, 0x50) == 0 {
                log("Ctrl+Alt+P is taken by another program: handoff from the phone still works.");
                return;
            }
            let mut m: MSG = std::mem::zeroed();
            while GetMessageW(&mut m, std::ptr::null_mut(), 0, 0) > 0 {
                if m.message == WM_HOTKEY {
                    std::thread::spawn(super::push);
                }
            }
        }
    }
}

#[cfg(target_os = "macos")]
fn now(pause: bool) -> Value {
    use std::process::Command;
    let _ = pause;
    let mut m = base(true);
    for (app, script) in [
        ("Safari", "tell application \"Safari\" to return (URL of front document) & \"\t\" & (name of front document)"),
        ("Google Chrome", "tell application \"Google Chrome\" to return (URL of active tab of front window) & \"\t\" & (title of active tab of front window)"),
    ] {
        if !Command::new("pgrep").args(["-x", app]).output().map_or(false, |o| o.status.success()) {
            continue;
        }
        let Ok(o) = Command::new("osascript").args(["-e", script]).output() else { continue };
        let r = String::from_utf8_lossy(&o.stdout).trim().to_string();
        if let Some((u, t)) = r.split_once('\t') {
            m.insert("url".into(), json!(u));
            m.insert("title".into(), json!(t));
            m.insert("app".into(), json!(app));
            break;
        }
    }
    Value::Object(m)
}

#[cfg(all(unix, not(target_os = "macos")))]
fn now(pause: bool) -> Value {
    use std::process::Command;
    let mut m = base(true);
    let Ok(o) = Command::new("playerctl").args(["-a", "metadata", "--format", "{{playerName}}\t{{status}}\t{{position}}\t{{xesam:url}}\t{{title}}\t{{artist}}"]).output() else {
        return Value::Object(m);
    };
    let mut best: Option<(i32, Vec<String>)> = None;
    for line in String::from_utf8_lossy(&o.stdout).lines() {
        let f: Vec<String> = line.split('\t').map(|s| s.to_string()).collect();
        if f.len() < 6 {
            continue;
        }
        let rank = (if f[1] == "Playing" { 2 } else { 0 }) + (if f[3].starts_with("http") { 1 } else { 0 });
        if best.as_ref().map_or(true, |b| rank > b.0) {
            best = Some((rank, f));
        }
    }
    if let Some((_, f)) = best {
        m.insert("url".into(), json!(if f[3].starts_with("http") { f[3].clone() } else { String::new() }));
        m.insert("title".into(), json!(f[4]));
        m.insert("app".into(), json!(f[0]));
        m.insert("artist".into(), json!(f[5]));
        m.insert("playing".into(), json!(f[1] == "Playing"));
        m.insert("media".into(), json!(f[4]));
        if let Ok(us) = f[2].trim().parse::<f64>() {
            m.insert("pos".into(), json!((us / 1e5).round() / 10.0));
        }
        if pause && f[1] == "Playing" {
            let _ = Command::new("playerctl").args(["-p", &f[0], "pause"]).status();
        }
    }
    Value::Object(m)
}
