//! Reaching the phone across IPv4 (docs/tunnel-protocol.md, "Across IPv4"). When none of the
//! phone's IPv6 addresses answers, both ends swap their public IPv4 addresses as sealed notes (in
//! the phone's own website zone when it has one, else on the public board ntfy.sh), punch through
//! their NATs over UDP, and run the same tunnel over a reliable stream on that path. The same as
//! the phone's Punch.kt and the earlier helpers'.
//!
//! The reliable stream (Udp87) is handed to the tunnel as a local socket: the tunnel code neither
//! knows nor cares that its bytes cross the internet as UDP.

use crate::https;
use crate::tunnel::{hmac, hmac16, same, Tunnel};
use crate::util::{log, now_ms};
use base64::Engine;
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread::{sleep, spawn};
use std::time::{Duration, Instant};

const PROBE: u8 = 1;
const PROBE_ACK: u8 = 2;
const PDATA: u8 = 3;
const PACK: u8 = 4;
const PKEEP: u8 = 5;
const PCLOSE: u8 = 6;
const PHEADER: usize = 20;
const PTAG: usize = 8;
const MSS: usize = 1200;
const PQUEUE: usize = 4096;
const PWINDOW: u32 = 4096;

const BOARD: &str = "https://ntfy.sh";
const DYNV6: &str = "https://dynv6.com/api/v2";

fn io_err(s: impl Into<String>) -> io::Error {
    io::Error::other(s.into())
}

fn tick() -> u64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

fn random<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    getrandom::getrandom(&mut b).expect("the system's random numbers");
    b
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

/// `a` comes before `b`, counting round the 32-bit numbers.
fn before(a: u32, b: u32) -> bool {
    (a.wrapping_sub(b) as i32) < 0
}

// ---------------------------------------------------------------- sealed notes

fn topic(psk: &[u8], label: &str) -> String {
    format!("l87-{}", hex(&hmac(psk, &[label.as_bytes()])[..10]))
}

fn shake_xor(key: &[u8], nonce: &[u8], data: &mut [u8]) {
    use sha3::digest::{ExtendableOutput, Update, XofReader};
    let mut h = sha3::Shake256::default();
    Update::update(&mut h, key);
    Update::update(&mut h, nonce);
    let mut ks = vec![0u8; data.len()];
    XofReader::read(&mut h.finalize_xof(), &mut ks);
    for (d, k) in data.iter_mut().zip(ks) {
        *d ^= k;
    }
}

fn seal(psk: &[u8], json: &str) -> String {
    let nonce: [u8; 16] = random();
    let mut ct = json.as_bytes().to_vec();
    shake_xor(&hmac(psk, &[b"L87P/1 seal"]), &nonce, &mut ct);
    let tag = hmac16(&hmac(psk, &[b"L87P/1 seal mac"]), &[&nonce, &ct]);
    let mut all = nonce.to_vec();
    all.extend_from_slice(&ct);
    all.extend_from_slice(&tag);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(all)
}

fn unseal(psk: &[u8], text: &str) -> Option<Value> {
    let all = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(text.trim().trim_end_matches('=')).ok()?;
    if all.len() < 33 {
        return None;
    }
    let (nonce, rest) = all.split_at(16);
    let (ct, tag) = rest.split_at(rest.len() - 16);
    if !same(tag, &hmac16(&hmac(psk, &[b"L87P/1 seal mac"]), &[nonce, ct])) {
        return None;
    }
    let mut pt = ct.to_vec();
    shake_xor(&hmac(psk, &[b"L87P/1 seal"]), nonce, &mut pt);
    serde_json::from_slice(&pt).ok()
}

fn is_answer(a: &Value, kind: &str, s: &str) -> bool {
    a["t"] == kind && a["s"] == s
}

// ---------------------------------------------------------------- the meeting place

/// Leaves a note for the phone on the public board and returns its sealed answer (the same kind and
/// session), or None. Listens before posting, so the answer cannot be missed.
fn ask_board(psk: &[u8], note: &str, kind: &str, s: &str, wait: Duration) -> io::Result<Option<Value>> {
    let sub = https::stream(&format!("{}/{}/json", BOARD, topic(psk, "L87P/1 down")), wait + Duration::from_secs(1))?;
    // The board's "open".
    sub.rx.recv_timeout(Duration::from_secs(10)).map_err(|_| io_err(format!("could not reach the board at {}", BOARD)))?;
    let r = https::request(
        "POST",
        &format!("{}/{}", BOARD, topic(psk, "L87P/1 up")),
        &[("Cache", "no".into()), ("Firebase", "no".into())],
        Some(&seal(psk, note)),
        Duration::from_secs(10),
    )?;
    if r.status >= 400 {
        return Err(io_err(format!("the board answered {}", r.status)));
    }
    let end = Instant::now() + wait;
    while let Some(left) = end.checked_duration_since(Instant::now()) {
        let Ok(line) = sub.rx.recv_timeout(left) else { break };
        let Ok(m) = serde_json::from_str::<Value>(&line) else { continue };
        if m["event"] != "message" {
            continue;
        }
        if let Some(a) = m["message"].as_str().and_then(|t| unseal(psk, t)) {
            if is_answer(&a, kind, s) {
                return Ok(Some(a));
            }
        }
    }
    Ok(None)
}

