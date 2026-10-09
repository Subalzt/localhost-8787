//! This computer's screen on the phone: a second screen in the same room, and the main screen from
//! another network, with the computer's sound, and (for the phone's camera view) this computer's
//! webcam and microphone.
//!
//! The phone asks for it ("display start ..."); this captures a monitor with ffmpeg (on the GPU:
//! Desktop Duplication, and NVENC when there is an NVIDIA card) and streams it to the phone's page
//! port as H.264 pictures (POST /api/display/stream), so it goes the way everything else does, the
//! tunnel included. The monitor is the extra one a virtual-display driver adds, which makes the
//! phone a real second screen; with none, the computer's own screen is mirrored. Touches on the
//! phone come back as "da x y" (where on that monitor, as fractions) and ordinary clicks.
//!
//! The virtual display is the phone's: this puts it on the desktop (extended, right of the
//! computer's screens) when the phone asks and takes it off when the phone closes, so windows left
//! on it come back instead of sitting on a screen nobody can see. Windows only: Linux and macOS
//! keep using the Python helper for the second screen.

#![cfg(windows)]

use crate::displays::{self, Mon};
use crate::loopback::Loopback;
use crate::phone;
use crate::util::{conf_dir, find_ffmpeg, log, read_conf, say, write_conf};
use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::os::windows::process::CommandExt;
use std::path::Path;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU32, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{sleep, spawn};
use std::time::{Duration, Instant};

const MAX_KBIT: i32 = 10_000;

static GEN: AtomicU32 = AtomicU32::new(0);
static SECOND: Mutex<Option<Arc<Proc>>> = Mutex::new(None);
static SHOWN: Mutex<Option<Mon>> = Mutex::new(None);
static DESK: Mutex<Option<Mon>> = Mutex::new(None);
static RELAYOUT: AtomicBool = AtomicBool::new(false);
/// The virtual display this helper put on the desktop, to take off again when the phone closes
/// ("device", or "device|clone" when it was duplicating the screen).
static ATTACHED_HERE: Mutex<Option<String>> = Mutex::new(None);
/// The virtual display whose HDR this helper turned off, to turn back on.
static HDR_OFF_HERE: Mutex<Option<String>> = Mutex::new(None);
/// The virtual display's mode before this helper changed it, "device|w|h|hz", to put back.
static MODE_BEFORE: Mutex<Option<String>> = Mutex::new(None);
static PHONE_HZ: AtomicI32 = AtomicI32::new(60);
static PHONE_BLOCKS: AtomicI64 = AtomicI64::new(2_073_600);
static REMOTE_KBIT: AtomicI32 = AtomicI32::new(700);
static LOCAL_KBIT: AtomicI32 = AtomicI32::new(0);
static LOCAL_FOR: Mutex<Option<String>> = Mutex::new(None);
static FAILED_KBIT: AtomicI32 = AtomicI32::new(i32::MAX);
static FAILED_AT: Mutex<Option<Instant>> = Mutex::new(None);
static RATE_READ: AtomicBool = AtomicBool::new(false);
static CAPTURE_ERR: Mutex<String> = Mutex::new(String::new());
static ACK: Mutex<(String, i64)> = Mutex::new((String::new(), 0));
static RETRYING: AtomicBool = AtomicBool::new(false);
static RELEASE_LOCK: Mutex<()> = Mutex::new(());

/// Set with each "start": another computer's page is watching (`VIEW`, its id, sent back with the
/// stream), so this computer's own main screen goes (`MIRROR`, no virtual display), at the
/// internet's size when that page is far away (`FAR`), and without the sound.
static VIEW: Mutex<Option<String>> = Mutex::new(None);
static MIRROR: AtomicBool = AtomicBool::new(false);
static FAR: AtomicBool = AtomicBool::new(false);
static WEBCAM: AtomicBool = AtomicBool::new(false);
static LISTEN: AtomicBool = AtomicBool::new(false);

static SOUND_GEN: AtomicU32 = AtomicU32::new(0);
static SOUND_PROC: Mutex<Option<Arc<Proc>>> = Mutex::new(None);

// ---------------------------------------------------------------- processes

struct Proc {
    child: Mutex<Child>,
}

impl Proc {
    fn kill(&self) {
        let _ = self.child.lock().unwrap().kill();
    }
    fn exited(&self) -> bool {
        self.child.lock().unwrap().try_wait().map(|s| s.is_some()).unwrap_or(true)
    }
}

struct Spawned {
    proc: Arc<Proc>,
    out: Option<ChildStdout>,
    input: Option<ChildStdin>,
    err: Arc<Mutex<String>>,
}

/// ffmpeg with its arguments as one string, passed exactly as written.
fn spawn_ff(ff: &Path, args: &str, stdout: bool, stdin: bool) -> std::io::Result<Spawned> {
    let mut c = Command::new(ff);
    c.raw_arg(args).current_dir(conf_dir()).creation_flags(0x0800_0000);
    c.stdin(if stdin { Stdio::piped() } else { Stdio::null() }).stdout(if stdout { Stdio::piped() } else { Stdio::null() }).stderr(Stdio::piped());
    let mut child = c.spawn()?;
    let (out, input, mut stderr) = (child.stdout.take(), child.stdin.take(), child.stderr.take());
    let err = Arc::new(Mutex::new(String::new()));
    if let Some(mut s) = stderr.take() {
        let e = err.clone();
        spawn(move || {
            let mut buf = [0u8; 4096];
            while let Ok(n) = s.read(&mut buf) {
                if n == 0 {
                    break;
                }
                let mut g = e.lock().unwrap();
                if g.len() < 16_384 {
                    g.push_str(&String::from_utf8_lossy(&buf[..n]));
                }
            }
        });
    }
    Ok(Spawned { proc: Arc::new(Proc { child: Mutex::new(child) }), out, input, err })
}

fn kill_stream() {
    if let Some(p) = SECOND.lock().unwrap().take() {
        p.kill();
    }
}

fn is_current(gen: u32) -> bool {
    GEN.load(Ordering::SeqCst) == gen
}

// ---------------------------------------------------------------- rates and sizes

fn blocks(w: i32, h: i32) -> i64 {
    ((w as i64 + 15) / 16) * ((h as i64 + 15) / 16)
}

/// The frame rate a monitor can be sent at: its own rate, the phone's, and what the decoder takes at its size.
fn stream_rate(m: &Mon) -> i32 {
    let mut rate = PHONE_HZ.load(Ordering::Relaxed).min(if m.hz > 1 { m.hz } else { 60 });
    // Windows draws a virtual display about 60 times a second whatever its mode says, so it is sent at 60.
    if m.virt {
        rate = rate.min(60);
    }
    rate = (rate as i64).min(PHONE_BLOCKS.load(Ordering::Relaxed) / blocks(m.w, m.h).max(1)) as i32;
    rate.max(30)
}

/// Puts the virtual display in the mode that suits the phone: the fastest refresh rate the phone
/// shows, then the sharpest size its decoder takes at that rate, nearest the phone's shape.
fn fit_mode(t: Mon, w: i32, h: i32) -> Mon {
    let want = if h > 0 { w as f64 / h as f64 } else { 16.0 / 9.0 };
    let (hz, budget) = (PHONE_HZ.load(Ordering::Relaxed), PHONE_BLOCKS.load(Ordering::Relaxed));
    let mut best: Option<(i32, i32, i32)> = None;
    for m in displays::modes(&t.device) {
        if m.2 > hz + 1 || m.0 < 1280 || m.0 < m.1 || blocks(m.0, m.1) * m.2 as i64 > budget {
            continue;
        }
        let shape = |m: (i32, i32, i32)| (m.0 as f64 / m.1 as f64 / want).ln().abs();
        let better = match best {
            None => true,
            Some(b) => m.2 > b.2 || (m.2 == b.2 && (m.0 as i64 * m.1 as i64) > (b.0 as i64 * b.1 as i64)) || (m.2 == b.2 && m.0 as i64 * m.1 as i64 == b.0 as i64 * b.1 as i64 && shape(m) < shape(b)),
        };
        if better {
            best = Some(m);
        }
    }
    let Some(b) = best else { return t };
    if b.0 == t.w && b.1 == t.h && b.2 == t.hz {
        return t;
    }
    {
        let mut before = MODE_BEFORE.lock().unwrap();
        if before.is_none() {
            let v = format!("{}|{}|{}|{}", t.device, t.w, t.h, t.hz);
            write_conf("second-screen-mode.txt", &v);
            *before = Some(v);
        }
    }
    if !displays::set_mode(&t.device, b.0, b.1, b.2) {
        return t;
    }
    say(&format!("The phone's screen is now {}x{} at {} Hz, the sharpest the phone takes at its full {} frames a second.", b.0, b.1, b.2, b.2));
    sleep(Duration::from_millis(300));
    displays::attached().into_iter().find(|m| m.device == t.device).unwrap_or(t)
}

