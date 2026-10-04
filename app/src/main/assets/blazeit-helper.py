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
import base64
import collections
import fcntl
import hashlib
import hmac
import http.client
import json
import os
import random
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

PHONE_PORT = 8787
# Downloaded through the phone's website: its name and a one-time sign-in code, put here by the
# phone, so this computer signs itself in without a PIN.
SITE_SIGNIN = None
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
    if on_tunnel(addr):
        return "over the internet, through the tunnel"
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


_asking = None
_typed = []
_typed_cv = threading.Condition()


def ask_once(question):
    """Asks once and reads the answer on its own thread; not asked again while it waits."""
    global _asking
    with _typed_cv:
        if _asking and _asking.is_alive():
            return

        def read():
            try:
                line = input(question)
            except EOFError:
                line = ""
            with _typed_cv:
                _typed.append(line.strip())
                _typed_cv.notify_all()

        _asking = threading.Thread(target=read, daemon=True)
        _asking.start()


def take_typed():
    """What was typed since the last look, or None."""
    with _typed_cv:
        return _typed.pop(0) if _typed else None


def find_phone(first_time, typed=None):
    told = False
    while True:
        if typed and ":" in typed:
            # An IPv6 address from the phone's Home: where to find it over the internet now.
            typed = typed.strip("[]")
            c = tunnel_conf()
            if c:
                tunnel_save_addrs([typed] + [a for a in c.get("addrs") or [] if a != typed])
            else:
                say("This computer has not been paired with the phone yet: pair once on the same network first.")
            typed = None
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
        addr = tunnel_path()
        if addr and ping(addr):
            moved = phone != addr
            move_to(addr)
            if moved:
                say("Found the phone %s." % link_name(addr))
            return
        if first_time and sys.stdin.isatty():
            # The question waits for an answer on its own thread: the search goes on meanwhile, and a
            # phone that turns up (plugged in, switched on) is found without anyone pressing a key.
            known = read_file(os.path.join(CONF, "site.txt")).strip()
            ask_once("Could not find the phone; still looking. Type the address it shows, or its website (%s) to "
                     "ask the phone from anywhere: " % (known or "yourname.dynv6.net"))
            t = take_typed()
            if t is None:
                time.sleep(2)
                continue
            # The phone's website: ask the phone through it, and get the tunnel's keys that way.
            site = re.sub(r"^https?://|[:/].*$", "", t.lower())
            if re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)+", site) and re.search(r"[a-z]", site):
                site_sign_in(site)
                continue
            m = re.search(r"(\d{1,3}(?:\.\d{1,3}){3}|[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7})", t)
            if m:
                typed = m.group(1)
                continue
        elif not told:
            say("Waiting for the phone. Switch Localhost 8787 on, or check both are on the same Wi-Fi.")
            told = True
        time.sleep(2)


def site_keep(name, cookie, conf):
    """Keeps what a sign-in through the website gave: the session, and the tunnel's keys."""
    if not conf.get("key"):
        raise OSError("the phone gave no tunnel keys")
    write_file(os.path.join(CONF, "session.txt"), cookie, private=True)
    write_file(os.path.join(CONF, "tunnel.json"), json.dumps(conf), private=True)
    write_file(os.path.join(CONF, "site.txt"), name, private=True)


def site_enroll(name, code):
    """With the one-time code baked into a helper downloaded through the website: no PIN."""
    try:
        r = urllib.request.Request("https://%s:8443/api/site/enroll" % name, data=json.dumps({"code": code}).encode(),
                                   method="POST", headers={"Content-Type": "application/json", "User-Agent": user_agent()})
        try:
            resp = urllib.request.urlopen(r, timeout=20)
        except urllib.error.HTTPError as e:
            try:
                why = json.loads(e.read().decode("utf-8")).get("message")
            except ValueError:
                why = None
            say(why or "The phone refused this computer's sign-in code.")
            return False
        m = re.search(r"xoosh_session=([^;,\s]+)", resp.headers.get("Set-Cookie") or "")
        if not m:
            raise OSError("the phone sent no session")
        site_keep(name, "xoosh_session=" + m.group(1), json.loads(resp.read().decode("utf-8")))
        say("Signed in to the phone through %s. This computer finds it by itself from now on: the cable or Wi-Fi "
            "when it is close, the internet when it is not." % name)
        return True
    except (OSError, ValueError, urllib.error.URLError) as e:
        say("Could not sign in through %s (%s)." % (name, e))
        return False


def site_sign_in(name):
    """From anywhere: asks the phone through its website (https://NAME:8443, a real certificate, so
    this is the phone and nobody reads along), as a browser there does; on Allow on the phone, keeps
    the session and the tunnel's keys, and from then on the tunnel finds the phone by itself."""
    base = "https://%s:8443" % name
    ua = {"User-Agent": user_agent()}
    try:
        try:
            r = urllib.request.urlopen(urllib.request.Request(base + "/api/pair", data=b"", method="POST", headers=ua), timeout=15)
        except urllib.error.HTTPError as e:
            try:
                why = json.loads(e.read().decode("utf-8")).get("message")
            except ValueError:
                why = None
            say(why or "The phone refused to ask.")
            return False
        d = json.loads(r.read().decode("utf-8") or "{}")
        say('On the phone, allow "Laptop control on %s (website)". Code: %s' % (NAME, d.get("code", "")))
        for _ in range(125):
            time.sleep(1)
            try:
                resp = urllib.request.urlopen(urllib.request.Request(base + "/api/pair/" + d.get("id", ""), headers=ua), timeout=15)
            except (OSError, urllib.error.URLError):
                continue
            text = resp.read().decode("utf-8", "replace")
            if "DENIED" in text:
                say("The phone said no.")
                return False
            if "EXPIRED" in text:
                say("Nobody answered on the phone.")
                return False
            if "APPROVED" not in text:
                continue
            m = re.search(r"xoosh_session=([^;,\s]+)", resp.headers.get("Set-Cookie") or "")
            if not m:
                raise OSError("the phone approved but sent no session")
            cookie = "xoosh_session=" + m.group(1)
            t = urllib.request.Request(base + "/api/tunnel", headers={"Cookie": cookie, "User-Agent": user_agent()})
            site_keep(name, cookie, json.loads(urllib.request.urlopen(t, timeout=15).read().decode("utf-8")))
            say("Allowed through %s. This computer finds the phone by itself from now on." % name)
            return True
        say("Nobody answered on the phone.")
        return False
    except (OSError, ValueError, urllib.error.URLError) as e:
        say("Could not reach the phone at %s (%s). The website needs IPv6 on this network; or pair once on the same "
            "network instead." % (base, e))
        return False


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


# ---------------------------------------------------------------------- the tunnel
#
# From another network: one TCP connection to the phone's global IPv6, our own encryption, every
# connection a stream inside it (docs/tunnel-protocol.md). The helper serves it on 127.0.0.1:18789
# the way it uses adb's port forward, so the relay, requests and event streams work unchanged.