fn zone_http(method: &str, path: &str, token: &str, body: Option<&str>) -> io::Result<String> {
    let mut h = vec![("Authorization", format!("Bearer {}", token)), ("Accept", "application/json".into())];
    if body.is_some() {
        h.push(("Content-Type", "application/json".into()));
    }
    let r = https::request(method, &format!("{}{}", DYNV6, path), &h, body, Duration::from_secs(15))?;
    if r.status >= 400 || r.status == 0 {
        return Err(io_err(format!("dynv6: HTTP {}", r.status)));
    }
    Ok(r.body)
}

fn zone_id(zone: &str, token: &str) -> io::Result<String> {
    static IDS: Mutex<Vec<(String, String)>> = Mutex::new(Vec::new());
    if let Some((_, id)) = IDS.lock().unwrap().iter().find(|(z, _)| z == zone) {
        return Ok(id.clone());
    }
    let v: Value = serde_json::from_str(&zone_http("GET", &format!("/zones/by-name/{}", zone), token, None)?).map_err(|_| io_err("dynv6 answered oddly"))?;
    let id = v["id"].as_u64().map(|n| n.to_string()).ok_or_else(|| io_err(format!("dynv6 does not know the zone {}", zone)))?;
    IDS.lock().unwrap().push((zone.to_string(), id.clone()));
    Ok(id)
}

/// The TXT records named `topic` in the zone: each one's id and text.
fn zone_records(zone: &str, token: &str, topic: &str) -> io::Result<Vec<(String, String)>> {
    fn walk(v: &Value, topic: &str, out: &mut Vec<(String, String)>) {
        match v {
            Value::Array(a) => a.iter().for_each(|x| walk(x, topic, out)),
            Value::Object(o) => {
                if v["type"] == "TXT" && v["name"] == topic {
                    out.push((v["id"].as_u64().map(|n| n.to_string()).unwrap_or_default(), v["data"].as_str().unwrap_or("").to_string()));
                } else {
                    o.values().for_each(|x| walk(x, topic, out));
                }
            }
            _ => {}
        }
    }
    let text = zone_http("GET", &format!("/zones/{}/records", zone_id(zone, token)?), token, None)?;
    let v: Value = serde_json::from_str(&text).map_err(|_| io_err("dynv6 answered oddly"))?;
    let mut out = Vec::new();
    walk(&v, topic, &mut out);
    Ok(out)
}

fn zone_clear(zone: &str, token: &str, topic: &str) {
    if let (Ok(recs), Ok(id)) = (zone_records(zone, token, topic), zone_id(zone, token)) {
        for (rid, _) in recs {
            let _ = zone_http("DELETE", &format!("/zones/{}/records/{}", id, rid), token, None);
        }
    }
}

/// A phone with a website takes punch notes in its own DNS zone, not on a public board: the note
/// goes in as a TXT record over HTTPS, and the phone's answer is read back the same way.
fn ask_zone(psk: &[u8], note: &str, kind: &str, s: &str, wait: Duration, zone: &str, token: &str) -> io::Result<Option<Value>> {
    let (up, down) = (topic(psk, "L87P/1 up"), topic(psk, "L87P/1 down"));
    zone_clear(zone, token, &up);
    let body = json!({"name": up, "type": "TXT", "data": seal(psk, note)}).to_string();
    zone_http("POST", &format!("/zones/{}/records", zone_id(zone, token)?), token, Some(&body))?;
    // The phone looks in its zone every 15 s; its answer is read back until it comes.
    let end = Instant::now() + wait.max(Duration::from_secs(35));
    let mut found = None;
    while Instant::now() < end && found.is_none() {
        for (_, text) in zone_records(zone, token, &down).unwrap_or_default() {
            if let Some(a) = unseal(psk, &text).filter(|a| is_answer(a, kind, s)) {
                found = Some(a);
                break;
            }
        }
        if found.is_none() {
            sleep(Duration::from_millis(1500));
        }
    }
    zone_clear(zone, token, &up);
    Ok(found)
}

fn ask(psk: &[u8], note: &str, kind: &str, s: &str, wait: Duration, zone: Option<(&str, &str)>) -> io::Result<Option<Value>> {
    match zone {
        Some((z, t)) => ask_zone(psk, note, kind, s, wait, z, t),
        None => ask_board(psk, note, kind, s, wait),
    }
}

/// The phone's current addresses, asked through the board (its IPv6 changes when mobile data
/// reconnects). Empty when it does not answer.
pub fn where_addrs(psk: &[u8]) -> Vec<String> {
    let s = hex(&random::<8>());
    let note = json!({"t": "where", "s": s, "at": now_ms() / 1000}).to_string();
    match ask_board(psk, &note, "where", &s, Duration::from_secs(6)) {
        Ok(Some(a)) => a["addrs"].as_array().map(|l| l.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default(),
        _ => Vec::new(),
    }
}

// ---------------------------------------------------------------- seeing the NAT

fn new_udp() -> io::Result<UdpSocket> {
    UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))
}

fn first_v4(host: &str, port: u16) -> Option<SocketAddr> {
    (host, port).to_socket_addrs().ok()?.find(|a| a.is_ipv4())
}

