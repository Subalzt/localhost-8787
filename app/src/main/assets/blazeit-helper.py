#!/usr/bin/env python3
"""
Localhost 8787, the laptop helper for Linux and macOS.

    python3 blazeit-linux.py        (or, on a Mac, python3 blazeit-mac.py: the same file)

Nothing is installed. Keep the terminal open; Ctrl+C (or closing it) stops the helper, and
starting it again replaces a copy already running. It does what the Windows helper does:

- finds the phone, on the fastest way there is (a USB cable with USB tethering, the phone's
  hotspot or direct link, the Wi-Fi both are on, and last the cable's USB debugging), and
  moves to a faster one when it appears;
- serves the page at http://localhost:8787, so its address never changes and downloads use
  every connection;
- keeps this computer's clipboard and the phone's in step: text, pictures, one file;
- lets the phone's Control tab drive this computer: trackpad, keyboard, media keys, volume;
- joins the phone's direct link or hotspot when the phone offers it (NetworkManager; on a Mac,
  join it from the Wi-Fi menu and the helper follows);
- shows this computer's screen on the phone, as a second screen (ffmpeg);
- opens the phone's screen here when the page asks (scrcpy);
- tells the phone's Monitor about this computer's side of the link.

Only Python 3 is required. The rest uses what the system has, and the helper says what is
missing when something needs it: wl-clipboard (Wayland) or xclip / xsel (X11) for the clipboard;
write access to /dev/uinput, or xdotool (X11), or ydotool, for the trackpad and keyboard; pactl or
wpctl for the volume; nmcli for the direct link; iw for Wi-Fi details; adb and scrcpy for the
phone's screen and the USB debugging fallback.

On a Mac it uses what macOS has: pbcopy and pbpaste (and AppKit, through JavaScript for Automation)
for the clipboard, Quartz events for the trackpad and keyboard (allow Accessibility for the app it
runs in: Terminal, say), AppleScript for the volume, and from Homebrew, ffmpeg for the second screen
(allow Screen Recording) and scrcpy for the phone's screen.

Options: --phone ADDRESS (skip the search), --no-browser (do not open the page), --name NAME.
"""

import argparse
import fcntl
import hashlib
import http.client
import json
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.parse

PHONE_PORT = 8787
DISCOVERY_PORT = 8788
LOCAL_PORTS = (8787, 8797, 8807)
ADB_FORWARD_PORT = 18787
CLIP_MAX_BYTES = 50 * 1024 * 1024
TEXT_MAX = 200000

MAC = sys.platform == "darwin"
SYSTEM = "Mac" if MAC else "Linux"

# Kept in the user's config folder: the pairing, the phone's last address, the log.
if MAC:
    CONF = os.path.expanduser("~/Library/Application Support/localhost-8787")
    CACHE = os.path.expanduser("~/Library/Caches/localhost-8787")
else:
    CONF = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "localhost-8787")
    CACHE = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "localhost-8787")


def is_wsl():
    try:
        with open("/proc/sys/kernel/osrelease") as f:
            return "microsoft" in f.read().lower()
    except OSError:
        return False


WSL = is_wsl()


def machine_name():
    # Under WSL the host name is the Windows machine's, which its own helper already uses.
    if WSL:
        return "Linux"
    if MAC:
        # The name the Mac shows in Sharing ("Periy's MacBook Air"), not its network host name.
        try:
            out = subprocess.run(["scutil", "--get", "ComputerName"], stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, timeout=5).stdout.decode("utf-8", "replace").strip()
        except (OSError, subprocess.SubprocessError):
            out = ""
        return out or socket.gethostname().split(".")[0] or "Mac"
    try:
        with open("/proc/sys/kernel/hostname") as f:
            name = f.read().strip()
    except OSError:
        name = socket.gethostname()
    return name or "Linux"


NAME = machine_name()


def user_agent():
    # The phone names a helper from this: "Laptop control on NAME (Linux)", or "(Mac)", shown as NAME.
    return "BlazeItPC/1 (%s; %s)" % (NAME, SYSTEM)


# ---------------------------------------------------------------------- saying and logging

log_lock = threading.Lock()


def log(s):
    try:
        with log_lock:
            os.makedirs(CONF, exist_ok=True)
            f = os.path.join(CONF, "helper.log")
            if os.path.exists(f) and os.path.getsize(f) > 512 * 1024:
                os.remove(f)
            with open(f, "a", encoding="utf-8") as out:
                out.write(time.strftime("%Y-%m-%d %H:%M:%S") + "  " + s + "\n")
    except OSError:
        pass


def say(s):
    print(time.strftime("%H:%M:%S") + "  " + s, flush=True)
    log(s)


said_once = set()


def say_once(key, s):
    if key in said_once:
        return
    said_once.add(key)
    say(s)


def run(args, input=None, timeout=5):
    """A command's output as bytes, or None when it is missing or fails."""
    try:
        r = subprocess.run(args, input=input, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
        return r.stdout if r.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def run_text(args, timeout=5):
    out = run(args, timeout=timeout)
    return out.decode("utf-8", "replace") if out is not None else None


def have(tool):
    return shutil.which(tool) is not None


def install_hint(package):
    """How to get a package on this system, for messages."""
    if MAC:
        return ("brew install %s" if have("brew") else "with Homebrew (brew.sh): brew install %s") % package
    for tool, cmd in (("dnf", "sudo dnf install %s"), ("apt", "sudo apt install %s"), ("pacman", "sudo pacman -S %s"),
                      ("zypper", "sudo zypper install %s"), ("apk", "sudo apk add %s")):
        if have(tool):
            return cmd % package
    return "install %s" % package


def read_file(path, default=""):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return default


def write_file(path, text, private=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    if private:
        os.chmod(path, 0o600)


# ---------------------------------------------------------------------- the phone and how to reach it

state_lock = threading.Lock()
phone = None           # (host, port) in use
usb_host = None        # the phone's address on the USB cable, while that is the link in use
session = None         # "xoosh_session=..."
streams = []           # connections held open to the phone, dropped when it moves
direct_ssid = None     # the phone's direct link or hotspot, while this computer is on it


def request(method, path, body=None, headers=None, timeout=5, addr=None, cookie=True):
    """One request to the phone, never through a proxy: (status, response, body bytes)."""
    host, port = addr or phone
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        h = {"User-Agent": user_agent(), "Connection": "close"}
        if cookie and session:
            h["Cookie"] = session
        if headers:
            h.update(headers)
        if isinstance(body, str):
            body = body.encode("utf-8")
        conn.request(method, path, body=body, headers=h)
        r = conn.getresponse()
        return r.status, r, r.read()
    finally:
        conn.close()


def ping(addr):
    try:
        status, _, body = request("GET", "/api/ping", addr=addr, timeout=1.5, cookie=False)
        return status == 200 and b'"ok":true' in body
    except (OSError, http.client.HTTPException):
        return False


def mac_ifconfig(text):
    """[(interface, address, netmask)] from macOS's ifconfig."""
    out, cur, seen = [], None, set()
    for line in text.splitlines():
        m = re.match(r"^([A-Za-z0-9]+):\s", line)
        if m:
            cur = m.group(1)
            continue
        m = re.match(r"\s+inet (\d+\.\d+\.\d+\.\d+) netmask (0x[0-9a-fA-F]+)", line)
        if m and cur and cur not in seen:
            seen.add(cur)
            out.append((cur, m.group(1), socket.inet_ntoa(struct.pack("!L", int(m.group(2), 16)))))
    return out


def mac_routes(text):
    """{interface: [gateway, ...]} from macOS's netstat -rn -f inet."""
    out = {}
    for line in text.splitlines():
        p = line.split()
        if len(p) >= 4 and "G" in p[2] and re.match(r"^\d+\.\d+\.\d+\.\d+$", p[1]):
            out.setdefault(p[3], [])
            if p[1] not in out[p[3]]:
                out[p[3]].append(p[1])
    return out


def mac_ports(text):
    """{device: hardware port name} from networksetup -listallhardwareports."""
    return {m.group(2): m.group(1) for m in re.finditer(r"Hardware Port: (.+)\nDevice: (\S+)", text)}


def if_addrs():
    """(interface, address, netmask) for every IPv4 interface that is up."""
    if MAC:
        return mac_ifconfig(run_text(["ifconfig"]) or "")
    out = []
    try:
        names = os.listdir("/sys/class/net")
    except OSError:
        return out
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        for name in names:
            req = struct.pack("256s", name[:15].encode())
            try:
                ip = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, req)[20:24])     # SIOCGIFADDR
                mask = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x891B, req)[20:24])   # SIOCGIFNETMASK
            except OSError:
                continue
            out.append((name, ip, mask))
    finally:
        s.close()
    return out


def gateways():
    """{interface: [gateway, ...]} from the kernel's routing table."""
    if MAC:
        return mac_routes(run_text(["netstat", "-rn", "-f", "inet"]) or "")
    out = {}
    try:
        with open("/proc/net/route") as f:
            next(f)
            for line in f:
                p = line.split()
                if len(p) < 4 or not int(p[3], 16) & 0x2:   # RTF_GATEWAY
                    continue
                gw = socket.inet_ntoa(struct.pack("<L", int(p[2], 16)))
                out.setdefault(p[0], [])
                if gw not in out[p[0]]:
                    out[p[0]].append(gw)
    except (OSError, StopIteration, ValueError):
        pass
    return out


USB_DRIVERS = {"rndis_host", "cdc_ncm", "cdc_ether", "ipheth", "cdc_eem"}


def usb_ifaces():
    """Network interfaces that are a phone's USB tethering."""
    if MAC:
        # A phone's USB tethering (NCM: macOS has no RNDIS) shows up as a port named after the
        # phone; Wi-Fi, Ethernet, Thunderbolt and Bluetooth are the other kinds there are.
        ports = mac_ports(run_text(["networksetup", "-listallhardwareports"]) or "")
        return [d for d, name in ports.items() if d.startswith("en")
                and not re.search(r"Wi-Fi|AirPort|Ethernet|LAN|Thunderbolt|Bluetooth|iPhone|iPad", name, re.I)]
    out = []
    try:
        for name in os.listdir("/sys/class/net"):
            drv = os.path.realpath("/sys/class/net/%s/device/driver" % name)
            if os.path.basename(drv) in USB_DRIVERS:
                out.append(name)
    except OSError:
        pass
    return out


def usb_gateways():
    gws = gateways()
    return [g for i in usb_ifaces() for g in gws.get(i, [])]


def on_usb(host):
    """The address is on a USB tethering interface (its gateway or not)."""
    return host in usb_gateways() or (iface_to(host) or "") in usb_ifaces()


def usb_mbps(iface):
    """The USB link's speed (480 for USB 2, 5000 and up for USB 3), from the USB device in sysfs."""
    if MAC:
        return 0   # macOS does not say per interface; the phone then takes the cable for USB 3
    p = os.path.realpath("/sys/class/net/%s/device" % iface)
    while p and p != "/":
        if os.path.exists(os.path.join(p, "idVendor")):
            try:
                return int(float(read_file(os.path.join(p, "speed"), "0")))
            except ValueError:
                return 0
        p = os.path.dirname(p)
    return 0


def iface_to(host):
    """This computer's interface on the phone's network (the cable, the hotspot, the shared Wi-Fi)."""
    try:
        t = struct.unpack("!L", socket.inet_aton(host))[0]
    except OSError:
        return None
    for name, ip, mask in if_addrs():
        a = struct.unpack("!L", socket.inet_aton(ip))[0]
        m = struct.unpack("!L", socket.inet_aton(mask))[0]
        if not ip.startswith("127.") and a & m == t & m:
            return name
    return None


def discover(wait=1.2):
    """Asks the network: the phone answers "XOOSH?" on UDP 8788."""
    found = []
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.25)
        targets = {"255.255.255.255"}
        for _, ip, mask in if_addrs():
            if ip.startswith("127."):
                continue
            a = struct.unpack("!L", socket.inet_aton(ip))[0]
            m = struct.unpack("!L", socket.inet_aton(mask))[0]
            targets.add(socket.inet_ntoa(struct.pack("!L", (a | ~m) & 0xFFFFFFFF)))
        for t in targets:
            try:
                s.sendto(b"XOOSH?", (t, DISCOVERY_PORT))
            except OSError:
                pass
        end = time.time() + wait
        while time.time() < end:
            try:
                data, (addr, _) = s.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                break
            if data.startswith(b"XOOSH ") and addr not in found:
                found.append(addr)
    finally:
        s.close()
    return found