fn local_cap(host: &str) -> i32 {
    if is_cable(host) { 100_000 } else { 60_000 }
}

fn is_cable(host: &str) -> bool {
    (host == "127.0.0.1" && phone::port() == phone::ADB_FORWARD_PORT) || phone::usb_gateways().iter().any(|g| g == host)
}

fn rate_file() -> &'static str {
    "screen-rate.txt"
}

/// The rate from afar, kept for next time, and said.
fn set_remote_kbit(kbit: i32, slower: bool) {
    if slower {
        FAILED_KBIT.store(REMOTE_KBIT.load(Ordering::Relaxed), Ordering::Relaxed);
        *FAILED_AT.lock().unwrap() = Some(Instant::now());
    }
    REMOTE_KBIT.store(kbit, Ordering::Relaxed);
    // The webcam's rate is its own: the screen's, kept for next time, stays as it was.
    if !WEBCAM.load(Ordering::Relaxed) {
        write_conf(rate_file(), &kbit.to_string());
    }
    say(&if slower {
        format!("The link to the phone is slower: the computer's screen goes at {} kbit/s now.", kbit)
    } else {
        format!("The link to the phone keeps up: the computer's screen goes at {} kbit/s now.", kbit)
    });
}

/// The next rate up after a calm stretch, or 0 when there is none to try.
fn next_kbit() -> i32 {
    if FAILED_AT.lock().unwrap().map_or(false, |t| t.elapsed() > Duration::from_secs(180)) {
        FAILED_KBIT.store(i32::MAX, Ordering::Relaxed);
    }
    let cur = REMOTE_KBIT.load(Ordering::Relaxed);
    let mut next = MAX_KBIT.min(cur * 3 / 2);
    let failed = FAILED_KBIT.load(Ordering::Relaxed);
    if failed != i32::MAX {
        next = next.min(failed * 85 / 100);
    }
    if next > cur * 21 / 20 { next } else { 0 }
}

fn main_screen() -> Option<Mon> {
    let mons = displays::attached();
    *DESK.lock().unwrap() = Some(displays::desktop(&mons));
    mons.iter().find(|m| m.primary).cloned().or_else(|| mons.first().cloned())
}

// ---------------------------------------------------------------- the monitor for the phone

/// The monitor for the phone: the virtual display (extended first when it is off or only
/// duplicating the computer's screen, if `attach`), else any other extra monitor, else the
/// computer's own screen to mirror. When the virtual display has become the main one, the
/// computer's own screen is made the main one again.
fn prepare_screen(w: i32, h: i32, attach: bool) -> Option<Mon> {
    let mut mons = displays::attached();
    if attach && !mons.iter().any(|m| m.virt) && displays::virtual_installed() {
        let cloned = mons.iter().any(|m| m.cloned);
        // As Windows+P's Extend does; failing that, the virtual display added by itself.
        if displays::extend() {
            mons = wait_for_virtual();
        }
        if !mons.iter().any(|m| m.virt) {
            if let Some(dev) = displays::detached_virtual() {
                if displays::attach(&dev, &mons, w, h) {
                    mons = wait_for_virtual();
                }
            }
        }
        match mons.iter().find(|m| m.virt) {
            Some(added) => {
                // Put back as it was when the phone closes: duplicating again, or off.
                let v = format!("{}{}", added.device, if cloned { "|clone" } else { "" });
                write_conf("second-screen.txt", &v);
                *ATTACHED_HERE.lock().unwrap() = Some(v);
                if cloned {
                    say("Windows was duplicating the computer's screen onto the virtual display; it is extended now, so the phone is a screen of its own.");
                }
            }
            None => say("Could not extend the desktop onto the virtual display. In Windows' display settings choose \"Extend these displays\", then try again."),
        }
    }
    if let (Some(virt), Some(own)) = (mons.iter().find(|m| m.virt), mons.iter().find(|m| !m.virt)) {
        if virt.primary && displays::make_primary(&own.device, &mons) {
            say("The phone's screen had become the main display, with the taskbar and new windows on it. The computer's screen is the main one again.");
            sleep(Duration::from_millis(300));
            mons = displays::attached();
        }
    }
    let mut target = pick(&mons);
    if let Some(t) = target.clone().filter(|t| t.virt) {
        target = Some(fit_mode(t, w, h));
        mons = displays::attached();
    }
    *DESK.lock().unwrap() = Some(displays::desktop(&mons));
    if let Some(t) = target.as_ref().filter(|t| t.virt) {
        if HDR_OFF_HERE.lock().unwrap().is_none() && displays::hdr_on(&t.device) {
            if displays::set_hdr(&t.device, false) {
                write_conf("second-screen-hdr.txt", &t.device);
                *HDR_OFF_HERE.lock().unwrap() = Some(t.device.clone());
                say("HDR is off on the phone's screen while the phone shows it, so its colours come out right; it goes back on after.");
            } else {
                say("The virtual display has HDR on, which makes the phone's picture too bright; turn \"Use HDR\" off for it in Windows' display settings.");
            }
        }
    }
    target
}

/// Windows takes a moment to bring a display up.
fn wait_for_virtual() -> Vec<Mon> {
    let mut mons = displays::attached();
    for _ in 0..20 {
        if mons.iter().any(|m| m.virt) {
            break;
        }
        sleep(Duration::from_millis(150));
        mons = displays::attached();
    }
    mons
}

fn pick(mons: &[Mon]) -> Option<Mon> {
    mons.iter().find(|m| m.virt).or_else(|| mons.iter().find(|m| !m.primary)).or_else(|| mons.iter().find(|m| m.primary)).or_else(|| mons.first()).cloned()
}

fn say_showing(m: &Mon) {
    let size = format!(" ({}x{}, {} frames a second)", m.w, m.h, stream_rate(m));
    if m.virt {
        say(&format!("The phone is a second screen{}, extended from the computer's. Drag windows onto it as onto any screen.", size));
    } else if !m.primary {
        say(&format!("Showing monitor {}{} on the phone.", m.device.trim_start_matches("\\\\.\\"), size));
    } else {
        say("No extra monitor on this computer, so the phone mirrors this screen. Install a virtual display driver to make it a real second screen.");
    }
}

// ---- HDR

/// How a monitor in HDR has to be captured, as the brightness Windows gives SDR white there; 0
/// when the ordinary 8-bit capture is right. (The virtual display has its HDR turned off.)
fn hdr_white(t: &Mon) -> f64 {
    if t.virt || !displays::hdr_on(&t.device) {
        return 0.0;
    }
    let white = displays::sdr_white_nits(&t.device);
    if white > 85.0 { white } else { 0.0 }
}

/// The shader that turns a 16-bit capture of an HDR desktop back into the SDR picture: the capture
/// is linear light, 1.0 = 80 nits, with SDR content at (sRGB-decoded) x white/80. Dividing by that
/// and encoding sRGB again gives the picture Windows started from, within 2 of 255.
fn hdr_shader(white: f64) -> String {
    let gain = format!("{:.6}", 80.0 / white);
    format!(
        "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC Localhost 8787: HDR desktop to SDR\n\
         vec4 hook() {{\n    vec3 l = max(HOOKED_texOff(0).rgb * {gain}, 0.0);\n    vec3 k = 0.9 + 0.1 * (1.0 - exp(-(l - 0.9) / 0.1));\n    l = mix(l, k, step(0.9, l));\n    vec3 s = mix(12.92 * l, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, l));\n    return vec4(s, 1.0);\n}}\n"
    )
}

// ---------------------------------------------------------------- ffmpeg command lines

/// NVENC and ffmpeg's own converter both turn the screen into video with the BT.601 sums. Labelled
/// in full (on the frames), the phone decodes with the same sums.
const LABEL: &str = "setparams=color_primaries=bt470bg:color_trc=smpte170m";

fn dxgi_of(t: &Mon) -> (i64, i64, String) {
    displays::dxgi_find(&t.device).map(|(a, o, n)| (a as i64, o as i64, n)).unwrap_or((-1, -1, String::new()))
}

