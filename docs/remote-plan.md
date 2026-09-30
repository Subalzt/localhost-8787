# Plan: reaching the phone across different networks

Status: the tunnel is built ([tunnel-protocol.md](tunnel-protocol.md)): the phone's end, the
Linux/Mac and Windows helpers, and phone to phone. Tested end to end over the local network;
the test across the internet waits for a laptop with IPv6 (see "Phase 0 results"). The proxy
case is set aside: the campus proxy allows CONNECT to 443 only, and Android apps cannot listen
below 1024.

What changed from the design below: instead of a TLS listener, the tunnel uses its own
encryption keyed at pairing (a TLS-inspecting proxy breaks pinned TLS; see
[Bypass-PROXY-with-WireGuard-over-HTTP-CONNECT](https://github.com/Subalzt/Bypass-PROXY-with-WireGuard-over-HTTP-CONNECT)),
and one connection carries every stream, so the helper serves it like adb's port forward.

## Goal

Every feature working when the laptop and the phone (or two laptops, or two phones) are on
different networks, with **the phone as the only server**: no public servers of any kind, not
even as a fallback. The phone introduces the two ends and they try to connect directly; when
they cannot, the phone relays.

Cases to cover:

1. A laptop on the phone's own hotspot (works today).
2. Two laptops, each on its own phone's hotspot:
   - phones in the same room or building (mostly works today: connection 12 in the README,
     the phones linked over the direct link);
   - phones far apart, each on mobile data.
3. A laptop on a LAN whose only way out is an HTTP proxy, `172.31.2.3:8080`, with the phone on
   mobile data.

The laptop helper (`blazeit-helper.py`, `blazeit-pc.bat`) may be used.

## The deciding constraint

With no public server, the laptop (through its proxy) or the other phone must open a
connection **straight to the phone over the internet**.

- **IPv4: impossible.** Mobile data puts the phone behind carrier NAT (100.64.0.0/10 or a
  private address); nothing can connect inbound. `NetInfo` already marks this as
  `Reach.CARRIER_NAT`.
- **IPv6: possible.** Many carriers give each phone a global IPv6 address, and some do not
  firewall inbound connections to it. Then `CONNECT [phone-v6]:port` through the proxy reaches
  the phone.

It fails, and nothing short of a public server fixes it, if:

- the carrier blocks inbound IPv6;
- the proxy allows CONNECT only to port 443 (Android apps cannot listen below 1024), or refuses
  IPv6 literals, or has no IPv6 route out.

Today the server listens on IPv4 only: `host = "0.0.0.0"` in
`app/src/main/java/dev/periy/bridge/server/BridgeServer.kt` (the `connector` block).

## Paths per case

| Case | Path |
| --- | --- |
| Laptop on the phone's hotspot | unchanged |
| Two hotspots, phones near | laptop A → phone A → (direct link) → phone B → laptop B, as today |
| Two hotspots, phones far | laptop A → phone A → **IPv6 over the internet** → phone B → laptop B |
| Proxy LAN, phone on mobile data | browser → helper at `localhost:8787` → **proxy CONNECT** → phone's IPv6, TLS |
| Laptop ↔ laptop, any case | WebRTC introduced by the phones; if no direct path within 8 s, the existing pipes through the phones (`server/Pipes.kt`). Behind the proxy there is no UDP, so always the pipes there. |

The helper relays each browser connection byte for byte (`bridge()` / `pump()` in
`blazeit-helper.py`), so once its connection to the phone can go through the proxy, every HTTP
feature works unchanged.

## Design

### 1. A remote door on the phone

- A second listener on IPv6 (for example port 8443), **TLS only**, off by default, a switch in
  Settings, optional auto-off timer.
- **Paired devices only.** Pairing (`/api/pair`) stays on the local network; on the remote door
  it answers 403, so strangers on the internet cannot even raise a prompt. Rate-limit per
  address.
- A self-signed certificate made on the phone. Its fingerprint is learned during local pairing
  (helper and linked phones) and pinned on every connection, which also refuses a proxy doing
  TLS interception.
- Browsers never see this certificate: they talk only to the helper on localhost, so there are
  no certificate warnings.

### 2. Knowing the phone's address (no server to ask)

- Mobile IPv6 addresses change on reconnect.
- While connected, the phone pushes every new global address to paired helpers and linked
  phones over `/events`; they save it.
- The helper tries the last-known address; if it fails, it asks for the address shown on the
  phone's Home (typed, or scanned from a QR code).
- Optional: new addresses sent phone to phone by **SMS**, the only internet-free way for two
  far-apart phones to find each other again (sideloaded app, so the SMS permission is fine).

### 3. The helper through the proxy

- `--proxy 172.31.2.3:8080`, and also the system proxy and `HTTPS_PROXY`.
- A new last-resort path "remote", after USB debugging in `candidates()` / `find_phone()`:
  `CONNECT [v6]:port` through the proxy, TLS with the pinned fingerprint, then the same relay.
- Moves back to a local path the moment one appears, as it does today (`direct_loop`).
- Then the same in `blazeit-pc.bat`.

### 4. Phone ↔ phone over the internet

- `server/Peers.kt` requests can go to the other phone's IPv6 remote door.
- If one phone accepts inbound connections, the other connects to it.
- If both carriers firewall inbound, both must open toward each other at the same moment
  (simultaneous open), which needs the address exchange first: the SMS route above. The part
  most likely to fail.
- Pipes through two phones then work unchanged.

### 5. Laptop ↔ laptop, introduced by the phones

- The phones already relay the WebRTC offer/answer (`bridge.html`, `DIRECT_WAIT_MS`) and answer
  STUN on UDP 3478 (`net/Stun.kt`, IPv4 only today: extend to IPv6).
- Laptops with IPv6 (through the phone's hotspot, if the carrier shares it) may connect
  directly; otherwise the pipes after 8 s, as today.

## Features and limits

- Remote: clipboard, sending files, browsing the phone, music streaming, notifications,
  trackpad and keyboard, laptop to laptop.
- Local only: the second screen and the phone's screen (scrcpy): video over mobile data through
  a proxy is too heavy and laggy; the page says so.
- Mobile data: every byte counts against the plan; through two phones it counts on both. Show a
  counter in Settings, warn above a size.
- Battery and idle drops: the app runs a foreground service already; whether the carrier drops
  idle inbound connections needs testing.

## Phases

0. **Feasibility.**
   - Listen on IPv6 as well (`"::"` instead of `"0.0.0.0"`, checking IPv4 still works on every
     link: cable, hotspot, direct link, Wi-Fi) and show the phone's global IPv6 on Home.
   - From the proxy-LAN laptop, phone on mobile data:
     `curl -v -x http://172.31.2.3:8080 "http://[PHONE-V6]:8787/api/ping"`
   - Also: does the proxy allow CONNECT to a non-443 port? To an IPv6 literal? From another
     network (a second phone's hotspot), can `[PHONE-V6]:8787` be reached at all (carrier
     firewall)?
   - Record the results in this file. If it cannot work, stop here.
1. Remote door: TLS listener, paired-only, fingerprint pinning, the Settings switch.
2. Helper: proxy tunnel, the remote path, address updates. Python first, then the `.bat`.
3. Phone ↔ phone over IPv6; optional SMS address exchange.
4. Laptop ↔ laptop over IPv6, data counters, local-only features marked, README section 14
   ("What works across different networks") updated.

## Phase 0 results

2026-09-30, Xiaomi 15 (HyperOS, Android 16).

**Built:** the server listens on `::` (one dual-stack socket). `/api/pair` answers 403 unless the
caller is IPv4, link-local/ULA, or a global IPv6 inside the /64 of one of the phone's non-mobile
links, so strangers on the internet cannot raise a pairing prompt. Home shows the internet
IPv6 (a tap copies `http://[v6]:8787/`); logcat tag `NetInfo` prints it each time it changes.

**The phone on mobile data** (`adb shell ip -6 addr`, `dumpsys connectivity`):

- Carrier: **Jio True5G, India** (`jionet`, NR).
- Global IPv6 on the internet network (`rmnet_data1`), for example
  `2409:40e3:2087:2bf4:8002:f4ff:fee5:ea72/64`, SLAAC, stable interface id (no temporary
  address). DNS `2405:200:800::11`.
- IPv4 is only 464XLAT: `192.0.0.2/27`. No IPv4 address anything could connect to, as expected.
- A second global IPv6 on `rmnet_data4` belongs to the IMS (VoLTE) network and is unreachable
  from the internet; Home ignores it by reading the default network, not the interface list.
- The hotspot (`wlan2`) shares the mobile /64: the phone is `2409:40e3:2087:2bf4::9d` there, so
  laptops on the hotspot get global IPv6 too (a client there had two, SLAAC and privacy). Useful
  for case 2 (laptop ↔ laptop over IPv6).

**Listening on `::`:**

| From | To | Result |
| --- | --- | --- |
| The phone itself | `127.0.0.1`, `::1`, its global IPv6 | answers |
| Laptop, adb forward | `127.0.0.1` | answers |
| Laptop on USB tethering | IPv4 `10.117.25.178` | answers; pairing and paired requests work, the phone records the laptop as plain `10.117.25.114`; 333 Mbps on one connection (cable at USB 2, the most it carries) |
| Laptop on USB tethering | link-local IPv6 (`fe80::…%if`) | answers, pairing works |
| Laptop on USB tethering | global IPv6 | none: the phone shares its /64 with the hotspot but not over USB |
| Laptop on the hotspot | IPv4, and IPv6 | to test |
| Laptop on the same Wi-Fi | IPv4 | to test |

**Through the proxy and from another network:** to test (below).

| Test | Result |
| --- | --- |
| Proxy CONNECT to 443 (baseline) | |
| Proxy reaches an IPv6-only site | |
| Proxy CONNECT to an IPv6 literal on 443 | |
| Proxy CONNECT to a non-443 port (IPv4) | |
| Proxy GET `http://[PHONE-V6]:8787/api/ping` | |
| Proxy CONNECT `[PHONE-V6]:8787` | |
| Another network's IPv6 → `[PHONE-V6]:8787` (Jio inbound firewall) | |

## Open questions

- ~~Which carrier and country the phones are on.~~ Jio, India (the Xiaomi 15). The second
  phone's carrier is still unknown.
- SMS address exchange, or is typing / scanning a new address enough?
