//! Keeps the computer from going to sleep while the helper runs (plugged in, or on a battery above
//! a quarter), so the phone reaches it from anywhere, a lecture hall included. The screen may
//! still turn off. Closing the lid still does what Windows is set to do.
//!
//! Windows only for now; elsewhere the system's own settings decide.

#[cfg(windows)]
pub fn awake_loop() {
    use crate::util::log;
    use std::time::Duration;
    use windows_sys::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
    use windows_sys::Win32::System::Power::SetThreadExecutionState;
    const CONTINUOUS: u32 = 0x8000_0000;
    const SYSTEM: u32 = 0x1;
    let mut last = 0u32;
    loop {
        let mut ps: SYSTEM_POWER_STATUS = unsafe { std::mem::zeroed() };
        let power = unsafe { GetSystemPowerStatus(&mut ps) } == 0 || ps.ACLineStatus != 0 || ps.BatteryLifePercent >= 25;
        let want = CONTINUOUS | if power { SYSTEM } else { 0 };
        if want != last {
            unsafe { SetThreadExecutionState(want) };
            if (want & SYSTEM) != (last & SYSTEM) || last == 0 {
                log(if power { "Keeping the computer awake for the phone." } else { "On a low battery: the computer may sleep again." });
            }
            last = want;
        }
        std::thread::sleep(Duration::from_secs(10));
    }
}

#[cfg(not(windows))]
pub fn awake_loop() {}