def adb_path():
    """The last way in: the cable with USB debugging on, through adb's port forward."""
    adb = shutil.which("adb")
    if not adb:
        return None
    out = run_text([adb, "devices"])
    if not out or not re.search(r"\n\S+\s+device\b", out):
        return None
    if run([adb, "forward", "tcp:%d" % ADB_FORWARD_PORT, "tcp:%d" % PHONE_PORT]) is None:
        return None
    return ("127.0.0.1", ADB_FORWARD_PORT)


def candidates():
    """Where the phone may be, fastest first."""
    out = [(g, PHONE_PORT) for g in usb_gateways()]
    saved = read_file(os.path.join(CONF, "phone.txt"))
    if saved:
        out.append((saved, PHONE_PORT))
    # On the phone's hotspot the phone is the gateway.
    for gws in gateways().values():
        out += [(g, PHONE_PORT) for g in gws]
    out += [(a, PHONE_PORT) for a in discover()]
    seen, unique = set(), []
    for c in out:
        if c not in seen:
            seen.add(c)
            unique.append(c)
    return unique


def link_name(addr):
    if addr[0] == "127.0.0.1" and addr[1] == ADB_FORWARD_PORT:
        return "over the cable's USB debugging (turn on USB tethering on the phone for full speed)"
    if addr[0] == usb_host:
        return "over the USB cable"
    if direct_ssid:
        return "on the phone's " + ("direct link" if direct_ssid.startswith("AndroidShare") else "hotspot")
    return "over Wi-Fi"


def move_to(addr):
    """Points everything at the phone's address on another link; open streams reconnect there."""
    global phone, usb_host
    with state_lock:
        usb_host = addr[0] if on_usb(addr[0]) else None
        if phone == addr:
            return
        phone = addr
        held = list(streams)
    for conn in held:
        try:
            conn.sock.shutdown(socket.SHUT_RDWR)
        except (OSError, AttributeError):
            pass
    if usb_host:
        i = iface_to(addr[0])
        if i:
            speed = usb_mbps(i)
            if 0 < speed < 600:
                say_once("usb2-" + addr[0], "The cable is running at USB 2 speed (about 40 MB/s). A USB 3 cable in a "
                                           "USB 3 port gives about 250 MB/s. Charging cables are usually USB 2.")


def find_phone(first_time, typed=None):
    told = False
    while True:
        if typed:
            addr = (typed, PHONE_PORT)
            if ping(addr):
                move_to(addr)
                write_file(os.path.join(CONF, "phone.txt"), typed)
                say("Found the phone at %s." % typed)
                return
            say("The phone does not answer at %s; searching instead." % typed)
            typed = None
        for addr in candidates():
            if ping(addr):
                moved = phone != addr
                move_to(addr)
                if moved:
                    say("Found the phone at %s, %s." % (addr[0], link_name(addr)))
                if direct_ssid is None and not on_usb(addr[0]):
                    write_file(os.path.join(CONF, "phone.txt"), addr[0])
                return
        addr = adb_path()
        if addr and ping(addr):
            move_to(addr)
            say("Found the phone %s." % link_name(addr))
            return
        if first_time and sys.stdin.isatty():
            try:
                t = input("Could not find the phone. Is Localhost 8787 switched on? Type the address it shows "
                          "(or press Enter to search again): ").strip()
            except EOFError:
                t = ""
            m = re.search(r"(\d{1,3}(?:\.\d{1,3}){3})", t)
            if m:
                typed = m.group(1)
                continue
        elif not told:
            say("Waiting for the phone. Switch Localhost 8787 on, or check both are on the same Wi-Fi.")
            told = True
        time.sleep(2)


def pair():
    while True:
        try:
            status, _, body = request("POST", "/api/pair", body=b"", cookie=False)
        except (OSError, http.client.HTTPException) as e:
            say("Could not reach the phone to pair (%s)." % e)
            time.sleep(3)
            find_phone(False)
            continue
        if status == 429:
            say("The phone is busy with other requests; trying again shortly.")
            time.sleep(5)
            continue
        d = json.loads(body or b"{}")
        pid, code = d.get("id", ""), d.get("code", "")
        say('On the phone, allow "Laptop control on %s". Code: %s' % (NAME if NAME == SYSTEM else "%s (%s)" % (NAME, SYSTEM), code))
        for _ in range(125):
            time.sleep(1)
            try:
                _, r, body = request("GET", "/api/pair/" + pid, cookie=False)
            except (OSError, http.client.HTTPException):
                continue
            text = body.decode("utf-8", "replace")
            if "APPROVED" in text:
                m = re.search(r"xoosh_session=([^;,\s]+)", r.getheader("Set-Cookie") or "")
                if not m:
                    raise RuntimeError("the phone approved but sent no session")
                cookie = "xoosh_session=" + m.group(1)
                write_file(os.path.join(CONF, "session.txt"), cookie, private=True)
                say("Allowed. This computer will not need to ask again.")
                return cookie
            if "DENIED" in text:
                say("The phone said no. Asking again in a moment.")
                time.sleep(5)
                break
            if "EXPIRED" in text:
                break


def open_stream(path, headers=None, timeout=40):
    """A long-lived GET (the control stream, the live events); dropped when the phone moves."""
    host, port = phone
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    h = {"User-Agent": user_agent(), "Cookie": session or ""}
    if headers:
        h.update(headers)
    conn.request("GET", path, headers=h)
    r = conn.getresponse()
    with state_lock:
        streams.append(conn)
    return conn, r


def close_stream(conn):
    with state_lock:
        if conn in streams:
            streams.remove(conn)
    try:
        conn.close()
    except OSError:
        pass


# ---------------------------------------------------------------------- localhost relay
#
# The page at http://localhost:8787: every connection the browser opens is relayed to the
# phone as it is, so the page's own pairing, uploads and downloads work unchanged. A connection
# stays tied to the link it was opened over, so after a move to a faster one, a quiet connection
# on the old link is closed and the browser opens a new one on the new link.

local_port = 0
relay_ready = threading.Event()
relayed = []
relayed_lock = threading.Lock()


class Relayed(object):
    def __init__(self, browser, addr):
        self.browser, self.addr, self.to_phone, self.last = browser, addr, None, time.time()


def pump(src, dst, r):
    try:
        while True:
            data = src.recv(1 << 20)
            if not data:
                break
            r.last = time.time()
            dst.sendall(data)
    except OSError:
        pass
    try:
        dst.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def bridge(browser):
    r = Relayed(browser, phone)
    with relayed_lock:
        relayed.append(r)
    try:
        r.to_phone = socket.create_connection(r.addr, timeout=10)
        r.to_phone.settimeout(None)
        for s in (browser, r.to_phone):
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        up = threading.Thread(target=pump, args=(browser, r.to_phone, r), daemon=True)
        up.start()
        pump(r.to_phone, browser, r)
        up.join()
    except OSError:
        pass
    finally:
        with relayed_lock:
            if r in relayed:
                relayed.remove(r)
        for s in (browser, r.to_phone):
            try:
                if s:
                    s.close()
            except OSError:
                pass


def sweep_loop():
    while True:
        time.sleep(1)
        with relayed_lock:
            old = [r for r in relayed if r.addr != phone and time.time() - r.last > 1.5]
        for r in old:
            for s in (r.browser, r.to_phone):
                try:
                    if s:
                        s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass


def relay_loop():
    global local_port
    listener = None
    for port in LOCAL_PORTS:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("127.0.0.1", port))
            s.listen(64)
            listener, local_port = s, port
            break
        except OSError:
            s.close()
    relay_ready.set()
    if listener is None:
        say("Could not serve the page on this computer (ports 8787, 8797 and 8807 are all busy). The trackpad still works.")
        return
    threading.Thread(target=sweep_loop, daemon=True).start()
    while True:
        try:
            c, _ = listener.accept()
        except OSError as e:
            log("Taking a connection: %s" % e)
            time.sleep(0.2)
            continue
        if phone is None:
            c.close()
            continue
        threading.Thread(target=bridge, args=(c,), daemon=True).start()


# ---------------------------------------------------------------------- link report
#
# Every two seconds while someone has the phone's Monitor open (every 15 otherwise): this
# computer's side of the link, and the round trip to the phone.

