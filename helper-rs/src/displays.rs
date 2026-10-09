//! Windows' monitors, for the second screen: which are on the desktop (and which is the virtual
//! display a driver such as MTT's Virtual Display Driver adds), extending the desktop onto the
//! virtual display and taking it off again, its size and refresh rate, HDR, and which graphics
//! adapter and output show a monitor (what ffmpeg's ddagrab needs to capture exactly it).
//!
//! The monitor list is read from Windows every time: it can change under a running stream.

#![cfg(windows)]

use std::ffi::c_void;
use std::thread::sleep;
use std::time::Duration;
use windows_sys::core::GUID;
use windows_sys::Win32::Devices::Display::*;
#[link(name = "dxgi")]
extern "system" {
    fn CreateDXGIFactory1(riid: *const GUID, factory: *mut *mut c_void) -> i32;
}
use windows_sys::Win32::Graphics::Gdi::*;

#[derive(Clone, Debug, PartialEq, Default)]
pub struct Mon {
    pub device: String,
    pub primary: bool,
    /// The virtual display on its own.
    pub virt: bool,
    /// A real screen the virtual one duplicates.
    pub cloned: bool,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    pub hz: i32,
}

const ATTACHED: u32 = 0x1;
const PRIMARY: u32 = 0x4;
const MIRRORING: u32 = 0x8;
const MONITOR_ACTIVE: u32 = 0x1;
const CURRENT: u32 = 0xFFFF_FFFF; // ENUM_CURRENT_SETTINGS
const REGISTRY: u32 = 0xFFFF_FFFE; // ENUM_REGISTRY_SETTINGS
const DM_POSITION: u32 = 0x20;
const DM_PELSWIDTH: u32 = 0x80000;
const DM_PELSHEIGHT: u32 = 0x100000;
const DM_DISPLAYFREQUENCY: u32 = 0x400000;
const CDS_UPDATEREGISTRY: u32 = 0x1;
const CDS_NORESET: u32 = 0x10000000;

/// What virtual-display drivers call themselves: IddSample and its forks, Parsec's, spacedesk's, Amyuni's.
const VIRTUAL_NAMES: [&str; 7] = ["virtual", "indirect", "iddsample", "mtt1337", "parsec", "spacedesk", "usbmmidd"];

