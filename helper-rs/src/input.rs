//! The phone's trackpad and keyboard on this computer: Windows' SendInput (as the earlier helper),
//! elsewhere enigo (X11/Wayland's libei on Linux, Quartz events on the Mac).

pub trait Pointer: Send {
    fn move_by(&mut self, dx: i32, dy: i32);
    fn button(&mut self, which: &str, down: bool);
    /// Wheel units as Windows counts them: 120 a notch.
    fn scroll(&mut self, dy: i32, dx: i32);
    fn key(&mut self, name: &str, down: bool);
    fn type_text(&mut self, text: &str);
}

#[cfg(windows)]
mod imp {
    use super::Pointer;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;

    pub struct Win;

    fn send(i: INPUT) {
        unsafe { SendInput(1, &i, std::mem::size_of::<INPUT>() as i32) };
    }

    fn mouse(flags: u32, dx: i32, dy: i32, data: i32) {
        let mut i: INPUT = unsafe { std::mem::zeroed() };
        i.r#type = INPUT_MOUSE;
        i.Anonymous.mi = MOUSEINPUT { dx, dy, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 };
        send(i);
    }

    fn vk(name: &str) -> Option<(u16, bool)> {
        let ext = matches!(name, "left" | "up" | "right" | "down" | "del" | "insert" | "home" | "end" | "pgup" | "pgdn" | "win");
        let v = match name {
            "enter" => 0x0D, "back" => 0x08, "tab" => 0x09, "esc" => 0x1B, "space" => 0x20,
            "left" => 0x25, "up" => 0x26, "right" => 0x27, "down" => 0x28,
            "del" => 0x2E, "insert" => 0x2D, "home" => 0x24, "end" => 0x23, "pgup" => 0x21, "pgdn" => 0x22,
            "win" => 0x5B, "ctrl" => 0x11, "alt" => 0x12, "shift" => 0x10,
            "f1" => 0x70, "f2" => 0x71, "f3" => 0x72, "f4" => 0x73, "f5" => 0x74, "f6" => 0x75,
            "f7" => 0x76, "f8" => 0x77, "f9" => 0x78, "f10" => 0x79, "f11" => 0x7A, "f12" => 0x7B,
            // Media keys: they reach whatever is playing, even in the background.
            "playpause" => 0xB3, "next" => 0xB0, "prev" => 0xB1, "volup" => 0xAF, "voldown" => 0xAE, "mute" => 0xAD,
            _ => {
                // Single letters and digits, for shortcuts such as Ctrl+C.
                let mut c = name.chars();
                let (Some(ch), None) = (c.next(), c.next()) else { return None };
                if !ch.is_ascii_alphanumeric() { return None; }
                ch.to_ascii_uppercase() as u16
            }
        };
        Some((v, ext))
    }

    impl Pointer for Win {
        fn move_by(&mut self, dx: i32, dy: i32) { mouse(MOUSEEVENTF_MOVE, dx, dy, 0); }
        fn button(&mut self, which: &str, down: bool) {
            let f = match (which, down) {
                ("r", true) => MOUSEEVENTF_RIGHTDOWN, ("r", false) => MOUSEEVENTF_RIGHTUP,
                ("m", true) => MOUSEEVENTF_MIDDLEDOWN, ("m", false) => MOUSEEVENTF_MIDDLEUP,
                (_, true) => MOUSEEVENTF_LEFTDOWN, (_, false) => MOUSEEVENTF_LEFTUP,
            };
            mouse(f, 0, 0, 0);
        }
        fn scroll(&mut self, dy: i32, dx: i32) {
            if dy != 0 { mouse(MOUSEEVENTF_WHEEL, 0, 0, dy); }
            if dx != 0 { mouse(MOUSEEVENTF_HWHEEL, 0, 0, dx); }
        }
        fn key(&mut self, name: &str, down: bool) {
            let Some((v, ext)) = vk(name) else { return };
            let mut i: INPUT = unsafe { std::mem::zeroed() };
            i.r#type = INPUT_KEYBOARD;
            i.Anonymous.ki = KEYBDINPUT {
                wVk: v, wScan: 0,
                dwFlags: (if down { 0 } else { KEYEVENTF_KEYUP }) | (if ext { KEYEVENTF_EXTENDEDKEY } else { 0 }),
                time: 0, dwExtraInfo: 0,
            };
            send(i);
        }
        fn type_text(&mut self, text: &str) {
            for u in text.encode_utf16() {
                for up in [false, true] {
                    let mut i: INPUT = unsafe { std::mem::zeroed() };
                    i.r#type = INPUT_KEYBOARD;
                    i.Anonymous.ki = KEYBDINPUT { wVk: 0, wScan: u, dwFlags: KEYEVENTF_UNICODE | if up { KEYEVENTF_KEYUP } else { 0 }, time: 0, dwExtraInfo: 0 };
                    send(i);
                }
            }
        }
    }