/// One STUN Binding request (RFC 5389) from `s`: the address the server saw it come from.
fn stun(s: &UdpSocket, host: &str, port: u16) -> Option<SocketAddr> {
    let server = first_v4(host, port)?;
    let tid: [u8; 12] = random();
    let mut req = vec![0, 1, 0, 0, 0x21, 0x12, 0xA4, 0x42];
    req.extend_from_slice(&tid);
    let mut buf = [0u8; 2048];
    let end = Instant::now() + Duration::from_millis(1500);
    let mut sent: Option<Instant> = None;
    let _ = s.set_read_timeout(Some(Duration::from_millis(400)));
    let mut found = None;
    while Instant::now() < end && found.is_none() {
        if sent.map_or(true, |t| t.elapsed() > Duration::from_millis(400)) {
            let _ = s.send_to(&req, server);
            sent = Some(Instant::now());
        }
        let Ok((n, _)) = s.recv_from(&mut buf) else { continue };
        if n < 20 || buf[8..20] != tid {
            continue;
        }
        let mut p = 20;
        while p + 4 <= n {
            let (kind, len) = (((buf[p] as usize) << 8) | buf[p + 1] as usize, ((buf[p + 2] as usize) << 8) | buf[p + 3] as usize);
            let v = p + 4;
            if (kind == 0x20 || kind == 1) && len >= 8 && v + 8 <= n && buf[v + 1] == 1 {
                let mut port = ((buf[v + 2] as u16) << 8) | buf[v + 3] as u16;
                let mut ip = [buf[v + 4], buf[v + 5], buf[v + 6], buf[v + 7]];
                if kind == 0x20 {
                    port ^= 0x2112;
                    for (b, m) in ip.iter_mut().zip([0x21, 0x12, 0xA4, 0x42]) {
                        *b ^= m;
                    }
                }
                found = Some(SocketAddr::from((ip, port)));
                break;
            }
            p = v + len + ((4 - len % 4) % 4);
        }
    }
    let _ = s.set_read_timeout(None);
    found
}

fn lan_ip() -> Option<String> {
    let s = new_udp().ok()?;
    s.connect("192.0.2.1:9").ok()?; // nothing is sent; this only picks the outgoing address
    Some(s.local_addr().ok()?.ip().to_string())
}

// ---------------------------------------------------------------- packets and knocking

/// Packet tags: HMAC-SHA256 over the packet, the first 8 bytes. One key per direction.
struct Tagger(Vec<u8>);

impl Tagger {
    fn packet(&self, kind: u8, role: u8, seq: u32, ack: u32, sack: u64, wnd: u16, payload: &[u8]) -> Vec<u8> {
        let mut b = Vec::with_capacity(PHEADER + payload.len() + PTAG);
        b.push(kind);
        b.push(role);
        b.extend_from_slice(&seq.to_be_bytes());
        b.extend_from_slice(&ack.to_be_bytes());
        b.extend_from_slice(&sack.to_be_bytes());
        b.extend_from_slice(&wnd.to_be_bytes());
        b.extend_from_slice(payload);
        let t = hmac(&self.0, &[&b]);
        b.extend_from_slice(&t[..PTAG]);
        b
    }

    fn valid(&self, b: &[u8]) -> bool {
        b.len() >= PHEADER + PTAG && same(&b[b.len() - PTAG..], &hmac(&self.0, &[&b[..b.len() - PTAG]])[..PTAG])
    }
}

fn be32(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]])
}

struct Found {
    sock: UdpSocket,
    to: SocketAddr,
}

