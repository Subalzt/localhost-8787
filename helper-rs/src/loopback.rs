//! What the computer's speakers are playing, as it plays: WASAPI's loopback capture of the default
//! output, in the device's own mix format (32-bit float, usually 48 kHz stereo). No driver and no
//! "Stereo Mix" needed. `read` hands on the bytes as they come; silence comes as zeros.
//!
//! Windows only. COM by hand, as volume.rs: the interfaces are tables of functions.

#![cfg(windows)]

use std::ffi::c_void;
use std::ptr::null_mut;
use windows_sys::core::GUID;
use windows_sys::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CoTaskMemFree, CLSCTX_ALL};

const CLSID_ENUMERATOR: GUID = GUID { data1: 0xBCDE0395, data2: 0xE52F, data3: 0x467C, data4: [0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E] };
const IID_ENUMERATOR: GUID = GUID { data1: 0xA95664D2, data2: 0x9614, data3: 0x4F35, data4: [0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6] };
const IID_AUDIO_CLIENT: GUID = GUID { data1: 0x1CB9AD4C, data2: 0xDBFA, data3: 0x4c32, data4: [0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2] };
const IID_CAPTURE_CLIENT: GUID = GUID { data1: 0xC8ADBD64, data2: 0xE71E, data3: 0x48a0, data4: [0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17] };

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

pub struct Loopback {
    client: *mut c_void,
    capture: *mut c_void,
    pub rate: u32,
    pub channels: u32,
    block_align: usize,
    pub float: bool,
}

unsafe impl Send for Loopback {}

fn check(hr: i32, what: &str) -> Result<(), String> {
    if hr < 0 { Err(format!("{} (0x{:08x})", what, hr as u32)) } else { Ok(()) }
}

impl Loopback {
    /// Starts capturing the default speakers. Use it on the thread that made it.
    pub fn new() -> Result<Loopback, String> {
        unsafe {
            CoInitializeEx(std::ptr::null(), 0);
            let mut e: *mut c_void = null_mut();
            check(CoCreateInstance(&CLSID_ENUMERATOR, null_mut(), CLSCTX_ALL, &IID_ENUMERATOR, &mut e), "no audio devices")?;
            // IMMDeviceEnumerator::GetDefaultAudioEndpoint is slot 4: render (0), multimedia (1).
            let default_endpoint: unsafe extern "system" fn(*mut c_void, i32, i32, *mut *mut c_void) -> i32 = std::mem::transmute(slot(e, 4));
            let mut dev: *mut c_void = null_mut();
            let hr = default_endpoint(e, 0, 1, &mut dev);
            release(e);
            check(hr, "no speakers")?;
            // IMMDevice::Activate is slot 3.
            let activate: unsafe extern "system" fn(*mut c_void, *const GUID, u32, *mut c_void, *mut *mut c_void) -> i32 = std::mem::transmute(slot(dev, 3));
            let mut client: *mut c_void = null_mut();
            let hr = activate(dev, &IID_AUDIO_CLIENT, CLSCTX_ALL, null_mut(), &mut client);
            release(dev);
            check(hr, "the speakers cannot be captured")?;
            // IAudioClient: Initialize 3, GetMixFormat 8, Start 10, Stop 11, GetService 14.
            let get_mix: unsafe extern "system" fn(*mut c_void, *mut *mut u8) -> i32 = std::mem::transmute(slot(client, 8));
            let mut fmt: *mut u8 = null_mut();
            check(get_mix(client, &mut fmt), "no mix format")?;
            let tag = u16::from_le_bytes([*fmt, *fmt.add(1)]);
            let channels = u16::from_le_bytes([*fmt.add(2), *fmt.add(3)]) as u32;
            let rate = u32::from_le_bytes([*fmt.add(4), *fmt.add(5), *fmt.add(6), *fmt.add(7)]);
            let block_align = u16::from_le_bytes([*fmt.add(12), *fmt.add(13)]) as usize;
            let bits = u16::from_le_bytes([*fmt.add(14), *fmt.add(15)]);
            // WAVE_FORMAT_IEEE_FLOAT, or EXTENSIBLE (which the mix format is) at 32 bits: float.
            let float = tag == 3 || (tag == 0xFFFE && bits == 32);
            // Shared, loopback, a 200 ms buffer.
            let init: unsafe extern "system" fn(*mut c_void, i32, u32, i64, i64, *const u8, *const c_void) -> i32 = std::mem::transmute(slot(client, 3));
            let hr = init(client, 0, 0x0002_0000, 2_000_000, 0, fmt, std::ptr::null());
            CoTaskMemFree(fmt as *const c_void);
            check(hr, "the speakers refused to be captured")?;
            let get_service: unsafe extern "system" fn(*mut c_void, *const GUID, *mut *mut c_void) -> i32 = std::mem::transmute(slot(client, 14));
            let mut capture: *mut c_void = null_mut();
            check(get_service(client, &IID_CAPTURE_CLIENT, &mut capture), "no capture service")?;
            let start: unsafe extern "system" fn(*mut c_void) -> i32 = std::mem::transmute(slot(client, 10));
            check(start(client), "could not start")?;
            Ok(Loopback { client, capture, rate, channels, block_align, float })
        }
    }

    /// What has been played since the last call (empty when nothing has).
    pub fn read(&self) -> Vec<u8> {
        let mut all = Vec::new();
        unsafe {
            // IAudioCaptureClient: GetBuffer 3, ReleaseBuffer 4, GetNextPacketSize 5.
            let next_size: unsafe extern "system" fn(*mut c_void, *mut u32) -> i32 = std::mem::transmute(slot(self.capture, 5));
            let get_buffer: unsafe extern "system" fn(*mut c_void, *mut *const u8, *mut u32, *mut u32, *mut u64, *mut u64) -> i32 = std::mem::transmute(slot(self.capture, 3));
            let release_buffer: unsafe extern "system" fn(*mut c_void, u32) -> i32 = std::mem::transmute(slot(self.capture, 4));
            let mut next = 0u32;
            while next_size(self.capture, &mut next) == 0 && next > 0 {
                let (mut data, mut frames, mut flags, mut pos, mut qpc) = (std::ptr::null(), 0u32, 0u32, 0u64, 0u64);
                if get_buffer(self.capture, &mut data, &mut frames, &mut flags, &mut pos, &mut qpc) != 0 {
                    break;
                }
                let n = frames as usize * self.block_align;
                if flags & 2 == 0 {
                    all.extend_from_slice(std::slice::from_raw_parts(data, n));
                } else {
                    all.resize(all.len() + n, 0); // AUDCLNT_BUFFERFLAGS_SILENT: zeros
                }
                release_buffer(self.capture, frames);
            }
        }
        all
    }
}

impl Drop for Loopback {
    fn drop(&mut self) {
        unsafe {
            let stop: unsafe extern "system" fn(*mut c_void) -> i32 = std::mem::transmute(slot(self.client, 11));
            stop(self.client);
            release(self.capture);
            release(self.client);
        }
    }
}
