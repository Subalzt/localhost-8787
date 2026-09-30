# The L87 tunnel

How a laptop (or another phone) reaches the phone from a different network, with the phone as
the only server. One TCP connection, our own encryption, every connection multiplexed inside it.
It is what [remote-plan.md](remote-plan.md) calls the remote door.

Why this shape, after [Bypass-PROXY-with-WireGuard-over-HTTP-CONNECT](https://github.com/Subalzt/Bypass-PROXY-with-WireGuard-over-HTTP-CONNECT):

- **Our own encryption, not TLS.** A proxy that inspects TLS replaces the certificate, so a
  pinned TLS connection through it never completes. Opaque bytes inside a tunnel go through.
- **One long-lived, multiplexed carrier**, not a connection per request: one handshake, one
  firewall pinhole, and every browser connection a stream inside it.
- **Keepalives**, so carrier NAT and firewall state stays open while idle.
- **Silence to strangers.** Anything that does not open with a valid hello is closed without a
  byte in reply, so a scan of the phone's address learns nothing.

## Where it runs

- The phone listens on TCP **8789**, on every address (`::`, dual-stack). Across networks that
  means its global IPv6: mobile data has no IPv4 anything can connect to.
- The laptop helper dials it and serves the tunnel on `127.0.0.1:18789`, the way it uses adb's
  port forward: every local connection there becomes a stream to the phone's page (port 8787).
  The helper's relay, requests and event streams then work unchanged.
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
| Stream cipher | SHAKE256 as a keyed keystream: `SHAKE256(key || counter)` |
| Frame MAC | HMAC-SHA256 over counter, length and ciphertext, truncated to 16 bytes |

Chosen so every end has them fast with nothing installed: Python's standard library has
SHAKE256 and HMAC in C (and no AES), the phone has HMAC natively, and Keccak and X25519 are
short to write where missing. Encrypt-then-MAC with independent keys; the keystream of each
frame is keyed by a counter that never repeats within a connection, and every connection has
fresh keys.

## Handshake

Client to phone, 69 bytes:

```
"L87T"  version=1  tid[16]  e_c[32]  mac1[16]
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
- When no local path answers (cable, hotspot, Wi-Fi, USB debugging), it dials the saved
  addresses, and moves back to a local path as soon as one answers.
- If the phone's address changed while the two were apart (mobile IPv6 changes on reconnect),
  there is no server to ask: type the address shown on the phone's Home into the helper
  (`--phone`), or scan it.

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

- The laptop needs IPv6 of its own when the phone is on mobile data. From an IPv4-only network
  with carrier NAT on the phone's side, nothing short of a public server connects the two.
- The carrier or the laptop's router may firewall inbound IPv6; then the connection fails at
  the TCP level (a timeout), which the helper reports as such.
- X25519 here uses ordinary big integers, not constant-time code. Its keys are ephemeral and
  used for one exchange each, which is what makes that acceptable.