/// Knocks from `main` (and 255 more sockets when this side's NAT is hard) at the other side's
/// addresses, spraying random ports on its address when its NAT is hard and this one's is not,
/// until a packet from it arrives: that socket and that address are the path. None after 15 s.
fn knock(main: UdpSocket, hard_here: bool, theirs: &[SocketAddr], hard_there: bool, tx: &[u8], rx: &[u8], role: u8) -> Option<Found> {
    let mut socks = vec![main];
    if hard_here && !hard_there {
        for _ in 1..256 {
            match new_udp() {
                Ok(s) => socks.push(s),
                Err(_) => break,
            }
        }
    }
    for s in &socks {
        let _ = s.set_nonblocking(true);
    }
    let (seal, check) = (Tagger(tx.to_vec()), Tagger(rx.to_vec()));
    let probe = seal.packet(PROBE, role, 0, 0, 0, 0, &[]);
    let ack = seal.packet(PROBE_ACK, role, 0, 0, 0, 0, &[]);
    let mut ports: Option<Vec<u16>> = None;
    if hard_there && !hard_here {
        if let Some(first) = theirs.first() {
            // The ports near the one STUN saw first (NATs that count up), then the rest at random.
            let seen = first.port() as i32;
            let mut order: Vec<u16> = Vec::new();
            let mut near = std::collections::HashSet::new();
            for d in 1..=256 {
                for p in [seen + d, seen - d] {
                    if (1024..=65535).contains(&p) && near.insert(p) {
                        order.push(p as u16);
                    }
                }
            }
            let mut rest: Vec<u16> = (1024..=65535u32).filter(|p| *p as i32 != seen && !near.contains(&(*p as i32))).map(|p| p as u16).collect();
            for i in (1..rest.len()).rev() {
                let j = u32::from_le_bytes(random::<4>()) as usize % (i + 1);
                rest.swap(i, j);
            }
            order.extend(rest);
            ports = Some(order);
        }
    }
    let start = Instant::now();
    let mut last: Option<Instant> = None;
    let mut next = 0usize;
    let mut buf = [0u8; 2048];
    let mut got: Option<(usize, SocketAddr)> = None;
    while got.is_none() && start.elapsed() < Duration::from_secs(15) {
        if last.map_or(true, |t| t.elapsed() >= Duration::from_millis(200)) {
            last = Some(Instant::now());
            for s in &socks {
                for t in theirs {
                    let _ = s.send_to(&probe, t);
                }
            }
        }
        if let (Some(ports), Some(first)) = (&ports, theirs.first()) {
            for _ in 0..30 {
                let _ = socks[0].send_to(&probe, SocketAddr::new(first.ip(), ports[next % ports.len()]));
                next += 1;
            }
        }
        'sweep: for (i, s) in socks.iter().enumerate() {
            for _ in 0..64 {
                match s.recv_from(&mut buf) {
                    Ok((n, from)) => {
                        if !check.valid(&buf[..n]) {
                            continue;
                        }
                        if buf[0] == PROBE {
                            for _ in 0..3 {
                                let _ = s.send_to(&ack, from);
                            }
                        }
                        if buf[0] == PROBE || buf[0] == PROBE_ACK {
                            got = Some((i, from));
                            break 'sweep;
                        }
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                    Err(_) => {} // a refused knock (ICMP) on Windows
                }
            }
        }
        // Spraying goes at 300 a second; plain knocking looks often.
        sleep(Duration::from_millis(if ports.is_some() { 100 } else { 20 }));
    }
    let (i, to) = got?;
    let sock = socks.swap_remove(i);
    let _ = sock.set_nonblocking(false);
    Some(Found { sock, to })
}

// ---------------------------------------------------------------- the reliable stream

struct Sent {
    seq: u32,
    data: Vec<u8>,
    at: u64,
    tries: u32,
}

struct St {
    pending: VecDeque<Vec<u8>>,
    inflight: BTreeMap<u32, Sent>,
    next_seq: u32,
    recover: u32,
    peer_wnd: u32,
    cwnd: f64,
    ssthresh: f64,
    srtt: f64,
    rttvar: f64,
    min_rtt: f64,
    wmax: f64,
    cubic_k: f64,
    origin: f64,
    rto: u64,
    epoch: u64,
    epoch_set: bool,
    expected: u32,
    unacked: u32,
    early: HashMap<u32, Vec<u8>>,
    chunks: VecDeque<Vec<u8>>,
    cur: Vec<u8>,
    pos: usize,
}

struct Udp {
    sock: UdpSocket,
    peer: Mutex<SocketAddr>,
    seal: Tagger,
    check: Tagger,
    role: u8,
    st: Mutex<St>,
    cv: Condvar,
    order: Mutex<()>,
    again: AtomicBool,
    open: AtomicBool,
    last_sent: AtomicU64,
    last_heard: AtomicU64,
}

/// A reliable, ordered stream of bytes over one UDP path, for the tunnel to run on: numbered
/// packets, cumulative and selective acks, loss found by time (a later packet came, this one did
/// not), resending, and CUBIC's window. The same as the phone's UdpCarrier.
#[derive(Clone)]
struct Udp87(Arc<Udp>);

impl Udp87 {
    fn new(sock: UdpSocket, peer: SocketAddr, tx: &[u8], rx: &[u8], role: u8) -> Udp87 {
        let _ = sock.set_read_timeout(Some(Duration::from_millis(200)));
        let now = tick();
        let u = Udp87(Arc::new(Udp {
            sock,
            peer: Mutex::new(peer),
            seal: Tagger(tx.to_vec()),
            check: Tagger(rx.to_vec()),
            role,
            st: Mutex::new(St {
                pending: VecDeque::new(),
                inflight: BTreeMap::new(),
                next_seq: 0,
                recover: 0,
                peer_wnd: PWINDOW,
                cwnd: 16.0,
                ssthresh: 1e9,
                srtt: 0.0,
                rttvar: 0.0,
                min_rtt: 0.0,
                wmax: 0.0,
                cubic_k: 0.0,
                origin: 0.0,
                rto: 1000,
                epoch: 0,
                epoch_set: false,
                expected: 0,
                unacked: 0,
                early: HashMap::new(),
                chunks: VecDeque::new(),
                cur: Vec::new(),
                pos: 0,
            }),
            cv: Condvar::new(),
            order: Mutex::new(()),
            again: AtomicBool::new(false),
            open: AtomicBool::new(true),
            last_sent: AtomicU64::new(now),
            last_heard: AtomicU64::new(now),
        }));
        let (a, b) = (u.clone(), u.clone());
        spawn(move || a.recv_loop());
        spawn(move || b.tick_loop());
        u
    }

    fn is_open(&self) -> bool {
        self.0.open.load(Ordering::Relaxed)
    }

