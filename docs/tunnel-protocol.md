# The L87 tunnel

How a laptop (or another phone) reaches the phone, with the phone as the only server: from a
different network, and on the phone's own networks too (Wi-Fi, its hotspot, the cable), so nothing
between the two ever goes in the clear. One TCP connection, our own encryption, every connection
multiplexed inside it. It is what [remote-plan.md](remote-plan.md) calls the remote door.

Why this shape, after [Bypass-PROXY-with-WireGuard-over-HTTP-CONNECT](https://github.com/Subalzt/Bypass-PROXY-with-WireGuard-over-HTTP-CONNECT):

- **Our own encryption, not TLS.** A proxy that inspects TLS replaces the certificate, so a
  pinned TLS connection through it never completes. Opaque bytes inside a tunnel go through.
- **One long-lived, multiplexed carrier**, not a connection per request: one handshake, one
  firewall pinhole, and every browser connection a stream inside it.
- **Keepalives**, so carrier NAT and firewall state stays open while idle.
- **Silence to strangers.** Anything that does not open with a valid hello is closed without a
  byte in reply, so a scan of the phone's address learns nothing.

## Where it runs

- The phone listens on TCP **8789**, on every address (`::`, dual-stack), whenever it serves.
  Across networks that means its global IPv6: mobile data has no IPv4 anything can connect to.
  A tunnel from the internet is let in only while *From other networks* is on; one from the
  phone's own networks (a private or link-local address, or a global IPv6 inside the /64 of one
  of its local links) always, and the phone hands its streams to the page at `127.0.0.86`
  instead of `127.0.0.87`, so the page treats them as the link they came over (Wi-Fi, hotspot,
  cable) and not the internet.
- The laptop helper dials it and serves the tunnel on a loopback address (`127.0.0.3:8787` on
  Windows, `127.0.0.1:18789` on Linux and Mac), the way it uses adb's port forward: every local
  connection there becomes a stream to the phone's page (port 8787). The helper's relay, requests
  and event streams then work unchanged.
- On the phone's own networks the helper does the same with a second tunnel to the phone's local
  address (`127.0.0.4:8787`, or `127.0.0.1:18799`), once it has its keys (asked for on the first
  visit). Linked phones do the same between themselves. Only the plain `GET /api/ping` that finds
  the phone, and a browser opened at the phone's own address, stay in the clear.
- The page's own rules still apply inside: a stream is an ordinary HTTP connection to the
  phone, with the session cookie as before.

## Keys

- The phone keeps one random 32-byte secret, `tunnel.key`, in its private files.
- Each paired device gets
  - `psk = HMAC-SHA256(secret, "L87T/1 psk" || deviceId)`, 32 bytes, and
  - `tid = HMAC-SHA256(secret, "L87T/1 id" || deviceId)[0:16]`, its tunnel id,

  handed over on the local network by `GET /api/tunnel` (paired, local callers only). Nothing
  is stored per device: removing a device from the phone's list revokes its tunnel too, and the
  phone closes that device's open tunnel at once.
- The tunnel id, not the device id, goes over the internet, so the device cannot be told apart
  from its pairing; it is still a stable id, the way a WireGuard public key is.

## Primitives

| Use | Primitive |
| --- | --- |
| Key agreement | X25519 (RFC 7748), a fresh key pair on both sides for every connection |
| Key derivation | HKDF-SHA256 (RFC 5869) |
| Handshake MACs | HMAC-SHA256, truncated to 16 bytes |
| Stream cipher | Version 2: AES-256-CTR. Version 1: SHAKE256 as a keyed keystream, `SHAKE256(key || counter)` |
| Frame MAC | HMAC-SHA256 over counter, length and ciphertext, truncated to 16 bytes |

Chosen so every end has them fast with nothing installed. Version 2 uses AES, which the phone
(Conscrypt) and Windows (CNG) run in the processor's AES instructions: frames seal at about
700 MB/s on the laptop, against about 60 MB/s for version 1's hand-written Keccak, so no link here
is held up by its encryption. Python's standard library has no AES, so the Linux and Mac helper
speaks version 1 (SHAKE256 and HMAC, both in C there); the phone answers both. X25519 is short to
write where missing. Encrypt-then-MAC with independent keys; the keystream of each
frame is keyed by a counter that never repeats within a connection, and every connection has
fresh keys.

## Handshake

Client to phone, 69 bytes:

```
"L87T"  version=2 (or 1)  tid[16]  e_c[32]  mac1[16]
mac1 = HMAC(psk, "L87T/1 hello" || the 53 bytes before it)[0:16]
```

The phone finds the device whose `tid` matches and checks `mac1`. If either fails it closes the
connection without replying. Otherwise, phone to client, 48 bytes:

```
e_s[32]  mac2[16]
```

