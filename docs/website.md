# The phone as a website

`https://NAME:8443` from any browser on any network with IPv6, signed in with a PIN; and the
laptop helper's sign-in from anywhere, with the same PIN. The phone is still the only server:
a free dynamic-DNS name points at it, and Let's Encrypt vouches for it.

Phone: `net/Site.kt`, the Website row in Settings (`ui/WebsiteRow.kt`). Page: the PIN screen in
`bridge.html` (`siteLogin`). Server: `/api/site`, `/api/site/login`.

## What the person sets up

1. A free name at [dynv6](https://dynv6.com) (for example `yourname.v6.navy`), activated by
   confirming the sign-up email.
2. An **HTTP token** there (Keys, in the account menu). Not the zone's own update token: the
   phone also adds a record for the certificate check.
3. On the phone, Settings, Website: the name, the token, a PIN of 6 digits or more, and their
   own "I agree" to Let's Encrypt's Subscriber Agreement. Then switch it on.

The token and the PIN's hash stay in the app's own files (`site.json`); the PIN itself is kept
nowhere (PBKDF2-SHA256, 120 000 rounds, a random salt).

## What the phone does

- **The name.** Every minute it checks its global IPv6; when it changed, it calls
  `https://dynv6.com/api/update?zone=NAME&token=…&ipv6=ADDR&ipv4=-` (the `-` removes the A record:
  behind carrier NAT an IPv4 address would lead nowhere).
- **The certificate.** Let's Encrypt (ACME, RFC 8555), proved by DNS: the phone's own account key
  (EC P-256, made once), an order for the name, a TXT record `_acme-challenge` with the challenge
  digest created through dynv6's API (`POST /api/v2/zones/{id}/records`, the zone found by
  `GET /api/v2/zones/by-name/NAME`, bearer token), 45 s for it to spread, the check, a CSR (an EC
  P-256 key, the name as CN and SAN, written in DER by hand), the certificate chain, and the TXT
  record removed again. Renewed when less than 30 days are left. One attempt at a time; a failed
  one waits 15 minutes (Let's Encrypt allows 5 failed checks an hour per name), and switching the
  website off and on tries again at once.
- **The door.** TLS (Android's own, TLS 1.3) on port 8443 (a phone cannot listen below 1024), on
  every address. Each connection is passed to the page from `127.0.0.88`, so the page knows it came
  this way; the door remembers which browser address is behind each connection, for the PIN's
  limits and the device list. At most 60 connections a minute from one address (one /64).

## What the page allows through the door

- The page itself, `/api/ping`, `/api/site` and `/api/site/login`; everything else needs the
  session, as on the local network.
- Never `/api/pair`: strangers on the internet must not be able to make the phone ask.
- `POST /api/site/login {"pin"}`: right, and this browser becomes a paired device ("… (website)",
  listed and removable on the phone) with a `Secure`, `HttpOnly`, `SameSite=Strict` session
  cookie. Wrong: at most 5 an address (/64) per 15 minutes, and 30 an hour from everywhere, after
  which the PIN is refused for that hour.

## The helper, from anywhere

When the helper cannot find the phone, its prompt also takes the website's name. It asks for the
PIN (not echoed), signs in exactly as a browser does, and keeps the session and the tunnel's keys
(`GET /api/tunnel` through the website). From then on the tunnel (IPv6, or the IPv4 punch) finds
the phone by itself; the website is only needed for that first sign-in. TLS with a real
certificate is what proves to the helper it is talking to the phone, and keeps the PIN private.

## Limits

- The browser's network needs IPv6: the phone has no IPv4 address anything can reach. From an
  IPv4-only network, use the helper (it punches through) after signing it in once from somewhere
  with IPv6, or pair it on the same network.
- A free dynv6 name stays active only while the account is used; dynv6 may remove names left
  unused for a long time.