TUNNEL_PORT = 8789
TUNNEL_LOCAL_PORT = 18789
T_HELLO, T_OPEN, T_DATA, T_FIN, T_RST, T_CREDIT, T_PING, T_PONG, T_ADDR, T_BYE = range(1, 11)
T_WINDOW = 512 * 1024      # unacknowledged bytes per stream, each way
T_CHUNK = 16384            # DATA body
T_CREDIT_STEP = 128 * 1024
T_IDLE_PING = 20
T_DEAD = 60
P25519 = 2 ** 255 - 19


def x25519(k, u):
    """RFC 7748. Plain integers, not constant-time: every key here is used once (see the spec)."""
    k = bytearray(k)
    k[0] &= 248
    k[31] &= 127
    k[31] |= 64
    k = int.from_bytes(bytes(k), "little")
    x1 = int.from_bytes(u, "little") & ((1 << 255) - 1)
    x2, z2, x3, z3, swap = 1, 0, x1, 1, 0
    for t in range(254, -1, -1):
        bit = (k >> t) & 1
        if swap ^ bit:
            x2, x3, z2, z3 = x3, x2, z3, z2
        swap = bit
        a, b, c, d = x2 + z2, x2 - z2, x3 + z3, x3 - z3
        aa, bb = a * a % P25519, b * b % P25519
        e = aa - bb
        da, cb = d * a % P25519, c * b % P25519
        x3, z3 = (da + cb) ** 2 % P25519, x1 * (da - cb) ** 2 % P25519
        x2, z2 = aa * bb % P25519, e * (aa + 121665 * e) % P25519
    if swap:
        x2, z2 = x3, z3
    return (x2 * pow(z2, P25519 - 2, P25519) % P25519).to_bytes(32, "little")


def hmac16(key, msg):
    return hmac.new(key, msg, hashlib.sha256).digest()[:16]


def hkdf_expand(prk, info, n):
    out, t, i = b"", b"", 1
    while len(out) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:n]


class TunnelCipher(object):
    """One direction: SHAKE256 keystream by frame counter, HMAC-SHA256 tag (encrypt-then-MAC)."""

    def __init__(self, enc, mac):
        self.enc, self.mac, self.n = enc, mac, 0

    def _stream(self, data):
        c = struct.pack(">Q", self.n)
        self.n += 1
        ks = hashlib.shake_256(self.enc + c).digest(len(data))
        return c, (int.from_bytes(data, "little") ^ int.from_bytes(ks, "little")).to_bytes(len(data), "little")

    def seal(self, pt):
        c, ct = self._stream(pt)
        ln = struct.pack(">I", len(ct))
        return ln + ct + hmac16(self.mac, c + ln + ct)

    def open(self, ct, tag):
        c = struct.pack(">Q", self.n)
        if not hmac.compare_digest(tag, hmac16(self.mac, c + struct.pack(">I", len(ct)) + ct)):
            raise OSError("a frame failed its check")
        return self._stream(ct)[1]


class FairLock(object):
    """Taken in the order asked for: on a slow link, streams take turns writing to the tunnel rather
    than the one that just wrote taking it straight back while the others wait."""

    def __init__(self):
        self._lock, self._waiting, self._held = threading.Lock(), collections.deque(), False

    def __enter__(self):
        with self._lock:
            if not self._held and not self._waiting:
                self._held = True
                return self
            turn = threading.Event()
            self._waiting.append(turn)
        turn.wait()   # handed over by the one before
        return self

    def __exit__(self, *_):
        with self._lock:
            if self._waiting:
                self._waiting.popleft().set()
            else:
                self._held = False