/// The ffmpeg command lines that stream a monitor to `dest`, best first; the shader goes in the config folder.
fn capture_tries(target: &Mon, hdr: f64, dest: &str, kbit: i32) -> Vec<String> {
    let (adapter, output, adapter_name) = dxgi_of(target);
    // As many frames as the monitor, the phone's panel and its decoder all manage (120 on a 120 Hz
    // phone), with the bit rate going up with them: 40 Mbit/s at 60, 80 at 120.
    let fps = stream_rate(target);
    let mbit = if kbit > 0 { (kbit / 1000).max(4) } else { 80.min(40.max(40 * fps / 60)) };
    let enc = format!(
        " -c:v h264_nvenc -preset p1 -tune ull -zerolatency 1 -rc cbr -b:v {m}M -maxrate {m}M -bufsize {b}M -g {g} -bf 0",
        m = mbit, b = 3.max(mbit / 13), g = fps * 2
    );
    // Frames captured on the NVIDIA card go straight to its encoder; from any other adapter they
    // are copied across first.
    let nvidia = adapter_name.to_lowercase().contains("nvidia");
    let grab = format!("ddagrab=output_idx={}:framerate={}:draw_mouse=1", output, fps);
    let mut tries = Vec::new();
    if adapter >= 0 && hdr > 0.0 {
        // An HDR monitor: the 16-bit capture, turned back into SDR on the GPU (libplacebo, Vulkan)
        // by the shader. The 16-bit frames go through memory, as ffmpeg cannot hand them from
        // Direct3D to Vulkan here.
        let _ = std::fs::write(conf_dir().join("hdr-to-sdr.hook"), hdr_shader(hdr));
        tries.push(format!(
            "-hide_banner -loglevel error -init_hw_device d3d11va=cap:{a} -init_hw_device vulkan=vk -filter_hw_device cap -filter_complex \"{grab}:output_fmt=16bit,hwdownload,format=rgbaf16le,\
             setparams=color_primaries=bt709:color_trc=iec61966-2-1:colorspace=gbr:range=pc,\
             libplacebo=format=nv12:colorspace=bt470bg:color_primaries=bt709:color_trc=iec61966-2-1:range=tv:peak_detect=0:custom_shader_path=hdr-to-sdr.hook,{LABEL}[v]\" -map \"[v]\"{enc}{dest}",
            a = adapter
        ));
    }
    if adapter >= 0 {
        tries.push(format!(
            "-hide_banner -loglevel error -init_hw_device d3d11va=cap:{a} -filter_hw_device cap -filter_complex \"{grab}{dl},{LABEL}[v]\" -map \"[v]\"{enc}{dest}",
            a = adapter, dl = if nvidia { "" } else { ",hwdownload,format=bgra" }
        ));
    }
    // Plain GDI capture of that part of the desktop reaches any monitor.
    let gdi = format!(
        "-hide_banner -loglevel error -f gdigrab -framerate {} -offset_x {} -offset_y {} -video_size {}x{} -draw_mouse 1 -i desktop",
        fps.min(60), target.x, target.y, target.w, target.h
    );
    tries.push(format!("{gdi} -vf {LABEL}{enc}{dest}"));
    tries.push(format!("{gdi} -vf format=yuv420p,{LABEL}:colorspace=bt470bg:range=tv -c:v libx264 -preset ultrafast -tune zerolatency -b:v 20M -g 120 -bf 0{dest}"));
    tries
}

/// The command lines for the main screen from afar, best first: as sharp and smooth as `kbit`
/// carries (1080p at 60 frames a second from 8 Mbit/s, down to 854 wide at 15 on a poor link), with
/// little buffering so the picture is current; on the NVIDIA card when there is one. The order never
/// depends on `kbit`, so the stream can start the same way at another rate.
fn remote_tries(target: &Mon, kbit: i32) -> Vec<String> {
    let (adapter, output, adapter_name) = dxgi_of(target);
    let mut wide = if kbit >= 4000 { 1920 } else if kbit >= 2400 { 1600 } else if kbit >= 1200 { 1280 } else if kbit >= 500 { 1024 } else { 854 };
    if target.w > 0 && wide > target.w {
        wide = target.w & !1;
    }
    let fps = if kbit >= 8000 { 60 } else if kbit >= 1200 { 30 } else if kbit >= 500 { 24 } else { 15 };
    let scale = format!("scale={}:-2:flags=bilinear,format=yuv420p", wide);
    // No large picture every few seconds: the refresh is spread over the frames (intra refresh), so
    // the stream stays as even as the link, and a lost moment heals within a second.
    let rate = format!(" -b:v {k}k -maxrate {k}k -bufsize {b}k -g {g} -bf 0", k = kbit, b = 100.max(kbit / 2), g = fps * 2);
    let nv = format!(" -c:v h264_nvenc -preset p4 -tune ll -zerolatency 1 -rc cbr -intra-refresh 1{}", rate);
    let x264 = format!(" -c:v libx264 -preset veryfast -tune zerolatency -intra-refresh 1{}", rate);
    let nvidia = adapter_name.to_lowercase().contains("nvidia");
    let dest = " -flush_packets 1 -flvflags no_duration_filesize -f flv pipe:1";
    let mut tries = Vec::new();
    if adapter >= 0 {
        let grab = format!(
            "-hide_banner -loglevel error -init_hw_device d3d11va=cap:{a} -filter_hw_device cap -filter_complex \"ddagrab=output_idx={o}:framerate={f}:draw_mouse=1,hwdownload,format=bgra,{scale},{LABEL}[v]\" -map \"[v]\"",
            a = adapter, o = output, f = fps
        );
        // NVENC handed frames from memory while the capture's Direct3D device is the filters' fails
        // to open ("CreateInputBuffer failed"); a CUDA device of its own lets it.
        if nvidia {
            tries.push(format!("{}{}{}", grab.replace(" -filter_hw_device cap ", " -filter_hw_device cap -init_hw_device cuda=cu "), nv, dest));
        }
        tries.push(format!("{grab}{x264}{dest}"));
    }
    tries.push(format!(
        "-hide_banner -loglevel error -f gdigrab -framerate {fps} -offset_x {} -offset_y {} -video_size {}x{} -draw_mouse 1 -i desktop -vf {scale},{LABEL}:colorspace=bt470bg:range=tv{x264}{dest}",
        target.x, target.y, target.w, target.h
    ));
    tries
}

// ---- the webcam, as a camera

static CAMERAS: Mutex<Option<(Option<String>, Option<String>)>> = Mutex::new(None);

/// This computer's webcam and microphone as DirectShow names them; looked for once.
fn find_webcam(ff: &Path) -> (Option<String>, Option<String>) {
    let mut g = CAMERAS.lock().unwrap();
    if let Some(c) = g.clone() {
        return c;
    }
    let (mut cam, mut mic) = (None, None);
    let out = Command::new(ff)
        .raw_arg("-hide_banner -list_devices true -f dshow -i dummy")
        .creation_flags(0x0800_0000)
        .stdin(Stdio::null())
        .output()
        .map(|o| String::from_utf8_lossy(&o.stderr).into_owned())
        .unwrap_or_default();
    for line in out.lines() {
        // "Name" (video)
        let Some(rest) = line.split_once('"').and_then(|(_, r)| r.split_once('"')) else { continue };
        let (name, tail) = rest;
        if tail.contains("(video)") && cam.is_none() {
            cam = Some(name.to_string());
        }
        if tail.contains("(audio)") && mic.is_none() {
            mic = Some(name.to_string());
        }
    }
    if cam.is_none() {
        say("No webcam found on this computer.");
    }
    *g = Some((cam.clone(), mic.clone()));
    (cam, mic)
}

/// The ffmpeg command lines that stream the webcam, best first: hardware H.264 when there is an NVIDIA GPU.
fn webcam_tries(ff: &Path, kbit: i32) -> Vec<String> {
    let Some(cam) = find_webcam(ff).0 else { return Vec::new() };
    let wide = if kbit >= 1800 { 1280 } else if kbit >= 800 { 960 } else { 640 };
    let fps = if kbit >= 600 { 30 } else { 15 };
    let cam = format!("video=\"{}\"", cam.replace('"', ""));
    let open = "-hide_banner -loglevel error -f dshow -rtbufsize 32M ";
    let vf = format!(" -vf scale={}:-2,fps={},format=yuv420p", wide, fps);
    let rate = format!(" -b:v {k}k -maxrate {k}k -bufsize {b}k -g {g} -bf 0", k = kbit, b = 100.max(kbit / 2), g = fps * 2);
    let nv = format!(" -c:v h264_nvenc -preset p4 -tune ll -zerolatency 1 -rc cbr{}", rate);
    let x264 = format!(" -c:v libx264 -preset veryfast -tune zerolatency{}", rate);
    let dest = " -flush_packets 1 -flvflags no_duration_filesize -f flv pipe:1";
    let nvidia = (0..16).any(|i| displays::dxgi_find(&format!("\\\\.\\DISPLAY{}", i)).map_or(false, |a| a.2.to_lowercase().contains("nvidia")));
    // At 720p and 30 frames a second when the camera has it; else as the camera gives it.
    let sized = format!("{open}-video_size 1280x720 -framerate 30 -i {cam}");
    let plain = format!("{open}-i {cam}");
    let mut tries = Vec::new();
    if nvidia {
        tries.push(format!("{sized}{vf}{nv}{dest}"));
    }
    tries.push(format!("{sized}{vf}{x264}{dest}"));
    tries.push(format!("{plain}{vf}{x264}{dest}"));
    tries
}

// ---------------------------------------------------------------- pictures: FLV to H.264 frames

