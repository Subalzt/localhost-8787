//! How this computer is doing, for the phone's Laptop health (server/LaptopHealth.kt): CPU, memory,
//! each NVIDIA GPU, battery, disks and the five busiest programs, as JSON.

use crate::http::quote;
use crate::phone;
use crate::util::{json_str, machine_name};
use std::process::Command;
use std::thread::sleep;
use std::time::Duration;
use sysinfo::{Disks, ProcessesToUpdate, System};

fn num(v: &str) -> String {
    v.trim().parse::<f64>().map(|d| format!("{}", (d * 100.0).round() / 100.0)).unwrap_or_else(|_| "null".into())
}

fn gpus() -> Vec<String> {
    let mut c = Command::new("nvidia-smi");
    c.args(["--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw", "--format=csv,noheader,nounits"]);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000);
    }
    let Ok(o) = c.output() else { return Vec::new() };
    String::from_utf8_lossy(&o.stdout).lines().filter_map(|l| {
        let f: Vec<&str> = l.split(',').collect();
        (f.len() >= 6).then(|| format!("{{\"name\":{},\"use\":{},\"memUsed\":{},\"memTotal\":{},\"temp\":{},\"power\":{}}}",
            json_str(f[0].trim()), num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5])))
    }).collect()
}

#[cfg(windows)]
fn battery() -> Option<String> {
    use windows_sys::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
    let mut s: SYSTEM_POWER_STATUS = unsafe { std::mem::zeroed() };
    if unsafe { GetSystemPowerStatus(&mut s) } == 0 || s.BatteryFlag == 128 || s.BatteryLifePercent > 100 {
        return None;
    }
    let minutes = if s.BatteryLifeTime == u32::MAX { -1 } else { (s.BatteryLifeTime / 60) as i64 };
    Some(format!("{{\"percent\":{},\"plugged\":{},\"charging\":{},\"minutes\":{}}}",
        s.BatteryLifePercent, s.ACLineStatus == 1, s.BatteryFlag & 8 != 0, minutes))
}

#[cfg(not(windows))]
fn battery() -> Option<String> {
    // Linux's power supplies.
    let dir = std::fs::read_dir("/sys/class/power_supply").ok()?;
    for e in dir.flatten() {
        let p = e.path();
        let read = |n: &str| std::fs::read_to_string(p.join(n)).map(|s| s.trim().to_string()).unwrap_or_default();
        if read("type") == "Battery" {
            let status = read("status");
            return Some(format!("{{\"percent\":{},\"plugged\":{},\"charging\":{},\"minutes\":-1}}",
                read("capacity").parse::<i32>().unwrap_or(0), matches!(status.as_str(), "Charging" | "Full" | "Not charging"), status == "Charging"));
        }
    }
    None
}

pub fn snapshot() -> String {
    let mut sys = System::new();
    sys.refresh_cpu_usage();
    sys.refresh_processes(ProcessesToUpdate::All, true);
    // CPU use, and each program's, over a third of a second.
    sleep(Duration::from_millis(330));
    sys.refresh_cpu_usage();
    sys.refresh_processes(ProcessesToUpdate::All, true);
    sys.refresh_memory();
    let cores = sys.cpus().len().max(1);
    let name = sys.cpus().first().map(|c| c.brand().trim().to_string()).unwrap_or_default();
    let mut top: Vec<(f32, String)> = sys.processes().values().map(|p| {
        let share = p.cpu_usage() / cores as f32;
        (share, format!("{{\"name\":{},\"cpu\":{:.1},\"mem\":{}}}", json_str(&p.name().to_string_lossy().trim_end_matches(".exe").to_string()), share, p.memory()))
    }).filter(|(s, _)| *s >= 0.5).collect();
    top.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    let disks: Vec<String> = Disks::new_with_refreshed_list().iter().filter(|d| !d.is_removable() && d.total_space() > 0).map(|d| {
        let mount = d.mount_point().to_string_lossy().trim_end_matches('\\').to_string();
        let label = d.name().to_string_lossy().into_owned();
        let name = if label.is_empty() || label == mount { mount } else { format!("{} {}", mount, label) };
        format!("{{\"name\":{},\"used\":{},\"total\":{}}}", json_str(&name), d.total_space() - d.available_space(), d.total_space())
    }).collect();
    let mut b = format!("{{\"name\":{},\"os\":{},\"uptime\":{}", json_str(&machine_name()),
        json_str(&System::long_os_version().unwrap_or_default()), System::uptime());
    b += &format!(",\"cpu\":{{\"use\":{:.1},\"name\":{},\"cores\":{}}}", sys.global_cpu_usage(), json_str(&name), cores);
    b += &format!(",\"top\":[{}]", top.iter().take(5).map(|t| t.1.clone()).collect::<Vec<_>>().join(","));
    b += &format!(",\"mem\":{{\"used\":{},\"total\":{}}}", sys.used_memory(), sys.total_memory());
    b += &format!(",\"gpus\":[{}],\"temps\":[]", gpus().join(","));
    if let Some(bat) = battery() {
        b += &format!(",\"battery\":{}", bat);
    }
    b += &format!(",\"disks\":[{}]}}", disks.join(","));
    b
}

pub fn answer(id: &str) {
    let _ = phone::post_json(&format!("/api/laptop/health/answer?id={}", quote(id)), &snapshot());
}