def recv_exact(s, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise OSError("the connection closed")
        buf += chunk
    return bytes(buf)


class TunnelStream(object):
    """A local connection carried as one stream: up from the socket, down to it through a queue."""

    def __init__(self, tunnel, sid, sock):
        self.t, self.sid, self.sock = tunnel, sid, sock
        self.window = T_WINDOW                  # what the phone will still take
        self.queue, self.queued = [], 0         # DATA waiting for the socket, None for FIN
        self.cv = threading.Condition()
        self.closed = False
        self.ends = 0                           # directions finished cleanly

    def start(self):
        threading.Thread(target=self.up, daemon=True).start()
        threading.Thread(target=self.down, daemon=True).start()

    def up(self):
        try:
            while True:
                data = self.sock.recv(T_CHUNK)
                if not data:
                    self.t.send(T_FIN, self.sid)
                    self.end()
                    return
                with self.cv:
                    while self.window < len(data) and not self.closed:
                        self.cv.wait(5)
                    if self.closed:
                        return
                    self.window -= len(data)
                self.t.send(T_DATA, self.sid, data)
        except OSError:
            self.reset("the local connection failed")

    def down(self):
        owed = 0
        try:
            while True:
                with self.cv:
                    while not self.queue and not self.closed:
                        self.cv.wait()
                    if not self.queue:
                        return
                    data = self.queue.pop(0)
                    if data is not None:
                        self.queued -= len(data)
                    empty = not self.queue
                if data is None:
                    self.sock.shutdown(socket.SHUT_WR)
                    self.end()
                    return
                self.sock.sendall(data)
                owed += len(data)
                if owed >= T_CREDIT_STEP or (empty and owed):
                    self.t.send(T_CREDIT, self.sid, struct.pack(">I", owed))
                    owed = 0
        except OSError:
            self.reset("the local connection failed")

    def end(self):
        """One direction is done; after both, the stream closes without a reset."""
        with self.cv:
            self.ends += 1
            if self.ends < 2 or self.closed:
                return
            self.closed = True
            self.cv.notify_all()
        try:
            self.sock.close()
        except OSError:
            pass
        self.t.forget(self.sid)

    def deliver(self, data):
        with self.cv:
            if self.closed:
                return
            if data is not None and self.queued + len(data) > T_WINDOW:
                raise OSError("the phone sent past the window")
            self.queue.append(data)
            if data is not None:
                self.queued += len(data)
            self.cv.notify_all()

    def credit(self, n):
        with self.cv:
            self.window += n
            self.cv.notify_all()

    def reset(self, why=None, send=True):
        with self.cv:
            if self.closed:
                return
            self.closed = True
            self.cv.notify_all()
        if send:
            try:
                self.t.send(T_RST, self.sid, (why or "").encode())
            except OSError:
                pass
        try:
            self.sock.close()
        except OSError:
            pass
        self.t.forget(self.sid)


class Tunnel(object):
    """The client end of one connection to the phone."""

    def __init__(self, sock, tx, rx, addr):
        self.sock, self.tx, self.rx, self.addr = sock, tx, rx, addr
        self.send_lock = FairLock()
        self.streams, self.next_sid, self.lock = {}, 1, threading.Lock()
        self.alive = True
        self.last_rx = self.last_tx = time.time()
        self.hello = threading.Event()
        self.info = {}
        self.sent = self.received = 0

    def send(self, kind, sid=0, body=b""):
        if isinstance(body, str):
            body = body.encode("utf-8")
        with self.send_lock:
            if not self.alive:
                raise OSError("the tunnel is closed")
            frame = self.tx.seal(struct.pack(">BI", kind, sid) + body)
            try:
                self.sock.sendall(frame)
            except OSError:
                self.close()
                raise
            self.sent += len(frame)
            self.last_tx = time.time()

    def open_stream(self, sock, port=PHONE_PORT):
        with self.lock:
            sid, self.next_sid = self.next_sid, self.next_sid + 2
            st = TunnelStream(self, sid, sock)
            self.streams[sid] = st
        self.send(T_OPEN, sid, struct.pack(">H", port))
        st.start()

    def forget(self, sid):
        with self.lock:
            self.streams.pop(sid, None)

    def run(self):
        """Reads frames until the connection ends."""
        try:
            while self.alive:
                ln = struct.unpack(">I", recv_exact(self.sock, 4))[0]
                if ln < 5 or ln > 65536 + 5:
                    raise OSError("a frame of %d bytes" % ln)
                rest = recv_exact(self.sock, ln + 16)
                pt = self.rx.open(rest[:ln], rest[ln:])
                self.received += ln + 20
                self.last_rx = time.time()
                kind, sid = struct.unpack(">BI", pt[:5])
                body = pt[5:]
                with self.lock:
                    st = self.streams.get(sid)
                if kind == T_DATA and st:
                    st.deliver(body)
                elif kind == T_FIN and st:
                    st.deliver(None)
                elif kind == T_CREDIT and st:
                    st.credit(struct.unpack(">I", body[:4])[0])
                elif kind == T_RST and st:
                    st.reset(send=False)
                elif kind == T_PING:
                    self.send(T_PONG, 0, body)
                elif kind in (T_HELLO, T_ADDR):
                    try:
                        self.info.update(json.loads(body.decode("utf-8")))
                    except ValueError:
                        pass
                    tunnel_save_addrs(self.info.get("addrs"))
                    self.hello.set()
                elif kind == T_BYE:
                    raise OSError("the phone closed the tunnel" + (": " + body.decode("utf-8", "replace") if body else ""))
        except OSError as e:
            if self.alive:
                log("Tunnel: %s" % e)
        finally:
            self.close()

    def keepalive(self):
        while self.alive:
            time.sleep(5)
            now = time.time()
            if now - self.last_rx > T_DEAD:
                log("Tunnel: nothing from the phone for %d s" % T_DEAD)
                self.close()
            elif now - self.last_tx > T_IDLE_PING:
                try:
                    self.send(T_PING, 0, os.urandom(8))
                except OSError:
                    pass

    def close(self):
        with self.lock:
            if not self.alive:
                return
            self.alive = False
            held = list(self.streams.values())
        for st in held:
            st.reset(send=False)
        try:
            self.sock.close()
        except OSError:
            pass


def tunnel_dial(host, port, tid, psk, timeout=8):
    """Connects over TCP and runs the handshake; the Tunnel is running when this returns."""
    s = socket.create_connection((host, port), timeout=timeout)
    try:
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    except OSError:
        s.close()
        raise
    return tunnel_start(s, tid, psk, (host, port), timeout)


def tunnel_start(s, tid, psk, addr, timeout=8):
    """The handshake over s (a TCP socket, or a UdpCarrier punched through), then the Tunnel."""
    try:
        s.settimeout(timeout)
        priv = os.urandom(32)
        hello = b"L87T\x01" + tid + x25519(priv, (9).to_bytes(32, "little"))
        hello += hmac16(psk, b"L87T/1 hello" + hello)
        s.sendall(hello)
        resp = recv_exact(s, 48)
        dh = x25519(priv, resp[:32])
        if dh == bytes(32):
            raise OSError("a bad key from the phone")
        th = hashlib.sha256(hello + resp[:32]).digest()
        okm = hkdf_expand(hmac.new(psk, dh, hashlib.sha256).digest(), b"L87T/1 keys" + th, 160)
        k = [okm[i:i + 32] for i in range(0, 160, 32)]
        if not hmac.compare_digest(resp[32:], hmac16(k[4], b"L87T/1 accept" + th)):
            raise OSError("the phone did not prove it knows this computer")
        s.settimeout(None)
    except (OSError, ValueError):
        s.close()
        raise
    t = Tunnel(s, TunnelCipher(k[0], k[1]), TunnelCipher(k[2], k[3]), addr)
    threading.Thread(target=t.run, daemon=True).start()
    threading.Thread(target=t.keepalive, daemon=True).start()
    t.send(T_HELLO, 0, json.dumps({"name": NAME, "v": 1}))
    if not t.hello.wait(timeout):
        t.close()
        raise OSError("the phone did not answer the hello")
    return t


# ---------------------------------------------------------------------- across IPv4
#
# When the phone's IPv6 cannot be reached (this network has none): both ends swap their public
# IPv4 addresses as two sealed notes on a public message board, punch through their NATs over UDP,
# and run the tunnel over a reliable stream on that path (docs/tunnel-protocol.md, "Across IPv4").

P_BOARD = "https://ntfy.sh"
P_STUN = (("stun.l.google.com", 19302), ("stun.cloudflare.com", 3478))
P_SOCKETS = 256             # a hard NAT's side opens this many, each its own mapping
P_PUNCH = 15                # seconds both ends knock
P_PROBE, P_PROBE_ACK, P_DATA, P_ACK, P_KEEP, P_CLOSE = range(1, 7)
P_HEADER, P_TAG = 20, 8
P_MSS = 1200                # payload per packet: under IPv6's minimum MTU even after NAT64
P_QUEUE = 4096
P_WINDOW = 4096


def punch_topic(psk, label):
    return "l87-" + hmac.new(psk, label, hashlib.sha256).digest()[:10].hex()


def punch_keystream(psk, nonce, n):
    return hashlib.shake_256(hmac.new(psk, b"L87P/1 seal", hashlib.sha256).digest() + nonce).digest(n)


def punch_seal(psk, text):
    nonce = os.urandom(16)
    pt = text.encode("utf-8")
    ct = bytes(a ^ b for a, b in zip(pt, punch_keystream(psk, nonce, len(pt))))
    tag = hmac16(hmac.new(psk, b"L87P/1 seal mac", hashlib.sha256).digest(), nonce + ct)
    return base64.urlsafe_b64encode(nonce + ct + tag).decode().rstrip("=")


def punch_open(psk, text):
    text = (text or "").strip()
    try:
        raw = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except ValueError:
        return None
    if len(raw) < 33:
        return None
    nonce, ct, tag = raw[:16], raw[16:-16], raw[-16:]
    if not hmac.compare_digest(tag, hmac16(hmac.new(psk, b"L87P/1 seal mac", hashlib.sha256).digest(), nonce + ct)):
        return None
    return bytes(a ^ b for a, b in zip(ct, punch_keystream(psk, nonce, len(ct)))).decode("utf-8", "replace")


def punch_stun(sock, host, port, wait=1.5):
    """One STUN Binding request from sock: (ip, port) as the server saw it, or None."""
    try:
        server = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_DGRAM)[0][4]
    except OSError:
        return None
    tid = os.urandom(12)
    req = struct.pack(">HHI", 1, 0, 0x2112A442) + tid
    end, sent = time.time() + wait, 0
    old = sock.gettimeout()
    sock.settimeout(0.4)
    try:
        while time.time() < end:
            if time.time() - sent > 0.4:
                sock.sendto(req, server)
                sent = time.time()
            try:
                data, _ = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return None
            if len(data) < 20 or data[8:20] != tid:
                continue
            i = 20
            while i + 4 <= len(data):
                kind, ln = struct.unpack(">HH", data[i:i + 4])
                v = data[i + 4:i + 4 + ln]
                if kind in (0x20, 0x01) and ln >= 8 and v[1] == 1:
                    p, ip = struct.unpack(">H", v[2:4])[0], v[4:8]
                    if kind == 0x20:
                        p ^= 0x2112
                        ip = bytes(a ^ b for a, b in zip(ip, b"\x21\x12\xa4\x42"))
                    return socket.inet_ntoa(ip), p
                i += 4 + ln + (-ln % 4)
    finally:
        sock.settimeout(old)
    return None