/// ffmpeg's FLV, one video tag at a time, each made back into an H.264 picture as the phone takes
/// it (start codes, the stream's settings before the first picture and every key picture), handed
/// to `on_frame` the moment its tag is in.
fn flv_frames(mut s: impl Read, mut on_frame: impl FnMut(Vec<u8>)) {
    let mut head = [0u8; 9];
    if s.read_exact(&mut head).is_err() || &head[..3] != b"FLV" {
        return;
    }
    let skip = u32::from_be_bytes([head[5], head[6], head[7], head[8]]) as i64 - 9;
    if skip > 0 && s.read_exact(&mut vec![0u8; skip as usize]).is_err() {
        return;
    }
    let mut prev = [0u8; 4];
    if s.read_exact(&mut prev).is_err() {
        return;
    }
    const SC: [u8; 4] = [0, 0, 0, 1];
    let mut config: Option<Vec<u8>> = None;
    let mut nal_len = 4usize;
    let mut first = true;
    loop {
        let mut th = [0u8; 11];
        if s.read_exact(&mut th).is_err() {
            return;
        }
        let kind = th[0] & 0x1F;
        let size = ((th[1] as usize) << 16) | ((th[2] as usize) << 8) | th[3] as usize;
        let mut data = vec![0u8; size];
        if (size > 0 && s.read_exact(&mut data).is_err()) || s.read_exact(&mut prev).is_err() {
            return;
        }
        if kind != 9 || size < 5 || (data[0] & 0x0F) != 7 {
            continue;
        }
        let key = (data[0] >> 4) == 1;
        if data[1] == 0 {
            // The decoder configuration: its SPS and PPS, as start-coded units.
            let mut cfg = Vec::new();
            let mut q = 5;
            if data.len() < 11 {
                continue;
            }
            nal_len = (data[q + 4] & 3) as usize + 1;
            let nsps = (data[q + 5] & 0x1F) as usize;
            q += 6;
            for _ in 0..nsps {
                if q + 2 > data.len() {
                    break;
                }
                let l = ((data[q] as usize) << 8) | data[q + 1] as usize;
                q += 2;
                cfg.extend_from_slice(&SC);
                cfg.extend_from_slice(&data[q.min(data.len())..(q + l).min(data.len())]);
                q += l;
            }
            let npps = if q < data.len() { let n = data[q] as usize; q += 1; n } else { 0 };
            for _ in 0..npps {
                if q + 2 > data.len() {
                    break;
                }
                let l = ((data[q] as usize) << 8) | data[q + 1] as usize;
                q += 2;
                cfg.extend_from_slice(&SC);
                cfg.extend_from_slice(&data[q.min(data.len())..(q + l).min(data.len())]);
                q += l;
            }
            config = Some(cfg);
            continue;
        }
        if data[1] != 1 {
            continue;
        }
        let mut au = Vec::with_capacity(size + 64);
        if (first || key) && config.is_some() {
            au.extend_from_slice(config.as_ref().unwrap());
        }
        first = false;
        let mut p = 5;
        while p + nal_len <= data.len() {
            let mut l = 0usize;
            for i in 0..nal_len {
                l = (l << 8) | data[p + i] as usize;
            }
            p += nal_len;
            if l == 0 || p + l > data.len() {
                break;
            }
            au.extend_from_slice(&SC);
            au.extend_from_slice(&data[p..p + l]);
            p += l;
        }
        on_frame(au);
    }
}

/// Whether an access unit starts the picture afresh (SPS or IDR), which is never dropped.
fn starts_afresh(f: &[u8]) -> bool {
    f.windows(4).any(|w| w[0] == 0 && w[1] == 0 && w[2] == 1 && matches!(w[3] & 0x1F, 7 | 5))
}

/// One ffmpeg's pictures as they come; `kbit` is the rate it was started at.
struct Src {
    id: u32,
    proc: Arc<Proc>,
    frames: VecDeque<(Instant, Vec<u8>)>,
    eof: bool,
    started: Instant,
    kbit: i32,
}

struct Gate {
    cur: Src,
    next: Option<Src>,
}

type Shared = Arc<(Mutex<Gate>, Condvar)>;

fn read_frames(gate: &Shared, id: u32, out: ChildStdout) {
    let gate = gate.clone();
    spawn(move || {
        flv_frames(out, |f| {
            let (m, cv) = &*gate;
            let mut g = m.lock().unwrap();
            let now = Instant::now();
            if g.cur.id == id {
                g.cur.frames.push_back((now, f));
            } else if let Some(n) = g.next.as_mut().filter(|n| n.id == id) {
                n.frames.push_back((now, f));
            }
            cv.notify_all();
        });
        let (m, cv) = &*gate;
        let mut g = m.lock().unwrap();
        if g.cur.id == id {
            g.cur.eof = true;
        } else if let Some(n) = g.next.as_mut().filter(|n| n.id == id) {
            n.eof = true;
        }
        cv.notify_all();
    });
}

// ---------------------------------------------------------------- to the phone

/// A POST to the phone's page port whose body is sent as it is made (chunked). The phone's answer
/// is read when it ends.
struct Upload {
    s: TcpStream,
}

impl Upload {
    fn open(host: &str, port: u16, path: &str, content_type: &str) -> std::io::Result<Upload> {
        let addr = if host.contains(':') { format!("[{}]:{}", host, port) } else { format!("{}:{}", host, port) };
        let sa: std::net::SocketAddr = addr.parse().map_err(|_| std::io::Error::other("bad address"))?;
        let mut s = TcpStream::connect_timeout(&sa, Duration::from_secs(10))?;
        s.set_nodelay(true)?;
        s.set_write_timeout(Some(Duration::from_secs(30)))?;
        let head = format!(
            "POST {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: {}\r\nCookie: {}\r\nContent-Type: {}\r\nTransfer-Encoding: chunked\r\n\r\n",
            path, host, crate::util::user_agent(), phone::session().unwrap_or_default(), content_type
        );
        s.write_all(head.as_bytes())?;
        Ok(Upload { s })
    }

    /// One chunk: a picture's length (u32, big-endian) and the picture, when `framed`.
    fn send(&mut self, data: &[u8], framed: bool) -> std::io::Result<()> {
        let len = data.len() + if framed { 4 } else { 0 };
        let mut buf = Vec::with_capacity(len + 16);
        buf.extend_from_slice(format!("{:x}\r\n", len).as_bytes());
        if framed {
            buf.extend_from_slice(&(data.len() as u32).to_be_bytes());
        }
        buf.extend_from_slice(data);
        buf.extend_from_slice(b"\r\n");
        self.s.write_all(&buf)
    }

    fn finish(mut self) {
        let _ = self.s.write_all(b"0\r\n\r\n");
        let _ = self.s.set_read_timeout(Some(Duration::from_secs(5)));
        let mut sink = [0u8; 512];
        let _ = self.s.read(&mut sink);
    }
}

fn acked_for(sid: &str) -> i64 {
    let a = ACK.lock().unwrap();
    if a.0 == sid { a.1 } else { -1 }
}

type Respawn<'a> = &'a dyn Fn(i32) -> Option<Spawned>;

