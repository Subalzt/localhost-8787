//! Where this computer is, for the map of your phones and laptops (the phone's server/Where.kt):
//! Windows' own position (Wi-Fi, and GPS where there is one), told to the phone when it moves 10 m,
//! and every 10 minutes when it does not. Needs Location on in Windows' settings, with "Let desktop
//! apps access your location"; said once when it is off. Windows only.

#[cfg(windows)]
pub fn where_loop() {
    use crate::phone;
    use crate::util::{log, now_ms, say};
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    use std::time::{Duration, Instant};
    use windows_sys::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};

    const SCRIPT: &str = r#"
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Device
$w = New-Object System.Device.Location.GeoCoordinateWatcher([System.Device.Location.GeoPositionAccuracy]::High)
$w.MovementThreshold = 5
[void]$w.TryStart($false, [TimeSpan]::FromSeconds(30))
$end = [DateTime]::UtcNow.AddSeconds(25)
while ($w.Status -ne 'Ready' -and $w.Status -ne 'Disabled' -and [DateTime]::UtcNow -lt $end) { Start-Sleep -Milliseconds 300 }
$inv = [Globalization.CultureInfo]::InvariantCulture
$c = $w.Position.Location
if ($w.Status -eq 'Disabled' -or $w.Permission -eq 'Denied') { 'OFF' }
elseif ($c.IsUnknown) { 'UNKNOWN' }
else { 'FIX|' + $c.Latitude.ToString('R', $inv) + '|' + $c.Longitude.ToString('R', $inv) + '|' + $c.HorizontalAccuracy.ToString('R', $inv) + '|' + $w.Position.Timestamp.ToUnixTimeMilliseconds() }
"#;

    fn metres(a: (f64, f64), b: (f64, f64)) -> f64 {
        let (r, p1, p2) = (6_371_000.0, a.0.to_radians(), b.0.to_radians());
        let (dp, dl) = ((b.0 - a.0).to_radians(), (b.1 - a.1).to_radians());
        let h = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
        2.0 * r * h.sqrt().asin()
    }

    fn battery() -> i32 {
        let mut ps: SYSTEM_POWER_STATUS = unsafe { std::mem::zeroed() };
        if unsafe { GetSystemPowerStatus(&mut ps) } == 0 || ps.BatteryFlag == 128 || ps.BatteryFlag == 255 || ps.BatteryLifePercent > 100 {
            -1
        } else {
            ps.BatteryLifePercent as i32
        }
    }

    std::thread::sleep(Duration::from_secs(15));
    let utf16: Vec<u8> = SCRIPT.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
    let enc = {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(utf16)
    };
    let (mut told, mut last, mut last_sent): (bool, Option<(f64, f64)>, Option<Instant>) = (false, None, None);
    loop {
        if phone::phone().is_some() && phone::session().is_some() {
            let out = Command::new("powershell.exe")
                .args(["-NoProfile", "-NonInteractive", "-EncodedCommand", &enc])
                .creation_flags(0x0800_0000)
                .output()
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                .unwrap_or_default();
            if out == "OFF" {
                if !told {
                    say("To show this computer on the map, turn on Location in Windows' Settings (Privacy & security, Location), with \"Let desktop apps access your location\".");
                }
                told = true;
            } else if let Some(f) = out.strip_prefix("FIX|") {
                let p: Vec<&str> = f.split('|').collect();
                if let (Some(Ok(lat)), Some(Ok(lon))) = (p.first().map(|s| s.parse::<f64>()), p.get(1).map(|s| s.parse::<f64>())) {
                    let acc = p.get(2).and_then(|s| s.parse::<f64>().ok()).filter(|a| a.is_finite()).unwrap_or(100.0).max(1.0);
                    let at = p.get(3).and_then(|s| s.parse::<u64>().ok()).unwrap_or_else(now_ms);
                    let due = last.map_or(true, |l| metres(l, (lat, lon)) > 10.0) || last_sent.map_or(true, |t| t.elapsed() >= Duration::from_secs(600));
                    if due {
                        let body = format!("{{\"lat\":{},\"lon\":{},\"acc\":{:.1},\"at\":{},\"battery\":{}}}", lat, lon, acc, at, battery());
                        match phone::post_json("/api/where/laptop", &body) {
                            Ok(_) => {
                                last = Some((lat, lon));
                                last_sent = Some(Instant::now());
                                told = false;
                            }
                            Err(e) => log(&format!("Where this computer is, to the phone: {}", e)),
                        }
                    }
                }
            }
        }
        std::thread::sleep(Duration::from_secs(60));
    }
}

#[cfg(not(windows))]
pub fn where_loop() {}