Both sides then compute

```
dh    = X25519(own ephemeral private, other's ephemeral public)    (all-zero: abort)
th    = SHA256(hello || e_s)
prk   = HMAC(psk, dh)                                               (HKDF-Extract, salt = psk)
okm   = HKDF-Expand(prk, "L87T/1 keys" || th, 160)
c2s_enc, c2s_mac, s2c_enc, s2c_mac, confirm = okm split into 32-byte keys
mac2  = HMAC(confirm, "L87T/1 accept" || th)[0:16]
```

The client checks `mac2`, which proves the phone knows the psk and finished the exchange, then
sends its first frame (HELLO). The phone treats the client as authenticated only once that
frame's MAC verifies. A replayed hello gets an answer the replayer cannot use.

## Frames

On the wire, in each direction:

```
len:u32be  ciphertext[len]  tag[16]
ks  = SHAKE256(enc_key || counter:u64be), first len bytes
ct  = plaintext XOR ks
tag = HMAC(mac_key, counter:u64be || len:u32be || ct)[0:16]
```

Version 2 makes the keystream with AES-256-CTR instead: key `enc_key`, initial counter block
`counter:u64be || 0:u64be`, so block `i` of a frame is `counter || i` (a frame is at most 4 097
blocks). The version byte is inside `mac1` and the transcript, so it cannot be changed on the way.
A client that gets no answer to version 2 (a phone with the older app) dials again with version 1.

`counter` starts at 0 and goes up by one per frame; it is not sent. `len` is at most
65 536 + 5. A bad tag closes the connection.

The plaintext:

```
type:u8  stream:u32be  body
```

| Type | Name | Body |
| --- | --- | --- |
| 1 | HELLO | JSON. Client: `{"name", "v"}`. Phone: `{"name", "v", "addrs", "port"}` |
| 2 | OPEN | `port:u16be`, the phone port the stream goes to (only the page's port is allowed) |
| 3 | DATA | up to 16 384 bytes |
| 4 | FIN | nothing: this side will send no more on the stream (half-close) |
| 5 | RST | optional UTF-8 reason: the stream is gone |
| 6 | CREDIT | `bytes:u32be`: the receiver has passed on that many more bytes |
| 7 | PING | 8 bytes |
| 8 | PONG | the PING's 8 bytes |
| 9 | ADDR | JSON `{"addrs": [...]}`: the phone's addresses changed |
| 10 | BYE | optional reason: the connection is closing |

- Streams are opened by the client with odd numbers (1, 3, 5, ...); even numbers are left for
  streams the phone opens in a later version.
- **Flow control.** Each stream may have 512 KiB unacknowledged in each direction. The receiver
  queues DATA for the stream's local socket and sends CREDIT as it writes it out (at least every
  128 KiB, or when the queue empties). A slow reader then only holds up its own stream, never
  the connection.
- **Keepalive.** Either side sends PING after 20 s without sending anything; a connection that
  has received nothing for 60 s is dead.
- Unknown frame types are ignored, so later versions can add some.

## Finding the phone again

- While the helper can reach the phone (any link), it fetches `GET /api/tunnel` and keeps the
  phone's addresses, port, `tid` and `psk` in its config folder. Over the tunnel, ADDR frames
  keep them current.
- When no local path answers (cable, hotspot, Wi-Fi, USB debugging), it dials every saved
  address at once (4 s each, the first up wins) and, at the same time, asks the phone where it
  is now: a `where` note on the board (see Across IPv4), which the phone answers with its current
  addresses and port. Mobile IPv6 changes when the phone reconnects; new addresses join the race
  the moment the answer comes (about 3 s in all), and are saved. Only if no IPv6 address answers
  does it punch over IPv4. It moves back to a local path as soon as one answers.
- The `where` note: `{"t": "where", "s", "at"}`; the answer: `{"t": "where", "s", "at",
  "addrs": [...], "port"}`. A `punch` answer carries `addrs` too.

## Across IPv4

When none of the phone's IPv6 addresses answers (the laptop's network has no IPv6), the two ends
punch through their NATs over UDP and run the same tunnel (handshake, frames, streams) over a
reliable stream on that path. Phone: `net/Punch.kt`; helpers: `punch_dial`, `Tunnel87.DialPunched`.

**Swapping addresses.** Two notes on a public message board, [ntfy.sh](https://ntfy.sh) (no
account), sent with `Cache: no` and `Firebase: no`, so the board keeps nothing and hands them only
to whoever is listening right then:

- Topics, per device: `up = "l87-" || hex(HMAC(psk, "L87P/1 up")[0:10])` (device to phone) and
  `down` the same with `"L87P/1 down"`. Unguessable without the psk.