/// Pictures to the phone, each whole with its length, let out only as fast as the phone says it has
/// them (plus a moment: 0.3 s from afar, 0.1 s nearby); a picture still waiting after half a second
/// from afar, or 150 ms nearby, is dropped (all but the newest), so the phone always shows the
/// computer as it is now.
///
/// The rate follows the link, looked at every 2 s: late pictures, or the phone's word coming back
/// much slower than it can (pictures queueing on the way), ask for less; calm asks for more. A new
/// rate never stops the picture: `respawn` starts ffmpeg at it beside the one running, and the new
/// one takes over on the same stream at its first picture. Returns "rate" only when ffmpeg could not
/// be started again that way, "phone" or "ended".
fn post_frames(first: Spawned, at: &str, remote: bool, respawn: Respawn) -> &'static str {
    static IDS: AtomicU32 = AtomicU32::new(1);
    let Spawned { proc, out, .. } = first;
    let Some(out) = out else { return "ended" };
    let rate_of = |remote: bool| if remote { REMOTE_KBIT.load(Ordering::Relaxed) } else { LOCAL_KBIT.load(Ordering::Relaxed) };
    let id0 = IDS.fetch_add(1, Ordering::Relaxed);
    let gate: Shared = Arc::new((
        Mutex::new(Gate { cur: Src { id: id0, proc, frames: VecDeque::new(), eof: false, started: Instant::now(), kbit: rate_of(remote) }, next: None }),
        Condvar::new(),
    ));
    read_frames(&gate, id0, out);
    let mut why = "ended";
    // The phone tells what it has had (displayack); no more than about 0.3 s of picture is let out
    // beyond that, so the tunnel's and Windows' buffers never fill with old frames. A phone that
    // never tells (an older app) gets the stream unmetered after 3 s.
    let sid: String = format!("{:012x}", crate::util::now_ms() ^ ((std::process::id() as u64) << 20)).chars().take(12).collect();
    let mut sent_bytes: i64 = 0;
    let began = Instant::now();
    let mut sent_at: VecDeque<(i64, Instant)> = VecDeque::new();
    let (mut rtt, mut rtt_short, mut rtt_least, mut rtt_base) = (0.7f64, f64::MAX, f64::MAX, f64::MAX);
    let view = VIEW.lock().unwrap().clone();
    let mut path = format!("/api/display/stream?s={}", sid);
    if let Some(v) = &view {
        path.push_str(&format!("&v={}", crate::http::quote(v)));
    }
    let up = Upload::open(at, phone::port(), &path, "video/h264-frames");
    let mut up = match up {
        Ok(u) => u,
        Err(_) => {
            let g = gate.0.lock().unwrap();
            g.cur.proc.kill();
            return "phone";
        }
    };
    let (mut dropped, mut dropped_short, mut calm) = (0u32, 0u32, 0i32);
    let (mut window, mut short_window) = (Instant::now(), Instant::now());
    'send: loop {
        let rate_now = rate_of(remote);
        let room = ((rate_now as i64 * 1000 / 8) as f64 * (rtt.min(2.0) + if remote { 0.3 } else { 0.1 })).max(24.0 * 1024.0) as i64;
        loop {
            let acked = acked_for(&sid);
            while let Some(&(k, t)) = sent_at.front() {
                if acked < k {
                    break;
                }
                rtt_short = rtt_short.min(t.elapsed().as_secs_f64());
                sent_at.pop_front();
            }
            if acked < 0 && began.elapsed() > Duration::from_secs(3) {
                break;
            }
            if sent_bytes - acked.max(0) <= room {
                break;
            }
            sleep(Duration::from_millis(5));
            let g = gate.0.lock().unwrap();
            if g.cur.eof && g.cur.frames.is_empty() {
                break;
            }
        }
        let mut frame: Option<Vec<u8>> = None;
        {
            let (m, cv) = &*gate;
            let mut g = m.lock().unwrap();
            loop {
                if SECOND.lock().unwrap().is_none() {
                    break; // stopped
                }
                let next_ready = g.next.as_ref().map_or(false, |n| !n.frames.is_empty());
                let next_dead = g.next.as_ref().map_or(false, |n| n.eof || n.started.elapsed() > Duration::from_secs(5));
                if next_ready {
                    // The new rate has its first picture: it goes on from here, the old one ends.
                    let n = g.next.take().unwrap();
                    g.cur.proc.kill();
                    g.cur = n;
                    *SECOND.lock().unwrap() = Some(g.cur.proc.clone());
                } else if next_dead {
                    // It did not start: keep the one running, at its rate.
                    let n = g.next.take().unwrap();
                    n.proc.kill();
                    if remote { REMOTE_KBIT.store(g.cur.kbit, Ordering::Relaxed) } else { LOCAL_KBIT.store(g.cur.kbit, Ordering::Relaxed) }
                }
                if !g.cur.frames.is_empty() || (g.cur.eof && g.next.is_none()) {
                    break;
                }
                g = cv.wait_timeout(g, Duration::from_millis(200)).unwrap().0;
            }
            if !g.cur.frames.is_empty() && SECOND.lock().unwrap().is_some() {
                let limit = Duration::from_millis(if remote { 500 } else { 150 });
                while g.cur.frames.len() > 1 && g.cur.frames.front().map_or(false, |f| f.0.elapsed() > limit && !starts_afresh(&f.1)) {
                    g.cur.frames.pop_front();
                    dropped += 1;
                    dropped_short += 1;
                }
                frame = g.cur.frames.pop_front().map(|f| f.1);
            }
        }
        let Some(f) = frame else { break };
        if up.send(&f, true).is_err() {
            why = "phone";
            break;
        }
        sent_bytes += f.len() as i64 + 4;
        sent_at.push_back((sent_bytes, Instant::now()));
        if sent_at.len() > 4000 {
            sent_at.pop_front();
        }
        if window.elapsed() >= Duration::from_secs(10) {
            window = Instant::now();
            log(&format!(
                "Screen {}: {} kbit/s, word back in {} ms, {} late pictures dropped in 10 s",
                if remote { "far" } else { "near" }, rate_of(remote), ((if rtt_least < 5.0 { rtt_least } else { rtt }) * 1000.0).round(), dropped
            ));
            dropped = 0;
            rtt_least = f64::MAX;
        }
        if short_window.elapsed() < Duration::from_secs(2) {
            continue;
        }
        short_window = Instant::now();
        let mut queued = false;
        if rtt_short < 5.0 {
            rtt = rtt_short;
            rtt_least = rtt_least.min(rtt_short);
            // The phone's word much slower than it has been: pictures are queueing somewhere.
            queued = rtt_base < 5.0 && rtt_short > rtt_base + 0.25 && rtt_short > rtt_base * 2.0;
            rtt_base = rtt_short.min(if rtt_base < 5.0 { rtt_base + 0.005 } else { rtt_short });
        }
        rtt_short = f64::MAX;
        let late_now = dropped_short;
        dropped_short = 0;
        if gate.0.lock().unwrap().next.is_some() {
            continue; // a new rate is starting
        }
        let mut want = 0;
        if !remote {
            // Nearby: down by 30% on many late pictures, up by a quarter after 20 calm seconds.
            calm = if late_now == 0 { calm + 2 } else { 0 };
            let local = LOCAL_KBIT.load(Ordering::Relaxed);
            if late_now > 8 && local > 8000 {
                want = 8000.max(local * 7 / 10);
            } else if calm >= 20 && local < local_cap(at) {
                want = local_cap(at).min(local * 5 / 4);
            }
            if want == 0 {
                continue;
            }
            say(&if want < local {
                format!("The link to the phone is busy: the screen goes at {} Mbit/s now.", want / 1000)
            } else {
                format!("The link to the phone keeps up: the screen goes at {} Mbit/s now.", want / 1000)
            });
            LOCAL_KBIT.store(want, Ordering::Relaxed);
        } else {
            calm = if late_now == 0 && !queued { calm + 2 } else { 0 };
            let cur = REMOTE_KBIT.load(Ordering::Relaxed);
            if (late_now > 5 || queued) && cur > 250 {
                want = 250.max(cur * if late_now > 5 { 6 } else { 8 } / 10);
                set_remote_kbit(want, true);
            } else if calm >= (if FAILED_KBIT.load(Ordering::Relaxed) == i32::MAX { 4 } else { 20 }) {
                want = next_kbit();
                if want > 0 {
                    set_remote_kbit(want, false);
                }
            }
            if want == 0 {
                continue;
            }
        }
        calm = 0;
        let np = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| respawn(want))).ok().flatten();
        let Some(np) = np else {
            why = "rate";
            break 'send;
        };
        let Spawned { proc: nproc, out: Some(nout), .. } = np else {
            why = "rate";
            break 'send;
        };
        let id = IDS.fetch_add(1, Ordering::Relaxed);
        {
            let mut g = gate.0.lock().unwrap();
            g.next = Some(Src { id, proc: nproc, frames: VecDeque::new(), eof: false, started: Instant::now(), kbit: want });
        }
        read_frames(&gate, id, nout);
    }
    if why == "ended" {
        up.finish();
    }
    let g = gate.0.lock().unwrap();
    g.cur.proc.kill();
    if let Some(n) = &g.next {
        n.proc.kill();
    }
    why
}

// ---------------------------------------------------------------- one go at streaming a monitor

#[derive(PartialEq)]
enum End {
    Phone,
    Relayout,
    Ended,
    Failed,
    Stopped,
}

