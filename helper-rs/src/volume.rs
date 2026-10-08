//! The laptop's master volume: the phone's volume bar sets it, and the phone is told where it is,
//! on connecting, right after a change, and whenever it changes here (the keys, the taskbar).
//!
//! Windows: the default speakers' IAudioEndpointVolume, as the earlier helper. Linux: `pactl` or
//! `wpctl`. macOS: AppleScript's volume settings.

use crate::phone;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::sleep;
use std::time::Duration;

/// Set after a change from the phone, so the next look is quick.
pub static DIRTY: AtomicBool = AtomicBool::new(true);

pub fn set(level: f32) {
    imp::set(level.clamp(0.0, 1.0));
    DIRTY.store(true, Ordering::Relaxed);
}

pub fn get() -> Option<(f32, bool)> {
    imp::get()
}

pub fn toggle_mute() {
    imp::toggle_mute();
    DIRTY.store(true, Ordering::Relaxed);
}

/// Reports the level on connecting, right after each change from the phone, and otherwise looks
/// every two seconds.
pub fn volume_loop() {
    let mut last = String::new();
    let mut n = 0;
    loop {
        sleep(Duration::from_millis(250));
        if phone::session().is_none() || phone::phone().is_none() {
            last.clear();
            continue;
        }
        n += 1;
        if !DIRTY.swap(false, Ordering::Relaxed) && n < 8 {
            continue;
        }
        n = 0;
        let Some((level, muted)) = imp::get() else { continue };
        let now = format!("{{\"level\":{},\"muted\":{}}}", trim(level.min(1.0)), muted);
        if now == last {
            continue;
        }
        last = match phone::post_json("/api/control/volume", &now) {
            Ok(_) => now,
            Err(_) => String::new(),
        };
    }
}

/// Three decimals without trailing zeros: 0.5, not 0.500.
fn trim(v: f32) -> String {
    let s = format!("{:.3}", v);
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

#[cfg(windows)]
mod imp {
    use std::ffi::c_void;
    use std::ptr::null_mut;
    use windows_sys::core::GUID;
    use windows_sys::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_ALL};

    const CLSID_ENUMERATOR: GUID = GUID { data1: 0xBCDE0395, data2: 0xE52F, data3: 0x467C, data4: [0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E] };
    const IID_ENUMERATOR: GUID = GUID { data1: 0xA95664D2, data2: 0x9614, data3: 0x4F35, data4: [0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6] };
    const IID_ENDPOINT_VOLUME: GUID = GUID { data1: 0x5CDF2C82, data2: 0x841E, data3: 0x4546, data4: [0x97, 0x22, 0x0C, 0xF7, 0x40, 0x78, 0x22, 0x9A] };

    type Hr = i32;

    // Each interface is a pointer to a table of functions; only the slots used are named.
    #[repr(C)]
    struct Unknown {
        query: usize,
        add_ref: usize,
        release: unsafe extern "system" fn(*mut c_void) -> u32,
    }

    #[repr(C)]
    struct EnumeratorVtbl {
        base: Unknown,
        enum_endpoints: usize,
        default_endpoint: unsafe extern "system" fn(*mut c_void, i32, i32, *mut *mut c_void) -> Hr,
    }

    #[repr(C)]
    struct DeviceVtbl {
        base: Unknown,
        activate: unsafe extern "system" fn(*mut c_void, *const GUID, u32, *mut c_void, *mut *mut c_void) -> Hr,
    }

    #[repr(C)]
    struct VolumeVtbl {
        base: Unknown,
        register: usize,
        unregister: usize,
        channel_count: usize,
        set_db: usize,
        set_scalar: unsafe extern "system" fn(*mut c_void, f32, *const GUID) -> Hr,
        get_db: usize,
        get_scalar: unsafe extern "system" fn(*mut c_void, *mut f32) -> Hr,
        set_channel_db: usize,
        set_channel_scalar: usize,
        get_channel_db: usize,
        get_channel_scalar: usize,
        set_mute: unsafe extern "system" fn(*mut c_void, i32, *const GUID) -> Hr,
        get_mute: unsafe extern "system" fn(*mut c_void, *mut i32) -> Hr,
    }

    /// A COM object, released when it goes.
    struct Com(*mut c_void);

    impl Com {
        fn vtbl<T>(&self) -> &T {
            unsafe { &**(self.0 as *mut *const T) }
        }
    }

    impl Drop for Com {
        fn drop(&mut self) {
            if !self.0.is_null() {
                unsafe { (self.vtbl::<Unknown>().release)(self.0) };
            }
        }
    }

    thread_local! {
        // Once for each thread that asks: COM wants every thread to say it will use it.
        static INIT: () = unsafe { CoInitializeEx(std::ptr::null(), 0); };
    }

    /// The default speakers as they are now: a different device may be plugged in any time.
    fn endpoint() -> Option<Com> {
        INIT.with(|_| {});
        unsafe {
            let mut e: *mut c_void = null_mut();
            if CoCreateInstance(&CLSID_ENUMERATOR, null_mut(), CLSCTX_ALL, &IID_ENUMERATOR, &mut e) < 0 {
                return None;
            }
            let e = Com(e);
            let mut dev: *mut c_void = null_mut();
            // Render, multimedia.
            if (e.vtbl::<EnumeratorVtbl>().default_endpoint)(e.0, 0, 1, &mut dev) < 0 {
                return None;
            }
            let dev = Com(dev);
            let mut vol: *mut c_void = null_mut();
            if (dev.vtbl::<DeviceVtbl>().activate)(dev.0, &IID_ENDPOINT_VOLUME, CLSCTX_ALL, null_mut(), &mut vol) < 0 {
                return None;
            }
            Some(Com(vol))
        }
    }

    pub fn get() -> Option<(f32, bool)> {
        let ep = endpoint()?;
        let v = ep.vtbl::<VolumeVtbl>();
        let (mut level, mut muted) = (0f32, 0i32);
        unsafe {
            if (v.get_scalar)(ep.0, &mut level) < 0 || (v.get_mute)(ep.0, &mut muted) < 0 {
                return None;
            }
        }
        Some((level, muted != 0))
    }

    pub fn set(level: f32) {
        let Some(ep) = endpoint() else { return };
        let v = ep.vtbl::<VolumeVtbl>();
        let none = GUID { data1: 0, data2: 0, data3: 0, data4: [0; 8] };
        unsafe {
            (v.set_scalar)(ep.0, level, &none);
            // Turning it up means wanting to hear it.
            let mut muted = 0i32;
            if level > 0.0 && (v.get_mute)(ep.0, &mut muted) >= 0 && muted != 0 {
                (v.set_mute)(ep.0, 0, &none);
            }
        }
    }

    pub fn toggle_mute() {
        let Some(ep) = endpoint() else { return };
        let v = ep.vtbl::<VolumeVtbl>();
        let none = GUID { data1: 0, data2: 0, data3: 0, data4: [0; 8] };
        unsafe {
            let mut muted = 0i32;
            if (v.get_mute)(ep.0, &mut muted) >= 0 {
                (v.set_mute)(ep.0, (muted == 0) as i32, &none);
            }
        }
    }
}

