//! New songs' lyrics, word by word. With the lyrics aligner set up on this computer
//! (tools/lyrics-align: it writes where it is to lyrics-align.txt here whenever it runs), songs added
//! to the phone are checked against their singing and timed word by word by themselves: every 10
//! minutes the phone's list is looked at, and the songs not seen before go to verify.py, at low
//! priority, on the graphics card. The first look only learns the list, as the songs already there
//! were done by hand.

use crate::phone;
use crate::util::{conf_dir, log, read_conf, say};
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::thread::sleep;
use std::time::Duration;

pub fn lyrics_loop() {
    sleep(Duration::from_secs(120));
    let mut said = String::new();
    loop {
        match once() {
            Ok(()) => said.clear(),
            Err(e) => {
                // Said once, not every 10 minutes while the phone is away.
                if said != e {
                    log(&format!("New songs' lyrics: {} (tried again every 10 minutes)", e));
                }
                said = e;
            }
        }
        sleep(Duration::from_secs(600));
    }
}

/// Every `"id":N` in the phone's list, in order.
fn ids_of(body: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = body;
    while let Some(i) = rest.find("\"id\":") {
        rest = &rest[i + 5..];
        let n: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if !n.is_empty() {
            out.push(n);
        }
    }
    out
}

fn once() -> Result<(), String> {
    let Some(folder) = read_conf("lyrics-align.txt") else { return Ok(()) };
    let (Some(host), Some(_)) = (phone::phone(), phone::session()) else { return Ok(()) };
    let folder = std::path::PathBuf::from(folder);
    let py = if cfg!(windows) { folder.join("venv/Scripts/python.exe") } else { folder.join("venv/bin/python") };
    let script = folder.join("verify.py");
    if !py.is_file() || !script.is_file() {
        return Ok(());
    }
    let r = phone::request("GET", "/api/music", None, &[], 30_000).map_err(|e| e.to_string())?;
    if r.status != 200 {
        return Ok(());
    }
    let ids = ids_of(&r.text());
    if ids.is_empty() {
        return Ok(());
    }
    let seen_file = conf_dir().join("lyrics-seen.txt");
    if !seen_file.exists() {
        std::fs::write(&seen_file, ids.join("\n")).map_err(|e| e.to_string())?;
        return Ok(());
    }
    let mut seen: Vec<String> = std::fs::read_to_string(&seen_file).unwrap_or_default().lines().map(|l| l.trim().to_string()).collect();
    let fresh: Vec<String> = ids.into_iter().filter(|i| !seen.contains(i)).collect();
    if fresh.is_empty() {
        return Ok(());
    }
    say(&format!(
        "Checking the lyrics of {} on the phone against the singing, and timing them word by word, on this computer's graphics card.",
        if fresh.len() == 1 { "1 new song".to_string() } else { format!("{} new songs", fresh.len()) }
    ));
    let mut c = Command::new(&py);
    c.args(["-W", "ignore", "-u"]).arg(&script).args(["--phone", &format!("{}:{}", host, phone::port()), "--ids", &fresh.join(","), "--fix", "--words"]);
    c.current_dir(&folder).env("PYTHONIOENCODING", "utf-8").stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000 | 0x0000_4000); // no window, below normal priority
    }
    let mut child = c.spawn().map_err(|e| e.to_string())?;
    let (mut fixed, mut timed) = (0, 0);
    if let Some(out) = child.stdout.take() {
        for line in BufReader::new(out).lines().map_while(Result::ok) {
            if line.starts_with('[') || line.contains("given the phone") || line.contains("by the word") {
                log(&format!("Lyrics: {}", line.trim()));
            }
            if line.contains("given the phone") {
                fixed += 1;
            }
            if line.contains("lines by the word") && !line.trim().starts_with("0/") {
                timed += 1;
            }
        }
    }
    let _ = child.wait();
    seen.extend(fresh.iter().cloned());
    let _ = std::fs::write(&seen_file, seen.join("\n"));
    say(&format!(
        "Checked the lyrics of {}: {}{}.",
        if fresh.len() == 1 { "1 new song".to_string() } else { format!("{} new songs", fresh.len()) },
        if timed > 0 { format!("{} timed word by word", timed) } else { "nothing to change".to_string() },
        if fixed > 0 { format!(", {} given better lyrics", fixed) } else { String::new() }
    ));
    Ok(())
}