fn capture(ff: &Path, target: &Mon, at: &str, port: u16, gen: u32, http: bool, remote: bool) -> End {
    *SHOWN.lock().unwrap() = Some(target.clone());
    let hdr = if remote { 0.0 } else { hdr_white(target) };
    if hdr > 0.0 {
        say(&format!("This screen is in HDR, with ordinary white at {} nits; the phone gets it turned back into an ordinary picture, so it is not too bright.", hdr.round()));
    }
    // To the page's port: FLV, which gives each frame's size, every frame let out the moment it is
    // made (nothing waits to fill a buffer), so each goes to the phone whole and at once.
    let dest = if http { " -flush_packets 1 -flvflags no_duration_filesize -f flv pipe:1".to_string() } else { format!(" -f h264 \"tcp://{}:{}?tcp_nodelay=1\"", at, port) };
    // Nearby over the page's port: at the rate the link carries, started once per link.
    if !remote && http && (LOCAL_KBIT.load(Ordering::Relaxed) <= 0 || LOCAL_FOR.lock().unwrap().as_deref() != Some(at)) {
        *LOCAL_FOR.lock().unwrap() = Some(at.to_string());
        let want = 80.min(40.max(40 * stream_rate(target) / 60)) * 1000;
        LOCAL_KBIT.store(local_cap(at).min(want) * 3 / 4, Ordering::Relaxed);
    }
    let webcam = WEBCAM.load(Ordering::Relaxed);
    let (ffp, tgt, dest2) = (ff.to_path_buf(), target.clone(), dest.clone());
    let build: Arc<dyn Fn(i32) -> Vec<String> + Send + Sync> = Arc::new(move |kbit: i32| -> Vec<String> {
        if webcam {
            webcam_tries(&ffp, if remote { REMOTE_KBIT.load(Ordering::Relaxed).min(kbit.max(250)) } else { kbit.clamp(250, 2500) })
        } else if remote {
            remote_tries(&tgt, kbit)
        } else {
            capture_tries(&tgt, hdr, &dest2, if http { kbit } else { 0 })
        }
    });
    let start_kbit = if remote { REMOTE_KBIT.load(Ordering::Relaxed) } else if webcam { LOCAL_KBIT.load(Ordering::Relaxed).max(2500).min(2500) } else { LOCAL_KBIT.load(Ordering::Relaxed) };
    let tries = build(start_kbit);
    for (way, args) in tries.iter().enumerate() {
        RELAYOUT.store(false, Ordering::SeqCst);
        let sp = match spawn_ff(ff, args, http, false) {
            Ok(s) => s,
            Err(e) => {
                *CAPTURE_ERR.lock().unwrap() = format!("way {} of {}: {}", way + 1, tries.len(), e);
                continue;
            }
        };
        *SECOND.lock().unwrap() = Some(sp.proc.clone());
        if !is_current(gen) {
            kill_stream();
            return End::Stopped; // stopped while this one started
        }
        let (proc, err) = (sp.proc.clone(), sp.err.clone());
        {
            let (t, p) = (target.clone(), proc.clone());
            spawn(move || watch_screens(gen, t, hdr, p, remote));
        }
        let sent: Arc<Mutex<Option<&'static str>>> = Arc::new(Mutex::new(None));
        let mut pump = None;
        if http {
            let (sent2, at2, build2) = (sent.clone(), at.to_string(), build.clone());
            let ff2 = ff.to_path_buf();
            pump = Some(spawn(move || {
                // ffmpeg started again the same way at another rate, for the stream to change to.
                let respawn = |kbit: i32| -> Option<Spawned> {
                    if !is_current(gen) {
                        return None;
                    }
                    // A webcam opens for one program at a time: the running one goes first (a
                    // moment's pause), where the screen's new rate starts beside the old one.
                    if webcam {
                        kill_stream();
                        sleep(Duration::from_millis(200));
                    }
                    let again = build2(if webcam { kbit.min(2500) } else { kbit });
                    spawn_ff(&ff2, again.get(way)?, true, false).ok()
                };
                let r = post_frames(sp, &at2, remote, &respawn);
                *sent2.lock().unwrap() = Some(r);
            }));
        }
        // Still going after a few seconds: it works. Stopped at once: try the next way.
        let quick = {
            let end = Instant::now() + Duration::from_secs(4);
            loop {
                if proc.exited() {
                    break true;
                }
                if Instant::now() >= end {
                    break false;
                }
                sleep(Duration::from_millis(50));
            }
        };
        if let Some(p) = pump {
            if !quick {
                let _ = p.join();
            } else {
                // Give the sender a moment to see the end.
                for _ in 0..50 {
                    if p.is_finished() {
                        break;
                    }
                    sleep(Duration::from_millis(100));
                }
            }
        } else if !quick {
            while !proc.exited() && is_current(gen) {
                sleep(Duration::from_millis(100));
            }
        }
        sleep(Duration::from_millis(300));
        if !is_current(gen) || SECOND.lock().unwrap().is_none() {
            return End::Stopped;
        }
        if RELAYOUT.load(Ordering::SeqCst) {
            return End::Relayout;
        }
        let result = *sent.lock().unwrap();
        if result == Some("rate") {
            return End::Relayout; // the link asked for another size: start again at it
        }
        if result == Some("phone") {
            return End::Phone;
        }
        let e = err.lock().unwrap().clone();
        if e.contains("Connection refused") || e.contains("Connection reset") || e.contains("Broken pipe") {
            return End::Phone;
        }
        if !quick {
            return End::Ended;
        }
        // Kept for the log, if every way fails: what ffmpeg said about this one.
        let last = e.trim().lines().last().unwrap_or("(nothing)").trim().to_string();
        let msg = format!("way {} of {}: {}", way + 1, tries.len(), last);
        log(&format!("Second screen, {}", msg));
        *CAPTURE_ERR.lock().unwrap() = msg;
    }
    End::Failed
}

/// While a stream runs: if the monitors change (the display extended, moved, resized, made the main
/// one, or taken off), or HDR or its SDR brightness is changed on the monitor shown, end it so it
/// starts again on the right monitor, at its new place, captured the right way.
fn watch_screens(gen: u32, target: Mon, hdr: f64, p: Arc<Proc>, remote: bool) {
    while is_current(gen) && !p.exited() {
        sleep(Duration::from_millis(1500));
        let mons = displays::attached();
        if mons.is_empty() {
            continue;
        }
        *DESK.lock().unwrap() = Some(displays::desktop(&mons));
        let now = mons.iter().find(|m| m.device == target.device);
        let best = pick(&mons);
        let moved = now.map_or(true, |n| n.x != target.x || n.y != target.y || n.w != target.w || n.h != target.h);
        // From afar it is always the main screen: another monitor is no better.
        let mirror = MIRROR.load(Ordering::Relaxed);
        let better = !remote && !mirror && best.as_ref().map_or(false, |b| b.device != target.device);
        let main = !remote && !mirror && now.map_or(false, |n| n.virt && n.primary);
        let light = !moved && now.map_or(false, |n| (hdr_white(n) - hdr).abs() > 1.0);
        if moved || better || main || light {
            RELAYOUT.store(true, Ordering::SeqCst);
            p.kill();
            return;
        }
    }
}

// ---------------------------------------------------------------- starting and stopping

pub struct Start {
    pub port: u16,
    pub w: i32,
    pub h: i32,
    pub hz: i32,
    pub blocks: i64,
    pub http: bool,
    pub view: Option<String>,
    pub mirror: bool,
    pub far: bool,
    pub webcam: bool,
    pub listen: bool,
}

/// "display start PORT W H [HZ [BLOCKS [http [view=ID|mirror|far|webcam|listen ...]]]]".
pub fn parse(d: &str) -> Option<Start> {
    let p: Vec<&str> = d.split(' ').collect();
    if p.first() != Some(&"start") || p.len() < 4 {
        return None;
    }
    let num = |i: usize| p.get(i).and_then(|v| v.parse::<i64>().ok());
    let mut s = Start {
        port: num(1)? as u16,
        w: num(2)? as i32,
        h: num(3)? as i32,
        // Newer phones add their refresh rate and decoder throughput.
        hz: num(4).map(|h| h.max(30) as i32).unwrap_or(60),
        // No more than H.264's level 5.2 carries (2,073,600 blocks a second: 4K at 64, 2560x1440 at
        // 120), whatever the decoder says: past it the encoder falls back to about 60 frames.
        blocks: num(5).filter(|b| *b > 0).unwrap_or(2_073_600).min(2_073_600),
        http: p.get(6) == Some(&"http"),
        view: None,
        mirror: false,
        far: false,
        webcam: false,
        listen: false,
    };
    for q in p.iter().skip(7) {
        if let Some(v) = q.strip_prefix("view=") {
            s.view = Some(v.to_string());
        } else {
            match *q {
                "mirror" => s.mirror = true,
                "far" => s.far = true,
                "webcam" => s.webcam = true,
                "listen" => s.listen = true,
                _ => {}
            }
        }
    }
    Some(s)
}

pub fn start(at: String, s: Start) {
    PHONE_HZ.store(s.hz, Ordering::Relaxed);
    PHONE_BLOCKS.store(s.blocks, Ordering::Relaxed);
    *VIEW.lock().unwrap() = s.view.clone();
    MIRROR.store(s.mirror, Ordering::Relaxed);
    FAR.store(s.far, Ordering::Relaxed);
    WEBCAM.store(s.webcam, Ordering::Relaxed);
    LISTEN.store(s.listen, Ordering::Relaxed);
    start_second_screen(&at, s.port, s.w, s.h, s.http);
}