    pub fn backend() -> Option<Box<dyn Pointer>> { Some(Box::new(Win)) }
}

#[cfg(not(windows))]
mod imp {
    use super::Pointer;
    use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};

    pub struct En(Enigo);

    fn key_of(name: &str) -> Option<Key> {
        Some(match name {
            "enter" => Key::Return, "back" => Key::Backspace, "tab" => Key::Tab, "esc" => Key::Escape, "space" => Key::Space,
            "left" => Key::LeftArrow, "up" => Key::UpArrow, "right" => Key::RightArrow, "down" => Key::DownArrow,
            "del" => Key::Delete, "home" => Key::Home, "end" => Key::End, "pgup" => Key::PageUp, "pgdn" => Key::PageDown,
            "win" => Key::Meta, "ctrl" => Key::Control, "alt" => Key::Alt, "shift" => Key::Shift,
            "f1" => Key::F1, "f2" => Key::F2, "f3" => Key::F3, "f4" => Key::F4, "f5" => Key::F5, "f6" => Key::F6,
            "f7" => Key::F7, "f8" => Key::F8, "f9" => Key::F9, "f10" => Key::F10, "f11" => Key::F11, "f12" => Key::F12,
            "playpause" => Key::MediaPlayPause, "next" => Key::MediaNextTrack, "prev" => Key::MediaPrevTrack,
            "volup" => Key::VolumeUp, "voldown" => Key::VolumeDown, "mute" => Key::VolumeMute,
            _ => {
                let mut c = name.chars();
                let (Some(ch), None) = (c.next(), c.next()) else { return None };
                if !ch.is_ascii_alphanumeric() { return None; }
                Key::Unicode(ch.to_ascii_lowercase())
            }
        })
    }

    impl Pointer for En {
        fn move_by(&mut self, dx: i32, dy: i32) { let _ = self.0.move_mouse(dx, dy, Coordinate::Rel); }
        fn button(&mut self, which: &str, down: bool) {
            let b = match which { "r" => Button::Right, "m" => Button::Middle, _ => Button::Left };
            let _ = self.0.button(b, if down { Direction::Press } else { Direction::Release });
        }
        fn scroll(&mut self, dy: i32, dx: i32) {
            // enigo counts notches, downwards positive; Windows' 120 a notch, upwards positive.
            if dy != 0 { let _ = self.0.scroll(-(dy as f32 / 120.0).round() as i32, Axis::Vertical); }
            if dx != 0 { let _ = self.0.scroll((dx as f32 / 120.0).round() as i32, Axis::Horizontal); }
        }
        fn key(&mut self, name: &str, down: bool) {
            if let Some(k) = key_of(name) {
                let _ = self.0.key(k, if down { Direction::Press } else { Direction::Release });
            }
        }
        fn type_text(&mut self, text: &str) { let _ = self.0.text(text); }
    }

    pub fn backend() -> Option<Box<dyn Pointer>> {
        Enigo::new(&Settings::default()).ok().map(|e| Box::new(En(e)) as Box<dyn Pointer>)
    }
}

pub use imp::backend;