- A note is `base64url(nonce[16] || ct || tag[16])`: `ct` is the JSON XOR
  `SHAKE256(HMAC(psk, "L87P/1 seal") || nonce)`, `tag = HMAC(HMAC(psk, "L87P/1 seal mac"),
  nonce || ct)[0:16]`.
- The device's note: `{"t": "punch", "s": session (8 bytes, hex), "at": unix seconds,
  "addr": "ip:port", "hard": bool, "lan": ["ip:port"]}`. The phone answers on `down` with the
  same shape and the same `s`. Notes more than 120 s off, or with a session seen before, are ignored.
- The phone listens on `up` for every paired device (one HTTPS stream,
  `GET /<topic>,<topic>/json`) only while *From other networks* is on. The device opens its
  `down` stream before posting, so the answer cannot be missed.

**Seeing the NAT.** Each end sends STUN Binding requests from the one UDP socket it will punch
with to `stun.l.google.com:19302` and `stun.cloudflare.com:3478`. The first answer is its public
address; the NAT is *hard* when the two servers saw different ports (a new mapping for every
destination), *easy* when they saw the same.

**Punching.** Both ends knock (PROBE) at the other's public and LAN addresses every 200 ms for up
to 15 s; the first valid PROBE or PROBE_ACK fixes the path (that socket, that address).

- Both easy: that is enough.
- One hard: the hard side knocks from 256 sockets, 256 mappings on its NAT; the easy side also
  sprays its probes over the hard side's ports, the 512 around the port STUN saw first (NATs that
  count up) and then the rest in a random order, 300 a second. One of its probes meets one of the
  256 mappings within seconds.
- Both hard: it fails, and the helper says so; use a hotspot, a cable, or IPv6.

Tested between home broadband on Airtel (hard, carrier NAT) and the phone on Jio data (easy over
NAT64): through in 5 to 9 s.

**Packets.** Every datagram, both while punching and after:

```
kind:u8  role:u8  seq:u32  ack:u32  sack:u64  wnd:u16  payload  tag[8]
tag = HMAC(dir_key, everything before it)[0:8]
k = HMAC(psk, "L87P/1 udp" || session);  device to phone: HMAC(k, "dev");  phone to device: HMAC(k, "phone")
```

| Kind | Name | |
| --- | --- | --- |
| 1 | PROBE | knocking; answered with PROBE_ACK |
| 2 | PROBE_ACK | |
| 3 | DATA | `seq`, up to 1 200 bytes (with the headers, under IPv6's minimum MTU even after NAT64) |
| 4 | ACK | |
| 5 | KEEP | after 5 s with nothing sent, so the NATs keep the path |
| 6 | CLOSE | |

`role` is 0 from the device and 1 from the phone. Every packet carries `ack` (the next `seq`
expected), `sack` (which of the 64 after it have arrived) and `wnd` (packets the receiver will
still hold). The path follows the other end when its address changes (a NAT rebinding) as long as
its packets check out.

**Reliability.** The receiver acks every second packet (or after 10 ms), at once when one arrives
out of order. The sender resends a packet when one sent after it has been acknowledged and it has
not, allowing a quarter of the round trip for reordering (RACK); and the oldest one when nothing
has come back for it in `srtt + max(4 rttvar, 200 ms)`. The window is Linux's: doubling each round
trip until the first loss, then CUBIC (back off to 0.7, back to the old size in a few seconds,
never slower than Reno), at most 2 048 packets. New packets leave strictly in order.

## Phone to phone

A linked phone is a device like any other on the phone it links with, so each holds the other's
tunnel details (`id`, `key`, `addrs`, `port`), taken over `GET /api/tunnel` and also handed over in
the link's greeting (`PeerHello.tunnel`), so a phone linked from afar has them without ever being
local. To reach a linked phone that does not answer on its local address, a phone dials the same
way the helper does (`TunnelClient.dialAny`): its known IPv6 addresses over TCP, then the addresses
it gives now through the board (`where`), then a path punched across IPv4 (`Punch.dial`, the
device's side, role 0). The tunnel is then served on a loopback port of the dialling phone, and
every request to the other phone goes there unchanged.

### Link codes

Two phones that have never shared a network link with a code one of them shows (`net/LinkCode.kt`):

- Four digits, shown as `4 8 2 1`, open for five minutes or until a phone links, with a secret of
  32 random bytes made beside it. The code never travels in any form (`net/LinkPake.kt`):
  - SPAKE2 (RFC 9382) over the 2048-bit MODP group of RFC 3526 (group 14, generator 2, working in
    its prime-order subgroup of squares). `w = SHA-512("L87L/2 w" || code) mod q`; `M` and `N` are
    squares of 2560-bit SHA-512 expansions of `"L87L/2 SPAKE2 M"` and `"... N"`. Exponents are 256
    random bits.
  - The typing phone listens on `l87-lk-<sid>` (sid: 8 random bytes, hex), then posts
    `{"s": sid, "x": X}` on the fixed topic `l87-link-v2`, `X = g^x · M^w`. The fixed topic says
    nothing about which code is open.
  - The showing phone checks `X` is in the subgroup (`X^q = 1`, `1 < X < p-1`), answers on
    `l87-lk-<sid>` with `{"s", "y": Y, "e": seal}`, `Y = g^y · N^w`, `Z = (X / M^w)^y`, and the
    secret sealed (the board's seal) under `HMAC(SHA-256(len-prefixed "L87L/2", sid, X, Y, Z, w), "L87L/2 seal")`.
  - The typing phone gets the same key from `Z = (Y / N^w)^x`; a seal that does not open means a
    wrong code. A guess is one try per exchange; the showing phone answers three per code and then
    says so.
- While it is open the showing phone's tunnel also answers for a stand-in device, `link-code`,
  whose keys come from that secret instead of the phone's own:
  `psk = HMAC-SHA256("L87L/2 psk", secret)`, `tid = HMAC-SHA256(psk, "L87L/2 id")[0:16]`. Its board
  topics follow from that psk as for any device, so the typing phone finds it by `where` and
  punches to it the same way.
- The typing phone dials with those keys, learns the page's port from the tunnel's HELLO
  (`page`), and asks to be let in through it exactly as on a shared Wi-Fi: `POST /api/pair`, a
  4-digit code to compare, and **Allow** on the showing phone. A code reaches the phone; only its
  owner lets anyone in.
- Once allowed, the typing phone takes the other phone's own tunnel details for itself
  (`/api/tunnel`) and sends its greeting with its own, so from then on each reaches the other with
  its permanent keys. The code closes half a minute after the link, and its tunnel with it. Both
  phones turn on *From other networks*.

### Messages

Messages between linked phones (`server/Messages.kt`) are posted straight to the other phone,
`POST /api/peers/msg`, whichever way it can be reached: the same Wi-Fi, or the tunnel. Each is
sealed on its own, so it is unreadable on a plain local link too:

- Key: `HMAC-SHA256(psk, "L87M/1 msg")`, where `psk` is the tunnel key the receiving phone made
  for the sending one (the sender has it from the receiver's `/api/tunnel`; the receiver derives
  it from the device the message came in on).
- AES-256-GCM, a fresh 12-byte nonce each time, `id/at` as associated data:
  `{"id", "at", "n": nonce, "c": ciphertext and tag}`, base64.
- A message waits on the sending phone until the other takes it, retried every 20 s. Read marks
  go back as `POST /api/peers/msg/read {"upTo": at}`, in the sender's own clock.

### Calls

Voice calls between linked phones (`server/Calls.kt`) are WebRTC, straight between the two:

- Setting up goes over the same channel as messages, `POST /api/peers/call`, each step sealed
  like a message under `HMAC(psk, "L87C/1 call")` with the call's id as associated data:
  `offer` and `answer` (each with its session description, sent once it holds the phone's own
  addresses and STUN's answer, or after a second), late `ice` candidates, `ringing`, and `end`
  with a reason (*Declined*, *Busy*, *No answer*).
- ICE uses public STUN (Cloudflare's, Google's) only to learn each phone's own public address,
  as the punching does, and no TURN: the sound never goes through anything but the two phones.
  Candidates are the phones' local addresses, their global IPv6, and IPv4 punched through.
- The sound is Opus over SRTP with DTLS keys, which the sealed setup makes known to the two
  phones only.

## Laptop to laptop

Two laptops reaching the same phone, one through the tunnel and one on the phone's network (or
both through tunnels), send files the way they do on one network: the phone introduces them and
they try to connect browser to browser (WebRTC), else the file goes through the phone.

- Streams enter the phone's page at `127.0.0.87`, so the page knows a request came through the
  tunnel; `/api/route` then says `via: "internet"`.
- `/api/route` lists the phone's STUN addresses: the one the page reached, and the phone's
  public IPv6. Browsers hide their own addresses behind `.local` names; the phone's STUN answer
  over IPv6 gives each its global IPv6 (a server-reflexive candidate), which the other laptop
  can reach. There is no IPv4 equivalent across networks: carrier NAT.
- The phone's STUN server (UDP 3478) answers IPv6 as well as IPv4.

## Limits

- From an IPv4-only network the two ends punch through (Across IPv4), which works unless both
  NATs are hard; then nothing short of a server carrying the traffic connects them.
- The carrier or the laptop's router may firewall inbound IPv6; then the connection fails at
  the TCP level (a timeout), which the helper reports as such.
- X25519 here uses ordinary big integers, not constant-time code. Its keys are ephemeral and
  used for one exchange each, which is what makes that acceptable.