def freq_band_channel(freq):
    if 2400 <= freq <= 2500:
        return "2.4 GHz", str(14 if freq == 2484 else (freq - 2407) // 5)
    if 4900 <= freq <= 5925:
        return "5 GHz", str((freq - 5000) // 5)
    if 5925 < freq <= 7125:
        return "6 GHz", str((freq - 5950) // 5)
    return "", ""


mac_wifi_cache = [0.0, {}]


def mac_wifi(text):
    """The link in use, from system_profiler SPAirPortDataType -json."""
    info = {}
    try:
        for card in json.loads(text)["SPAirPortDataType"][0].get("spairport_airport_interfaces", []):
            cur = card.get("spairport_current_network_information")
            if not cur:
                continue
            name = cur.get("_name", "")
            info["ssid"] = "" if name.startswith("<") else name   # "<redacted>" without Location access
            # "36 (5GHz, 80MHz)" on recent macOS, "149,80" on older ones.
            ch = str(cur.get("spairport_network_channel", ""))
            m = re.match(r"(\d+)", ch)
            if m:
                n = int(m.group(1))
                info["channel"] = m.group(1)
                info["band"] = "6 GHz" if "6GHz" in ch else "2.4 GHz" if "2GHz" in ch or n <= 14 else "5 GHz"
            mode = cur.get("spairport_network_phymode", "")
            info["radio"] = ("Wi-Fi 7" if mode.endswith("be") else "Wi-Fi 6" if mode.endswith("ax") else
                             "Wi-Fi 5" if mode.endswith("ac") else "Wi-Fi 4" if mode.endswith("n") else "")
            rate = cur.get("spairport_network_rate")
            if isinstance(rate, (int, float)):
                info["rxMbps"] = info["txMbps"] = int(rate)
            m = re.match(r"(-?\d+) dBm", cur.get("spairport_signal_noise", ""))
            if m:
                info["signalPercent"] = max(0, min(100, 2 * (int(m.group(1)) + 100)))
            break
    except (ValueError, KeyError, IndexError, TypeError):
        pass
    return info


def mac_counters(text):
    """(received, sent) bytes from netstat -ib -I IFACE: the interface's own line."""
    for line in text.splitlines():
        p = line.split()
        if len(p) >= 10 and p[2].startswith("<Link#"):
            try:
                return int(p[-5]), int(p[-2])
            except ValueError:
                return 0, 0
    return 0, 0


def wifi_info(iface):
    """What this computer's Wi-Fi says about its link: iw first, nmcli otherwise."""
    if MAC:
        # system_profiler takes a second or two: asked at most every ten seconds.
        if time.time() - mac_wifi_cache[0] > 10:
            mac_wifi_cache[:] = [time.time(), mac_wifi(run_text(["system_profiler", "SPAirPortDataType", "-json"], timeout=20) or "")]
        return mac_wifi_cache[1]
    info = {}
    if iface and have("iw"):
        out = run_text(["iw", "dev", iface, "link"]) or ""
        m = re.search(r"SSID: (.*)", out)
        if m:
            info["ssid"] = m.group(1).strip()
        m = re.search(r"freq: ([\d.]+)", out)
        if m:
            info["band"], info["channel"] = freq_band_channel(int(float(m.group(1))))
        m = re.search(r"signal: (-?\d+)", out)
        if m:
            info["signalPercent"] = max(0, min(100, 2 * (int(m.group(1)) + 100)))
        for key, field in (("rx bitrate", "rxMbps"), ("tx bitrate", "txMbps")):
            m = re.search(key + r": ([\d.]+) MBit/s(.*)", out)
            if m:
                info[field] = int(float(m.group(1)))
                if field == "txMbps":
                    rest = m.group(2)
                    info["radio"] = ("Wi-Fi 7" if "EHT-MCS" in rest else "Wi-Fi 6" if "HE-MCS" in rest
                                     else "Wi-Fi 5" if "VHT-MCS" in rest else "Wi-Fi 4" if "MCS" in rest else "")
    if "ssid" not in info and iface and have("nmcli"):
        out = run_text(["nmcli", "-t", "-f", "IN-USE,SSID,FREQ,RATE,SIGNAL", "dev", "wifi", "list", "ifname", iface, "--rescan", "no"]) or ""
        for line in out.splitlines():
            if line.startswith("*:"):
                p = re.split(r"(?<!\\):", line)
                if len(p) >= 5:
                    info["ssid"] = p[1].replace("\\:", ":")
                    try:
                        info["band"], info["channel"] = freq_band_channel(int(p[2].split()[0]))
                        info["rxMbps"] = info["txMbps"] = int(p[3].split()[0])
                        info["signalPercent"] = int(p[4])
                    except (ValueError, IndexError):
                        pass
    return info


def report_link():
    """One report to the phone; how long to wait before the next (2 s while someone watches)."""
    if not phone or not session:
        return 15
    host = phone[0]
    iface = None if host == "127.0.0.1" else iface_to(host)
    t0 = time.time()
    rtt = int((time.time() - t0) * 1000) if ping(phone) else -1
    rtt = -1 if rtt < 0 else max(rtt, 1)
    rx = tx = 0
    if iface and MAC:
        rx, tx = mac_counters(run_text(["netstat", "-ib", "-I", iface]) or "")
    elif iface:
        try:
            rx = int(read_file("/sys/class/net/%s/statistics/rx_bytes" % iface, "0"))
            tx = int(read_file("/sys/class/net/%s/statistics/tx_bytes" % iface, "0"))
        except ValueError:
            pass
    w = {} if host == usb_host else wifi_info(iface)
    report = {
        "ssid": w.get("ssid", ""), "signalPercent": w.get("signalPercent", 0),
        "rxMbps": w.get("rxMbps", 0), "txMbps": w.get("txMbps", 0),
        "channel": w.get("channel", ""), "band": w.get("band", ""), "radio": w.get("radio", ""),
        "rttMs": rtt, "usbMbps": usb_mbps(iface) if iface and host == usb_host else 0,
        "iface": iface or "", "rxBytes": rx, "txBytes": tx,
    }
    _, _, body = request("POST", "/api/monitor/link", body=json.dumps(report),
                         headers={"Content-Type": "application/json"}, timeout=3)
    return 2 if b'"watch"' in body else 15


def link_loop():
    while True:
        try:
            wait = report_link()
        except (OSError, http.client.HTTPException, ValueError):
            wait = 15
        time.sleep(wait)


# ---------------------------------------------------------------------- direct link and hotspot
#
# When the phone offers a fast link for laptops, this computer joins it with NetworkManager:
# the phone's direct link (its own offline network) or, in hotspot mode, its ordinary hotspot.
# The page keeps working, since it talks to localhost. When the link ends, this computer goes
# back to the Wi-Fi it was on. A USB cable beats all of them.

home_conn = None
added_conn = False
gave_up_on = None


def wifi_device():
    out = run_text(["nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "dev"]) or ""
    for line in out.splitlines():
        p = line.split(":")
        if len(p) >= 3 and p[1] == "wifi" and p[2] != "unavailable":
            return p[0]
    return None


def active_wifi_conn():
    out = run_text(["nmcli", "-t", "-f", "NAME,TYPE", "con", "show", "--active"]) or ""
    for line in out.splitlines():
        p = re.split(r"(?<!\\):", line)
        if len(p) >= 2 and p[1] == "802-11-wireless":
            return p[0].replace("\\:", ":")
    return None


def join_direct(ssid, passphrase, host, hotspot):
    global direct_ssid, home_conn, added_conn, gave_up_on
    if MAC:
        say_once("mac-direct", "The phone offers its " + ("hotspot" if hotspot else "direct link") + ' ("%s"). On a Mac, join it '
                 "from the Wi-Fi menu; the helper follows the phone there by itself." % ssid)
        gave_up_on = ssid
        return
    if not have("nmcli") or not wifi_device():
        say_once("no-nm", "The phone offers its " + ("hotspot" if hotspot else "direct link") + ", but this computer has no "
                 "Wi-Fi that NetworkManager (nmcli) can use; staying on the current link.")
        gave_up_on = ssid
        return
    known = ssid in (run_text(["nmcli", "-t", "-f", "NAME", "con", "show"]) or "").splitlines()
    if not known and not passphrase:
        say("The phone's hotspot is on, but this computer does not know its password. Add it in the phone's Settings (Laptop link).")
        gave_up_on = ssid
        return
    current = active_wifi_conn()
    if current and current != ssid:
        home_conn = current
        write_file(os.path.join(CONF, "home-wifi.txt"), current)
    say("The phone's hotspot is on. Moving this computer onto it; the internet stays on through the phone." if hotspot else
        "The phone started its direct link. Moving this computer onto it; there is no internet while on it.")
    # A network that has only just started is not in the last scan yet.
    run(["nmcli", "dev", "wifi", "rescan", "ssid", ssid], timeout=15)
    for _ in range(10):
        if ssid in (run_text(["nmcli", "-t", "-f", "SSID", "dev", "wifi", "list", "--rescan", "no"]) or "").splitlines():
            break
        time.sleep(1)
    if known:
        ok = run(["nmcli", "con", "up", "id", ssid], timeout=40) is not None
        added_conn = False
    else:
        ok = run(["nmcli", "dev", "wifi", "connect", ssid, "password", passphrase], timeout=40) is not None
        added_conn = ok
    direct_ssid = ssid
    write_file(os.path.join(CONF, "direct-wifi.txt"), ssid + "\n" + ("added" if added_conn else "saved"))
    for _ in range(50):
        if ok and ping((host, PHONE_PORT)):
            move_to((host, PHONE_PORT))
            say("On the phone's hotspot. The page carries on over it, and the internet works." if hotspot else
                "On the direct link. The page carries on over it at full speed.")
            return
        time.sleep(0.5)
    say("Could not reach the phone on " + ("its hotspot" if hotspot else "its direct link") + "; going back.")
    gave_up_on = ssid
    leave_direct()


def leave_direct(quiet=False):
    global direct_ssid, added_conn
    ssid = direct_ssid
    direct_ssid = None
    try:
        os.remove(os.path.join(CONF, "direct-wifi.txt"))
    except OSError:
        pass
    home = home_conn or read_file(os.path.join(CONF, "home-wifi.txt"))
    if home:
        run(["nmcli", "con", "up", "id", home], timeout=40)
    # Only a network this helper added itself (the direct link's, never one you saved) goes.
    if ssid and added_conn and ssid.startswith("AndroidShare"):
        run(["nmcli", "con", "delete", "id", ssid])
    added_conn = False
    if not quiet:
        say("The phone's link ended. Back on " + (home or "your Wi-Fi") + ".")
        time.sleep(3)
        find_phone(False)


def direct_loop():
    global usb_host, gave_up_on, direct_ssid, added_conn
    rec = read_file(os.path.join(CONF, "direct-wifi.txt")).split("\n")
    if rec[0]:
        direct_ssid, added_conn = rec[0], len(rec) > 1 and rec[1] == "added"
    misses = usb_misses = 0
    while True:
        time.sleep(2.5)
        try:
            if not session or not phone:
                continue
            usb = next((g for g in usb_gateways() + ([usb_host] if usb_host else []) if ping((g, PHONE_PORT))), None)
            if usb:
                usb_misses = 0
                if direct_ssid:
                    leave_direct(quiet=True)
                if phone != (usb, PHONE_PORT):
                    move_to((usb, PHONE_PORT))
                    say("USB cable to the phone found: using it. It is several times faster than any Wi-Fi link.")
                continue
            if usb_host and phone[0] == usb_host:
                usb_misses += 1
                if not on_usb(usb_host) or usb_misses >= 2:
                    usb_misses = 0
                    say("The USB cable is gone; looking for the phone over Wi-Fi.")
                    usb_host = None
                    find_phone(False)
                    continue
            try:
                status, _, body = request("GET", "/api/direct", timeout=3)
                d = json.loads(body) if status == 200 else None
            except (OSError, http.client.HTTPException, ValueError):
                d = None
            if d is None:
                misses += 1
                if direct_ssid and misses >= 3:
                    leave_direct()
                    misses = 0
                continue
            misses = 0
            on = d.get("state") == "on" and d.get("laptop", True) is not False
            ssid, host = d.get("ssid", ""), d.get("host", "")
            if on and ssid and host and direct_ssid != ssid and gave_up_on != ssid:
                join_direct(ssid, d.get("passphrase", ""), host, d.get("kind") == "hotspot")
            elif not on and direct_ssid:
                leave_direct()
            if not on:
                gave_up_on = None
        except Exception as e:   # keep the loop alive whatever one pass hits
            log("Direct link: %s" % e)


# ---------------------------------------------------------------------- clipboard
#
# One clipboard for this computer and the phone: text, a picture, or one file up to 50 MB.

class WlClipboard(object):
    """Wayland: wl-clipboard."""
    name = "wl-clipboard"

    def types(self):
        out = run_text(["wl-paste", "--list-types"], timeout=3)
        return out.split() if out else []

    def read(self, mime):
        return run(["wl-paste", "--no-newline", "--type", mime], timeout=10)

    def write(self, mime, data):
        # wl-copy keeps serving the clipboard in the background once it has read its input.
        return run(["wl-copy", "--type", mime], input=data, timeout=10) is not None

    def clear(self):
        run(["wl-copy", "--clear"], timeout=3)


class XClipboard(object):
    """X11: xclip."""
    name = "xclip"

    def types(self):
        out = run_text(["xclip", "-selection", "clipboard", "-o", "-t", "TARGETS"], timeout=3)
        return out.split() if out else []

    def read(self, mime):
        return run(["xclip", "-selection", "clipboard", "-o", "-t", mime], timeout=10)

    def write(self, mime, data):
        try:
            p = subprocess.Popen(["xclip", "-selection", "clipboard", "-i", "-t", mime],
                                 stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            p.communicate(data, timeout=10)
            return True
        except (OSError, subprocess.SubprocessError):
            return False

    def clear(self):
        self.write("UTF8_STRING", b"")


class XselClipboard(object):
    """X11 with xsel: text only."""
    name = "xsel"

    def types(self):
        return ["UTF8_STRING"]

    def read(self, mime):
        return run(["xsel", "--clipboard", "--output"], timeout=3)

    def write(self, mime, data):
        if not mime.startswith("text") and mime != "UTF8_STRING":
            return False
        return run(["xsel", "--clipboard", "--input"], input=data, timeout=5) is not None

    def clear(self):
        run(["xsel", "--clipboard", "--clear"], timeout=3)


# The general pasteboard's types, as the mime types the rest of this helper speaks.
MAC_TYPES = {"public.utf8-plain-text": "text/plain;charset=utf-8", "public.png": "image/png", "public.jpeg": "image/jpeg",
             "public.tiff": "image/tiff", "com.compuserve.gif": "image/gif", "public.heic": "image/heic",
             "public.file-url": "text/uri-list"}
MAC_UTI = {v: k for k, v in MAC_TYPES.items()}
MAC_WATCH = """
ObjC.import('AppKit');
var pb = $.NSPasteboard.generalPasteboard, last = -1;
while (true) {
  var c = pb.changeCount;
  if (c !== last) { last = c; console.log(c + ' ' + JSON.stringify(ObjC.deepUnwrap(pb.types) || [])); }
  delay(0.3);
}
"""


def jxa(script, timeout=10):
    """Runs JavaScript for Automation (with AppKit to hand); its result as text."""
    return run_text(["osascript", "-l", "JavaScript", "-e", script], timeout=timeout)


class MacClipboard(object):
    """macOS: pbcopy and pbpaste for text; AppKit, through JavaScript for Automation, for pictures and files.
    A small script left running tells when the clipboard changes, so nothing is read until it does."""
    name = "macOS"
    ENV = dict(os.environ, LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8")   # pbcopy and pbpaste speak the locale's encoding

    def __init__(self):
        self.types_now = []
        self.ready = threading.Event()
        self.watcher = subprocess.Popen(["osascript", "-l", "JavaScript", "-e", MAC_WATCH],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        threading.Thread(target=self.watch, daemon=True).start()
        # What is on the clipboard already is known before anything is compared with it.
        self.ready.wait(5)

    def watch(self):
        for raw in self.watcher.stderr:
            m = re.match(rb"(\d+) (\[.*\])", raw.strip())
            if m:
                try:
                    utis = json.loads(m.group(2).decode("utf-8", "replace"))
                except ValueError:
                    continue
                types = [MAC_TYPES[u] for u in utis if u in MAC_TYPES]
                # A TIFF alone (copied from some apps) is sent on as PNG, made with sips.
                if "image/tiff" in types and "image/png" not in types:
                    types.append("image/png")
                self.types_now = types
                self.ready.set()

    def close(self):
        if self.watcher.poll() is None:
            self.watcher.terminate()

    def types(self):
        return list(self.types_now)

    def read(self, mime):
        if mime.startswith("text/plain"):
            try:
                return subprocess.run(["pbpaste"], stdout=subprocess.PIPE, env=self.ENV, timeout=5).stdout
            except (OSError, subprocess.SubprocessError):
                return None
        if mime == "text/uri-list":
            out = jxa("ObjC.import('AppKit'); var items = $.NSPasteboard.generalPasteboard.pasteboardItems, out = [];"
                      "for (var i = 0; i < items.count; i++) { var s = items.objectAtIndex(i).stringForType('public.file-url');"
                      "if (!s.isNil()) out.push(s.js); } out.join('\\n');")
            return out.strip().encode() if out else None
        os.makedirs(CACHE, exist_ok=True)
        tmp = os.path.join(CACHE, "clip-in")
        for uti in [MAC_UTI.get(mime)] + (["public.tiff"] if mime == "image/png" else []):
            if not uti:
                continue
            ok = jxa("ObjC.import('AppKit'); var d = $.NSPasteboard.generalPasteboard.dataForType(%s);"
                     "d.isNil() ? 'none' : (d.writeToFileAtomically(%s, true), 'ok');" % (json.dumps(uti), json.dumps(tmp)))
            if ok and ok.strip() == "ok":
                if uti == "public.tiff" and mime == "image/png":
                    if run(["sips", "-s", "format", "png", tmp, "--out", tmp + ".png"], timeout=15) is None:
                        return None
                    tmp += ".png"
                with open(tmp, "rb") as f:
                    return f.read()
        return None

    def write(self, mime, data):
        if mime.startswith("text/plain") or mime == "UTF8_STRING":
            try:
                return subprocess.run(["pbcopy"], input=data, env=self.ENV, timeout=5).returncode == 0
            except (OSError, subprocess.SubprocessError):
                return False
        if mime == "text/uri-list":
            path = urllib.parse.unquote(data.decode("utf-8", "replace").strip().split()[0][len("file://"):])
            out = jxa("ObjC.import('AppKit'); var pb = $.NSPasteboard.generalPasteboard; pb.clearContents;"
                      "pb.writeObjects($([$.NSURL.fileURLWithPath(%s)])) ? 'ok' : 'no';" % json.dumps(path))
            return bool(out) and out.strip() == "ok"
        uti = MAC_UTI.get(mime)
        if not uti:
            return False
        os.makedirs(CACHE, exist_ok=True)
        tmp = os.path.join(CACHE, "clip-out")
        with open(tmp, "wb") as f:
            f.write(data)
        out = jxa("ObjC.import('AppKit'); var pb = $.NSPasteboard.generalPasteboard; pb.clearContents;"
                  "pb.setDataForType($.NSData.dataWithContentsOfFile(%s), %s) ? 'ok' : 'no';" % (json.dumps(tmp), json.dumps(uti)))
        return bool(out) and out.strip() == "ok"

    def clear(self):
        jxa("ObjC.import('AppKit'); $.NSPasteboard.generalPasteboard.clearContents; 'ok';")


def clipboard_backend():
    if MAC:
        return MacClipboard() if have("osascript") and have("pbcopy") else None
    if os.environ.get("WAYLAND_DISPLAY") and have("wl-paste") and have("wl-copy"):
        return WlClipboard()
    if os.environ.get("DISPLAY") and have("xclip"):
        return XClipboard()
    if os.environ.get("DISPLAY") and have("xsel"):
        return XselClipboard()
    return None


TEXT_TYPES = ("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "STRING", "TEXT")
clip = None
clip_queue = []
clip_lock = threading.Lock()
clip_sync = True
last_text = None
last_blob_sig = None
last_blob_v = -1
blob_in_flight = False
ours = None   # what this helper last put on the clipboard, so a Clear removes only that


def mime_of(name):
    ext = os.path.splitext(name)[1].lower()
    return {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
            ".bmp": "image/bmp", ".heic": "image/heic", ".pdf": "application/pdf", ".txt": "text/plain", ".zip": "application/zip",
            ".mp4": "video/mp4", ".mp3": "audio/mpeg"}.get(ext, "application/octet-stream")


def send_clip_text(text):
    try:
        request("POST", "/api/clipboard", body=json.dumps({"text": text}),
                headers={"Content-Type": "application/json", "Bridge-Auto": "1"}, timeout=4)
    except (OSError, http.client.HTTPException):
        pass


def send_clip_blob(data, name, mime):
    global last_blob_v, blob_in_flight
    blob_in_flight = True
    try:
        _, _, body = request("POST", "/api/clipboard/blob?name=" + urllib.parse.quote(name), body=data,
                             headers={"Content-Type": mime, "Bridge-Auto": "1"}, timeout=30)
        m = re.search(rb'"v":(\d+)', body)
        if m:
            last_blob_v = int(m.group(1))
        say(("Picture" if mime.startswith("image/") else "File") + " copied here is on the phone's clipboard.")
    except (OSError, http.client.HTTPException) as e:
        say("Could not put it on the phone's clipboard (%s)." % e)
    finally:
        blob_in_flight = False


def fetch_clip_blob(v, name, image):
    """The picture or file on the shared clipboard, onto this computer's."""
    try:
        d = os.path.join(CACHE, "clipboard")
        os.makedirs(d, exist_ok=True)
        for old in os.listdir(d):
            try:
                os.remove(os.path.join(d, old))
            except OSError:
                pass
        name = re.sub(r"[/\x00]", "_", name) or ("Picture.png" if image else "File")
        path = os.path.join(d, name)
        status, _, body = request("GET", "/api/clipboard/blob?v=%d" % v, timeout=30)
        if status != 200:
            return
        with open(path, "wb") as f:
            f.write(body)
        with clip_lock:
            clip_queue.append(("image" if image else "file", path))
    except (OSError, http.client.HTTPException):
        pass


def apply_clip(kind, value):
    global last_text, last_blob_sig, ours
    if kind == "text":
        if clip.write("UTF8_STRING" if clip.name in ("xclip", "xsel") else "text/plain;charset=utf-8", value.encode("utf-8")):
            last_text = ours = value
    elif kind == "clear":
        # Cleared on the phone or a page: only what the sync itself put here goes.
        now = clip.read("UTF8_STRING" if clip.name in ("xclip", "xsel") else TEXT_TYPES[0])
        if ours is not None and now is not None and now.decode("utf-8", "replace") == ours:
            clip.clear()
    else:
        path = value
        if kind == "image":
            with open(path, "rb") as f:
                data = f.read()
            if clip.write(mime_of(path), data):
                last_blob_sig = hashlib.md5(data).hexdigest()
        else:
            uri = "file://" + urllib.parse.quote(path)
            # GNOME's Files (and Nemo, Caja) paste a file from their own type; the rest from a URI list.
            gnome = re.search(r"GNOME|Unity|Cinnamon|MATE|Budgie|Pantheon", os.environ.get("XDG_CURRENT_DESKTOP", ""), re.I)
            if clip.write("x-special/gnome-copied-files" if gnome else "text/uri-list",
                          ("copy\n" + uri).encode() if gnome else (uri + "\r\n").encode()):
                st = os.stat(path)
                last_blob_sig = "%s|%d|%d" % (path, st.st_size, int(st.st_mtime))


def clip_signature(types):
    """What is on the clipboard now, as the loop below would tell it apart: ("text", text) or ("blob", sig)."""
    if not types:
        return None
    if "text/uri-list" in types:
        uris = (clip.read("text/uri-list") or b"").decode("utf-8", "replace").split()
        files = [urllib.parse.unquote(u[7:]) for u in uris if u.startswith("file://")]
        if len(files) == 1 and os.path.isfile(files[0]):
            st = os.stat(files[0])
            return "blob", "%s|%d|%d" % (files[0], st.st_size, int(st.st_mtime))
    image = next((t for t in types if t.startswith("image/")), None)
    if image and not any(t in types for t in TEXT_TYPES):
        data = clip.read("image/png" if "image/png" in types else image)
        return ("blob", hashlib.md5(data).hexdigest()) if data else None
    mime = next((t for t in TEXT_TYPES if t in types), None)
    data = clip.read(mime) if mime else None
    return ("text", data.decode("utf-8", "replace")) if data is not None else None


def clip_loop():
    global last_text, last_blob_sig
    # What is on the clipboard already stays here: only what is copied from now on goes over.
    baseline = True
    while True:
        time.sleep(0.6)
        try:
            with clip_lock:
                pending = clip_queue.pop(0) if clip_queue else None
            if pending:
                apply_clip(*pending)
                continue
            if not clip_sync or not session or not phone:
                continue
            types = clip.types()
            if baseline:
                baseline = False
                seen = clip_signature(types)
                if seen:
                    kind, sig = seen
                    if kind == "text":
                        last_text = sig
                    else:
                        last_blob_sig = sig
                continue
            if not types:
                continue
            if "text/uri-list" in types:
                uris = (clip.read("text/uri-list") or b"").decode("utf-8", "replace").split()
                files = [urllib.parse.unquote(u[7:]) for u in uris if u.startswith("file://")]
                # One file goes on the shared clipboard; a folder or several files are for Send files.
                if len(files) == 1 and os.path.isfile(files[0]):
                    st = os.stat(files[0])
                    sig = "%s|%d|%d" % (files[0], st.st_size, int(st.st_mtime))
                    if sig != last_blob_sig:
                        last_blob_sig = sig
                        if st.st_size > CLIP_MAX_BYTES:
                            say("That file is over 50 MB, too big for the clipboard; send it from the page instead.")
                        else:
                            with open(files[0], "rb") as f:
                                send_clip_blob(f.read(), os.path.basename(files[0]), mime_of(files[0]))
                    continue
            image = next((t for t in types if t.startswith("image/")), None)
            if image and not any(t in types for t in TEXT_TYPES):
                data = clip.read("image/png" if "image/png" in types else image)
                if data:
                    sig = hashlib.md5(data).hexdigest()
                    if sig != last_blob_sig:
                        last_blob_sig = sig
                        ext = ".png" if "image/png" in types else "." + image.split("/")[1]
                        send_clip_blob(data, "Picture " + time.strftime("%Y-%m-%d %H%M%S") + ext,
                                       "image/png" if "image/png" in types else image)
                continue
            mime = next((t for t in TEXT_TYPES if t in types), None)
            if mime:
                data = clip.read(mime)
                if data is None:
                    continue
                text = data.decode("utf-8", "replace")
                if text and text != last_text and len(text) <= TEXT_MAX:
                    last_text = text
                    send_clip_text(text)
        except Exception as e:
            log("Clipboard: %s" % e)


# ---------------------------------------------------------------------- the phone's live events

def events_loop():
    global clip_sync, last_text, last_blob_v
    while True:
        conn = None
        try:
            if not session or not phone:
                time.sleep(2)
                continue
            at = phone
            try:
                _, _, body = request("GET", "/api/state", timeout=5)
                clip_sync = b'"clipSync":false' not in body
            except (OSError, http.client.HTTPException):
                pass
            conn, r = open_stream("/events")
            ev, data = None, []
            snapshot = clip_snapshot = True
            while phone == at:
                raw = r.fp.readline()
                if not raw:
                    break
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line:
                    if line.startswith("event:"):
                        ev = line[6:].strip()
                    elif line.startswith("data:"):
                        data.append(line[5:][1:] if line[5:6] == " " else line[5:])
                    continue
                d = "\n".join(data)
                if ev == "clipsync":
                    clip_sync = d == "on"
                elif ev == "display":
                    # "start PORT W H [HZ ...]" when the phone opens its second screen; anything else stops it.
                    p = d.split()
                    if p and p[0] == "start" and len(p) >= 4:
                        fps = max(30, min(120, int(p[4]))) if len(p) >= 5 else 60
                        threading.Thread(target=start_second_screen, args=(at[0], int(p[1]), int(p[2]), int(p[3]), fps),
                                         daemon=True).start()
                    else:
                        stop_second_screen()
                elif ev == "mirror" and not snapshot:
                    threading.Thread(target=open_phone_screen, daemon=True).start()
                elif ev == "clip" and clip:
                    try:
                        c = json.loads(d)
                    except ValueError:
                        c = {}
                    v = int(c.get("v", -1))
                    if clip_snapshot:
                        clip_snapshot = False
                        last_blob_v = v
                    elif blob_in_flight:
                        last_blob_v = v   # our own upload, announced back
                    elif clip_sync and v != last_blob_v:
                        last_blob_v = v
                        kind = c.get("kind", "")
                        if kind in ("image", "file"):
                            fetch_clip_blob(v, c.get("name", ""), kind == "image")
                        elif kind == "empty":
                            with clip_lock:
                                clip_queue.append(("clear", None))
                elif ev == "clipboard":
                    # The first one is part of what the phone sends on connecting; it also marks that
                    # burst as over, so a "mirror" after it is a real request (with or without a clipboard tool).
                    if snapshot:
                        snapshot = False
                        if last_text is None:
                            last_text = d
                    elif clip and clip_sync and d and d != last_text:
                        last_text = d
                        with clip_lock:
                            clip_queue.append(("text", d))
                ev, data = None, []
        except (OSError, http.client.HTTPException, ValueError):
            pass
        finally:
            if conn:
                close_stream(conn)
        time.sleep(1.5)


# ---------------------------------------------------------------------- the phone's screen

screen_proc = None


def open_phone_screen():
    global screen_proc
    if screen_proc and screen_proc.poll() is None:
        say("The phone's screen is already open.")
        return
    scrcpy = shutil.which("scrcpy")
    if not scrcpy:
        say("To see the phone's screen here, install scrcpy (%s), with USB debugging on on the phone." % install_hint("scrcpy"))
        return
    target = []
    adb = shutil.which("adb")
    if adb:
        for line in (run_text([adb, "devices"]) or "").splitlines():
            p = line.strip().split("\t")
            if len(p) == 2 and p[1] == "device" and ":" not in p[0]:
                target = ["-s", p[0]]
                break
    if not target and phone:
        target = ["--tcpip=%s:5555" % phone[0]]
    say("Opening the phone's screen" + (" over the USB cable." if target and target[0] == "-s" else " over Wi-Fi."))
    try:
        screen_proc = subprocess.Popen([scrcpy] + target + ["--window-title=Phone (Localhost 8787)", "--stay-awake",
                                                           "--video-bit-rate=16M", "--max-fps=60"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        err = screen_proc.communicate()[1].decode("utf-8", "replace")
        if screen_proc.returncode not in (0, None):
            if "Could not find any ADB device" in err or "failed to connect" in err:
                say("Could not reach the phone over adb. On the phone: Developer options, turn on USB debugging "
                    "(and on Xiaomi, USB debugging (Security settings)); plug it in once, and allow this computer.")
            else:
                say("The phone's screen closed: " + (err.strip().splitlines() or [""])[-1])
    except OSError as e:
        say("Could not open the phone's screen (%s)." % e)


# ---------------------------------------------------------------------- the phone as a second screen
#
# The phone's "Use as a second screen" listens on a port and takes a raw H.264 stream. This
# computer's screen goes there: an extra monitor if there is one (a real one, or a virtual one
# made with xrandr), else the main screen, mirrored. Taps on the phone come back as "da x y",
# fractions of what it shows. On X11, ffmpeg grabs the screen (encoded on an NVIDIA card, else
# with VA-API on Intel or AMD, else in software); on a wlroots Wayland desktop (sway, Hyprland),
# wf-recorder does. GNOME and KDE under Wayland only hand the screen out through their own
# screen-sharing prompt, which this helper does not drive yet.

screen_gen = 0
stream_proc = None
shown = None   # (x, y, w, h) of what the phone shows, for its taps


def monitors():
    """[(name, x, y, w, h, primary)] of the monitors in use (X11, from xrandr; on a Mac, in points)."""
    if MAC:
        return mac_displays()
    out = run_text(["xrandr", "--query"]) or ""
    mons = []
    for m in re.finditer(r"^(\S+) connected( primary)? (\d+)x(\d+)\+(\d+)\+(\d+)", out, re.M):
        mons.append((m.group(1), int(m.group(5)), int(m.group(6)), int(m.group(3)), int(m.group(4)), bool(m.group(2))))
    return mons


def desktop_size():
    """The whole desktop's size (every monitor), for absolute pointing."""
    if MAC:
        mons = mac_displays()
        return (max(m[1] + m[3] for m in mons), max(m[2] + m[4] for m in mons)) if mons else None
    out = run_text(["xrandr", "--query"]) or ""
    m = re.search(r"current (\d+) x (\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else None


def session_kind():
    if MAC:
        return "mac"
    if os.environ.get("WAYLAND_DISPLAY") or os.environ.get("XDG_SESSION_TYPE") == "wayland":
        return "wayland"
    return "x11" if os.environ.get("DISPLAY") else ""


def capture_tries(geom, w, h, fps, dest, grab=None):
    """ffmpeg arguments, fastest encoder first: X11's screen grab, or the one given (macOS's)."""
    x, y, gw, gh = geom
    grab = grab or ["-f", "x11grab", "-framerate", str(fps), "-video_size", "%dx%d" % (gw, gh), "-draw_mouse", "1",
                    "-i", "%s+%d,%d" % (os.environ.get("DISPLAY", ":0"), x, y)]
    # Fitted to the phone's screen; labelled BT.601 in full, as the phone decodes it.
    fit = "scale=%d:%d:force_original_aspect_ratio=decrease:force_divisible_by=2" % (w, h)
    label = "setparams=color_primaries=bt470bg:color_trc=smpte170m:colorspace=bt470bg:range=tv"
    mbit = min(80, max(40, 40 * fps // 60))
    rate = ["-b:v", "%dM" % mbit, "-maxrate", "%dM" % mbit, "-bufsize", "%dM" % max(3, mbit // 13), "-g", str(fps * 2), "-bf", "0"]
    base = ["-hide_banner", "-loglevel", "error"]
    # And written into the stream's own header, which every encoder then carries (OpenH264 would
    # otherwise leave it out, and the phone would take the picture for BT.709 and shift its colours).
    dest = ["-color_primaries", "bt470bg", "-color_trc", "smpte170m", "-colorspace", "bt470bg", "-color_range", "tv",
            "-bsf:v", "h264_metadata=video_format=5:colour_primaries=5:transfer_characteristics=6:"
                      "matrix_coefficients=5:video_full_range_flag=0"] + dest
    tries = []
    if MAC:
        # The Mac's own hardware encoder.
        tries.append(base + grab + ["-vf", fit + ",format=nv12," + label, "-c:v", "h264_videotoolbox", "-realtime", "1",
                                    "-allow_sw", "1"] + rate + dest)
    if os.path.exists("/proc/driver/nvidia/version"):
        tries.append(base + grab + ["-vf", fit + ",format=yuv420p," + label, "-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull",
                                    "-zerolatency", "1", "-rc", "cbr"] + rate + dest)
    if os.path.exists("/dev/dri/renderD128"):
        tries.append(base + ["-vaapi_device", "/dev/dri/renderD128"] + grab +
                     ["-vf", fit + ",format=nv12," + label + ",hwupload", "-c:v", "h264_vaapi"] + rate + dest)
    tries.append(base + grab + ["-vf", fit + ",format=yuv420p," + label, "-c:v", "libx264", "-preset", "ultrafast",
                                "-tune", "zerolatency", "-b:v", "20M", "-g", "120", "-bf", "0"] + dest)
    # Fedora's ffmpeg has no libx264; it has Cisco's OpenH264.
    tries.append(base + grab + ["-vf", fit + ",format=yuv420p," + label, "-c:v", "libopenh264", "-b:v", "20M",
                                "-g", "120", "-allow_skip_frames", "1"] + dest)
    return tries


def pick_screen():
    """An extra monitor if there is one, else the main one, mirrored."""
    mons = monitors()
    if not mons:
        size = desktop_size()
        return (0, 0) + size if size else None
    main = next((m for m in mons if m[5]), mons[0])   # the one marked primary, else the first
    extra = [m for m in mons if m is not main]
    m = extra[0] if extra else main
    return m[1], m[2], m[3], m[4]


def mac_screen_devices(text):
    """{screen number: avfoundation device index} from ffmpeg -f avfoundation -list_devices true."""
    return {int(m.group(2)): int(m.group(1)) for m in re.finditer(r"\[(\d+)\] Capture screen (\d+)", text)}


def stream_x11(at, port, w, h, fps, gen):
    global stream_proc, shown
    ff = shutil.which("ffmpeg")
    if not ff:
        say("The second screen needs ffmpeg on this computer (%s)." % install_hint("ffmpeg"))
        return
    geom = pick_screen()
    if not geom:
        say("Could not tell this screen's size (xrandr is missing).")
        return
    shown = geom
    grab = None
    if MAC:
        # avfoundation numbers the screens as CoreGraphics lists them, after the cameras.
        mons = monitors()
        which = next((i for i, m in enumerate(mons) if (m[1], m[2], m[3], m[4]) == geom), 0)
        listing = subprocess.run([ff, "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=10).stderr.decode("utf-8", "replace")
        device = mac_screen_devices(listing).get(which)
        if device is None:
            say("ffmpeg lists no screen to capture. Allow Screen Recording for the app this helper runs in (System "
                "Settings, Privacy & Security, Screen Recording), then start the second screen again.")
            return
        grab = ["-f", "avfoundation", "-capture_cursor", "1", "-framerate", str(fps), "-pixel_format", "nv12",
                "-i", "%d:none" % device]
        say_once("mac-screen-rec", "If the phone shows only the wallpaper or a black screen, allow Screen Recording for "
                 "the app this helper runs in (System Settings, Privacy & Security, Screen Recording), and start it again.")
    say("Showing this computer's %s on the phone (%dx%d)." % ("extra monitor" if len(monitors()) > 1 else "screen, mirrored,", geom[2], geom[3]))
    dest = ["-f", "h264", "tcp://%s:%d?tcp_nodelay=1" % (at, port)]
    for args in capture_tries(geom, w, h, fps, dest, grab):
        if gen != screen_gen:
            return
        try:
            p = subprocess.Popen([ff] + args, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        except OSError as e:
            say("Could not start ffmpeg (%s)." % e)
            return
        stream_proc = p
        try:
            err = p.communicate(timeout=4)[1].decode("utf-8", "replace")
        except subprocess.TimeoutExpired:
            # Still going after a few seconds: it works; it runs until the phone or this helper ends it.
            err = p.communicate()[1].decode("utf-8", "replace")
            if gen == screen_gen and not re.search(r"Connection (refused|reset)|Broken pipe", err):
                log("Second screen ended: %s" % err.strip()[-300:])
            return
        if gen != screen_gen:
            return
        if re.search(r"Connection (refused|reset)|Broken pipe", err):
            return   # the phone closed it
        log("Second screen, trying the next encoder: %s" % err.strip()[-300:])
    say("Could not stream this screen to the phone.")


def stream_wlroots(at, port, w, h, fps, gen):
    """wf-recorder writes the stream; this helper hands it to the phone."""
    global stream_proc, shown
    shown = None
    try:
        sock = socket.create_connection((at, port), timeout=10)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except OSError as e:
        say("Could not reach the phone's second screen (%s)." % e)
        return
    try:
        p = subprocess.Popen(["wf-recorder", "--muxer=h264", "--codec=libx264", "-p", "preset=ultrafast", "-p", "tune=zerolatency",
                              "-p", "bf=0", "-r", str(fps), "--file=/dev/stdout"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError as e:
        say("Could not start wf-recorder (%s)." % e)
        sock.close()
        return
    stream_proc = p
    say("Showing this computer's screen on the phone.")
    try:
        while gen == screen_gen:
            data = p.stdout.read1(1 << 16) if hasattr(p.stdout, "read1") else p.stdout.read(1 << 16)
            if not data:
                break
            sock.sendall(data)
    except OSError:
        pass
    finally:
        sock.close()
        if p.poll() is None:
            p.terminate()


def start_second_screen(at, port, w, h, fps):
    global screen_gen
    stop_second_screen()
    gen = screen_gen
    if WSL:
        say("The phone asked to be a second screen: under WSL the Windows helper shows it.")
        return
    kind = session_kind()
    if kind in ("x11", "mac"):
        stream_x11(at, port, w, h, fps, gen)
    elif kind == "wayland" and have("wf-recorder") and not re.search(r"GNOME|KDE", os.environ.get("XDG_CURRENT_DESKTOP", ""), re.I):
        stream_wlroots(at, port, w, h, fps, gen)
    elif kind == "wayland" and re.search(r"GNOME|KDE", os.environ.get("XDG_CURRENT_DESKTOP", ""), re.I):
        say("The phone asked to be a second screen. On GNOME or KDE under Wayland that needs their screen-sharing "
            "prompt, which this helper does not drive yet: log in to an X11 (Xorg) session to use it.")
    elif kind == "wayland":
        say("The phone asked to be a second screen. Under Wayland that needs wf-recorder (%s), for sway, "
            "Hyprland and other wlroots desktops; or log in to an X11 session." % install_hint("wf-recorder"))
    else:
        say("The phone asked to be a second screen, but this helper sees no desktop (no DISPLAY or WAYLAND_DISPLAY).")


def stop_second_screen():
    global screen_gen, stream_proc, shown
    screen_gen += 1
    p, stream_proc, shown = stream_proc, None, None
    if p and p.poll() is None:
        p.terminate()
        try:
            p.wait(timeout=2)
        except subprocess.TimeoutExpired:
            p.kill()


# ---------------------------------------------------------------------- volume

class PactlVolume(object):
    def get(self):
        out = run_text(["pactl", "get-sink-volume", "@DEFAULT_SINK@"]) or ""
        m = re.search(r"(\d+)%", out)
        mute = run_text(["pactl", "get-sink-mute", "@DEFAULT_SINK@"]) or ""
        return (int(m.group(1)) / 100.0 if m else None), "yes" in mute

    def set(self, level):
        run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", "%d%%" % round(level * 100)])

    def toggle_mute(self):
        run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"])


class WpctlVolume(object):
    def get(self):
        out = run_text(["wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"]) or ""
        m = re.search(r"Volume: ([\d.]+)", out)
        return (float(m.group(1)) if m else None), "MUTED" in out

    def set(self, level):
        run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "%.3f" % level])

    def toggle_mute(self):
        run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"])


class MacVolume(object):
    """AppleScript's volume settings."""
    def get(self):
        out = run_text(["osascript", "-e", "set s to get volume settings",
                        "-e", 'return (output volume of s as text) & "," & (output muted of s as text)']) or ""
        m = re.match(r"(\d+),(true|false)", out.strip())
        return (int(m.group(1)) / 100.0, m.group(2) == "true") if m else (None, False)

    def set(self, level):
        run(["osascript", "-e", "set volume output volume %d" % round(level * 100)])

    def toggle_mute(self):
        run(["osascript", "-e", "set volume output muted not (output muted of (get volume settings))"])


def volume_backend():
    if MAC:
        return MacVolume()
    if have("pactl") and run(["pactl", "info"]) is not None:
        return PactlVolume()
    if have("wpctl"):
        return WpctlVolume()
    return None


volume = None
volume_dirty = True


def volume_loop():
    """Reports the volume on connecting, right after a change from the phone, and when it changes here."""
    global volume_dirty
    last, n = None, 0
    while True:
        time.sleep(0.25)
        try:
            if not session or not phone or not volume:
                last = None
                continue
            n += 1
            if not volume_dirty and n < 8:
                continue
            n, volume_dirty = 0, False
            level, muted = volume.get()
            if level is None:
                continue
            now = json.dumps({"level": round(min(level, 1.0), 3), "muted": muted})
            if now == last:
                continue
            request("POST", "/api/control/volume", body=now, headers={"Content-Type": "application/json"}, timeout=3)
            last = now
        except (OSError, http.client.HTTPException):
            last = None


# ---------------------------------------------------------------------- trackpad and keyboard
#
# The phone's Control tab sends one command per line: m dx dy (move), b l|r|m d|u (button),
# c l|r|m (click), w dy dx (wheel, 120 a notch), z n (zoom), kd/ku/k NAME (key), h a+b (shortcut),
# t TEXT (type), v LEVEL (volume), vm (mute).

# Linux key codes (input-event-codes.h).
KEYS = {
    "enter": 28, "back": 14, "tab": 15, "esc": 1, "space": 57, "left": 105, "up": 103, "right": 106, "down": 108,
    "del": 111, "insert": 110, "home": 102, "end": 107, "pgup": 104, "pgdn": 109, "win": 125, "ctrl": 29, "alt": 56,
    "shift": 42, "f1": 59, "f2": 60, "f3": 61, "f4": 62, "f5": 63, "f6": 64, "f7": 65, "f8": 66, "f9": 67, "f10": 68,
    "f11": 87, "f12": 88, "playpause": 164, "next": 163, "prev": 165, "volup": 115, "voldown": 114, "mute": 113,
}
# A US keyboard: each character as (key code, shift).
US = {}
for _i, _c in enumerate("1234567890"):
    US[_c] = (2 + _i, False)
for _row, _start in (("qwertyuiop", 16), ("asdfghjkl", 30), ("zxcvbnm", 44)):
    for _i, _c in enumerate(_row):
        US[_c] = (_start + _i, False)
        US[_c.upper()] = (_start + _i, True)
for _c, _code in (("-", 12), ("=", 13), ("[", 26), ("]", 27), (";", 39), ("'", 40), ("`", 41), ("\\", 43), (",", 51),
                  (".", 52), ("/", 53), (" ", 57), ("\n", 28), ("\t", 15)):
    US[_c] = (_code, False)
for _c, _base in zip('!@#$%^&*()_+{}:"~|<>?', "1234567890-=[];'`\\,./"):
    US[_c] = (US[_base][0], True)
for _c in "abcdefghijklmnopqrstuvwxyz0123456789":
    KEYS.setdefault(_c, US[_c][0])

BTN = {"l": 0x110, "r": 0x111, "m": 0x112}

# Key codes are places on the keyboard, not letters: on these layouts some letters sit elsewhere,
# so a shortcut such as Ctrl+A is sent from where A is (on AZERTY, where Q is on US keys).
LAYOUT_LETTERS = {
    "fr": {"a": 16, "q": 30, "z": 17, "w": 44, "m": 39},
    "be": {"a": 16, "q": 30, "z": 17, "w": 44, "m": 39},
    "de": {"y": 44, "z": 21}, "at": {"y": 44, "z": 21}, "ch": {"y": 44, "z": 21},
    "cz": {"y": 44, "z": 21}, "hu": {"y": 44, "z": 21}, "sk": {"y": 44, "z": 21},
}
layout = None


def keyboard_layout():
    """This desktop's keyboard layout ("us", "de", "fr"...), as far as it can be told."""
    global layout
    if layout is None:
        found = os.environ.get("XKB_DEFAULT_LAYOUT", "")
        if not found:
            m = re.search(r"layout:\s*(\S+)", run_text(["setxkbmap", "-query"]) or "")
            found = m.group(1) if m else ""
        if not found:
            m = re.search(r"\('xkb', '([^']+)'\)", run_text(["gsettings", "get", "org.gnome.desktop.input-sources", "sources"]) or "")
            found = m.group(1) if m else ""
        if not found:
            m = re.search(r"X11 Layout: (\S+)", run_text(["localectl", "status"]) or "")
            found = m.group(1) if m else "us"
        layout = found.split(",")[0].split("+")[0].lower() or "us"
    return layout


class UInput(object):
    """A virtual keyboard and mouse through /dev/uinput: works on X11 and Wayland alike."""
    name = "uinput"
    EV_SYN, EV_KEY, EV_REL = 0, 1, 2
    REL_X, REL_Y, REL_HWHEEL, REL_WHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0, 1, 6, 8, 11, 12

    def __init__(self):
        self.wheel = [0, 0]
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT, UI_DEV_CREATE = 0x40045564, 0x40045565, 0x40045566, 0x5501
        for ev in (self.EV_SYN, self.EV_KEY, self.EV_REL):
            fcntl.ioctl(self.fd, UI_SET_EVBIT, ev)
        for code in set(KEYS.values()) | {c for c, _ in US.values()} | set(BTN.values()):
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, code)
        for rel in (self.REL_X, self.REL_Y, self.REL_WHEEL, self.REL_HWHEEL, self.REL_WHEEL_HI_RES, self.REL_HWHEEL_HI_RES):
            fcntl.ioctl(self.fd, UI_SET_RELBIT, rel)
        # struct uinput_user_dev: name, id (bus, vendor, product, version), ff_effects_max, abs arrays.
        dev = struct.pack("80sHHHHi" + "64i" * 4, b"Localhost 8787 trackpad", 0x03, 0x1D6B, 0x8787, 1, 0, *([0] * 256))
        os.write(self.fd, dev)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)
        time.sleep(0.3)   # let the desktop pick the new device up

    def emit(self, t, code, value):
        os.write(self.fd, struct.pack("llHHi", 0, 0, t, code, value))

    def syn(self):
        self.emit(self.EV_SYN, 0, 0)

    def move(self, dx, dy):
        if dx:
            self.emit(self.EV_REL, self.REL_X, dx)
        if dy:
            self.emit(self.EV_REL, self.REL_Y, dy)
        self.syn()

    def button(self, which, down):
        self.emit(self.EV_KEY, BTN.get(which, BTN["l"]), 1 if down else 0)
        self.syn()

    def scroll(self, dy, dx):
        # High-resolution wheel (120 a notch, as the phone sends), and whole notches for older apps.
        for axis, hi, lo, v in ((0, self.REL_WHEEL_HI_RES, self.REL_WHEEL, dy), (1, self.REL_HWHEEL_HI_RES, self.REL_HWHEEL, dx)):
            if not v:
                continue
            self.emit(self.EV_REL, hi, v)
            self.wheel[axis] += v
            notches = int(self.wheel[axis] / 120)
            if notches:
                self.wheel[axis] -= notches * 120
                self.emit(self.EV_REL, lo, notches)
        self.syn()

    def key(self, name, down):
        code = LAYOUT_LETTERS.get(keyboard_layout(), {}).get(name) or KEYS.get(name)
        if code is not None:
            self.emit(self.EV_KEY, code, 1 if down else 0)
            self.syn()

    def point_at(self, fx, fy):
        """A tap on the second screen: an absolute pointer (as a virtual machine's tablet is) goes there."""
        if not hasattr(self, "abs_fd"):
            UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_ABSBIT, UI_DEV_CREATE = 0x40045564, 0x40045565, 0x40045567, 0x5501
            self.abs_fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
            for ev in (self.EV_SYN, self.EV_KEY, 3):
                fcntl.ioctl(self.abs_fd, UI_SET_EVBIT, ev)
            for code in BTN.values():
                fcntl.ioctl(self.abs_fd, UI_SET_KEYBIT, code)
            for axis in (0, 1):
                fcntl.ioctl(self.abs_fd, UI_SET_ABSBIT, axis)
            absmax = [0] * 64
            absmax[0] = absmax[1] = 65535
            dev = struct.pack("80sHHHHi" + "64i" * 4, b"Localhost 8787 second screen", 0x03, 0x1D6B, 0x8788, 1, 0,
                              *(absmax + [0] * 192))
            os.write(self.abs_fd, dev)
            fcntl.ioctl(self.abs_fd, UI_DEV_CREATE)
            time.sleep(0.3)
        nx, ny = to_desktop(fx, fy)
        for axis, v in ((0, nx), (1, ny)):
            os.write(self.abs_fd, struct.pack("llHHi", 0, 0, 3, axis, int(round(v * 65535))))
        os.write(self.abs_fd, struct.pack("llHHi", 0, 0, 0, 0, 0))

    def type(self, text):
        if keyboard_layout() != "us":
            return False   # other layouts put symbols elsewhere: the clipboard carries the text instead
        for ch in text:
            code, shift = US.get(ch, (None, False))
            if code is None:
                continue
            if shift:
                self.emit(self.EV_KEY, 42, 1)
            self.emit(self.EV_KEY, code, 1)
            self.syn()
            self.emit(self.EV_KEY, code, 0)
            if shift:
                self.emit(self.EV_KEY, 42, 0)
            self.syn()
        return all(ch in US for ch in text)


XDO_KEYS = {
    "enter": "Return", "back": "BackSpace", "tab": "Tab", "esc": "Escape", "space": "space", "left": "Left", "up": "Up",
    "right": "Right", "down": "Down", "del": "Delete", "insert": "Insert", "home": "Home", "end": "End", "pgup": "Prior",
    "pgdn": "Next", "win": "super", "ctrl": "ctrl", "alt": "alt", "shift": "shift", "playpause": "XF86AudioPlay",
    "next": "XF86AudioNext", "prev": "XF86AudioPrev", "volup": "XF86AudioRaiseVolume", "voldown": "XF86AudioLowerVolume",
    "mute": "XF86AudioMute",
}


class XdoTool(object):
    """X11: xdotool, one process per action; moves are gathered and sent a few times a second."""
    name = "xdotool"

    def __init__(self):
        self.pending = [0, 0]
        self.wheel = [0, 0]
        self.lock = threading.Lock()
        threading.Thread(target=self.flush_loop, daemon=True).start()

    def xdo(self, *args):
        run(["xdotool"] + [str(a) for a in args], timeout=3)

    def flush(self):
        """Sends the moves gathered so far; everything else waits for them, so a click lands where the pointer went."""
        with self.lock:
            dx, dy = self.pending
            self.pending = [0, 0]
            if dx or dy:
                self.xdo("mousemove_relative", "--", dx, dy)

    def flush_loop(self):
        while True:
            time.sleep(0.012)
            self.flush()

    def move(self, dx, dy):
        with self.lock:
            self.pending[0] += dx
            self.pending[1] += dy

    def button(self, which, down):
        self.flush()
        self.xdo("mousedown" if down else "mouseup", {"l": 1, "m": 2, "r": 3}.get(which, 1))

    def scroll(self, dy, dx):
        self.flush()
        for axis, v, pos, neg in ((0, dy, 4, 5), (1, dx, 7, 6)):
            if not v:
                continue
            self.wheel[axis] += v
            notches = int(self.wheel[axis] / 120)
            if notches:
                self.wheel[axis] -= notches * 120
                self.xdo("click", "--repeat", abs(notches), pos if notches > 0 else neg)

    def key(self, name, down):
        self.flush()
        sym = XDO_KEYS.get(name) or (name.upper() if name.startswith("f") and name[1:].isdigit() else name)
        self.xdo("keydown" if down else "keyup", sym)

    def type(self, text):
        self.flush()
        self.xdo("type", "--delay", 0, "--", text)
        return True

    def point_at(self, fx, fy):
        size = desktop_size()
        if size:
            nx, ny = to_desktop(fx, fy)
            with self.lock:
                self.pending = [0, 0]
                self.xdo("mousemove", int(round(nx * (size[0] - 1))), int(round(ny * (size[1] - 1))))


class YdoTool(object):
    """ydotool (its daemon, ydotoold, must be running): works on Wayland too."""
    name = "ydotool"

    def __init__(self):
        self.wheel = [0, 0]

    def ydo(self, *args):
        run(["ydotool"] + [str(a) for a in args], timeout=3)

    def move(self, dx, dy):
        self.ydo("mousemove", "-x", dx, "-y", dy)

    def button(self, which, down):
        code = {"l": 0x00, "r": 0x01, "m": 0x02}.get(which, 0)
        self.ydo("click", hex(code | (0x40 if down else 0x80)))

    def scroll(self, dy, dx):
        for axis, v in ((0, dy), (1, dx)):
            if not v:
                continue
            self.wheel[axis] += v
            notches = int(self.wheel[axis] / 120)
            if notches:
                self.wheel[axis] -= notches * 120
                self.ydo("mousemove", "-w", "-x", notches if axis else 0, "-y", 0 if axis else notches)

    def key(self, name, down):
        code = KEYS.get(name)
        if code is not None:
            self.ydo("key", "%d:%d" % (code, 1 if down else 0))

    def type(self, text):
        self.ydo("type", "--", text)
        return True

    def point_at(self, fx, fy):
        size = desktop_size()
        if size:
            nx, ny = to_desktop(fx, fy)
            self.ydo("mousemove", "--absolute", "-x", int(round(nx * (size[0] - 1))), "-y", int(round(ny * (size[1] - 1))))


# ---------------------------------------------------------------------- macOS: Quartz
#
# CoreGraphics through ctypes: the displays (in points, as the pointer moves), and posted events
# for the pointer and keys. Posting events needs Accessibility access for the app this helper runs
# in (Terminal, say); without it macOS drops them silently, so the helper asks at the start.

quartz = None


def cg():
    """CoreGraphics, loaded once, with the calls this helper makes typed for ctypes."""
    global quartz
    if quartz is None:
        import ctypes
        import ctypes.util

        class CGPoint(ctypes.Structure):
            _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]

        class CGSize(ctypes.Structure):
            _fields_ = [("width", ctypes.c_double), ("height", ctypes.c_double)]

        class CGRect(ctypes.Structure):
            _fields_ = [("origin", CGPoint), ("size", CGSize)]

        q = ctypes.CDLL(ctypes.util.find_library("CoreGraphics") or "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
        cf = ctypes.CDLL(ctypes.util.find_library("CoreFoundation") or "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        vp, u32 = ctypes.c_void_p, ctypes.c_uint32
        for name, res, args in (
                ("CGEventCreate", vp, [vp]), ("CGEventGetLocation", CGPoint, [vp]),
                ("CGEventCreateMouseEvent", vp, [vp, u32, CGPoint, u32]),
                ("CGEventCreateKeyboardEvent", vp, [vp, ctypes.c_uint16, ctypes.c_bool]),
                ("CGEventCreateScrollWheelEvent2", vp, [vp, u32, u32, ctypes.c_int32, ctypes.c_int32, ctypes.c_int32]),
                ("CGEventPost", None, [u32, vp]), ("CGEventSetFlags", None, [vp, ctypes.c_uint64]),
                ("CGEventSetIntegerValueField", None, [vp, u32, ctypes.c_int64]),
                ("CGEventKeyboardSetUnicodeString", None, [vp, ctypes.c_ulong, ctypes.POINTER(ctypes.c_uint16)]),
                ("CGGetActiveDisplayList", ctypes.c_int32, [u32, ctypes.POINTER(u32), ctypes.POINTER(u32)]),
                ("CGDisplayBounds", CGRect, [u32]), ("CGMainDisplayID", u32, [])):
            f = getattr(q, name)
            f.restype, f.argtypes = res, args
        cf.CFRelease.restype, cf.CFRelease.argtypes = None, [vp]
        quartz = (q, cf, CGPoint, ctypes)
    return quartz


def mac_displays():
    """[(id, x, y, w, h, main)] of the displays in use, in points, in CoreGraphics' order."""
    try:
        q, _, _, ctypes = cg()
        ids, n = (ctypes.c_uint32 * 16)(), ctypes.c_uint32()
        if q.CGGetActiveDisplayList(16, ids, ctypes.byref(n)) != 0:
            return []
        main = q.CGMainDisplayID()
        out = []
        for i in range(n.value):
            r = q.CGDisplayBounds(ids[i])
            out.append((ids[i], int(r.origin.x), int(r.origin.y), int(r.size.width), int(r.size.height), ids[i] == main))
        return out
    except (OSError, AttributeError):
        return []


# Where keys are on a Mac's keyboard (virtual key codes, the same on every layout for the keys
# that are not letters; letters as on US keys, for shortcuts).
MAC_KEYS = {
    "enter": 0x24, "back": 0x33, "tab": 0x30, "esc": 0x35, "space": 0x31, "left": 0x7B, "right": 0x7C, "down": 0x7D,
    "up": 0x7E, "del": 0x75, "insert": 0x72, "home": 0x73, "end": 0x77, "pgup": 0x74, "pgdn": 0x79,
    "f1": 0x7A, "f2": 0x78, "f3": 0x63, "f4": 0x76, "f5": 0x60, "f6": 0x61, "f7": 0x62, "f8": 0x64, "f9": 0x65,
    "f10": 0x6D, "f11": 0x67, "f12": 0x6F,
}
for _c, _code in zip("asdfhgzxcv" + "bqweryt123465=97-80]ou[ip" + "lj'k;\\,/nm.`",
                     list(range(0x00, 0x0A)) + list(range(0x0B, 0x24)) + list(range(0x25, 0x30)) + [0x32]):
    MAC_KEYS.setdefault(_c, _code)
# The modifiers: key code and event flag. The phone speaks Windows: its Ctrl is the Mac's Command
# (Ctrl+C is ⌘C), its Alt too (the app switcher is ⌘-Tab), and its Windows key is Control.
MAC_MODS = {"cmd": (0x37, 0x100000), "shift": (0x38, 0x20000), "option": (0x3A, 0x80000), "control": (0x3B, 0x40000)}
MAC_MOD_OF = {"ctrl": "cmd", "alt": "cmd", "shift": "shift", "win": "control"}
# Windows shortcuts the phone's gestures send, as the Mac does the same things.
MAC_COMBOS = {
    ("win", "tab"): ["control", "up"],          # Task View: Mission Control
    ("win", "d"): ["f11"],                      # show the desktop
    ("ctrl", "win", "right"): ["control", "right"], ("ctrl", "win", "left"): ["control", "left"],   # switch Spaces
    ("win",): ["cmd", "space"],                 # the Start menu: Spotlight
    ("alt", "f4"): ["cmd", "q"],
    ("ctrl", "alt", "del"): ["cmd", "option", "esc"], ("ctrl", "shift", "esc"): ["cmd", "option", "esc"],   # Force Quit
}
MEDIA = {"playpause": 16, "next": 17, "prev": 18}   # NX_KEYTYPE_*


class MacInput(object):
    """Quartz events: the pointer, buttons (double-clicks counted, as macOS wants), scrolling, keys and typing."""
    name = "Quartz"

    def __init__(self):
        self.q, self.cf, self.CGPoint, self.ctypes = cg()
        self.down = set()        # buttons held
        self.mods = set()        # Mac modifiers held
        self.last_click = (0.0, "", 0.0, 0.0, 0)   # time, button, x, y, count

    def post(self, e):
        if e:
            self.q.CGEventPost(0, e)   # kCGHIDEventTap
            self.cf.CFRelease(e)

    def where(self):
        e = self.q.CGEventCreate(None)
        p = self.q.CGEventGetLocation(e)
        self.cf.CFRelease(e)
        return p.x, p.y

    def mouse_to(self, x, y):
        # Kept on the screens; a move with a button held is a drag.
        mons = mac_displays()
        if mons:
            x = max(min(m[1] for m in mons), min(x, max(m[1] + m[3] for m in mons) - 1))
            y = max(min(m[2] for m in mons), min(y, max(m[2] + m[4] for m in mons) - 1))
        kind = 6 if "l" in self.down else 7 if "r" in self.down else 27 if "m" in self.down else 5
        button = 1 if kind == 7 else 2 if kind == 27 else 0
        self.post(self.q.CGEventCreateMouseEvent(None, kind, self.CGPoint(x, y), button))

    def move(self, dx, dy):
        x, y = self.where()
        self.mouse_to(x + dx, y + dy)

    def button(self, which, down):
        x, y = self.where()
        kind, button = {"l": (1, 0), "r": (3, 1), "m": (25, 2)}.get(which, (1, 0))
        if not down:
            kind += 1
        if down:
            t, b, lx, ly, n = self.last_click
            n = n + 1 if b == which and time.time() - t < 0.45 and abs(lx - x) < 6 and abs(ly - y) < 6 else 1
            self.last_click = (time.time(), which, x, y, n)
            self.down.add(which)
        else:
            self.down.discard(which)
        e = self.q.CGEventCreateMouseEvent(None, kind, self.CGPoint(x, y), button)
        self.q.CGEventSetIntegerValueField(e, 1, self.last_click[4])   # kCGMouseEventClickState: 2 is a double-click
        self.post(e)

    def scroll(self, dy, dx):
        # Pixels, 60 to the phone's notch; the second wheel runs the other way round to Windows'.
        self.post(self.q.CGEventCreateScrollWheelEvent2(None, 0, 2, int(dy / 2), int(-dx / 2), 0))

    def flags(self):
        f = 0
        for m in self.mods:
            f |= MAC_MODS[m][1]
        return f

    def keycode(self, name, down):
        """A key by its code, with the modifiers held."""
        if name in MAC_MODS:
            (self.mods.add if down else self.mods.discard)(name)
            code = MAC_MODS[name][0]
        else:
            code = MAC_KEYS.get(name)
            if code is None:
                return
        e = self.q.CGEventCreateKeyboardEvent(None, code, down)
        self.q.CGEventSetFlags(e, self.flags())
        self.post(e)

    def key(self, name, down):
        global volume_dirty
        if name in MEDIA:
            if down:
                mac_media_key(MEDIA[name])
            return
        if name in ("volup", "voldown", "mute"):
            if down and volume:
                if name == "mute":
                    volume.toggle_mute()
                else:
                    level = volume.get()[0]
                    if level is not None:
                        volume.set(max(0.0, min(1.0, level + (0.0625 if name == "volup" else -0.0625))))
                volume_dirty = True
            return
        self.keycode(MAC_MOD_OF.get(name, name), down)

    def combo(self, keys):
        """A Windows shortcut that the Mac does with other keys; False to send it as it is."""
        mac = MAC_COMBOS.get(tuple(keys))
        if not mac:
            return False
        for k in mac:
            self.keycode(k, True)
        for k in reversed(mac):
            self.keycode(k, False)
        return True

    def type(self, text):
        # Any character, on any layout: the key event carries the text itself.
        for ch in text:
            if ch in "\r\n":
                self.keycode("enter", True)
                self.keycode("enter", False)
                continue
            units = ch.encode("utf-16-le")
            buf = (self.ctypes.c_uint16 * (len(units) // 2)).from_buffer_copy(units)
            for down in (True, False):
                e = self.q.CGEventCreateKeyboardEvent(None, 0, down)
                self.q.CGEventKeyboardSetUnicodeString(e, len(units) // 2, buf)
                self.post(e)
        return True

    def point_at(self, fx, fy):
        """A tap on the second screen: the pointer goes to that spot of the display shown."""
        mons = mac_displays()
        x, y, w, h = shown or next(((m[1], m[2], m[3], m[4]) for m in mons if m[5]), (0, 0, 1, 1))
        self.mouse_to(x + fx * (w - 1), y + fy * (h - 1))


def mac_media_key(code):
    """Play/pause, next and previous, as the keyboard's media keys send them: to whatever is playing."""
    jxa("ObjC.import('AppKit'); ObjC.import('CoreGraphics');"
        "[0xa, 0xb].forEach(function (s) { var e = $.NSEvent.otherEventWithTypeLocationModifierFlagsTimestampWindowNumberContextSubtypeData1Data2("
        "14, {x: 0, y: 0}, s << 8, 0, 0, null, 8, (%d << 16) | (s << 8), -1); $.CGEventPost(0, e.CGEvent); }); 'ok';" % code)


def mac_trusted(prompt):
    """Whether this process may post events (Accessibility); with prompt, macOS offers to allow it."""
    try:
        import ctypes
        import ctypes.util
        ax = ctypes.CDLL("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
        if not prompt:
            ax.AXIsProcessTrusted.restype = ctypes.c_bool
            return bool(ax.AXIsProcessTrusted())
        cf = ctypes.CDLL(ctypes.util.find_library("CoreFoundation"))
        key = ctypes.c_void_p.in_dll(ax, "kAXTrustedCheckOptionPrompt")
        true = ctypes.c_void_p.in_dll(cf, "kCFBooleanTrue")
        cf.CFDictionaryCreate.restype = ctypes.c_void_p
        cf.CFDictionaryCreate.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p),
                                          ctypes.c_long, ctypes.c_void_p, ctypes.c_void_p]
        keys, values = (ctypes.c_void_p * 1)(key), (ctypes.c_void_p * 1)(true)
        d = cf.CFDictionaryCreate(None, keys, values, 1,
                                  ctypes.addressof(ctypes.c_void_p.in_dll(cf, "kCFTypeDictionaryKeyCallBacks")),
                                  ctypes.addressof(ctypes.c_void_p.in_dll(cf, "kCFTypeDictionaryValueCallBacks")))
        ax.AXIsProcessTrustedWithOptions.restype = ctypes.c_bool
        ax.AXIsProcessTrustedWithOptions.argtypes = [ctypes.c_void_p]
        return bool(ax.AXIsProcessTrustedWithOptions(d))
    except (OSError, ValueError, AttributeError):
        return True   # cannot tell: carry on, and the events say for themselves


def to_desktop(fx, fy):
    """A point on what the phone shows (fractions of it) as fractions of the whole desktop."""
    size = desktop_size()
    if not shown or not size:
        return fx, fy
    x, y, w, h = shown
    return ((x + fx * (w - 1)) / max(1, size[0] - 1), (y + fy * (h - 1)) / max(1, size[1] - 1))


def input_backend():
    if MAC:
        if not mac_trusted(False):
            mac_trusted(True)   # macOS shows its own dialog, pointing at the setting
            say("The phone's trackpad and keyboard need Accessibility access for the app running this helper: System "
                "Settings, Privacy & Security, Accessibility, turn it on for Terminal (or the app you use), then start the helper again.")
        return MacInput()
    if WSL:
        say_once("wsl-input", "Under WSL the Windows helper runs the trackpad and keyboard; this one keeps to the page and the clipboard.")
        return None
    if os.access("/dev/uinput", os.W_OK):
        try:
            return UInput()
        except OSError as e:
            log("uinput: %s" % e)
    if os.environ.get("DISPLAY") and os.environ.get("XDG_SESSION_TYPE", "x11") != "wayland" and have("xdotool"):
        return XdoTool()
    if have("ydotool"):
        return YdoTool()
    return None


pointer = None


def type_text(text):
    """Typed as keys where the keyboard has them; anything else goes in through the clipboard."""
    global last_text
    if pointer.type(text):
        return
    if clip and clip.write("UTF8_STRING" if clip.name in ("xclip", "xsel") else "text/plain;charset=utf-8", text.encode("utf-8")):
        last_text = text
        pointer.key("ctrl", True)
        pointer.key("v", True)
        pointer.key("v", False)
        pointer.key("ctrl", False)


def handle(line):
    global volume_dirty
    a = line.split(" ")
    cmd = a[0]
    if cmd == "p" or not cmd:
        return
    if cmd in ("v", "vm"):
        if not volume:
            say_once("no-volume", "The volume needs pactl or wpctl (PipeWire or PulseAudio).")
            return
        if cmd == "v":
            volume.set(max(0.0, min(1.0, float(a[1]))))
        else:
            volume.toggle_mute()
        volume_dirty = True
        return
    if cmd == "da":
        if pointer and hasattr(pointer, "point_at"):
            pointer.point_at(max(0.0, min(1.0, float(a[1]))), max(0.0, min(1.0, float(a[2]))))
        return
    if not pointer:
        if not WSL:   # under WSL, the Windows helper takes these
            say_once("no-input", "The phone's trackpad and keyboard need one of: write access to /dev/uinput (for this session: "
                     "sudo setfacl -m u:$USER:rw /dev/uinput), xdotool on X11 (%s), or ydotool." % install_hint("xdotool"))
        return
    if cmd == "m":
        pointer.move(int(a[1]), int(a[2]))
    elif cmd == "b":
        pointer.button(a[1], a[2] == "d")
    elif cmd == "c":
        pointer.button(a[1], True)
        pointer.button(a[1], False)
    elif cmd == "w":
        pointer.scroll(int(a[1]), int(a[2]))
    elif cmd == "z":
        pointer.key("ctrl", True)
        pointer.scroll(120 * int(a[1]), 0)
        pointer.key("ctrl", False)
    elif cmd == "kd":
        pointer.key(a[1], True)
    elif cmd == "ku":
        pointer.key(a[1], False)
    elif cmd == "k":
        if hasattr(pointer, "combo") and pointer.combo([a[1]]):
            return
        pointer.key(a[1], True)
        pointer.key(a[1], False)
    elif cmd == "h":
        keys = a[1].split("+")
        if hasattr(pointer, "combo") and pointer.combo(keys):
            return
        for k in keys:
            pointer.key(k, True)
        for k in reversed(keys):
            pointer.key(k, False)
    elif cmd == "t":
        type_text(urllib.parse.unquote(line[2:]))


def open_page(port):
    url = "http://localhost:%d/" % port
    say("Opening the Localhost 8787 page at %s for full-speed transfers." % url)
    for cmd in (["open", url] if MAC else None, ["xdg-open", url], ["wslview", url], ["cmd.exe", "/c", "start", "", url] if WSL else None):
        if cmd and have(cmd[0]):
            try:
                subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
                return
            except OSError:
                continue


def control_loop(no_browser):
    global session
    path = os.path.join(CONF, "session.txt")
    cookie = read_file(path) or None
    announced = False
    while True:
        at = phone
        conn = None
        try:
            if cookie is None:
                cookie = pair()
            session = cookie
            at = phone
            conn, r = open_stream("/api/control/stream", headers={"Bridge-Heartbeat": "slow"})
            if r.status == 401:
                say("The phone no longer knows this computer; asking again.")
                cookie = None
                try:
                    os.remove(path)
                except OSError:
                    pass
                continue
            if r.status != 200:
                raise OSError("the phone answered %d" % r.status)
            if not announced:
                say("Ready. The phone's Control tab now drives this computer." if pointer else "Ready.")
                relay_ready.wait(3)
                if local_port and not no_browser:
                    open_page(local_port)
                elif local_port:
                    say("The page: http://localhost:%d/" % local_port)
                announced = True
            else:
                say("Reconnected, %s." % link_name(at))
            while True:
                raw = r.fp.readline()
                if not raw:
                    break
                try:
                    handle(raw.decode("utf-8", "replace").rstrip("\r\n"))
                except (ValueError, IndexError, OSError) as e:
                    log("Control: %s" % e)
            if phone == at:
                say("The phone closed the connection.")
        except (OSError, http.client.HTTPException, RuntimeError, ValueError) as e:
            # Moving to another link drops this stream on purpose; it reconnects there.
            if phone == at:
                say("Lost the phone (%s)." % e)
        finally:
            if conn:
                close_stream(conn)
        time.sleep(0.8)
        if phone is None or not ping(phone):
            find_phone(False)


# ---------------------------------------------------------------------- starting and stopping

def take_over():
    """Starting the helper again replaces the copy already running."""
    pid_file = os.path.join(CONF, "helper.pid")
    old = read_file(pid_file)
    if old.isdigit() and int(old) != os.getpid():
        try:
            if MAC:
                cmdline = run(["ps", "-p", old, "-o", "command="]) or b""
            else:
                with open("/proc/%s/cmdline" % old, "rb") as f:
                    cmdline = f.read()
            if b"blazeit-" in cmdline:
                os.kill(int(old), signal.SIGTERM)
                for _ in range(30):
                    time.sleep(0.1)
                    try:
                        os.kill(int(old), 0)
                    except OSError:
                        break
                say("Replaced the helper that was already running.")
        except (OSError, ValueError):
            pass
    write_file(pid_file, str(os.getpid()))


def stop(*_):
    try:
        stop_second_screen()
        if hasattr(clip, "close"):
            clip.close()   # the Mac's clipboard watcher, which would otherwise outlive the helper
    except Exception:
        pass
    try:
        if direct_ssid:
            leave_direct(quiet=True)
    except Exception:
        pass
    try:
        if read_file(os.path.join(CONF, "helper.pid")) == str(os.getpid()):
            os.remove(os.path.join(CONF, "helper.pid"))
    except OSError:
        pass
    print()
    say("Stopped.")
    os._exit(0)


def main():
    global clip, volume, pointer, NAME
    ap = argparse.ArgumentParser(description="Localhost 8787 laptop helper for Linux and macOS.")
    ap.add_argument("--phone", help="the phone's address, to skip the search")
    ap.add_argument("--no-browser", action="store_true", help="do not open the page")
    ap.add_argument("--name", help="this computer's name on the phone (default: %s)" % NAME)
    args = ap.parse_args()
    if args.name:
        NAME = args.name
    os.makedirs(CONF, exist_ok=True)
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGHUP, stop)
    take_over()
    say("Localhost 8787 laptop helper for %s, as \"%s\". Keep this terminal open; Ctrl+C stops it." % (SYSTEM, NAME))

    clip = clipboard_backend()
    if not clip and not MAC:
        say("The clipboard stays unsynced until wl-clipboard (Wayland) or xclip (X11) is installed: %s." %
            install_hint("wl-clipboard" if os.environ.get("WAYLAND_DISPLAY") else "xclip"))
    volume = volume_backend()
    pointer = input_backend()
    if not pointer and not WSL and not MAC:
        say("The phone's trackpad and keyboard need one of: write access to /dev/uinput (for this session: "
            "sudo setfacl -m u:$USER:rw /dev/uinput), xdotool on X11 (%s), or ydotool." % install_hint("xdotool"))

    find_phone(True, typed=args.phone)
    for loop in (relay_loop, link_loop, direct_loop, events_loop, volume_loop) + ((clip_loop,) if clip else ()):
        threading.Thread(target=loop, daemon=True).start()
    control_loop(args.no_browser)


if __name__ == "__main__":
    main()
