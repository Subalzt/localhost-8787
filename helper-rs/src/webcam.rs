//! The phone as this computer's webcam. "Localhost 8787 Phone Camera" (tools/vcam: a Windows 11
//! virtual camera, its media source run by Windows' Frame Server) reads each picture from shared
//! memory it makes. While an app has the camera open it says so there; this helper then turns the
//! phone's front camera on, decodes its stream with ffmpeg into NV12 1280x720 (turned upright,
//! filling the frame) and leaves each picture there. Closed by every app for a few seconds, the
//! phone's camera goes back as it was. The phone's microphone comes too where VB-CABLE is
//! installed (pick "CABLE Output" as the microphone). Windows only.

#[cfg(windows)]
pub use win::{install, webcam_loop};

#[cfg(not(windows))]
pub fn webcam_loop() {}

#[cfg(not(windows))]
pub fn install() {}

#[cfg(windows)]
mod win {
    use crate::phone;
    use crate::util::{find_ffmpeg, log};
    use serde_json::{json, Value};
    use std::io::{Read, Write};
    use std::os::windows::process::CommandExt;
    use std::process::{Child, Command, Stdio};
    use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread::{sleep, spawn};
    use std::time::{Duration, Instant};
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::Media::Audio::*;
    use windows_sys::Win32::System::Memory::{MapViewOfFile, OpenFileMappingW};
    use windows_sys::Win32::System::SystemInformation::GetTickCount64;

    const CAM_W: usize = 1280;
    const CAM_H: usize = 720;
    const CAM_HEADER: usize = 64;
    const CAM_FRAME: usize = CAM_W * CAM_H * 3 / 2;

    /// The shared memory, as the camera's media source lays it out.
    struct View(*mut u8);
    unsafe impl Send for View {}
    unsafe impl Sync for View {}

    impl View {
        fn i32_at(&self, off: usize) -> i32 {
            unsafe { std::ptr::read_volatile(self.0.add(off) as *const i32) }
        }
        fn i64_at(&self, off: usize) -> i64 {
            unsafe { std::ptr::read_volatile(self.0.add(off) as *const i64) }
        }
        fn set_i32(&self, off: usize, v: i32) {
            unsafe { std::ptr::write_volatile(self.0.add(off) as *mut i32, v) }
        }
        fn set_i64(&self, off: usize, v: i64) {
            unsafe { std::ptr::write_volatile(self.0.add(off) as *mut i64, v) }
        }
        /// Each NV12 picture into the camera's memory, its count odd while it is written.
        fn put(&self, pic: &[u8]) {
            let seq = self.i32_at(12);
            self.set_i32(12, seq | 1);
            unsafe { std::ptr::copy_nonoverlapping(pic.as_ptr(), self.0.add(CAM_HEADER), pic.len()) };
            self.set_i64(20, unsafe { GetTickCount64() } as i64);
            self.set_i32(12, (seq | 1) + 1);
        }
    }

    fn open_view() -> Option<View> {
        // Made by the camera's media source the first time an app opens it.
        let name: Vec<u16> = "Global\\Localhost8787Camera\0".encode_utf16().collect();
        unsafe {
            let map = OpenFileMappingW(0x0002 | 0x0004, 0, name.as_ptr());
            if map.is_null() {
                return None;
            }
            let v = MapViewOfFile(map, 0x0002 | 0x0004, 0, 0, 0);
            if v.Value.is_null() {
                CloseHandle(map);
                return None;
            }
            Some(View(v.Value as *mut u8))
        }
    }

    struct Cam {
        stop: Arc<AtomicBool>,
        children: Arc<Mutex<Vec<Child>>>,
    }

    impl Cam {
        fn alive(&self) -> bool {
            !self.stop.load(Ordering::Relaxed)
        }
        fn kill(&self) {
            self.stop.store(true, Ordering::Relaxed);
            for c in self.children.lock().unwrap().iter_mut() {
                let _ = c.kill();
            }
        }
    }

    fn phone_get(path: &str) -> String {
        phone::request("GET", path, None, &[], 10_000).map(|r| r.text()).unwrap_or_default()
    }

    fn phone_set(v: &Value) {
        let _ = phone::post_json("/api/cams/set", &v.to_string());
    }

    /// Camera mode on the phone, front lens, no motion watch or clips while it is a webcam; what to
    /// put back after.
    fn phone_on(cable: bool) -> Value {
        let st: Value = serde_json::from_str(&phone_get("/api/cams/state")).unwrap_or_default();
        let back = json!({
            "on": st["on"] == true,
            "lens": st["lens"].as_str().filter(|l| !l.is_empty()).unwrap_or("back"),
            "motion": st["motion"] != false,
            "record": st["record"] != false,
            "sound": st["sound"] != false,
        });
        // The microphone on too, for the laptop's "CABLE Input" (VB-CABLE) when it has one.
        let mut on = json!({"on": true, "lens": "front", "motion": false, "record": false});
        if cable {
            on["sound"] = json!(true);
        }
        phone_set(&on);
        back
    }