    fn send(&self, kind: u8, seq: u32, payload: &[u8]) {
        let (ack, sack, wnd) = {
            let mut s = self.0.st.lock().unwrap();
            let mut bits = 0u64;
            for i in 0..64u32 {
                if s.early.contains_key(&s.expected.wrapping_add(1 + i)) {
                    bits |= 1 << i;
                }
            }
            if kind == PDATA || kind == PACK || kind == PKEEP {
                s.unacked = 0;
            }
            (s.expected, bits, PWINDOW.saturating_sub(s.early.len() as u32))
        };
        let pk = self.0.seal.packet(kind, self.0.role, seq, ack, sack, wnd as u16, payload);
        let to = *self.0.peer.lock().unwrap();
        let _ = self.0.sock.send_to(&pk, to);
        self.0.last_sent.store(tick(), Ordering::Relaxed);
    }

    /// Sends what the window allows: one thread at a time, so packets leave in order.
    fn pump(&self) {
        self.0.again.store(true, Ordering::SeqCst);
        while self.0.again.load(Ordering::SeqCst) {
            let Ok(_g) = self.0.order.try_lock() else { return };
            self.0.again.store(false, Ordering::SeqCst);
            let mut out: Vec<(u32, Vec<u8>)> = Vec::new();
            {
                let mut s = self.0.st.lock().unwrap();
                let room = (s.cwnd as i64).min(s.peer_wnd as i64) - s.inflight.len() as i64;
                while (out.len() as i64) < room && out.len() < 256 {
                    let Some(data) = s.pending.pop_front() else { break };
                    let seq = s.next_seq;
                    s.next_seq = s.next_seq.wrapping_add(1);
                    s.inflight.insert(seq, Sent { seq, data: data.clone(), at: tick(), tries: 0 });
                    out.push((seq, data));
                }
            }
            for (seq, data) in out {
                self.send(PDATA, seq, &data);
            }
        }
    }