fn start_second_screen(at: &str, port: u16, w: i32, h: i32, http: bool) {
    let gen = GEN.fetch_add(1, Ordering::SeqCst) + 1;
    // A file "screen-far.txt" beside the helper sends the far picture on any link, for trying it out.
    let via_tunnel = crate::far::local().map_or(false, |(h2, p2)| h2 == at && p2 == phone::port());
    let remote = http && (via_tunnel || FAR.load(Ordering::Relaxed) || conf_dir().join("screen-far.txt").exists());
    let mirror = MIRROR.load(Ordering::Relaxed);
    let (webcam, view) = (WEBCAM.load(Ordering::Relaxed), VIEW.lock().unwrap().clone());
    if webcam {
        say("Someone is watching this computer's webcam, through the phone.");
    } else if view.is_some() {
        say("Another computer is viewing and driving this one, through the phone.");
    }
    kill_stream();
    let Some(ff) = find_ffmpeg() else {
        say("The second screen needs ffmpeg on this computer: in a terminal, run  winget install Gyan.FFmpeg  then try again.");
        return;
    };
    let mut target = if remote || mirror { main_screen() } else { prepare_screen(w, h, true) };
    if remote && !RATE_READ.swap(true, Ordering::Relaxed) {
        // Where the last time ended, a little under it, rather than climbing from the bottom again.
        if let Some(saved) = read_conf(rate_file()).and_then(|s| s.parse::<i32>().ok()) {
            REMOTE_KBIT.store((saved * 85 / 100).clamp(250, MAX_KBIT), Ordering::Relaxed);
        }
    }
    // The webcam from afar starts gently (500 kbit/s at most) and climbs as the link allows.
    if webcam && remote {
        REMOTE_KBIT.store(REMOTE_KBIT.load(Ordering::Relaxed).min(500), Ordering::Relaxed);
    }
    // The sound goes too: to the phone's screen view, or into the stream of the page watching; with
    // the webcam, the microphone, and only to someone listening.
    if http && webcam {
        if LISTEN.load(Ordering::Relaxed) {
            start_mic(&ff, at, view.as_deref());
        }
    } else if http {
        start_sound(&ff, at, remote, view.as_deref());
    }
    let mut said: Option<String> = None;
    let mut failures = 0;
    while let Some(t) = target.clone() {
        if !is_current(gen) {
            break;
        }
        if said.as_deref() != Some(t.device.as_str()) {
            said = Some(t.device.clone());
            if view.is_some() {
            } else if remote {
                say(&format!("Showing this computer's screen on the phone, from another network: smaller, at what the link carries ({} kbit/s to start).", REMOTE_KBIT.load(Ordering::Relaxed)));
            } else {
                say_showing(&t);
            }
        }
        let end = capture(&ff, &t, at, port, gen, http, remote);
        if !is_current(gen) {
            return;
        }
        // The phone closed it, or went (unplugged, out of reach) without saying so: put the
        // computer's screens back rather than leave a display nobody sees.
        if end == End::Phone {
            stop();
            return;
        }
        if end == End::Relayout {
            failures = 0;
        } else {
            failures += 1;
            if failures >= 3 {
                let err = CAPTURE_ERR.lock().unwrap().clone();
                say(&format!("Could not stream this screen to the phone ({}x{}; {}).", t.w, t.h, if err.is_empty() { "no word from ffmpeg".to_string() } else { err }));
                stop();
                return;
            }
        }
        // The monitors changed, or the capture broke on a change: look again.
        sleep(Duration::from_millis(300));
        if !is_current(gen) {
            return;
        }
        target = if remote || mirror { main_screen() } else { prepare_screen(w, h, false) };
    }
}

pub fn stop() {
    GEN.fetch_add(1, Ordering::SeqCst);
    kill_stream();
    stop_sound();
    release_screen();
}

/// The phone says what it has had of the screen stream named so.
pub fn ack(d: &str) {
    let q: Vec<&str> = d.split(' ').collect();
    if let (Some(sid), Some(n)) = (q.first(), q.get(1).and_then(|n| n.parse::<i64>().ok())) {
        *ACK.lock().unwrap() = (sid.to_string(), n);
    }
}

/// Called at the start: a helper closed while the phone was a second screen left the virtual
/// display on the desktop, with windows on it that nobody can see: take it off.
pub fn recover() {
    *HDR_OFF_HERE.lock().unwrap() = read_conf("second-screen-hdr.txt");
    *MODE_BEFORE.lock().unwrap() = read_conf("second-screen-mode.txt");
    *ATTACHED_HERE.lock().unwrap() = read_conf("second-screen.txt");
    if HDR_OFF_HERE.lock().unwrap().is_some() || MODE_BEFORE.lock().unwrap().is_some() || ATTACHED_HERE.lock().unwrap().is_some() {
        release_screen();
    }
}

/// Closing the window while a stream runs does the same, rather than leave the display there.
pub fn on_exit() {
    kill_stream();
    release_screen();
}

fn on_desktop(dev: &str) -> bool {
    displays::attached().iter().any(|m| m.device == dev)
}

fn forget(file: &str) {
    let _ = std::fs::remove_file(conf_dir().join(file));
}

/// Puts the virtual display this helper extended back as it found it: duplicating the computer's
/// screen again, or off the desktop. Either way Windows moves its windows to the computer's screen.
/// Each part is forgotten (and its file deleted) only once it is really put back: Windows refuses
/// display changes while it is locked or showing the screen saver, and then `retry_release` tries
/// again until it can.
fn release_screen() {
    let _g = RELEASE_LOCK.lock().unwrap();
    // HDR and its mode first: once the display is off the desktop there is nothing to set. HDR
    // before the mode, as a request made while the mode is still changing gets lost.
    let hdr = HDR_OFF_HERE.lock().unwrap().clone();
    let hdr = hdr.filter(|h| !(displays::set_hdr(h, true) || !on_desktop(h)));
    *HDR_OFF_HERE.lock().unwrap() = hdr.clone();
    if hdr.is_none() {
        forget("second-screen-hdr.txt");
    }
    let mode = MODE_BEFORE.lock().unwrap().clone();
    let mode = mode.filter(|m| {
        let p: Vec<&str> = m.split('|').collect();
        let ok = p.len() != 4 || !on_desktop(p[0]) || displays::set_mode(p[0], p[1].parse().unwrap_or(0), p[2].parse().unwrap_or(0), p[3].parse().unwrap_or(0));
        !ok
    });
    *MODE_BEFORE.lock().unwrap() = mode.clone();
    if mode.is_none() {
        forget("second-screen-mode.txt");
    }
    let was = ATTACHED_HERE.lock().unwrap().clone();
    let was = match was {
        Some(w) if hdr.is_none() && mode.is_none() => {
            let p: Vec<&str> = w.split('|').collect();
            let clone = p.get(1) == Some(&"clone");
            if if clone { displays::duplicate() } else { displays::detach(p[0]) || !on_desktop(p[0]) } {
                say(if clone {
                    "The phone's screen is closed; Windows duplicates the computer's screen again, as before, and the windows on it are back on the computer's."
                } else {
                    "Took the phone's screen off the desktop; its windows are back on the computer's."
                });
                None
            } else {
                Some(w)
            }
        }
        other => other,
    };
    *ATTACHED_HERE.lock().unwrap() = was.clone();
    if was.is_none() {
        forget("second-screen.txt");
    }
    if hdr.is_some() || mode.is_some() || was.is_some() {
        retry_release();
    }
}

/// Tries `release_screen` again every 15 seconds until Windows lets it, unless the phone takes the screen again.
fn retry_release() {
    if RETRYING.swap(true, Ordering::SeqCst) {
        return;
    }
    say("Windows is not taking display changes right now (locked, or the screen saver is on); the computer's screens go back as they were as soon as it does.");
    spawn(|| {
        loop {
            if HDR_OFF_HERE.lock().unwrap().is_none() && MODE_BEFORE.lock().unwrap().is_none() && ATTACHED_HERE.lock().unwrap().is_none() {
                break;
            }
            sleep(Duration::from_secs(15));
            if SECOND.lock().unwrap().is_some() {
                break; // in use again: it is released when that ends
            }
            release_screen(); // "retrying" stays set, so a failure here starts no second loop
        }
        RETRYING.store(false, Ordering::SeqCst);
    });
}

// ---------------------------------------------------------------- touches

/// The mouse to a point on the captured monitor, given as fractions of its width and height.
pub fn point_at(fx: f32, fy: f32) {
    let (Some(s), Some(d)) = (SHOWN.lock().unwrap().clone(), DESK.lock().unwrap().clone()) else { return };
    // Everything here is in real pixels, so the input goes out as from a DPI-aware program: scaled
    // coordinates differ per monitor once the two screens have different scaling.
    let x = s.x as f64 + fx as f64 * (s.w - 1) as f64;
    let y = s.y as f64 + fy as f64 * (s.h - 1) as f64;
    let dx = ((x - d.x as f64) * 65535.0 / ((d.w - 1).max(1)) as f64).round() as i32;
    let dy = ((y - d.y as f64) * 65535.0 / ((d.h - 1).max(1)) as f64).round() as i32;
    let was = displays::real_pixels();
    crate::input::move_absolute(dx, dy);
    displays::restore_dpi(was);
}

// ---------------------------------------------------------------- the computer's sound with its screen
//
// What the speakers play goes to the phone with the screen: WASAPI loopback, AAC from ffmpeg (160
// kbit/s nearby, 64 from afar), posted to the page's port like the picture. From afar, sound still
// waiting after 400 ms is dropped, so it keeps up with the picture rather than falling behind it.

fn stop_sound() {
    SOUND_GEN.fetch_add(1, Ordering::SeqCst);
    if let Some(p) = SOUND_PROC.lock().unwrap().take() {
        p.kill();
    }
}

