//! This computer's files on the phone: its usual folders (and drives) at the top, then any folder;
//! a file sent to the phone, or one from the phone saved here. Answered on the page's port, the way
//! the phone reaches everything here (nothing on this computer listens).

use crate::http::{self, quote};
use crate::phone;
use crate::util::{json_str, machine_name, say};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, UNIX_EPOCH};

fn count_items(p: &Path) -> i64 {
    match fs::read_dir(p) {
        Ok(it) => it.take(9999).count() as i64,
        Err(_) => -1,
    }
}

fn modified(p: &Path) -> u64 {
    fs::metadata(p).and_then(|m| m.modified()).ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn entry(name: &str, path: &str, dir: bool, size: u64, modified: u64, items: i64) -> String {
    format!("{{\"name\":{},\"path\":{},\"dir\":{},\"size\":{},\"modified\":{},\"items\":{}}}",
        json_str(name), json_str(path), dir, size, modified, items)
}

fn top() -> Vec<String> {
    let home = crate::util::home();
    let mut out = Vec::new();
    for name in ["Desktop", "Documents", "Downloads", "Pictures", "Music", "Videos", "Movies"] {
        let full = home.join(name);
        if full.is_dir() {
            out.push(entry(name, &full.to_string_lossy(), true, 0, modified(&full), count_items(&full)));
        }
    }
    out.push(entry("Home", &home.to_string_lossy(), true, 0, 0, count_items(&home)));
    if cfg!(windows) {
        // Each drive, with how big it is.
        for l in b'A'..=b'Z' {
            let root = format!("{}:\\", l as char);
            if Path::new(&root).is_dir() {
                out.push(entry(&format!("{}:", l as char), &root, true, 0, 0, count_items(Path::new(&root))));
            }
        }
    } else {
        out.push(entry("This computer", "/", true, 0, 0, count_items(Path::new("/"))));
    }
    out
}

pub fn list(rid: &str, path: &str) {
    let mut entries = Vec::new();
    let mut error = String::new();
    if path.is_empty() {
        entries = top();
    } else {
        match fs::read_dir(path) {
            Ok(it) => {
                let mut items: Vec<_> = it.flatten().collect();
                items.sort_by_key(|e| (!e.file_type().map(|t| t.is_dir()).unwrap_or(false), e.file_name().to_string_lossy().to_lowercase()));
                for e in items {
                    let name = e.file_name().to_string_lossy().into_owned();
                    if name.starts_with('.') { continue; }
                    let Ok(md) = fs::symlink_metadata(e.path()) else { continue };
                    let dir = md.is_dir();
                    let m = md.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as u64).unwrap_or(0);
                    entries.push(entry(&name, &e.path().to_string_lossy(), dir, if dir { 0 } else { md.len() }, m, if dir { count_items(&e.path()) } else { -1 }));
                    if entries.len() >= 3000 { break; }
                }
            }
            Err(e) => error = e.to_string(),
        }
    }
    let body = format!("{{\"path\":{},\"laptop\":{},\"error\":{},\"entries\":[{}]}}",
        json_str(path), json_str(&machine_name()), json_str(&error), entries.join(","));
    let _ = phone::post_json(&format!("/api/laptop/fs/answer?id={}", quote(rid)), &body);
}

/// A file here, to the phone (it keeps it with what it has received).
pub fn send(rid: &str, path: &str) {
    let p = Path::new(path);
    let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
    let q = format!("/api/laptop/fs/file?id={}&name={}", quote(rid), quote(&name));
    if p.is_dir() {
        let _ = phone::request("POST", &format!("{}&error={}", q, quote("Folders are not sent by this helper yet")), None, &[], 10_000);
        return;
    }
    let mut f = match fs::File::open(p) {
        Ok(f) => f,
        Err(e) => {
            let _ = phone::request("POST", &format!("{}&error={}", q, quote(&format!("The computer could not open it: {}", e))), None, &[], 10_000);
            return;
        }
    };
    let size = f.metadata().map(|m| m.len()).unwrap_or(0);
    let host = phone::phone().unwrap_or_default();
    let h = vec![("Cookie", phone::session().unwrap_or_default()), ("Content-Type", "application/octet-stream".to_string())];
    match http::post_from(&host, crate::phone::port(), &format!("{}&size={}", q, size), &h, size, &mut f, Duration::from_secs(20)) {
        Ok(_) => say(&format!("Sent {} to the phone.", name)),
        Err(e) => say(&format!("Could not send {} to the phone: {}", name, e)),
    }
}

pub fn downloads_dir() -> PathBuf {
    let d = crate::util::home().join("Downloads");
    let _ = fs::create_dir_all(&d);
    d
}

/// A file the phone sends into [folder] here: fetched from the phone, put beside one of the same
/// name rather than over it, kept only whole; the phone hears how it went.
pub fn receive(rid: &str, folder: &str, name: &str, size: i64, show: bool) {
    let done = format!("/api/laptop/fs/put-done?id={}", quote(rid));
    let result: Result<PathBuf, String> = (|| {
        let dir = Path::new(folder);
        if !dir.is_dir() { return Err("that folder is not there any more".into()); }
        let safe: String = name.chars().map(|c| if "/\\\0:*?\"<>|".contains(c) { '_' } else { c }).collect::<String>().trim().to_string();
        let safe = if safe.is_empty() { "file".to_string() } else { safe };
        let (stem, ext) = match safe.rfind('.') { Some(i) if i > 0 => (safe[..i].to_string(), safe[i..].to_string()), _ => (safe.clone(), String::new()) };
        let mut target = dir.join(&safe);
        let mut i = 1;
        while target.exists() { target = dir.join(format!("{} ({}){}", stem, i, ext)); i += 1; }
        let part = PathBuf::from(format!("{}.part", target.to_string_lossy()));
        let host = phone::phone().unwrap_or_default();
        let h = vec![("Cookie", phone::session().unwrap_or_default())];
        let mut st = http::open(&host, crate::phone::port(), "GET", &format!("/api/laptop/fs/out/{}", quote(rid)), &h, &[], Duration::from_secs(20), Some(Duration::from_secs(60)))
            .map_err(|e| e.to_string())?;
        if st.status != 200 { return Err(format!("the phone said {}", st.status)); }
        let copied = (|| -> std::io::Result<u64> { let mut f = fs::File::create(&part)?; std::io::copy(&mut st.body, &mut f) })();
        match copied {
            Ok(n) if size < 0 || n == size as u64 => {}
            Ok(_) => { let _ = fs::remove_file(&part); return Err("only part of it came".into()); }
            Err(e) => { let _ = fs::remove_file(&part); return Err(e.to_string()); }
        }
        fs::rename(&part, &target).map_err(|e| e.to_string())?;
        Ok(target)
    })();
    match result {
        Ok(t) => {
            let fname = t.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            say(&format!("Saved {}{} in {}.", fname, if show { ", dropped from another computer," } else { " from the phone" }, folder));
            if show { let _ = open::that(folder); }
            let _ = phone::request("POST", &format!("{}&name={}", done, quote(&fname)), None, &[], 10_000);
        }
        Err(e) => {
            say(&format!("Could not save {} from the phone: {}", name, e));
            let _ = phone::request("POST", &format!("{}&error={}", done, quote(&format!("The laptop could not save it: {}", e))), None, &[], 10_000);
        }
    }
}