def punch_mapped(sock):
    """(public (ip, port), hard): hard when the NAT gives each destination its own port."""
    seen = [a for a in (punch_stun(sock, h, p) for h, p in P_STUN) if a]
    if not seen:
        return None
    return seen[0], any(a[1] != seen[0][1] for a in seen)


def punch_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))   # nothing is sent; this only picks the outgoing address
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def punch_addr(s):
    try:
        host, port = s.rsplit(":", 1)
        return host, int(port)
    except (AttributeError, ValueError):
        return None


class PunchSealer(object):
    """Packet tags: HMAC-SHA256 over the packet, the first 8 bytes. One key per direction."""

    def __init__(self, key):
        self.key = key

    def packet(self, kind, role, seq=0, ack=0, sack=0, wnd=0, payload=b""):
        b = struct.pack(">BBIIQH", kind, role, seq & 0xFFFFFFFF, ack & 0xFFFFFFFF, sack, wnd) + payload
        return b + hmac.new(self.key, b, hashlib.sha256).digest()[:P_TAG]

    def valid(self, b):
        return len(b) >= P_HEADER + P_TAG and hmac.compare_digest(
            b[-P_TAG:], hmac.new(self.key, b[:-P_TAG], hashlib.sha256).digest()[:P_TAG])


def punch_knock(main, hard_here, theirs, hard_there, tx, rx, role):
    """Knocks at the other side (from P_SOCKETS sockets when this NAT is hard, spraying its ports
    when its NAT is) until a packet of its comes back: (socket, address), or None."""
    socks = [main]
    if hard_here and not hard_there:
        for _ in range(P_SOCKETS - 1):
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.bind(("0.0.0.0", 0))
                socks.append(s)
            except OSError:
                break
    found, done = [], threading.Event()
    seal, check = PunchSealer(tx), PunchSealer(rx)
    probe, ack = seal.packet(P_PROBE, role), seal.packet(P_PROBE_ACK, role)

    def listen(s):
        s.settimeout(0.25)
        while not done.is_set():
            try:
                data, frm = s.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            if not check.valid(data):
                continue
            if data[0] == P_PROBE:
                for _ in range(3):
                    try:
                        s.sendto(ack, frm)
                    except OSError:
                        pass
            if data[0] in (P_PROBE, P_PROBE_ACK):
                found.append((s, frm))
                done.set()
                return

    for s in socks:
        threading.Thread(target=listen, args=(s,), daemon=True).start()
    ports = []
    if hard_there and not hard_here and theirs:
        # The ports near the one STUN saw first (NATs that count up), then the rest at random.
        seen = theirs[0][1]
        near = [p for d in range(1, 257) for p in (seen + d, seen - d) if 1024 <= p <= 65535]
        skip = set(near)
        skip.add(seen)
        rest = [p for p in range(1024, 65536) if p not in skip]
        random.shuffle(rest)
        ports = near + rest
    start, last, nxt = time.time(), 0, 0
    while not done.is_set() and time.time() - start < P_PUNCH:
        if time.time() - last >= 0.2:
            last = time.time()
            for s in socks:
                for t in theirs:
                    try:
                        s.sendto(probe, t)
                    except OSError:
                        pass
        for _ in range(30 if ports else 0):
            try:
                main.sendto(probe, (theirs[0][0], ports[nxt % len(ports)]))
            except OSError:
                pass
            nxt += 1
        done.wait(0.1 if ports else 0.05)
    done.set()
    got = found[0] if found else None
    for s in socks:
        if not got or s is not got[0]:
            try:
                s.close()
            except OSError:
                pass
    return got