fn is_virtual(s: &str) -> bool {
    let s = s.to_lowercase();
    !s.is_empty() && VIRTUAL_NAMES.iter().any(|v| s.contains(v))
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn text(w: &[u16]) -> String {
    let n = w.iter().position(|c| *c == 0).unwrap_or(w.len());
    String::from_utf16_lossy(&w[..n])
}

fn new_device() -> DISPLAY_DEVICEW {
    let mut d: DISPLAY_DEVICEW = unsafe { std::mem::zeroed() };
    d.cb = std::mem::size_of::<DISPLAY_DEVICEW>() as u32;
    d
}

fn new_mode() -> DEVMODEW {
    let mut m: DEVMODEW = unsafe { std::mem::zeroed() };
    m.dmSize = std::mem::size_of::<DEVMODEW>() as u16;
    m
}

fn position(dm: &DEVMODEW) -> (i32, i32) {
    unsafe {
        let p = dm.Anonymous1.Anonymous2.dmPosition;
        (p.x, p.y)
    }
}

fn set_position(dm: &mut DEVMODEW, x: i32, y: i32) {
    unsafe {
        dm.Anonymous1.Anonymous2.dmPosition.x = x;
        dm.Anonymous1.Anonymous2.dmPosition.y = y;
    }
}

/// The adapter's outputs, by index; `device` None is the list of adapters.
fn enum_device(device: Option<&str>, i: u32) -> Option<DISPLAY_DEVICEW> {
    let mut d = new_device();
    let name = device.map(wide);
    let ok = unsafe { EnumDisplayDevicesW(name.as_ref().map_or(std::ptr::null(), |n| n.as_ptr()), i, &mut d, 0) };
    (ok != 0).then_some(d)
}

fn settings(device: &str, mode: u32) -> Option<DEVMODEW> {
    let mut dm = new_mode();
    (unsafe { EnumDisplaySettingsW(wide(device).as_ptr(), mode, &mut dm) } != 0).then_some(dm)
}

/// The monitors on an output (only those showing a picture, if `active`): whether any is virtual,
/// and whether any is real. Duplicated screens are one output with two monitors.
fn monitors_of(output: &str, active: bool) -> (bool, bool) {
    let (mut virt, mut real) = (false, false);
    for j in 0..8 {
        let Some(m) = enum_device(Some(output), j) else { break };
        if active && m.StateFlags & MONITOR_ACTIVE == 0 {
            continue;
        }
        if is_virtual(&text(&m.DeviceID)) || is_virtual(&text(&m.DeviceString)) { virt = true } else { real = true }
    }
    (virt, real)
}

/// Every monitor that is part of the desktop now.
pub fn attached() -> Vec<Mon> {
    let mut list = Vec::new();
    for i in 0..64 {
        let Some(a) = enum_device(None, i) else { break };
        if a.StateFlags & ATTACHED == 0 || a.StateFlags & MIRRORING != 0 {
            continue;
        }
        let name = text(&a.DeviceName);
        let Some(dm) = settings(&name, CURRENT).filter(|d| d.dmPelsWidth > 0) else { continue };
        let (virt, real) = monitors_of(&name, true);
        let (x, y) = position(&dm);
        list.push(Mon {
            device: name,
            primary: a.StateFlags & PRIMARY != 0,
            // An output showing a real screen and the virtual one is Windows' "Duplicate": not a second screen.
            virt: is_virtual(&text(&a.DeviceString)) || (virt && !real),
            cloned: virt && real,
            x,
            y,
            w: dm.dmPelsWidth as i32,
            h: dm.dmPelsHeight as i32,
            hz: dm.dmDisplayFrequency as i32,
        });
    }
    list
}

/// Whether a virtual-display driver is installed, on the desktop or not.
pub fn virtual_installed() -> bool {
    for i in 0..64 {
        let Some(a) = enum_device(None, i) else { break };
        let (virt, _) = monitors_of(&text(&a.DeviceName), false);
        if is_virtual(&text(&a.DeviceString)) || virt {
            return true;
        }
    }
    false
}

/// A virtual display that is installed, with its monitor, but not on the desktop.
pub fn detached_virtual() -> Option<String> {
    for i in 0..64 {
        let Some(a) = enum_device(None, i) else { break };
        if a.StateFlags & (ATTACHED | MIRRORING) != 0 {
            continue;
        }
        let name = text(&a.DeviceName);
        let (virt, real) = monitors_of(&name, false);
        if virt || (is_virtual(&text(&a.DeviceString)) && real) {
            return Some(name);
        }
    }
    None
}

/// Windows+P's "Extend": every connected screen, the virtual one included, becomes part of one desktop.
pub fn extend() -> bool {
    unsafe { SetDisplayConfig(0, std::ptr::null(), 0, std::ptr::null(), SDC_TOPOLOGY_EXTEND | SDC_APPLY) == 0 }
}

/// Windows+P's "Duplicate".
pub fn duplicate() -> bool {
    unsafe { SetDisplayConfig(0, std::ptr::null(), 0, std::ptr::null(), SDC_TOPOLOGY_CLONE | SDC_APPLY) == 0 }
}

/// The whole desktop, all monitors together.
pub fn desktop(mons: &[Mon]) -> Mon {
    let mut d = Mon::default();
    if mons.is_empty() {
        return d;
    }
    let (mut l, mut t, mut r, mut b) = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
    for m in mons {
        l = l.min(m.x);
        t = t.min(m.y);
        r = r.max(m.x + m.w);
        b = b.max(m.y + m.h);
    }
    d.x = l;
    d.y = t;
    d.w = r - l;
    d.h = b - t;
    d
}

/// Of the display's own sizes, the one that suits the phone (w x h, landscape): the nearest shape
/// first, so the phone is filled, then the nearest height, so it is sharp but not tiny.
fn best_size(dev: &str, w: i32, h: i32) -> (i32, i32) {
    let want = if h > 0 { w as f64 / h as f64 } else { 16.0 / 9.0 };
    let (mut best, mut pick) = (f64::MAX, (0, 0));
    for i in 0..1000 {
        let Some(dm) = settings(dev, i) else { break };
        let (mw, mh) = (dm.dmPelsWidth as i32, dm.dmPelsHeight as i32);
        if mw < 640 || mh < 360 || mw > 3840 || mh > 2160 || mw < mh {
            continue; // the phone decodes up to 4K
        }
        let score = (mw as f64 / mh as f64 / want).ln().abs() * 4.0 + (mh as f64 / (h.max(360)) as f64).ln().abs();
        if score < best {
            best = score;
            pick = (mw, mh);
        }
    }
    if pick.0 > 0 {
        return pick;
    }
    match settings(dev, REGISTRY).filter(|d| d.dmPelsWidth > 0) {
        Some(r) => (r.dmPelsWidth as i32, r.dmPelsHeight as i32),
        None => (1920, 1080),
    }
}

fn apply() -> bool {
    unsafe { ChangeDisplaySettingsExW(std::ptr::null(), std::ptr::null(), std::ptr::null_mut(), 0, std::ptr::null()) == 0 }
}

/// A monitor's modes, each (width, height, refresh rate), once each.
pub fn modes(dev: &str) -> Vec<(i32, i32, i32)> {
    let mut list: Vec<(i32, i32, i32)> = Vec::new();
    for i in 0..5000 {
        let Some(dm) = settings(dev, i) else { break };
        let m = (dm.dmPelsWidth as i32, dm.dmPelsHeight as i32, dm.dmDisplayFrequency as i32);
        if dm.dmBitsPerPel >= 24 && !list.contains(&m) {
            list.push(m);
        }
    }
    list
}

fn change(dev: &str, dm: &DEVMODEW) -> bool {
    let name = wide(dev);
    unsafe { ChangeDisplaySettingsExW(name.as_ptr(), dm, std::ptr::null_mut(), CDS_UPDATEREGISTRY | CDS_NORESET, std::ptr::null()) == 0 && apply() }
}

/// Sets a monitor's size and refresh rate, keeping its place.
pub fn set_mode(dev: &str, w: i32, h: i32, hz: i32) -> bool {
    let Some(mut dm) = settings(dev, CURRENT) else { return false };
    dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY;
    dm.dmPelsWidth = w as u32;
    dm.dmPelsHeight = h as u32;
    dm.dmDisplayFrequency = hz as u32;
    change(dev, &dm)
}

/// Puts a display on the desktop, extended to the right of the others, at a size suiting the phone.
pub fn attach(dev: &str, mons: &[Mon], w: i32, h: i32) -> bool {
    let (bw, bh) = best_size(dev, w, h);
    let (mut right, mut top) = (0, 0);
    for m in mons {
        right = right.max(m.x + m.w);
        if m.primary {
            top = m.y;
        }
    }
    let mut dm = new_mode();
    dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT;
    set_position(&mut dm, right, top);
    dm.dmPelsWidth = bw as u32;
    dm.dmPelsHeight = bh as u32;
    change(dev, &dm)
}

/// Takes a display off the desktop; Windows moves its windows onto the others.
pub fn detach(dev: &str) -> bool {
    let mons = attached();
    let Some(m) = mons.iter().find(|x| x.device == dev) else { return false };
    if m.primary {
        match mons.iter().find(|x| x.device != dev) {
            Some(other) if make_primary(&other.device, &mons) => {}
            _ => return false,
        }
    }
    let mut dm = new_mode();
    dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT; // all zero: off the desktop
    change(dev, &dm)
}

/// Makes a monitor the main one (the taskbar, new windows). The main monitor is the one at 0,0, so
/// every monitor moves by the same amount. Windows' current layout is changed as it stands
/// (DISPLAYCONFIG_PATH_INFO is 72 bytes, DISPLAYCONFIG_MODE_INFO 64; a source mode, type 1, holds
/// width, height and position at 16, 20, 28 and 32) and saved as this layout's own. The older
/// ChangeDisplaySettingsEx refuses to move the main monitor here.
pub fn make_primary(dev: &str, mons: &[Mon]) -> bool {
    let Some(np) = mons.iter().find(|x| x.device == dev) else { return false };
    if np.x == 0 && np.y == 0 {
        return true;
    }
    const ACTIVE_ONLY: u32 = 2;
    let (mut npaths, mut nmodes) = (0u32, 0u32);
    unsafe {
        if GetDisplayConfigBufferSizes(ACTIVE_ONLY, &mut npaths, &mut nmodes) != 0 {
            return false;
        }
        let mut paths = vec![0u8; npaths as usize * 72];
        let mut modes = vec![0u8; nmodes as usize * 64];
        if QueryDisplayConfig(ACTIVE_ONLY, &mut npaths, paths.as_mut_ptr() as *mut _, &mut nmodes, modes.as_mut_ptr() as *mut _, std::ptr::null_mut()) != 0 {
            return false;
        }
        let mut found = false;
        for i in 0..nmodes as usize {
            let o = i * 64;
            if u32::from_le_bytes(modes[o..o + 4].try_into().unwrap()) != 1 {
                continue; // source modes only
            }
            let rd = |at: usize| i32::from_le_bytes(modes[o + at..o + at + 4].try_into().unwrap());
            let (x, y) = (rd(28), rd(32));
            if x == np.x && y == np.y && rd(16) == np.w {
                found = true;
            }
            modes[o + 28..o + 32].copy_from_slice(&(x - np.x).to_le_bytes());
            modes[o + 32..o + 36].copy_from_slice(&(y - np.y).to_le_bytes());
        }
        if !found {
            return false;
        }
        const USE_SUPPLIED: u32 = 0x20;
        const SAVE: u32 = 0x200;
        const ALLOW_CHANGES: u32 = 0x400;
        SetDisplayConfig(npaths, paths.as_ptr() as *const _, nmodes, modes.as_ptr() as *const _, SDC_APPLY | USE_SUPPLIED | SAVE | ALLOW_CHANGES) == 0
    }
}

// ---- HDR

/// The target (adapter LUID and id, 12 bytes) of the path showing a monitor, for the HDR calls.
fn target_of(dev: &str) -> Option<[u8; 12]> {
    const ACTIVE_ONLY: u32 = 2;
    let (mut np, mut nm) = (0u32, 0u32);
    unsafe {
        if GetDisplayConfigBufferSizes(ACTIVE_ONLY, &mut np, &mut nm) != 0 {
            return None;
        }
        let mut paths = vec![0u8; np as usize * 72];
        let mut modes = vec![0u8; nm as usize * 64];
        if QueryDisplayConfig(ACTIVE_ONLY, &mut np, paths.as_mut_ptr() as *mut _, &mut nm, modes.as_mut_ptr() as *mut _, std::ptr::null_mut()) != 0 {
            return None;
        }
        for i in 0..np as usize {
            let o = i * 72;
            let mut name = packet(1, 84, &paths[o + 20..o + 32]); // the source's GDI name
            if DisplayConfigGetDeviceInfo(name.as_mut_ptr() as *mut _) != 0 {
                continue;
            }
            let units: Vec<u16> = name[20..84].chunks(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
            if !text(&units).eq_ignore_ascii_case(dev) {
                continue;
            }
            let mut t = [0u8; 12];
            t.copy_from_slice(&paths[o + 20..o + 32]);
            return Some(t);
        }
    }
    None
}

/// A packet for DisplayConfig*DeviceInfo: type and size, then an adapter LUID and id.
fn packet(kind: i32, size: usize, target: &[u8]) -> Vec<u8> {
    let mut p = vec![0u8; size];
    p[0..4].copy_from_slice(&kind.to_le_bytes());
    p[4..8].copy_from_slice(&(size as i32).to_le_bytes());
    p[8..20].copy_from_slice(&target[..12]);
    p
}

/// Whether Windows' HDR ("Use HDR") is on for a monitor.
pub fn hdr_on(dev: &str) -> bool {
    let Some(t) = target_of(dev) else { return false };
    let mut info = packet(9, 32, &t); // GET_ADVANCED_COLOR_INFO
    unsafe { DisplayConfigGetDeviceInfo(info.as_mut_ptr() as *mut _) == 0 && u32::from_le_bytes(info[20..24].try_into().unwrap()) & 2 != 0 }
}

/// The brightness Windows gives ordinary (SDR) white on a monitor in HDR, in nits: the "SDR content
/// brightness" slider. 80 when it cannot be read (the slider's lowest).
pub fn sdr_white_nits(dev: &str) -> f64 {
    let Some(t) = target_of(dev) else { return 80.0 };
    let mut w = packet(11, 24, &t); // GET_SDR_WHITE_LEVEL, in thousandths of 80 nits
    if unsafe { DisplayConfigGetDeviceInfo(w.as_mut_ptr() as *mut _) } != 0 {
        return 80.0;
    }
    let level = u32::from_le_bytes(w[20..24].try_into().unwrap());
    if level > 0 { level as f64 * 80.0 / 1000.0 } else { 80.0 }
}

/// Turns Windows' HDR on or off for a monitor, as its switch in Settings does. Tried again for a
/// couple of seconds: just after a change of mode or layout, Windows can refuse or quietly drop it.
pub fn set_hdr(dev: &str, on: bool) -> bool {
    for attempt in 0..4 {
        if attempt > 0 {
            sleep(Duration::from_millis(500));
        }
        let Some(t) = target_of(dev) else { continue };
        let mut set = packet(10, 24, &t); // SET_ADVANCED_COLOR_STATE
        set[20..24].copy_from_slice(&(on as i32).to_le_bytes());
        if unsafe { DisplayConfigSetDeviceInfo(set.as_mut_ptr() as *mut _) } != 0 {
            continue;
        }
        for _ in 0..20 {
            if hdr_on(dev) == on {
                break;
            }
            sleep(Duration::from_millis(100));
        }
        if hdr_on(dev) == on {
            return true;
        }
    }
    false
}

// ---- which graphics adapter shows a monitor (DXGI), by hand: vtable slots, no wrappers

const IID_FACTORY1: GUID = GUID { data1: 0x770aae78, data2: 0xf26f, data3: 0x4dba, data4: [0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87] };

type Slot = *const usize;

unsafe fn slot(obj: *mut c_void, i: usize) -> usize {
    *((*(obj as *const Slot)).add(i))
}

unsafe fn release(obj: *mut c_void) {
    if !obj.is_null() {
        let f: unsafe extern "system" fn(*mut c_void) -> u32 = std::mem::transmute(slot(obj, 2));
        f(obj);
    }
}

/// Which adapter, and which output on it, shows a monitor ("\\.\DISPLAY2"), and the adapter's
/// name: what the GPU screen capture (Desktop Duplication, ffmpeg's ddagrab) needs. The order of
/// Windows' monitor list is not the adapters' order, so it is looked up by name.
pub fn dxgi_find(device: &str) -> Option<(u32, u32, String)> {
    unsafe {
        let mut factory: *mut c_void = std::ptr::null_mut();
        if CreateDXGIFactory1(&IID_FACTORY1, &mut factory) != 0 || factory.is_null() {
            return None;
        }
        let mut found = None;
        'adapters: for a in 0..16u32 {
            // IDXGIFactory1::EnumAdapters1 is slot 12.
            let enum_adapters: unsafe extern "system" fn(*mut c_void, u32, *mut *mut c_void) -> i32 = std::mem::transmute(slot(factory, 12));
            let mut adapter: *mut c_void = std::ptr::null_mut();
            if enum_adapters(factory, a, &mut adapter) != 0 {
                break;
            }
            // IDXGIAdapter::GetDesc is slot 8: DXGI_ADAPTER_DESC, the description first (128 wide characters).
            let get_desc: unsafe extern "system" fn(*mut c_void, *mut u8) -> i32 = std::mem::transmute(slot(adapter, 8));
            let mut desc = [0u8; 512];
            get_desc(adapter, desc.as_mut_ptr());
            let units: Vec<u16> = desc[..256].chunks(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
            let adapter_name = text(&units);
            for o in 0..16u32 {
                // IDXGIAdapter::EnumOutputs is slot 7.
                let enum_outputs: unsafe extern "system" fn(*mut c_void, u32, *mut *mut c_void) -> i32 = std::mem::transmute(slot(adapter, 7));
                let mut output: *mut c_void = std::ptr::null_mut();
                if enum_outputs(adapter, o, &mut output) != 0 {
                    break;
                }
                // IDXGIOutput::GetDesc is slot 7: DXGI_OUTPUT_DESC, the device name first (32 wide characters).
                let out_desc: unsafe extern "system" fn(*mut c_void, *mut u8) -> i32 = std::mem::transmute(slot(output, 7));
                let mut od = [0u8; 128];
                out_desc(output, od.as_mut_ptr());
                let units: Vec<u16> = od[..64].chunks(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
                release(output);
                if text(&units).eq_ignore_ascii_case(device) {
                    found = Some((a, o, adapter_name.clone()));
                    release(adapter);
                    break 'adapters;
                }
            }
            release(adapter);
        }
        release(factory);
        found
    }
}

/// This thread works in real pixels (per-monitor v2) until `restore_dpi`; returns what it was.
pub fn real_pixels() -> isize {
    unsafe { windows_sys::Win32::UI::HiDpi::SetThreadDpiAwarenessContext(-4isize as _) as isize }
}

pub fn restore_dpi(was: isize) {
    if was != 0 {
        unsafe { windows_sys::Win32::UI::HiDpi::SetThreadDpiAwarenessContext(was as _) };
    }
}
