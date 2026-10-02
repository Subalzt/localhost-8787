# The phone as a website

`https://NAME:8443` from any browser on any network with IPv6, let in with a tap on the phone
(Allow, as on the local network); and the laptop helper's sign-in from anywhere, the same way.
The phone is still the only server: a free dynamic-DNS name points at it, and Let's Encrypt
vouches for it.

Phone: `net/Site.kt`, the Website row in Settings (`ui/WebsiteRow.kt`). Page: `siteLogin` in
`bridge.html`. Server: `/api/site`, `/api/pair` (limited through the website), `/api/site/enroll`.

## What the person sets up

1. A free name at [dynv6](https://dynv6.com) (for example `yourname.v6.navy`), activated by
   confirming the sign-up email.
2. An **HTTP token** there (Keys, in the account menu). Not the zone's own update token: the
   phone also adds a record for the certificate check.
3. On the phone, Settings, Website: the name, the token, and their own "I agree" to Let's
   Encrypt's Subscriber Agreement. Then switch it on.

The token stays in the app's own files (`site.json`).

## What the phone does

- **The name.** Every 20 s it looks at its global IPv6, mobile data's own when there is one (the
  carrier lets connections in; a home router usually does not), and when it changed calls
  `https://dynv6.com/api/update?zone=NAME&token=…&ipv6=ADDR`. Each step (the name, the
  certificate, the door) runs on its own: one failing never keeps the door shut.
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
  this way; the door remembers which browser address is behind each connection, for the limits on
  asking, the device list, and telling a browser on the phone's own links from one on the internet. At most 60 connections a minute from one address (one /64).

## What the page allows through the door

- The page itself, `/api/ping`, `/api/site`, `/api/site/enroll` and asking the phone
  (`/api/pair`); everything else needs the session, as on the local network.
- Asking the phone: the same request and 4-digit code as on the local network, named
  "… (website)" and showing the browser's own address. Anyone who finds the name could make the
  phone ask, so at most 10 asks per 10 minutes from one address (/64) and 60 an hour from
  everywhere. On Allow, the browser gets a `Secure`, `HttpOnly`, `SameSite=Strict` session cookie.

## The helper, from anywhere

When the helper cannot find the phone, its prompt also takes the website's name. It asks the
phone exactly as a browser does (tap Allow on the phone), and on Allow keeps the session and the
tunnel's keys (`GET /api/tunnel` through the website). From then on the tunnel (IPv6, or the IPv4 punch) finds
the phone by itself; the website is only needed for that first sign-in. TLS with a real
certificate is what proves to the helper it is talking to the phone. A helper downloaded through
the website carries a one-time code instead and needs no Allow at all.

## Limits

- The browser's network needs IPv6: the phone has no IPv4 address anything can reach. From an
  IPv4-only network, use the helper (it punches through) after signing it in once from somewhere
  with IPv6, or pair it on the same network.
- A free dynv6 name stays active only while the account is used; dynv6 may remove names left
  unused for a long time.