    fn recv_loop(&self) {
        let mut buf = [0u8; 2048];
        while self.is_open() {
            let (n, from) = match self.0.sock.recv_from(&mut buf) {
                Ok(r) => r,
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::ConnectionReset) => continue,
                Err(_) => break,
            };
            let b = &buf[..n];
            if !self.0.check.valid(b) || b[1] == self.0.role {
                continue;
            }
            self.0.last_heard.store(tick(), Ordering::Relaxed);
            {
                // The other end's NAT moved it.
                let mut p = self.0.peer.lock().unwrap();
                if *p != from {
                    *p = from;
                }
            }
            let (kind, seq, ack, wnd) = (b[0], be32(b, 2), be32(b, 6), ((b[18] as u32) << 8) | b[19] as u32);
            let sack = ((be32(b, 10) as u64) << 32) | be32(b, 14) as u64;
            match kind {
                PROBE => {
                    let pk = self.0.seal.packet(PROBE_ACK, self.0.role, 0, 0, 0, 0, &[]);
                    let _ = self.0.sock.send_to(&pk, from);
                }
                PCLOSE => {
                    self.finish();
                    return;
                }
                PDATA | PACK | PKEEP => {
                    self.acked(ack, sack, wnd);
                    if kind == PDATA {
                        self.data(seq, b[PHEADER..n - PTAG].to_vec());
                    }
                }
                _ => {}
            }
        }
        self.finish();
    }

    fn data(&self, seq: u32, payload: Vec<u8>) {
        let now;
        {
            let mut s = self.0.st.lock().unwrap();
            if before(seq, s.expected) {
                now = true; // a copy of one already had
            } else if seq == s.expected {
                s.chunks.push_back(payload);
                s.expected = s.expected.wrapping_add(1);
                let filled = !s.early.is_empty();
                loop {
                    let next = s.expected;
                    let Some(e) = s.early.remove(&next) else { break };
                    s.chunks.push_back(e);
                    s.expected = next.wrapping_add(1);
                }
                s.unacked += 1;
                now = filled || s.unacked >= 2;
                self.0.cv.notify_all();
            } else if seq.wrapping_sub(s.expected) < PWINDOW {
                s.early.insert(seq, payload);
                now = true;
            } else {
                now = false;
            }
        }
        if now {
            self.send(PACK, 0, &[]);
        }
    }

    fn acked(&self, ack: u32, sack: u64, wnd: u32) {
        let mut resend: Vec<(u32, Vec<u8>)> = Vec::new();
        {
            let mut s = self.0.st.lock().unwrap();
            s.peer_wnd = wnd.max(4);
            let now = tick();
            let (mut newly, mut sample, mut latest) = (0u32, -1i64, 0i64);
            let (mut cumulative, mut any) = (false, false);
            let done: Vec<u32> = s.inflight.keys().take_while(|k| before(**k, ack)).copied().collect();
            let note = |x: &Sent, any: &mut bool, sample: &mut i64, latest: &mut i64| {
                if x.tries == 0 {
                    *sample = now as i64 - x.at as i64;
                }
                if !*any || x.at as i64 - *latest > 0 {
                    *latest = x.at as i64;
                }
                *any = true;
            };
            for k in done {
                if let Some(x) = s.inflight.remove(&k) {
                    note(&x, &mut any, &mut sample, &mut latest);
                    newly += 1;
                    cumulative = true;
                }
            }
            for i in 0..64u32 {
                if sack & (1 << i) == 0 {
                    continue;
                }
                if let Some(x) = s.inflight.remove(&ack.wrapping_add(1 + i)) {
                    note(&x, &mut any, &mut sample, &mut latest);
                    newly += 1;
                }
            }
            if sample >= 0 {
                let sm = sample as f64;
                if s.srtt == 0.0 {
                    s.srtt = sm;
                    s.rttvar = sm / 2.0;
                } else {
                    s.rttvar = 0.75 * s.rttvar + 0.25 * (s.srtt - sm).abs();
                    s.srtt = 0.875 * s.srtt + 0.125 * sm;
                }
                s.min_rtt = if s.min_rtt == 0.0 { sm } else { s.min_rtt.min(sm) };
            }
            if cumulative {
                // Linux: at least 200 ms over the round trip.
                s.rto = (s.srtt + (4.0 * s.rttvar).max(200.0)).min(3000.0) as u64;
            }
            Self::grow(&mut s, newly, now);
            // A packet sent after this one has come and this one has not (allowing a little
            // reordering): lost, send it again. Originals leave in order, so the first one never
            // resent and sent too late to count ends the search.
            let mut lost: Vec<u32> = Vec::new();
            if any {
                let reo = (s.srtt / 4.0).max(5.0) as i64;
                for x in s.inflight.values() {
                    if lost.len() >= 64 {
                        break;
                    }
                    if latest - reo - x.at as i64 > 0 {
                        lost.push(x.seq);
                    } else if x.tries == 0 {
                        break;
                    }
                }
            }
            if let Some(&first) = lost.first() {
                if !before(first, s.recover) {
                    s.wmax = s.cwnd;
                    s.epoch_set = false;
                    s.ssthresh = (s.cwnd * 0.7).max(8.0);
                    s.cwnd = s.ssthresh;
                    s.recover = s.next_seq;
                }
                for k in lost {
                    if let Some(x) = s.inflight.get_mut(&k) {
                        x.at = now;
                        x.tries += 1;
                        resend.push((x.seq, x.data.clone()));
                    }
                }
            }
            self.0.cv.notify_all();
        }
        for (seq, data) in resend {
            self.send(PDATA, seq, &data);
        }
        self.pump();
    }

    /// As Linux: doubling until the first loss, then CUBIC; never slower than Reno.
    fn grow(s: &mut St, n: u32, now: u64) {
        if n == 0 {
            return;
        }
        if s.cwnd < s.ssthresh {
            s.cwnd += n as f64;
        } else {
            if !s.epoch_set {
                s.epoch_set = true;
                s.epoch = now;
                s.cubic_k = ((s.wmax - s.cwnd).max(0.0) / 0.4).cbrt();
                s.origin = s.wmax.max(s.cwnd);
            }
            let t = (now as f64 - s.epoch as f64 + s.min_rtt) / 1000.0;
            let target = s.origin + 0.4 * (t - s.cubic_k).powi(3);
            s.cwnd += n as f64 * (target - s.cwnd).max(1.0) / s.cwnd;
        }
        s.cwnd = s.cwnd.min(2048.0);
    }

    fn tick_loop(&self) {
        while self.is_open() {
            sleep(Duration::from_millis(10));
            let now = tick();
            let mut first: Option<(u32, Vec<u8>)> = None;
            let ack_due;
            {
                // Nothing back for the oldest packet in a whole timeout: send it again, and start
                // slow (once per loss; the acks for it then show what else is missing).
                let mut s = self.0.st.lock().unwrap();
                let rto = s.rto;
                let recover = s.recover;
                let next_seq = s.next_seq;
                let cwnd = s.cwnd;
                if let Some(x) = s.inflight.values_mut().next() {
                    if now - x.at > rto {
                        x.at = now;
                        x.tries += 1;
                        first = Some((x.seq, x.data.clone()));
                    }
                }
                if let Some((seq, _)) = &first {
                    if !before(*seq, recover) {
                        s.wmax = cwnd;
                        s.epoch_set = false;
                        s.ssthresh = (cwnd * 0.7).max(8.0);
                        s.cwnd = 8.0;
                        s.recover = next_seq;
                    }
                    s.rto = (rto * 2).min(5000);
                }
                ack_due = s.unacked > 0;
            }
            if let Some((seq, data)) = first {
                self.send(PDATA, seq, &data);
            }
            if ack_due {
                self.send(PACK, 0, &[]);
            }
            self.pump();
            if now - self.0.last_sent.load(Ordering::Relaxed) > 5000 {
                self.send(PKEEP, 0, &[]);
            }
            if now - self.0.last_heard.load(Ordering::Relaxed) > 60_000 {
                log("Punched path: nothing from the other end for 60 s");
                self.finish();
            }
        }
    }

    fn finish(&self) {
        if self.0.open.swap(false, Ordering::SeqCst) {
            let _g = self.0.st.lock().unwrap();
            self.0.cv.notify_all();
        }
    }

    fn close(&self) {
        if self.is_open() {
            for _ in 0..3 {
                self.send(PCLOSE, 0, &[]);
            }
        }
        self.finish();
    }

    /// Waits for bytes; 0 once the path is closed and everything has been read.
    fn read(&self, out: &mut [u8]) -> usize {
        let mut s = self.0.st.lock().unwrap();
        loop {
            if s.pos >= s.cur.len() {
                match s.chunks.pop_front() {
                    Some(c) => {
                        s.cur = c;
                        s.pos = 0;
                    }
                    None if !self.is_open() => return 0,
                    None => {
                        s = self.0.cv.wait_timeout(s, Duration::from_secs(1)).unwrap().0;
                        continue;
                    }
                }
            }
            let n = out.len().min(s.cur.len() - s.pos);
            out[..n].copy_from_slice(&s.cur[s.pos..s.pos + n]);
            s.pos += n;
            return n;
        }
    }

    fn write(&self, data: &[u8]) -> io::Result<()> {
        for chunk in data.chunks(MSS) {
            let mut s = self.0.st.lock().unwrap();
            while self.is_open() && s.pending.len() + s.inflight.len() >= PQUEUE {
                s = self.0.cv.wait_timeout(s, Duration::from_secs(1)).unwrap().0;
            }
            if !self.is_open() {
                return Err(io_err("the path is closed"));
            }
            s.pending.push_back(chunk.to_vec());
            drop(s);
        }
        self.pump();
        Ok(())
    }
}

