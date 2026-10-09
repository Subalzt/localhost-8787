//! HTTPS, for the two places this helper has to talk to the internet and not to the phone: the
//! phone's website zone (dynv6) and the public message board (ntfy.sh), where the two ends swap
//! their addresses before punching through IPv4 NATs. The helper carries no TLS of its own: it runs
//! the system's `curl` (Windows 10 and later, macOS and Linux all have one), which also follows the
//! proxy the network asks for (a lab's), from the environment or Windows' own settings.

use std::io::{self, BufRead, BufReader, Write};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::Duration;

pub struct Reply {
    pub status: u16,
    pub body: String,
}

fn curl() -> Command {
    let mut c = Command::new(if cfg!(windows) { "curl.exe" } else { "curl" });
    c.args(["-sS", "--connect-timeout", "10"]);
    if let Some(p) = proxy() {
        c.args(["--proxy", &p]);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000);
    }
    c
}

fn url_of(p: &str) -> String {
    if p.contains("://") { p.to_string() } else { format!("http://{}", p) }
}

/// The proxy to use for HTTPS: the environment's, else (Windows) the one in Internet Settings.
fn proxy() -> Option<String> {
    for k in ["HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"] {
        if let Ok(v) = std::env::var(k) {
            if !v.trim().is_empty() {
                return Some(url_of(v.trim()));
            }
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let q = |name: &str| -> Option<String> {
            let o = Command::new("reg")
                .args(["query", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings", "/v", name])
                .creation_flags(0x0800_0000)
                .output()
                .ok()?;
            let t = String::from_utf8_lossy(&o.stdout).into_owned();
            let line = t.lines().find(|l| l.trim_start().starts_with(name))?;
            line.split_whitespace().last().map(|s| s.to_string())
        };
        if q("ProxyEnable").as_deref() == Some("0x1") {
            let server = q("ProxyServer")?;
            let pick = if server.contains('=') {
                server.split(';').find_map(|p| p.strip_prefix("https=")).or_else(|| server.split(';').find_map(|p| p.strip_prefix("http=")))?.to_string()
            } else {
                server
            };
            return Some(url_of(&pick));
        }
    }
    None
}

/// One request. `headers` are "Name: value" pairs; `body` is sent as given.
pub fn request(method: &str, url: &str, headers: &[(&str, String)], body: Option<&str>, timeout: Duration) -> io::Result<Reply> {
    let mut c = curl();
    c.args(["--max-time", &timeout.as_secs().max(1).to_string(), "-X", method, "-w", "\n%{http_code}"]);
    for (k, v) in headers {
        c.args(["-H", &format!("{}: {}", k, v)]);
    }
    if body.is_some() {
        c.args(["--data-binary", "@-"]);
    }
    c.arg(url).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = c.spawn().map_err(|e| io::Error::new(e.kind(), format!("curl is needed to reach across IPv4 and could not be run ({})", e)))?;
    if let Some(mut si) = child.stdin.take() {
        if let Some(b) = body {
            let _ = si.write_all(b.as_bytes());
        }
    }
    let out = child.wait_with_output()?;
    if !out.status.success() {
        let why = String::from_utf8_lossy(&out.stderr).trim().to_string();
        return Err(io::Error::other(if why.is_empty() { "curl failed".to_string() } else { why }));
    }
    let text = String::from_utf8_lossy(&out.stdout).into_owned();
    let (body, code) = text.rsplit_once('\n').unwrap_or(("", &text));
    Ok(Reply { status: code.trim().parse().unwrap_or(0), body: body.to_string() })
}

/// A streaming GET (the board's /json), read line by line: lines arrive on the channel, which
/// closes when the stream ends. Dropping the Stream stops it.
pub struct Lines {
    child: Child,
    pub rx: mpsc::Receiver<String>,
}

impl Drop for Lines {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

pub fn stream(url: &str, timeout: Duration) -> io::Result<Lines> {
    let mut c = curl();
    c.args(["-N", "--max-time", &timeout.as_secs().max(1).to_string(), url]).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
    let mut child = c.spawn().map_err(|e| io::Error::new(e.kind(), format!("curl is needed to reach across IPv4 and could not be run ({})", e)))?;
    let out = child.stdout.take().ok_or_else(|| io::Error::other("no output"))?;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        for l in BufReader::new(out).lines() {
            match l {
                Ok(l) => if tx.send(l).is_err() { break },
                Err(_) => break,
            }
        }
    });
    Ok(Lines { child, rx })
}

/// A file fetched to `dest` (redirects followed, as GitHub's downloads need).
pub fn download(url: &str, dest: &std::path::Path, timeout: Duration) -> io::Result<()> {
    let mut c = curl();
    c.args(["-L", "--fail", "--max-time", &timeout.as_secs().max(1).to_string(), "-o"]).arg(dest).arg(url);
    let o = c.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped()).output().map_err(|e| io::Error::new(e.kind(), format!("curl could not be run ({})", e)))?;
    if o.status.success() {
        Ok(())
    } else {
        Err(io::Error::other(String::from_utf8_lossy(&o.stderr).trim().to_string()))
    }
}