class UdpCarrier(object):
    """A reliable, ordered byte stream over one UDP path, shaped like a socket for the Tunnel:
    numbered packets, cumulative and selective acks, resending on loss, a congestion window."""

    def __init__(self, sock, peer, tx, rx, role):
        self.sock, self.peer, self.role = sock, peer, role
        self.seal, self.check = PunchSealer(tx), PunchSealer(rx)
        self.cv = threading.Condition()
        self.order = threading.Lock()                 # new packets leave in the order numbered
        self.again = False                            # someone asked to send while another was
        self.alive = True
        self.pending = collections.deque()
        self.inflight = collections.OrderedDict()     # seq -> [data, sent at, tries]
        self.next_seq, self.cwnd, self.ssthresh, self.recover = 0, 16.0, 1e9, 0
        self.peer_wnd, self.srtt, self.rttvar, self.rto = P_WINDOW, 0.0, 0.0, 1.0
        self.min_rtt, self.wmax, self.epoch = 0.0, 0.0, None   # CUBIC
        self.expected, self.early, self.unacked = 0, {}, 0
        self.chunks, self.buf = collections.deque(), b""
        self.timeout = None
        self.last_sent = self.last_heard = time.time()
        sock.settimeout(0.2)
        threading.Thread(target=self._recv_loop, daemon=True).start()
        threading.Thread(target=self._tick_loop, daemon=True).start()

    # The socket calls the Tunnel makes.
    def settimeout(self, t):
        self.timeout = t

    def setsockopt(self, *_):
        pass

    def sendall(self, data):
        for i in range(0, len(data), P_MSS):
            with self.cv:
                while self.alive and len(self.pending) + len(self.inflight) >= P_QUEUE:
                    self.cv.wait(1)
                if not self.alive:
                    raise OSError("the path is closed")
                self.pending.append(bytes(data[i:i + P_MSS]))
        self._pump()

    def recv(self, n):
        end = time.time() + self.timeout if self.timeout else None
        with self.cv:
            while not self.buf and not self.chunks and self.alive:
                left = end - time.time() if end else 1
                if left <= 0:
                    raise socket.timeout("nothing in time")
                self.cv.wait(left)
            if not self.buf and self.chunks:
                self.buf = self.chunks.popleft()
            out, self.buf = self.buf[:n], self.buf[n:]
            return out

    def close(self):
        if self.alive:
            for _ in range(3):
                self._send(P_CLOSE)
        self._finish()

    # Inside.
    def _sack(self):
        bits = 0
        for i in range(64):
            if self.expected + 1 + i in self.early:
                bits |= 1 << i
        return bits

    def _send(self, kind, seq=0, payload=b""):
        with self.cv:
            ack, sack, wnd = self.expected, self._sack(), max(0, P_WINDOW - len(self.early))
            if kind in (P_DATA, P_ACK, P_KEEP):
                self.unacked = 0
        try:
            self.sock.sendto(self.seal.packet(kind, self.role, seq, ack, sack, wnd, payload), self.peer)
        except OSError:
            pass
        self.last_sent = time.time()

    def _pump(self):
        """Sends what the window allows. One thread at a time, so packets leave in order; a thread
        that finds another at it leaves it to that one, which goes round again."""
        self.again = True
        while self.again:
            if not self.order.acquire(False):
                return
            try:
                self.again = False
                out = []
                with self.cv:
                    room = min(int(self.cwnd), self.peer_wnd) - len(self.inflight)
                    while room > 0 and len(out) < 256 and self.pending:
                        seq = self.next_seq
                        self.next_seq += 1
                        data = self.pending.popleft()
                        self.inflight[seq] = [data, time.time(), 0]
                        out.append((seq, data))
                        room -= 1
                for seq, data in out:
                    self._send(P_DATA, seq, data)
            finally:
                self.order.release()

    def _recv_loop(self):
        while self.alive:
            try:
                data, frm = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            if not self.check.valid(data) or data[1] == self.role:
                continue
            self.last_heard = time.time()
            if frm != self.peer:
                self.peer = frm       # the other end's NAT moved it
            kind, _, seq, ack, sack, wnd = struct.unpack(">BBIIQH", data[:P_HEADER])
            if kind == P_PROBE:
                try:
                    self.sock.sendto(self.seal.packet(P_PROBE_ACK, self.role), frm)
                except OSError:
                    pass
            elif kind == P_CLOSE:
                self._finish()
                return
            elif kind in (P_DATA, P_ACK, P_KEEP):
                self._acked(ack, sack, wnd)
                if kind == P_DATA:
                    self._data(seq, data[P_HEADER:-P_TAG])

    def _data(self, seq, payload):
        with self.cv:
            d = (seq - self.expected) & 0xFFFFFFFF
            if d >= 0x80000000:
                now = True                                  # a copy of one already had
            elif d == 0:
                self.chunks.append(payload)
                self.expected += 1
                filled = bool(self.early)
                while self.expected in self.early:
                    self.chunks.append(self.early.pop(self.expected))
                    self.expected += 1
                self.unacked += 1
                now = filled or self.unacked >= 2
                self.cv.notify_all()
            elif d < P_WINDOW:
                self.early[seq] = payload
                now = True
            else:
                now = False
        if now:
            self._send(P_ACK)

    def _acked(self, ack, sack, wnd):
        resend = []
        with self.cv:
            self.peer_wnd = max(wnd, 4)
            now, newly, sample, cumulative, latest = time.time(), 0, None, False, None
            for seq in list(self.inflight):
                if seq >= ack:
                    break
                e = self.inflight.pop(seq)
                if not e[2]:
                    sample = now - e[1]
                latest = e[1] if latest is None else max(latest, e[1])
                newly += 1
                cumulative = True
            for i in range(64):
                if sack >> i & 1:
                    e = self.inflight.pop(ack + 1 + i, None)
                    if e:
                        if not e[2]:
                            sample = now - e[1]
                        latest = e[1] if latest is None else max(latest, e[1])
                        newly += 1
            if sample is not None:
                if not self.srtt:
                    self.srtt, self.rttvar = sample, sample / 2
                else:
                    self.rttvar = 0.75 * self.rttvar + 0.25 * abs(self.srtt - sample)
                    self.srtt = 0.875 * self.srtt + 0.125 * sample
                self.min_rtt = min(self.min_rtt, sample) if self.min_rtt else sample
            if cumulative:
                self.rto = min(3.0, self.srtt + max(4 * self.rttvar, 0.2))   # Linux: at least 200 ms over the round trip
            self._grow(newly, now)
            # A packet sent after this one has come and this one has not (allowing a little
            # reordering): lost, send it again. Originals leave in order, so the first one never
            # resent and sent too late to count ends the search.
            lost = []
            if latest is not None:
                reo = max(self.srtt / 4, 0.005)
                for s, e in self.inflight.items():
                    if len(lost) >= 64:
                        break
                    if e[1] < latest - reo:
                        lost.append(s)
                    elif not e[2]:
                        break
            if lost:
                if lost[0] >= self.recover:       # once per window: one loss, one step back
                    self.wmax, self.epoch = self.cwnd, None
                    self.ssthresh = max(self.cwnd * 0.7, 8.0)
                    self.cwnd, self.recover = self.ssthresh, self.next_seq
                for s in lost[:64]:
                    e = self.inflight[s]
                    e[1], e[2] = now, e[2] + 1
                    resend.append((s, e[0]))
            self.cv.notify_all()
        for s, d in resend:
            self._send(P_DATA, s, d)
        self._pump()

    def _grow(self, n, now):
        """The window after n packets were acknowledged, as Linux grows it: doubling each round trip
        until the first loss, then CUBIC, back to the size it last lost at in a few seconds whatever
        the round trip, and probing past it; never slower than Reno's one packet per round trip."""
        if not n:
            return
        if self.cwnd < self.ssthresh:
            self.cwnd += n
        else:
            if self.epoch is None:
                self.epoch = now
                self.k = (max(0.0, self.wmax - self.cwnd) / 0.4) ** (1 / 3.0)
                self.origin = max(self.wmax, self.cwnd)
            t = now - self.epoch + self.min_rtt
            target = self.origin + 0.4 * (t - self.k) ** 3
            self.cwnd += n * max(target - self.cwnd, 1.0) / self.cwnd
        self.cwnd = min(self.cwnd, 2048.0)

    def _tick_loop(self):
        while self.alive:
            time.sleep(0.01)
            now, resend = time.time(), []
            with self.cv:
                # Nothing back for the oldest packet in a whole timeout: send it again, and start
                # slow (once per loss; the acks for it then show what else is missing).
                first = next(iter(self.inflight), None)
                if first is not None and now - self.inflight[first][1] > self.rto:
                    if first >= self.recover:
                        self.wmax, self.epoch = self.cwnd, None
                        self.ssthresh = max(self.cwnd * 0.7, 8.0)
                        self.cwnd, self.recover = 8.0, self.next_seq
                    self.rto = min(self.rto * 2, 5.0)
                    e = self.inflight[first]
                    e[1], e[2] = now, e[2] + 1
                    resend.append((first, e[0]))
                due = self.unacked > 0
            for s, d in resend:
                self._send(P_DATA, s, d)
            if due:
                self._send(P_ACK)
            self._pump()
            if now - self.last_sent > 5:
                self._send(P_KEEP)
            if now - self.last_heard > 60:
                self._finish()

    def _finish(self):
        with self.cv:
            if not self.alive:
                return
            self.alive = False
            self.cv.notify_all()
        try:
            self.sock.close()
        except OSError:
            pass


def punch_post(topic, text):
    r = urllib.request.Request(P_BOARD + "/" + topic, data=text.encode(), method="POST",
                               headers={"Cache": "no", "Firebase": "no"})
    urllib.request.urlopen(r, timeout=10).read()