/// The reliable stream as a local socket, for the tunnel's handshake and frames: what it writes
/// crosses the punched path, and what arrives from it is what it reads.
fn local_socket(u: Udp87) -> io::Result<TcpStream> {
    let l = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
    let client = TcpStream::connect(l.local_addr()?)?;
    let (server, from) = l.accept()?;
    if Some(from) != client.local_addr().ok() {
        return Err(io_err("another program connected to the tunnel's local socket"));
    }
    let _ = client.set_nodelay(true);
    let _ = server.set_nodelay(true);
    let (mut up, mut down) = (server.try_clone()?, server);
    let ud = u.clone();
    spawn(move || {
        // The tunnel's bytes out, over the path.
        let mut buf = vec![0u8; 1 << 16];
        loop {
            match up.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => if u.write(&buf[..n]).is_err() { break },
            }
        }
        u.close();
        let _ = up.shutdown(Shutdown::Both);
    });
    spawn(move || {
        // What arrives from the path, to the tunnel.
        let mut buf = vec![0u8; 1 << 16];
        loop {
            let n = ud.read(&mut buf);
            if n == 0 || down.write_all(&buf[..n]).is_err() {
                break;
            }
        }
        let _ = down.shutdown(Shutdown::Both);
    });
    Ok(client)
}

fn parse_addr(a: &str) -> Option<SocketAddr> {
    a.parse::<SocketAddr>().ok().filter(|a| a.is_ipv4())
}

/// Across IPv4: a note to the phone, its answer, then the punch, then the same handshake as a
/// direct dial over the punched path. `zone` is the phone's website name and its dynv6 token, when
/// it has them. Errors say why it could not.
pub fn dial_punched(tid: &[u8], psk: &[u8], zone: Option<(&str, &str)>) -> io::Result<Tunnel> {
    let sock = new_udp()?;
    let a1 = stun(&sock, "stun.l.google.com", 19302);
    let a2 = stun(&sock, "stun.cloudflare.com", 3478);
    let me = a1.or(a2).ok_or_else(|| io_err("this network gives no public IPv4 address to punch from (STUN did not answer)"))?;
    let hard = matches!((a1, a2), (Some(x), Some(y)) if x.port() != y.port());
    let s = hex(&random::<8>());
    let session: Vec<u8> = (0..8).filter_map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()).collect();
    let mut note = json!({"t": "punch", "s": s, "at": now_ms() / 1000, "addr": me.to_string(), "hard": hard});
    if zone.is_none() {
        // A DNS note holds 255 characters: the local address only on the board.
        let lan = lan_ip().map(|ip| vec![format!("{}:{}", ip, sock.local_addr().map(|a| a.port()).unwrap_or(0))]).unwrap_or_default();
        note["lan"] = json!(lan);
    } else if let Some((z, _)) = zone {
        log(&format!("Asking the phone through its website's zone ({})", z));
    }
    let answer = ask(psk, &note.to_string(), "punch", &s, Duration::from_secs(12), zone)?
        .ok_or_else(|| io_err("the phone did not answer (is it on, with From other networks on?)"))?;
    let mut theirs: Vec<SocketAddr> = Vec::new();
    theirs.extend(answer["addr"].as_str().and_then(parse_addr));
    for l in answer["lan"].as_array().into_iter().flatten() {
        theirs.extend(l.as_str().and_then(parse_addr));
    }
    if theirs.is_empty() {
        return Err(io_err("the phone could not see its own public address"));
    }
    let hard_there = answer["hard"] == true;
    let k = hmac(psk, &[b"L87P/1 udp", &session]);
    let (to_phone, to_dev) = (hmac(&k, &[b"dev"]), hmac(&k, &[b"phone"]));
    log(&format!(
        "Punching to {} (here {}, hard here {}, there {})",
        theirs.iter().map(|t| t.to_string()).collect::<Vec<_>>().join(", "), me, hard, hard_there
    ));
    let path = knock(sock, hard, &theirs, hard_there, &to_phone, &to_dev, 0)
        .ok_or_else(|| io_err(format!("no way through the two networks' NATs ({} here, {} there)", if hard { "hard" } else { "easy" }, if hard_there { "hard" } else { "easy" })))?;
    log(&format!("Punched through to {}", path.to));
    let u = Udp87::new(path.sock, path.to, &to_phone, &to_dev, 0);
    let local = local_socket(u.clone())?;
    match crate::tunnel::dial_over(local, tid, psk, Duration::from_secs(8)) {
        Ok(t) => Ok(t),
        Err(e) => {
            u.close();
            Err(e)
        }
    }
}