    fn hidden(mut c: Command) -> Command {
        c.creation_flags(0x0800_0000);
        c
    }

    /// The phone camera's stream ("x-l87-frames": each picture with its length and time, sound
    /// frames marked by the top bit) into ffmpeg, and its pictures into the shared memory.
    fn start(view: Arc<View>) -> Option<Cam> {
        let Some(ff) = find_ffmpeg() else {
            log("Webcam: needs ffmpeg.");
            sleep(Duration::from_secs(5));
            return None;
        };
        // Upright: how far the phone says to turn its picture (once the camera runs on the new lens).
        let mut rot = 0;
        for _ in 0..20 {
            let st: Value = serde_json::from_str(&phone_get("/api/cams/state")).unwrap_or_default();
            if st["running"] == true && st["lens"] == "front" {
                rot = st["rotation"].as_i64().unwrap_or(0);
                break;
            }
            sleep(Duration::from_millis(500));
        }
        let turn = match rot { 90 => "transpose=1,", 180 => "transpose=1,transpose=1,", 270 => "transpose=2,", _ => "" };
        let vf = format!("{}scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},format=nv12", turn, w = CAM_W, h = CAM_H);
        let mut video = hidden(Command::new(&ff))
            .args(["-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0", "-threads", "1", "-f", "h264", "-i", "pipe:0", "-vf", &vf, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "nv12", "pipe:1"])
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
        let (mut v_in, mut v_out) = (video.stdin.take()?, video.stdout.take()?);
        let vid = format!("webcam-{:x}", crate::util::now_ms() & 0xfffffff);
        // The phone's microphone too, into VB-CABLE, where there is one.
        let cable = cable_device();
        let mut audio: Option<(Child, std::process::ChildStdin)> = None;
        let stop = Arc::new(AtomicBool::new(false));
        let children = Arc::new(Mutex::new(Vec::new()));
        if let Some(dev) = cable {
            if let Ok(mut a) = hidden(Command::new(&ff))
                .args(["-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-f", "aac", "-i", "pipe:0", "-f", "s16le", "-ar", "48000", "-ac", "1", "pipe:1"])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn()
            {
                if let (Some(i), Some(o)) = (a.stdin.take(), a.stdout.take()) {
                    let stop = stop.clone();
                    spawn(move || cable_play(dev, o, stop));
                    audio = Some((a, i));
                }
            }
        }
        let host = phone::phone()?;
        let heads = vec![("Cookie", phone::session()?)];
        let path = format!("/api/cams/stream?v={}{}", vid, if audio.is_some() { "&listen=1" } else { "" });
        let st = crate::http::open(&host, phone::port(), "GET", &path, &heads, &[], Duration::from_secs(15), Some(Duration::from_secs(15)));
        let mut st = match st {
            Ok(s) if s.status == 200 => s,
            Ok(s) => {
                log(&format!("Webcam: the phone answered {}", s.status));
                let _ = video.kill();
                if let Some((mut a, _)) = audio { let _ = a.kill(); }
                return None;
            }
            Err(e) => {
                log(&format!("Webcam: {}", e));
                let _ = video.kill();
                if let Some((mut a, _)) = audio { let _ = a.kill(); }
                return None;
            }
        };
        phone::track(&st.socket);
        let seen = Arc::new(AtomicI64::new(0));
        {
            let mut ch = children.lock().unwrap();
            ch.push(video);
        }
        let audio_in = audio.map(|(a, i)| {
            children.lock().unwrap().push(a);
            i
        });
        // In: the frames, unwrapped, into ffmpeg.
        {
            let (stop, seen) = (stop.clone(), seen.clone());
            let mut audio_in = audio_in;
            spawn(move || {
                let mut head = [0u8; 8];
                let mut body = vec![0u8; 1 << 20];
                while !stop.load(Ordering::Relaxed) {
                    if st.body.read_exact(&mut head).is_err() {
                        break;
                    }
                    let len = u32::from_be_bytes([head[0], head[1], head[2], head[3]]);
                    let sound = len & 0x8000_0000 != 0;
                    let n = (len & 0x7fff_ffff) as usize;
                    if n > body.len() {
                        body.resize(n, 0);
                    }
                    if st.body.read_exact(&mut body[..n]).is_err() {
                        break;
                    }
                    seen.fetch_add(8 + n as i64, Ordering::Relaxed);
                    let ok = if sound {
                        match audio_in.as_mut() {
                            Some(a) => { let _ = a.write_all(&body[..n]).and_then(|_| a.flush()); true }
                            None => true,
                        }
                    } else {
                        v_in.write_all(&body[..n]).and_then(|_| v_in.flush()).is_ok()
                    };
                    if !ok {
                        break;
                    }
                }
                stop.store(true, Ordering::Relaxed);
            });
        }
        // What has arrived, told back, so the camera paces itself.
        {
            let (stop, seen, vid) = (stop.clone(), seen.clone(), vid.clone());
            spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    let _ = phone::post_json(&format!("/api/cams/ack?v={}&n={}", vid, seen.load(Ordering::Relaxed)), "{}");
                    sleep(Duration::from_millis(300));
                }
            });
        }
        // Out: each NV12 picture into the camera's shared memory.
        {
            let stop = stop.clone();
            spawn(move || {
                let mut pic = vec![0u8; CAM_FRAME];
                while v_out.read_exact(&mut pic).is_ok() {
                    view.put(&pic);
                }
                stop.store(true, Ordering::Relaxed);
            });
        }
        Some(Cam { stop, children })
    }

    pub fn has_cable() -> bool {
        cable_device().is_some()
    }

    pub fn webcam_loop() {
        let mut view: Option<Arc<View>> = None;
        let mut cam: Option<Cam> = None;
        let mut restore: Option<Value> = None;
        let mut quiet_since: Option<Instant> = None;
        loop {
            if view.is_none() {
                match open_view() {
                    Some(v) => view = Some(Arc::new(v)),
                    None => {
                        sleep(Duration::from_secs(2));
                        continue;
                    }
                }
            }
            let v = view.clone().unwrap();
            // Wanted, and asked for a picture in the last 3 s: a flag left set by an app that died
            // (or a service that kept the camera) must never keep the phone's camera on.
            let asked = v.i64_at(28);
            let wanted = v.i32_at(16) == 1 && asked > 0 && (unsafe { GetTickCount64() } as i64) - asked < 3000;
            let running = cam.as_ref().map_or(false, |c| c.alive());
            if wanted && !running && phone::phone().is_some() && phone::session().is_some() {
                if let Some(c) = cam.take() {
                    c.kill();
                }
                if restore.is_none() {
                    restore = Some(phone_on(cable_device().is_some()));
                }
                cam = start(v.clone());
                if cam.is_some() {
                    log("Phone camera on as this computer's webcam.");
                }
            }
            if wanted {
                quiet_since = None;
            } else if running || restore.is_some() {
                match quiet_since {
                    None => quiet_since = Some(Instant::now()),
                    Some(t) if t.elapsed() > Duration::from_secs(4) => {
                        if let Some(c) = cam.take() {
                            c.kill();
                        }
                        if let Some(r) = restore.take() {
                            phone_set(&r);
                        }
                        quiet_since = None;
                        log("Phone camera off: no app uses the webcam.");
                    }
                    _ => {}
                }
            }
            sleep(Duration::from_millis(500));
        }
    }

    // ---- the phone's microphone into VB-CABLE: 48 kHz mono 16-bit through Windows' waveOut

    /// VB-CABLE's playback side ("CABLE Input"), or None when it is not installed.
    fn cable_device() -> Option<u32> {
        unsafe {
            for i in 0..waveOutGetNumDevs() {
                let mut caps: WAVEOUTCAPSW = std::mem::zeroed();
                if waveOutGetDevCapsW(i as usize, &mut caps, std::mem::size_of::<WAVEOUTCAPSW>() as u32) == 0 {
                    let name: [u16; 32] = caps.szPname; // a copy: the struct is packed
                    let n = name.iter().position(|c| *c == 0).unwrap_or(name.len());
                    if String::from_utf16_lossy(&name[..n]).starts_with("CABLE Input") {
                        return Some(i);
                    }
                }
            }
        }
        None
    }

    /// ffmpeg's PCM into the cable, 20 ms at a time, eight pieces queued at most (160 ms behind, no more).
    fn cable_play(dev: u32, mut src: impl Read, stop: Arc<AtomicBool>) {
        const PIECE: usize = 48000 * 2 / 50;
        const PIECES: usize = 8;
        unsafe {
            let fmt = WAVEFORMATEX { wFormatTag: 1, nChannels: 1, nSamplesPerSec: 48000, nAvgBytesPerSec: 96000, nBlockAlign: 2, wBitsPerSample: 16, cbSize: 0 };
            let mut h: HWAVEOUT = std::mem::zeroed();
            if waveOutOpen(&mut h, dev, &fmt, 0, 0, 0) != 0 {
                log("Webcam: could not open CABLE Input.");
                return;
            }
            let hsz = std::mem::size_of::<WAVEHDR>() as u32;
            let mut bufs: Vec<Vec<u8>> = (0..PIECES).map(|_| vec![0u8; PIECE]).collect();
            let mut hdrs: Vec<Box<WAVEHDR>> = Vec::new();
            for b in bufs.iter_mut() {
                let mut w: WAVEHDR = std::mem::zeroed();
                w.lpData = b.as_mut_ptr();
                w.dwBufferLength = PIECE as u32;
                w.dwFlags = 1; // WHDR_DONE: free to fill
                hdrs.push(Box::new(w));
            }
            let mut pcm = vec![0u8; PIECE];
            let mut k = 0;
            while !stop.load(Ordering::Relaxed) && src.read_exact(&mut pcm).is_ok() {
                let hp: *mut WAVEHDR = &mut *hdrs[k];
                // Every piece queued: too far behind, so this one is let go rather than lag.
                if (*hp).dwFlags & 1 == 0 {
                    continue;
                }
                if (*hp).dwFlags & 2 != 0 {
                    waveOutUnprepareHeader(h, hp, hsz);
                }
                std::ptr::copy_nonoverlapping(pcm.as_ptr(), (*hp).lpData, PIECE);
                (*hp).dwFlags = 0;
                (*hp).dwBufferLength = PIECE as u32;
                waveOutPrepareHeader(h, hp, hsz);
                waveOutWrite(h, hp, hsz);
                k = (k + 1) % PIECES;
            }
            waveOutReset(h);
            for hd in hdrs.iter_mut() {
                let hp: *mut WAVEHDR = &mut **hd;
                waveOutUnprepareHeader(h, hp, hsz);
            }
            waveOutClose(h);
        }
    }

    // ---- adding the camera to this computer, once

    /// Adds the camera, once: vcam.dll from the phone, copied where Windows' camera service may
    /// load it (Program Files) and registered (one administrator prompt), then the camera itself
    /// made for this user; run again, it puts a newer copy in. Called from the phone's Devices tab.
    pub fn install() {
        let dir = std::path::PathBuf::from(std::env::var("ProgramFiles").unwrap_or_else(|_| "C:\\Program Files".into())).join("Localhost 8787");
        let tmp = std::env::temp_dir().join("vcam.dll");
        let Some(host) = phone::phone() else { return };
        let heads = vec![("Cookie", phone::session().unwrap_or_default())];
        let got = crate::http::request(&host, phone::port(), "GET", "/api/laptop/vcam.dll", &heads, &[], Duration::from_secs(60));
        match got {
            Ok(r) if r.status == 200 && std::fs::write(&tmp, &r.body).is_ok() => {}
            Ok(r) => {
                log(&format!("Could not add the camera: the phone answered {}", r.status));
                return;
            }
            Err(e) => {
                log(&format!("Could not add the camera: {}", e));
                return;
            }
        }
        let dll = dir.join("vcam.dll");
        // One prompt: copy it into Program Files and register it for the Frame Server. Windows'
        // camera service keeps an older copy loaded: stopped first (it starts again when an app
        // wants a camera).
        let q = |p: &std::path::Path| p.to_string_lossy().replace('\'', "''");
        let inner = format!(
            "Stop-Service FrameServer, FrameServerMonitor -Force -ErrorAction SilentlyContinue; Start-Sleep 2; \
             New-Item -ItemType Directory -Force '{d}' | Out-Null; Copy-Item -Force '{t}' '{l}'; \
             Start-Process regsvr32 -ArgumentList '/s', ('\"' + '{l}' + '\"') -Wait",
            d = q(&dir), t = q(&tmp), l = q(&dll)
        );
        let enc = {
            use base64::Engine;
            base64::engine::general_purpose::STANDARD.encode(inner.encode_utf16().flat_map(|u| u.to_le_bytes()).collect::<Vec<u8>>())
        };
        let outer = format!("Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','{}'", enc);
        let _ = hidden(Command::new("powershell.exe")).args(["-NoProfile", "-NonInteractive", "-Command", &outer]).status();
        let add = hidden(Command::new("rundll32.exe")).arg(format!("{},Install", dll.display())).status();
        match add {
            Ok(s) if s.success() => log("Phone Camera added: pick \"Localhost 8787 Phone Camera\" in any app."),
            Ok(s) => log(&format!("Could not add the camera (0x{:08x}).", s.code().unwrap_or(-1) as u32)),
            Err(e) => log(&format!("Could not add the camera: {}", e)),
        }
    }
}

/// `--webcam-check`: what the phone-as-webcam needs, and what this computer has of it.
pub fn check() {
    println!("ffmpeg: {}", crate::util::find_ffmpeg().map(|p| p.display().to_string()).unwrap_or_else(|| "not found".into()));
    #[cfg(windows)]
    {
        println!("VB-CABLE (microphone): {}", if win::has_cable() { "installed" } else { "not installed" });
        println!("camera driver: {}", if std::path::Path::new(r"C:\Program Files\Localhost 8787\vcam.dll").exists() { "installed" } else { "not installed (Devices > Phone as webcam > Add)" });
    }
}