fn start_sound(ff: &Path, at: &str, remote: bool, view: Option<&str>) {
    stop_sound();
    let gen = SOUND_GEN.load(Ordering::SeqCst);
    let (ff, at, view) = (ff.to_path_buf(), at.to_string(), view.map(|v| v.to_string()));
    spawn(move || {
        let cap = match Loopback::new() {
            Ok(c) => c,
            Err(e) => {
                say(&format!("The computer's sound could not be captured ({}); the phone gets the picture only.", e));
                return;
            }
        };
        if !cap.float {
            say("The computer's sound is not in a format this helper takes, so the phone gets the picture only.");
            return;
        }
        let args = format!(
            "-hide_banner -loglevel error -f f32le -ar {} -ac {} -i pipe:0 -ac 2 -ar 48000 -c:a aac -b:a {} -f adts pipe:1",
            cap.rate, cap.channels, if remote { "64k" } else { "160k" }
        );
        let Ok(sp) = spawn_ff(&ff, &args, true, true) else { return };
        *SOUND_PROC.lock().unwrap() = Some(sp.proc.clone());
        let (proc, mut input) = (sp.proc.clone(), sp.input.unwrap());
        // Captured sound into ffmpeg every 10 ms; silence keeps flowing as zeros once something has played.
        spawn(move || {
            while SOUND_GEN.load(Ordering::SeqCst) == gen && !proc.exited() {
                let b = cap.read();
                if !b.is_empty() && input.write_all(&b).and_then(|_| input.flush()).is_err() {
                    break;
                }
                sleep(Duration::from_millis(10));
            }
        });
        post_sound(sp.proc.clone(), sp.out.unwrap(), &at, remote || view.is_some(), gen, view.as_deref());
    });
}

/// The microphone, as AAC, to someone listening to the webcam.
fn start_mic(ff: &Path, at: &str, view: Option<&str>) {
    stop_sound();
    let gen = SOUND_GEN.load(Ordering::SeqCst);
    let (ff, at, view) = (ff.to_path_buf(), at.to_string(), view.map(|v| v.to_string()));
    spawn(move || {
        let Some(mic) = find_webcam(&ff).1 else { return };
        let args = format!(
            "-hide_banner -loglevel error -f dshow -audio_buffer_size 50 -i audio=\"{}\" -ac 1 -ar 48000 -c:a aac -b:a 64k -f adts pipe:1",
            mic.replace('"', "")
        );
        match spawn_ff(&ff, &args, true, false) {
            Ok(sp) => {
                *SOUND_PROC.lock().unwrap() = Some(sp.proc.clone());
                post_sound(sp.proc.clone(), sp.out.unwrap(), &at, true, gen, view.as_deref());
            }
            Err(e) => say(&format!("The microphone could not be heard ({}).", e)),
        }
    });
}

/// ffmpeg's ADTS frames to the phone; from afar, frames older than 400 ms are let go.
fn post_sound(p: Arc<Proc>, mut src: ChildStdout, at: &str, remote: bool, gen: u32, view: Option<&str>) {
    let shared: Arc<(Mutex<(VecDeque<(Instant, Vec<u8>)>, bool)>, Condvar)> = Arc::new((Mutex::new((VecDeque::new(), false)), Condvar::new()));
    {
        let shared = shared.clone();
        spawn(move || {
            let mut head = [0u8; 7];
            while src.read_exact(&mut head).is_ok() {
                if head[0] != 0xFF || (head[1] & 0xF0) != 0xF0 {
                    continue;
                }
                let len = (((head[3] & 3) as usize) << 11) | ((head[4] as usize) << 3) | (head[5] >> 5) as usize;
                if len < 7 {
                    continue;
                }
                let mut f = vec![0u8; len];
                f[..7].copy_from_slice(&head);
                if src.read_exact(&mut f[7..]).is_err() {
                    break;
                }
                let (m, cv) = &*shared;
                m.lock().unwrap().0.push_back((Instant::now(), f));
                cv.notify_all();
            }
            let (m, cv) = &*shared;
            m.lock().unwrap().1 = true;
            cv.notify_all();
        });
    }
    // Set up as the picture's upload is, to the sound's own route.
    let path = format!("/api/display/audio{}", view.map(|v| format!("?v={}", crate::http::quote(v))).unwrap_or_default());
    if let Ok(mut up) = Upload::open(at, phone::port(), &path, "audio/aac") {
        while SOUND_GEN.load(Ordering::SeqCst) == gen {
            let f = {
                let (m, cv) = &*shared;
                let mut g = m.lock().unwrap();
                while g.0.is_empty() && !g.1 {
                    g = cv.wait_timeout(g, Duration::from_secs(1)).unwrap().0;
                    if SOUND_GEN.load(Ordering::SeqCst) != gen {
                        break;
                    }
                }
                if remote {
                    while g.0.len() > 1 && g.0.front().map_or(false, |f| f.0.elapsed() > Duration::from_millis(400)) {
                        g.0.pop_front();
                    }
                }
                g.0.pop_front()
            };
            let Some((_, f)) = f else { break };
            if up.send(&f, false).is_err() {
                break;
            }
        }
    }
    p.kill();
}

/// `--screen-check`: what this computer can do for the second screen. Lists the monitors and the
/// graphics adapter behind each, runs each way of capturing the main screen for a few seconds into
/// the picture parser (nothing is kept or sent), and listens to the speakers for a moment.
pub fn check() {
    let mons = displays::attached();
    println!("monitors:");
    for m in &mons {
        let (a, o, n) = dxgi_of(m);
        println!(
            "  {} {}x{} at {},{} {} Hz{}{}{}  adapter {} output {} ({}) hdr {}",
            m.device, m.w, m.h, m.x, m.y, m.hz, if m.primary { " main" } else { "" }, if m.virt { " virtual" } else { "" }, if m.cloned { " duplicated" } else { "" },
            a, o, n, displays::hdr_on(&m.device)
        );
    }
    println!("virtual display driver installed: {}", displays::virtual_installed());
    let Some(ff) = find_ffmpeg() else {
        println!("ffmpeg: not found");
        return;
    };
    println!("ffmpeg: {}", ff.display());
    if let Some(t) = mons.iter().find(|m| m.primary).or(mons.first()) {
        let dest = " -flush_packets 1 -flvflags no_duration_filesize -f flv pipe:1";
        let mut all = remote_tries(t, 4000);
        all.extend(capture_tries(t, 0.0, dest, 0));
        for (i, args) in all.iter().enumerate() {
            let first = args.split(" -").find(|p| p.starts_with("f gdigrab") || p.starts_with("init_hw_device")).unwrap_or("").to_string();
            match spawn_ff(&ff, args, true, false) {
                Ok(sp) => {
                    let (n, bytes) = (Arc::new(AtomicI32::new(0)), Arc::new(AtomicI64::new(0)));
                    let (n2, b2) = (n.clone(), bytes.clone());
                    let out = sp.out.unwrap();
                    let reader = spawn(move || flv_frames(out, |f| { n2.fetch_add(1, Ordering::Relaxed); b2.fetch_add(f.len() as i64, Ordering::Relaxed); }));
                    sleep(Duration::from_secs(4));
                    let frames = n.load(Ordering::Relaxed);
                    sp.proc.kill();
                    let _ = reader.join();
                    let err = sp.err.lock().unwrap().trim().lines().last().unwrap_or("").to_string();
                    println!("way {} ({}): {} pictures in 4 s, {} KB{}", i + 1, first.trim(), frames, bytes.load(Ordering::Relaxed) / 1024, if frames == 0 { format!("  ffmpeg: {}", err) } else { String::new() });
                }
                Err(e) => println!("way {}: {}", i + 1, e),
            }
        }
    }
    match Loopback::new() {
        Ok(l) => {
            let mut total = 0;
            let mut loud = 0u32;
            for _ in 0..50 {
                let b = l.read();
                total += b.len();
                loud += b.chunks(4).filter(|c| c.len() == 4 && f32::from_le_bytes([c[0], c[1], c[2], c[3]]).abs() > 0.001).count() as u32;
                sleep(Duration::from_millis(20));
            }
            println!("speakers: {} Hz, {} channels, {}, {} bytes in 1 s, {} audible samples", l.rate, l.channels, if l.float { "float" } else { "not float" }, total, loud);
        }
        Err(e) => println!("speakers: {}", e),
    }
}

/// `--screen-selftest PORT`: streams the main screen to a stand-in phone on this computer's
/// loopback for eight seconds (mirror mode: no display is changed), then stops.
pub fn selftest(port: u16) {
    phone::move_to("127.0.0.1", port);
    phone::set_session(Some("session=test".into()));
    let s = Start { port, w: 1920, h: 1080, hz: 60, blocks: 2_073_600, http: true, view: None, mirror: true, far: false, webcam: false, listen: false };
    let t = spawn(move || start("127.0.0.1".into(), s));
    sleep(Duration::from_secs(8));
    stop();
    let _ = t.join();
}