/// `--punch-selftest`: two ends of the reliable stream on this computer's loopback, a megabyte
/// stream each way, first clean, then through a relay that drops one packet in ten and swaps some.
pub fn selftest() -> bool {
    fn pair(via: Option<(UdpSocket, SocketAddr, SocketAddr)>) -> (Udp87, Udp87, Option<UdpSocket>) {
        let (a, b) = (UdpSocket::bind("127.0.0.1:0").unwrap(), UdpSocket::bind("127.0.0.1:0").unwrap());
        let (k1, k2) = (hmac(b"k", &[b"1"]).to_vec(), hmac(b"k", &[b"2"]).to_vec());
        let (aa, ba) = (a.local_addr().unwrap(), b.local_addr().unwrap());
        let (pa, pb) = match &via {
            Some((relay, _, _)) => { let r = relay.local_addr().unwrap(); (r, r) }
            None => (ba, aa),
        };
        let _ = via.as_ref().map(|v| (v.1, v.2));
        (Udp87::new(a, pa, &k1, &k2, 0), Udp87::new(b, pb, &k2, &k1, 1), None)
    }
    fn run(label: &str, lossy: bool) -> bool {
        // A relay between the two when lossy: each end is told the relay's address, and the relay
        // knows which end is which by the order they first speak.
        let (a, b);
        let mut stop = None;
        if lossy {
            let relay = UdpSocket::bind("127.0.0.1:0").unwrap();
            let ra = relay.local_addr().unwrap();
            let (sa, sb) = (UdpSocket::bind("127.0.0.1:0").unwrap(), UdpSocket::bind("127.0.0.1:0").unwrap());
            let (aa, ba) = (sa.local_addr().unwrap(), sb.local_addr().unwrap());
            let (k1, k2) = (hmac(b"k", &[b"1"]).to_vec(), hmac(b"k", &[b"2"]).to_vec());
            a = Udp87::new(sa, ra, &k1, &k2, 0);
            b = Udp87::new(sb, ra, &k2, &k1, 1);
            let flag = Arc::new(AtomicBool::new(true));
            let f2 = flag.clone();
            stop = Some(flag);
            relay.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
            spawn(move || {
                let mut buf = [0u8; 2048];
                let mut held: Option<(Vec<u8>, SocketAddr)> = None;
                let mut n = 0u32;
                while f2.load(Ordering::Relaxed) {
                    let Ok((len, from)) = relay.recv_from(&mut buf) else { continue };
                    let to = if from == aa { ba } else { aa };
                    n += 1;
                    if n % 50 == 0 { continue } // lost
                    if n % 7 == 0 { held = Some((buf[..len].to_vec(), to)); continue } // late
                    let _ = relay.send_to(&buf[..len], to);
                    if let Some((p, t)) = held.take() { let _ = relay.send_to(&p, t); }
                }
            });
        } else {
            let (x, y, _) = pair(None);
            a = x;
            b = y;
        }
        let size = 1 << 20;
        let data: Vec<u8> = (0..size).map(|i: usize| (i * 31 % 251) as u8).collect();
        let mut ok = true;
        let start = Instant::now();
        let (a2, b2, d2) = (a.clone(), b.clone(), data.clone());
        let t1 = spawn(move || { let _ = a2.write(&d2); });
        let d3 = data.clone();
        let t2 = spawn(move || { let _ = b2.write(&d3); });
        for (name, end) in [("a", a.clone()), ("b", b.clone())] {
            let mut got = Vec::with_capacity(size);
            let mut buf = vec![0u8; 65536];
            let end_at = Instant::now() + Duration::from_secs(60);
            while got.len() < size && Instant::now() < end_at {
                let n = end.read(&mut buf);
                if n == 0 { break }
                got.extend_from_slice(&buf[..n]);
            }
            if got != data { println!("{}: end {} got {} of {} bytes, wrong", label, name, got.len(), size); ok = false; }
        }
        let _ = (t1.join(), t2.join());
        println!("{}: {} in {:.2} s", label, if ok { "every byte right, both ways" } else { "FAILED" }, start.elapsed().as_secs_f64());
        a.close();
        b.close();
        if let Some(f) = stop { f.store(false, Ordering::Relaxed); }
        ok
    }
    run("clean loopback", false) & run("one packet in fifty lost, some late", true)
}

/// `--seal PSK_HEX TEXT` and `--unseal PSK_HEX NOTE`: a note sealed here for the other helpers and
/// the phone to open, or one of theirs opened here (to check the formats agree).
pub fn seal_check(args: &[String]) {
    let unhex = |s: &str| -> Vec<u8> { (0..s.len() / 2).filter_map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()).collect() };
    match args {
        [m, psk, text] if m == "--seal" => println!("{}", seal(&unhex(psk), text)),
        [m, psk, text] if m == "--unseal" => println!("{}", unseal(&unhex(psk), text).map(|v| v.to_string()).unwrap_or_else(|| "not opened".into())),
        _ => println!("usage: --seal|--unseal PSK_HEX TEXT"),
    }
}