#[cfg(target_os = "macos")]
mod imp {
    use std::process::Command;

    fn osa(args: &[&str]) -> Option<String> {
        let mut c = Command::new("osascript");
        for a in args {
            c.arg("-e").arg(a);
        }
        c.output().ok().filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
    }

    pub fn get() -> Option<(f32, bool)> {
        let out = osa(&["set s to get volume settings", "return (output volume of s as text) & \",\" & (output muted of s as text)"])?;
        let (v, m) = out.split_once(',')?;
        Some((v.trim().parse::<f32>().ok()? / 100.0, m.trim() == "true"))
    }

    pub fn set(level: f32) {
        osa(&[&format!("set volume output volume {}", (level * 100.0).round() as i32)]);
    }

    pub fn toggle_mute() {
        osa(&["set volume output muted not (output muted of (get volume settings))"]);
    }
}

#[cfg(all(unix, not(target_os = "macos")))]
mod imp {
    use std::process::{Command, Stdio};

    fn out(prog: &str, args: &[&str]) -> Option<String> {
        Command::new(prog).args(args).stderr(Stdio::null()).output().ok().filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
    }

    fn have(prog: &str, probe: &[&str]) -> bool {
        out(prog, probe).is_some()
    }

    /// PulseAudio or PipeWire's pulse door first, else WirePlumber's own tool.
    fn pactl() -> bool {
        have("pactl", &["info"])
    }

    pub fn get() -> Option<(f32, bool)> {
        if pactl() {
            let v = out("pactl", &["get-sink-volume", "@DEFAULT_SINK@"])?;
            let pct = v.split('%').next()?.rsplit(|c: char| !c.is_ascii_digit()).next()?.parse::<f32>().ok()?;
            let muted = out("pactl", &["get-sink-mute", "@DEFAULT_SINK@"]).map(|m| m.contains("yes")).unwrap_or(false);
            Some((pct / 100.0, muted))
        } else {
            let v = out("wpctl", &["get-volume", "@DEFAULT_AUDIO_SINK@"])?;
            let level = v.split("Volume:").nth(1)?.split_whitespace().next()?.parse::<f32>().ok()?;
            Some((level, v.contains("MUTED")))
        }
    }

    pub fn set(level: f32) {
        if pactl() {
            let _ = Command::new("pactl").args(["set-sink-volume", "@DEFAULT_SINK@", &format!("{}%", (level * 100.0).round() as i32)]).status();
            if level > 0.0 {
                let _ = Command::new("pactl").args(["set-sink-mute", "@DEFAULT_SINK@", "0"]).status();
            }
        } else {
            let _ = Command::new("wpctl").args(["set-volume", "@DEFAULT_AUDIO_SINK@", &format!("{:.3}", level)]).status();
            if level > 0.0 {
                let _ = Command::new("wpctl").args(["set-mute", "@DEFAULT_AUDIO_SINK@", "0"]).status();
            }
        }
    }

    pub fn toggle_mute() {
        if pactl() {
            let _ = Command::new("pactl").args(["set-sink-mute", "@DEFAULT_SINK@", "toggle"]).status();
        } else {
            let _ = Command::new("wpctl").args(["set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"]).status();
        }
    }
}