def punch_ask(psk, note, wait=12):
    """Leaves note for the phone on the board and returns its answer (same "t" and "s"), or None.
    Listens before posting, so the answer cannot be missed. OSError when the board is unreachable."""
    try:
        board = urllib.request.urlopen(P_BOARD + "/" + punch_topic(psk, b"L87P/1 down") + "/json", timeout=wait + 1)
    except (OSError, urllib.error.URLError) as e:
        raise OSError("could not reach the board at %s (%s)" % (P_BOARD, e))
    try:
        board.readline()                               # the board's "open"
        punch_post(punch_topic(psk, b"L87P/1 up"), punch_seal(psk, json.dumps(note)))
        end = time.time() + wait
        while time.time() < end:
            line = board.readline()
            if not line:
                return None
            try:
                o = json.loads(line.decode("utf-8"))
                a = json.loads(punch_open(psk, o.get("message")) or "null") if o.get("event") == "message" else None
            except ValueError:
                continue
            if a and a.get("t") == note["t"] and a.get("s") == note["s"]:
                return a
    except (OSError, urllib.error.URLError):
        return None
    finally:
        board.close()
    return None


def tunnel_where(c):
    """The phone's current addresses, asked through the board (its IPv6 changes when mobile data
    reconnects). [] when it does not answer."""
    try:
        a = punch_ask(base64.b64decode(c["key"]), {"t": "where", "s": os.urandom(8).hex(), "at": int(time.time())}, wait=6)
    except OSError:
        return []
    return [x for x in (a or {}).get("addrs") or [] if isinstance(x, str)]


def punch_dial(c):
    """Across IPv4: a note to the phone through the board, its answer, then the punch. Returns
    the UdpCarrier and the phone's address; OSError when it cannot."""
    psk = base64.b64decode(c["key"])
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))
    try:
        me = punch_mapped(sock)
        if not me:
            raise OSError("this network gives no public IPv4 address to punch from (STUN did not answer)")
        session = os.urandom(8)
        lan = punch_lan_ip()
        note = {"t": "punch", "s": session.hex(), "at": int(time.time()), "addr": "%s:%d" % me[0],
                "hard": me[1], "lan": ["%s:%d" % (lan, sock.getsockname()[1])] if lan else []}
        answer = punch_ask(psk, note)
        if not answer:
            raise OSError("the phone did not answer through the board (is it on, with From other networks on?)")
        theirs = [a for a in [punch_addr(answer.get("addr"))] + [punch_addr(x) for x in answer.get("lan") or []] if a]
        if not theirs:
            raise OSError("the phone could not see its own public address")
        k = hmac.new(psk, b"L87P/1 udp" + session, hashlib.sha256).digest()
        to_phone = hmac.new(k, b"dev", hashlib.sha256).digest()
        to_dev = hmac.new(k, b"phone", hashlib.sha256).digest()
        log("Punching to %s (here %s:%d, hard here %s, there %s)" % (
            ", ".join("%s:%d" % t for t in theirs), me[0][0], me[0][1], me[1], answer.get("hard")))
        got = punch_knock(sock, me[1], theirs, bool(answer.get("hard")), to_phone, to_dev, 0)
        if not got:
            raise OSError("no way through the two networks' NATs (%s here, %s there)" % (
                "hard" if me[1] else "easy", "hard" if answer.get("hard") else "easy"))
        return UdpCarrier(got[0], got[1], to_phone, to_dev, 0), got[1]
    except BaseException:
        try:
            sock.close()
        except OSError:
            pass
        raise


tunnel = None               # the running Tunnel, while there is one
tunnel_lock = threading.Lock()
tunnel_local = None         # ("127.0.0.1", port) where it is served here


def tunnel_conf():
    try:
        with open(os.path.join(CONF, "tunnel.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def tunnel_save_addrs(addrs):
    c = tunnel_conf()
    if c and addrs and addrs != c.get("addrs"):
        c["addrs"] = addrs
        write_file(os.path.join(CONF, "tunnel.json"), json.dumps(c), private=True)


def tunnel_stale():
    """The tunnel's keys are from before the current session (an older pairing): out of date."""
    s, t = os.path.join(CONF, "session.txt"), os.path.join(CONF, "tunnel.json")
    try:
        return os.path.exists(s) and (not os.path.exists(t) or os.path.getmtime(t) < os.path.getmtime(s))
    except OSError:
        return False


def tunnel_learn():
    """While the phone is reachable, keeps what the tunnel needs: its addresses, port and this computer's keys."""
    try:
        status, _, body = request("GET", "/api/tunnel", timeout=5)
        if status != 200:
            return
        d = json.loads(body.decode("utf-8"))
        if d.get("key") and d.get("id"):
            write_file(os.path.join(CONF, "tunnel.json"), json.dumps(d), private=True)
    except (OSError, http.client.HTTPException, ValueError):
        pass


def tunnel_serve():
    """127.0.0.1:18789 (or the next free port): each connection there is a stream to the phone."""
    global tunnel_local
    for port in (TUNNEL_LOCAL_PORT, TUNNEL_LOCAL_PORT + 10, TUNNEL_LOCAL_PORT + 20):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("127.0.0.1", port))
            s.listen(64)
        except OSError:
            s.close()
            continue
        tunnel_local = ("127.0.0.1", port)

        def accept():
            while True:
                try:
                    c, _ = s.accept()
                except OSError:
                    time.sleep(0.2)
                    continue
                t = tunnel
                if t is None or not t.alive:
                    c.close()
                    continue
                c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                try:
                    t.open_stream(c)
                except OSError:
                    c.close()

        threading.Thread(target=accept, daemon=True).start()
        return tunnel_local
    return None


def tunnel_path():
    """The last way in, from another network: dials the phone's saved addresses. None when it cannot."""
    global tunnel
    c = tunnel_conf()
    if not c:
        return None
    with tunnel_lock:
        if tunnel and tunnel.alive:
            return tunnel_local
        if tunnel_local is None and tunnel_serve() is None:
            return None
        tid, psk = bytes.fromhex(c["id"]), base64.b64decode(c["key"])
        port = int(c.get("port", TUNNEL_PORT))
        # Its saved addresses all at once; meanwhile the board is asked where it is now, in case
        # its IPv6 changed while the two were apart, and any new ones join the race the moment they come.
        saved = list(c.get("addrs") or [])
        race = DialRace(port, tid, psk)
        for a in saved:
            race.add(a)

        def ask():
            now = tunnel_where(c)
            fresh = [a for a in now if a not in saved]
            if fresh:
                log("The phone's addresses changed: %s" % ", ".join(fresh))
                tunnel_save_addrs(now)
                for a in fresh:
                    race.add(a)
            race.close()

        threading.Thread(target=ask, daemon=True).start()
        tunnel = race.wait(20)
        if tunnel:
            return tunnel_local
        # No IPv6 way to it: across IPv4, punched through both NATs.
        try:
            carrier, addr = punch_dial(c)
            tunnel = tunnel_start(carrier, tid, psk, addr)
            log("Tunnel to %s:%d over UDP, punched through" % addr)
            return tunnel_local
        except OSError as e:
            say_once("punch-" + str(e), "Could not reach the phone across IPv4 (%s)." % e)
    return None


class DialRace(object):
    """Addresses dialled at once as they come; the first tunnel up wins, any later ones are closed."""

    def __init__(self, port, tid, psk, timeout=4):
        self.port, self.tid, self.psk, self.timeout = port, tid, psk, timeout
        self.cv = threading.Condition()
        self.won, self.pending, self.open = None, 0, True

    def add(self, host):
        with self.cv:
            if self.won:
                return
            self.pending += 1
        threading.Thread(target=self._one, args=(host,), daemon=True).start()

    def close(self):
        """No more addresses will come."""
        with self.cv:
            self.open = False
            self.cv.notify_all()

    def _one(self, host):
        t = None
        try:
            t = tunnel_dial(host, self.port, self.tid, self.psk, self.timeout)
        except OSError as e:
            say_once("tunnel-" + host + str(e), "Could not reach the phone at %s over the internet (%s)." % (host, e))
        with self.cv:
            self.pending -= 1
            if t and not self.won:
                self.won = t
                log("Tunnel to [%s]:%s" % (host, self.port))
                t = None
            self.cv.notify_all()
        if t:
            t.close()

    def wait(self, limit):
        """The winner, or None once every address has failed and no more will come."""
        end = time.time() + limit
        with self.cv:
            while not self.won and (self.open or self.pending) and time.time() < end:
                self.cv.wait(0.5)
            return self.won


def on_tunnel(addr=None):
    return tunnel_local is not None and (addr or phone) == tunnel_local


def tunnel_loop():
    """Keeps the tunnel's details current, and leaves the tunnel as soon as a local path answers."""
    last_learn = 0
    while True:
        time.sleep(5)
        if phone is None or not session:
            continue
        if not on_tunnel():
            # Every 10 minutes while close; at once when the keys are older than the session.
            if time.time() - last_learn > 600 or (tunnel_stale() and time.time() - last_learn > 30):
                tunnel_learn()
                last_learn = time.time()
            t = tunnel
            if t and t.alive and all(r.addr != tunnel_local for r in relayed):
                t.close()   # nothing uses it any more
            continue
        for addr in candidates():
            if ping(addr):
                say("The phone is close again: %s, %s." % (addr[0], link_name(addr)))
                move_to(addr)
                last_learn = 0
                break


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
                    # "start PORT W H [HZ BLOCKS [http]]" when the phone opens its screen view; anything else
                    # stops it. "http": the phone takes the stream on the page's port, the way this helper
                    # reaches it (the tunnel included), rather than on PORT.
                    p = d.split()
                    if p and p[0] == "start" and len(p) >= 4:
                        fps = max(30, min(120, int(p[4]))) if len(p) >= 5 else 60
                        http_ok = len(p) >= 7 and p[6] == "http"
                        threading.Thread(target=start_second_screen, args=(at, int(p[1]), int(p[2]), int(p[3]), fps, http_ok),
                                         daemon=True).start()
                    else:
                        stop_second_screen()
                elif ev == "laptopfs" and not snapshot:
                    # The phone asks for a folder here, or a file from it (LaptopFiles on the phone).
                    q = d.split(" ")
                    if len(q) >= 3:
                        path = base64.b64decode(q[2]).decode("utf-8", "replace") if q[2] else ""
                        threading.Thread(target=laptop_send if q[0] == "get" else laptop_list, args=(q[1], path), daemon=True).start()
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


def capture_tries(geom, w, h, fps, dest, grab=None, remote=False):
    """ffmpeg arguments, fastest encoder first: X11's screen grab, or the one given (macOS's).
    From afar (the phone reached through the tunnel): 1024 wide, 20 frames a second, 900 kbit/s, the
    refresh spread over the frames (intra refresh) so the stream stays even."""
    x, y, gw, gh = geom
    if remote:
        fps, w, h = min(fps, 20), 1024, 640
    grab = grab or ["-f", "x11grab", "-framerate", str(fps), "-video_size", "%dx%d" % (gw, gh), "-draw_mouse", "1",
                    "-i", "%s+%d,%d" % (os.environ.get("DISPLAY", ":0"), x, y)]
    # Fitted to the phone's screen; labelled BT.601 in full, as the phone decodes it.
    fit = "scale=%d:%d:force_original_aspect_ratio=decrease:force_divisible_by=2" % (w, h)
    label = "setparams=color_primaries=bt470bg:color_trc=smpte170m:colorspace=bt470bg:range=tv"
    mbit = min(80, max(40, 40 * fps // 60))
    rate = ["-b:v", "%dM" % mbit, "-maxrate", "%dM" % mbit, "-bufsize", "%dM" % max(3, mbit // 13), "-g", str(fps * 2), "-bf", "0"]
    if remote:
        rate = ["-b:v", "900k", "-maxrate", "900k", "-bufsize", "450k", "-g", str(fps * 10), "-bf", "0", "-intra-refresh", "1"]
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
    tries.append(base + grab + ["-vf", fit + ",format=yuv420p," + label, "-c:v", "libx264", "-preset", "veryfast" if remote else "ultrafast",
                                "-tune", "zerolatency"] + (rate if remote else ["-b:v", "20M", "-g", "120", "-bf", "0"]) + dest)
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


# ---------------------------------------------------------------------- this computer's files on the phone
#
# Read only, and only what this user can read: the usual folders and the whole disk at the top,
# then any folder's folders and files; a file is sent to the phone, which keeps it with what it has
# received. Answered on the page's port, so it works through the tunnel too.

def laptop_list(rid, path):
    entries, error = [], ""
    try:
        if not path:
            home = os.path.expanduser("~")
            for name in ("Desktop", "Documents", "Downloads", "Pictures", "Music", "Videos", "Movies"):
                full = os.path.join(home, name)
                if os.path.isdir(full):
                    entries.append({"name": name, "path": full, "dir": True})
            entries.append({"name": "Home", "path": home, "dir": True})
            entries.append({"name": "This computer", "path": "/", "dir": True})
        else:
            with os.scandir(path) as it:
                items = sorted(it, key=lambda e: (not e.is_dir(follow_symlinks=False), e.name.lower()))
            for e in items:
                if e.name.startswith("."):
                    continue
                try:
                    st = e.stat(follow_symlinks=False)
                except OSError:
                    continue
                is_dir = e.is_dir(follow_symlinks=False)
                entries.append({"name": e.name, "path": e.path, "dir": is_dir, "size": 0 if is_dir else st.st_size,
                                "modified": int(st.st_mtime * 1000)})
                if len(entries) >= 3000:
                    break
    except OSError as e:
        error = str(e)
    body = json.dumps({"path": path, "laptop": machine_name(), "error": error, "entries": entries})
    try:
        request("POST", "/api/laptop/fs/answer?id=" + urllib.parse.quote(rid), body=body,
                headers={"Content-Type": "application/json"}, timeout=20)
    except OSError:
        pass


def laptop_send(rid, path):
    q = "/api/laptop/fs/file?id=%s&name=%s" % (urllib.parse.quote(rid), urllib.parse.quote(os.path.basename(path)))
    try:
        f = open(path, "rb")
    except OSError as e:
        try:
            request("POST", q + "&error=" + urllib.parse.quote("The computer could not open it: %s" % e), timeout=10)
        except OSError:
            pass
        return
    with f:
        size = os.fstat(f.fileno()).st_size
        host, port = phone
        conn = http.client.HTTPConnection(host, port, timeout=60)
        try:
            conn.putrequest("POST", q + "&size=%d" % size)
            conn.putheader("User-Agent", user_agent())
            conn.putheader("Content-Type", "application/octet-stream")
            conn.putheader("Content-Length", str(size))
            if session:
                conn.putheader("Cookie", session)
            conn.endheaders()
            while True:
                b = f.read(256 * 1024)
                if not b:
                    break
                conn.send(b)
            conn.getresponse().read()
            say("Sent %s to the phone." % os.path.basename(path))
        except (OSError, http.client.HTTPException) as e:
            say("Could not send %s to the phone: %s" % (os.path.basename(path), e))
        finally:
            conn.close()


def post_stream(src, addr, gen, path="/api/display/stream", kind="video/h264"):
    """Posts ffmpeg's or wf-recorder's stream to the phone's page port, the way this helper reaches it
    (the tunnel included), until it ends: "ended", or "phone" when the phone closed it or went."""
    host, port = addr
    conn = http.client.HTTPConnection(host, port, timeout=10)
    try:
        conn.putrequest("POST", path)
        conn.putheader("User-Agent", user_agent())
        conn.putheader("Content-Type", kind)
        conn.putheader("Transfer-Encoding", "chunked")
        if session:
            conn.putheader("Cookie", session)
        conn.endheaders()
        conn.sock.settimeout(None)
        while gen == screen_gen:
            data = src.read1(1 << 16) if hasattr(src, "read1") else src.read(1 << 16)
            if not data:
                break
            conn.send(b"%x\r\n" % len(data) + data + b"\r\n")
        conn.send(b"0\r\n\r\n")
        conn.getresponse().read()
        return "ended"
    except (OSError, http.client.HTTPException):
        return "phone"
    finally:
        conn.close()


def stream_x11(at, port, w, h, fps, gen, http_ok=False):
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
    remote = http_ok and tunnel_local is not None and tuple(at) == tuple(tunnel_local)
    if remote:
        say("From another network: this screen goes smaller, at 900 kbit/s, so the link keeps up.")
    dest = ["-f", "h264", "pipe:1"] if http_ok else ["-f", "h264", "tcp://%s:%d?tcp_nodelay=1" % (at[0], port)]
    for args in capture_tries(geom, w, h, fps, dest, grab, remote):
        if gen != screen_gen:
            return
        try:
            p = subprocess.Popen([ff] + args, stdout=subprocess.PIPE if http_ok else subprocess.DEVNULL, stderr=subprocess.PIPE)
        except OSError as e:
            say("Could not start ffmpeg (%s)." % e)
            return
        stream_proc = p
        if http_ok:
            errs = []
            threading.Thread(target=lambda: errs.append(p.stderr.read()), daemon=True).start()
            began = time.time()
            why = post_stream(p.stdout, at, gen)
            if p.poll() is None:
                p.terminate()
            p.wait()
            err = b"".join(errs).decode("utf-8", "replace")
            if why == "phone" or gen != screen_gen:
                return
            if time.time() - began > 4:
                log("Second screen ended: %s" % err.strip()[-300:])
                return
            log("Second screen, trying the next encoder: %s" % err.strip()[-300:])
            continue
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


def stream_wlroots(at, port, w, h, fps, gen, http_ok=False):
    """wf-recorder writes the stream; this helper hands it to the phone."""
    global stream_proc, shown
    shown = None
    if http_ok:
        try:
            p = subprocess.Popen(["wf-recorder", "--muxer=h264", "--codec=libx264", "-p", "preset=ultrafast", "-p", "tune=zerolatency",
                                  "-p", "bf=0", "-r", str(fps), "--file=/dev/stdout"],
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError as e:
            say("Could not start wf-recorder (%s)." % e)
            return
        stream_proc = p
        say("Showing this computer's screen on the phone.")
        post_stream(p.stdout, at, gen)
        if p.poll() is None:
            p.terminate()
        return
    try:
        sock = socket.create_connection((at[0], port), timeout=10)
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


sound_proc = None


def start_sound(at, gen, remote):
    """The computer's sound with its screen: on Linux, PulseAudio's or PipeWire's monitor of the
    speakers through ffmpeg, as AAC, posted to the phone like the picture. (A Mac has no way to
    capture what it plays without an extra audio driver, so it sends the picture only.)"""
    global sound_proc
    ff = shutil.which("ffmpeg")
    if MAC or not ff or not (have("pactl") or have("pw-cli")):
        return
    try:
        p = subprocess.Popen([ff, "-hide_banner", "-loglevel", "error", "-f", "pulse", "-i", "@DEFAULT_MONITOR@",
                              "-ac", "2", "-ar", "48000", "-c:a", "aac", "-b:a", "64k" if remote else "160k", "-f", "adts", "pipe:1"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError:
        return
    sound_proc = p
    threading.Thread(target=lambda: (post_stream(p.stdout, at, gen, "/api/display/audio", "audio/aac"),
                                     p.poll() is None and p.terminate()), daemon=True).start()


def start_second_screen(at, port, w, h, fps, http_ok=False):
    global screen_gen
    stop_second_screen()
    gen = screen_gen
    if http_ok and not WSL:
        start_sound(at, gen, tunnel_local is not None and tuple(at) == tuple(tunnel_local))
    if WSL:
        say("The phone asked to be a second screen: under WSL the Windows helper shows it.")
        return
    kind = session_kind()
    if kind in ("x11", "mac"):
        stream_x11(at, port, w, h, fps, gen, http_ok)
    elif kind == "wayland" and have("wf-recorder") and not re.search(r"GNOME|KDE", os.environ.get("XDG_CURRENT_DESKTOP", ""), re.I):
        stream_wlroots(at, port, w, h, fps, gen, http_ok)
    elif kind == "wayland" and re.search(r"GNOME|KDE", os.environ.get("XDG_CURRENT_DESKTOP", ""), re.I):
        say("The phone asked to be a second screen. On GNOME or KDE under Wayland that needs their screen-sharing "
            "prompt, which this helper does not drive yet: log in to an X11 (Xorg) session to use it.")
    elif kind == "wayland":
        say("The phone asked to be a second screen. Under Wayland that needs wf-recorder (%s), for sway, "
            "Hyprland and other wlroots desktops; or log in to an X11 session." % install_hint("wf-recorder"))
    else:
        say("The phone asked to be a second screen, but this helper sees no desktop (no DISPLAY or WAYLAND_DISPLAY).")


def stop_second_screen():
    global screen_gen, stream_proc, shown, sound_proc
    screen_gen += 1
    sp, sound_proc = sound_proc, None
    if sp and sp.poll() is None:
        sp.terminate()
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
                tunnel_learn()    # new pairing, new keys: the old ones stop working at once
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

    if SITE_SIGNIN and not read_file(os.path.join(CONF, "session.txt")):
        site_enroll(*SITE_SIGNIN)
    find_phone(True, typed=args.phone)
    for loop in (relay_loop, link_loop, direct_loop, events_loop, volume_loop, tunnel_loop) + ((clip_loop,) if clip else ()):
        threading.Thread(target=loop, daemon=True).start()
    control_loop(args.no_browser)


if __name__ == "__main__":
    main()
