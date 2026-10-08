//! The L87 tunnel (docs/tunnel-protocol.md): one TCP connection to the phone, our own encryption,
//! every local connection a stream inside it. Served here on a loopback address, so the relay,
//! requests and event streams reach the phone through it unchanged.

use crate::util::log;
use aes::cipher::{KeyIvInit, StreamCipher};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use sha3::digest::{ExtendableOutput, Update, XofReader};
use std::collections::{HashMap, VecDeque};
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{sleep, spawn};
use std::time::Duration;

type HmacSha256 = Hmac<Sha256>;
type Aes256Ctr = ctr::Ctr128BE<aes::Aes256>;

const HELLO: u8 = 1;
const OPEN: u8 = 2;
const DATA: u8 = 3;
const FIN: u8 = 4;
const RST: u8 = 5;
const CREDIT: u8 = 6;
const PING: u8 = 7;
const PONG: u8 = 8;
const ADDR: u8 = 9;
const BYE: u8 = 10;
const WINDOW: i64 = 512 * 1024;
const CHUNK: usize = 16384;
const CREDIT_STEP: usize = 128 * 1024;
const IDLE_PING_MS: u64 = 20_000;
const DEAD_MS: u64 = 60_000;

pub(crate) fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut m = HmacSha256::new_from_slice(key).expect("any key length");
    for p in parts {
        Mac::update(&mut m, p);
    }
    m.finalize().into_bytes().into()
}

pub(crate) fn hmac16(key: &[u8], parts: &[&[u8]]) -> [u8; 16] {
    let mut o = [0u8; 16];
    o.copy_from_slice(&hmac(key, parts)[..16]);
    o
}

pub(crate) fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |d, (x, y)| d | (x ^ y)) == 0
}

fn now_ms() -> u64 {
    crate::util::now_ms()
}

/// One direction: the keystream by frame counter (AES-256-CTR in version 2, SHAKE256 in version 1),
/// and an HMAC-SHA256 tag over counter, length and ciphertext (encrypt-then-MAC).
struct Cipher {
    enc: [u8; 32],
    mac: [u8; 32],
    n: u64,
    aes: bool,
}

impl Cipher {
    fn xor(&self, counter: u64, data: &mut [u8]) {
        if self.aes {
            let mut iv = [0u8; 16];
            iv[..8].copy_from_slice(&counter.to_be_bytes());
            let mut c = Aes256Ctr::new(&self.enc.into(), &iv.into());
            c.apply_keystream(data);
        } else {
            let mut h = sha3::Shake256::default();
            Update::update(&mut h, &self.enc);
            Update::update(&mut h, &counter.to_be_bytes());
            let mut ks = vec![0u8; data.len()];
            XofReader::read(&mut h.finalize_xof(), &mut ks);
            for (d, k) in data.iter_mut().zip(ks) {
                *d ^= k;
            }
        }
    }

    fn seal(&mut self, pt: &[u8]) -> Vec<u8> {
        let c = self.n;
        self.n += 1;
        let mut ct = pt.to_vec();
        self.xor(c, &mut ct);
        let len = (ct.len() as u32).to_be_bytes();
        let tag = hmac16(&self.mac, &[&c.to_be_bytes(), &len, &ct]);
        let mut out = Vec::with_capacity(4 + ct.len() + 16);
        out.extend_from_slice(&len);
        out.extend_from_slice(&ct);
        out.extend_from_slice(&tag);
        out
    }

    fn open(&mut self, ct: &mut [u8], tag: &[u8]) -> io::Result<()> {
        let c = self.n;
        let len = (ct.len() as u32).to_be_bytes();
        if !same(tag, &hmac16(&self.mac, &[&c.to_be_bytes(), &len, ct])) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "a frame failed its check"));
        }
        self.n += 1;
        self.xor(c, ct);
        Ok(())
    }
}

/// A local connection carried as one stream.
struct Stream {
    sid: u32,
    sock: TcpStream,
    /// What the phone will still take (CREDIT adds to it).
    window: Mutex<i64>,
    window_cv: Condvar,
    /// DATA waiting for the local socket; None is the phone's FIN.
    queue: Mutex<(VecDeque<Option<Vec<u8>>>, usize)>,
    queue_cv: Condvar,
    closed: AtomicBool,
    ends: AtomicU32,
}

struct Inner {
    writer: Mutex<(TcpStream, Cipher)>,
    raw: TcpStream,
    streams: Mutex<HashMap<u32, Arc<Stream>>>,
    next_sid: AtomicU32,
    alive: AtomicBool,
    last_rx: AtomicU64,
    last_tx: AtomicU64,
    info: Mutex<serde_json::Value>,
    hello: (Mutex<bool>, Condvar),
}

#[derive(Clone)]
pub struct Tunnel(Arc<Inner>);

impl Tunnel {
    pub fn alive(&self) -> bool {
        self.0.alive.load(Ordering::Relaxed)
    }

    #[allow(dead_code)]
    /// The phone's HELLO and ADDR: its name, addresses and port.
    pub fn info(&self) -> serde_json::Value {
        self.0.info.lock().map(|i| i.clone()).unwrap_or_default()
    }

    fn send(&self, kind: u8, sid: u32, body: &[u8]) -> io::Result<()> {
        let mut pt = Vec::with_capacity(5 + body.len());
        pt.push(kind);
        pt.extend_from_slice(&sid.to_be_bytes());
        pt.extend_from_slice(body);
        let mut w = self.0.writer.lock().map_err(|_| io::Error::other("poisoned"))?;
        if !self.alive() {
            return Err(io::Error::new(io::ErrorKind::NotConnected, "the tunnel is closed"));
        }
        let frame = w.1.seal(&pt);
        if let Err(e) = w.0.write_all(&frame) {
            drop(w);
            self.close("");
            return Err(e);
        }
        self.0.last_tx.store(now_ms(), Ordering::Relaxed);
        Ok(())
    }

    fn forget(&self, sid: u32) {
        if let Ok(mut s) = self.0.streams.lock() {
            s.remove(&sid);
        }
    }

    pub fn close(&self, why: &str) {
        if !self.0.alive.swap(false, Ordering::SeqCst) {
            return;
        }
        if !why.is_empty() {
            // A last word, best effort, with the lock taken without waiting on a stuck writer.
            if let Ok(mut w) = self.0.writer.try_lock() {
                let mut pt = vec![BYE, 0, 0, 0, 0];
                pt.extend_from_slice(why.as_bytes());
                let f = w.1.seal(&pt);
                let _ = w.0.write_all(&f);
            }
        }
        let held: Vec<Arc<Stream>> = self.0.streams.lock().map(|s| s.values().cloned().collect()).unwrap_or_default();
        for st in held {
            self.reset(&st, "", false);
        }
        let _ = self.0.raw.shutdown(Shutdown::Both);
    }

    /// Carries the local connection [sock] to the phone's [port] (the page's) as a new stream.
    pub fn open_stream(&self, sock: TcpStream, port: u16) -> io::Result<()> {
        let sid = self.0.next_sid.fetch_add(2, Ordering::SeqCst);
        let st = Arc::new(Stream {
            sid, sock,
            window: Mutex::new(WINDOW), window_cv: Condvar::new(),
            queue: Mutex::new((VecDeque::new(), 0)), queue_cv: Condvar::new(),
            closed: AtomicBool::new(false), ends: AtomicU32::new(0),
        });
        self.0.streams.lock().map_err(|_| io::Error::other("poisoned"))?.insert(sid, st.clone());
        self.send(OPEN, sid, &port.to_be_bytes())?;
        let (t1, s1) = (self.clone(), st.clone());
        spawn(move || t1.up(s1));
        let (t2, s2) = (self.clone(), st);
        spawn(move || t2.down(s2));
        Ok(())
    }

    fn up(&self, st: Arc<Stream>) {
        let mut buf = vec![0u8; CHUNK];
        let mut r = match st.sock.try_clone() { Ok(r) => r, Err(_) => return self.reset(&st, "", true) };
        loop {
            match r.read(&mut buf) {
                Ok(0) => {
                    let _ = self.send(FIN, st.sid, &[]);
                    self.end(&st);
                    return;
                }
                Ok(n) => {
                    let mut w = st.window.lock().unwrap();
                    while *w < n as i64 && !st.closed.load(Ordering::Relaxed) {
                        w = st.window_cv.wait_timeout(w, Duration::from_secs(5)).unwrap().0;
                    }
                    if st.closed.load(Ordering::Relaxed) {
                        return;
                    }
                    *w -= n as i64;
                    drop(w);
                    if self.send(DATA, st.sid, &buf[..n]).is_err() {
                        return;
                    }
                }
                Err(_) => return self.reset(&st, "the local connection failed", true),
            }
        }
    }

    fn down(&self, st: Arc<Stream>) {
        let mut owed = 0usize;
        let mut w = match st.sock.try_clone() { Ok(w) => w, Err(_) => return };
        loop {
            let (item, empty) = {
                let mut q = st.queue.lock().unwrap();
                while q.0.is_empty() && !st.closed.load(Ordering::Relaxed) {
                    q = st.queue_cv.wait(q).unwrap();
                }
                let Some(item) = q.0.pop_front() else { return };
                if let Some(d) = &item {
                    q.1 -= d.len();
                }
                (item, q.0.is_empty())
            };
            match item {
                None => {
                    let _ = w.shutdown(Shutdown::Write);
                    self.end(&st);
                    return;
                }
                Some(d) => {
                    if w.write_all(&d).is_err() {
                        return self.reset(&st, "the local connection failed", true);
                    }
                    owed += d.len();
                    if owed >= CREDIT_STEP || (empty && owed > 0) {
                        let _ = self.send(CREDIT, st.sid, &(owed as u32).to_be_bytes());
                        owed = 0;
                    }
                }
            }
        }
    }

    /// One direction is done; after both, the stream closes without a reset.
    fn end(&self, st: &Arc<Stream>) {
        if st.ends.fetch_add(1, Ordering::SeqCst) + 1 < 2 || st.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        st.queue_cv.notify_all();
        st.window_cv.notify_all();
        let _ = st.sock.shutdown(Shutdown::Both);
        self.forget(st.sid);
    }

    fn reset(&self, st: &Arc<Stream>, why: &str, send: bool) {
        if st.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        st.queue_cv.notify_all();
        st.window_cv.notify_all();
        if send {
            let _ = self.send(RST, st.sid, why.as_bytes());
        }
        let _ = st.sock.shutdown(Shutdown::Both);
        self.forget(st.sid);
    }

    fn run(&self, mut r: TcpStream, mut rx: Cipher) {
        let result: io::Result<()> = (|| loop {
            let mut lb = [0u8; 4];
            r.read_exact(&mut lb)?;
            let len = u32::from_be_bytes(lb) as usize;
            if !(5..=65536 + 5).contains(&len) {
                return Err(io::Error::new(io::ErrorKind::InvalidData, format!("a frame of {} bytes", len)));
            }
            let mut rest = vec![0u8; len + 16];
            r.read_exact(&mut rest)?;
            let (ct, tag) = rest.split_at_mut(len);
            rx.open(ct, tag)?;
            self.0.last_rx.store(now_ms(), Ordering::Relaxed);
            let kind = ct[0];
            let sid = u32::from_be_bytes([ct[1], ct[2], ct[3], ct[4]]);
            let body = &ct[5..];
            let st = self.0.streams.lock().ok().and_then(|s| s.get(&sid).cloned());
            match (kind, st) {
                (DATA, Some(st)) => {
                    let mut q = st.queue.lock().unwrap();
                    if q.1 + body.len() > WINDOW as usize {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "the phone sent past the window"));
                    }
                    q.1 += body.len();
                    q.0.push_back(Some(body.to_vec()));
                    st.queue_cv.notify_all();
                }
                (FIN, Some(st)) => {
                    st.queue.lock().unwrap().0.push_back(None);
                    st.queue_cv.notify_all();
                }
                (CREDIT, Some(st)) if body.len() >= 4 => {
                    *st.window.lock().unwrap() += u32::from_be_bytes([body[0], body[1], body[2], body[3]]) as i64;
                    st.window_cv.notify_all();
                }
                (RST, Some(st)) => self.reset(&st, "", false),
                (PING, _) => { let _ = self.send(PONG, 0, body); }
                (HELLO, _) | (ADDR, _) => {
                    if let Ok(v) = serde_json::from_slice::<serde_json::Value>(body) {
                        if let (Ok(mut i), Some(o)) = (self.0.info.lock(), v.as_object()) {
                            if !i.is_object() { *i = serde_json::json!({}); }
                            for (k, val) in o { i[k] = val.clone(); }
                        }
                        if let Some(a) = v.get("addrs") {
                            crate::far::save_addrs(a);
                        }
                    }
                    let (m, cv) = &self.0.hello;
                    *m.lock().unwrap() = true;
                    cv.notify_all();
                }
                (BYE, _) => return Err(io::Error::other(format!("the phone closed the tunnel{}",
                    if body.is_empty() { String::new() } else { format!(": {}", String::from_utf8_lossy(body)) }))),
                _ => {} // unknown kinds, and frames for streams already gone
            }
        })();
        if let Err(e) = result {
            if self.alive() {
                log(&format!("Tunnel: {}", e));
            }
        }
        self.close("");
    }

    fn keepalive(&self) {
        while self.alive() {
            sleep(Duration::from_secs(5));
            let now = now_ms();
            if now - self.0.last_rx.load(Ordering::Relaxed) > DEAD_MS {
                log("Tunnel: nothing from the phone for 60 s");
                self.close("");
            } else if now - self.0.last_tx.load(Ordering::Relaxed) > IDLE_PING_MS {
                let mut b = [0u8; 8];
                let _ = getrandom::getrandom(&mut b);
                let _ = self.send(PING, 0, &b);
            }
        }
    }
}

fn handshake(mut s: TcpStream, tid: &[u8], psk: &[u8], version: u8, timeout: Duration) -> io::Result<Tunnel> {
    s.set_read_timeout(Some(timeout))?;
    let mut private = [0u8; 32];
    getrandom::getrandom(&mut private).map_err(|e| io::Error::other(e.to_string()))?;
    let mut nine = [0u8; 32];
    nine[0] = 9;
    let public = x25519_dalek::x25519(private, nine);
    let mut hello = Vec::with_capacity(69);
    hello.extend_from_slice(b"L87T");
    hello.push(version);
    hello.extend_from_slice(tid);
    hello.extend_from_slice(&public);
    let mac1 = hmac16(psk, &[b"L87T/1 hello", &hello]);
    hello.extend_from_slice(&mac1);
    s.write_all(&hello)?;
    let mut resp = [0u8; 48];
    s.read_exact(&mut resp).map_err(|e| {
        if e.kind() == io::ErrorKind::UnexpectedEof { io::Error::new(io::ErrorKind::ConnectionAborted, "the connection closed") } else { e }
    })?;
    let mut e_s = [0u8; 32];
    e_s.copy_from_slice(&resp[..32]);
    let dh = x25519_dalek::x25519(private, e_s);
    if dh == [0u8; 32] {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "a bad key from the phone"));
    }
    let th: [u8; 32] = Sha256::new().chain_update(&hello).chain_update(e_s).finalize().into();
    let prk = hmac(psk, &[&dh]);
    let hk = hkdf::Hkdf::<Sha256>::from_prk(&prk).map_err(|_| io::Error::other("hkdf"))?;
    let mut okm = [0u8; 160];
    let mut info = b"L87T/1 keys".to_vec();
    info.extend_from_slice(&th);
    hk.expand(&info, &mut okm).map_err(|_| io::Error::other("hkdf"))?;
    let k = |i: usize| -> [u8; 32] { let mut o = [0u8; 32]; o.copy_from_slice(&okm[i * 32..i * 32 + 32]); o };
    if !same(&resp[32..], &hmac16(&k(4), &[b"L87T/1 accept", &th])) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "the phone did not prove it knows this computer"));
    }
    s.set_read_timeout(None)?;
    let aes = version == 2;
    let tx = Cipher { enc: k(0), mac: k(1), n: 0, aes };
    let rx = Cipher { enc: k(2), mac: k(3), n: 0, aes };
    let reader = s.try_clone()?;
    let t = Tunnel(Arc::new(Inner {
        writer: Mutex::new((s.try_clone()?, tx)),
        raw: s,
        streams: Mutex::new(HashMap::new()),
        next_sid: AtomicU32::new(1),
        alive: AtomicBool::new(true),
        last_rx: AtomicU64::new(now_ms()),
        last_tx: AtomicU64::new(now_ms()),
        info: Mutex::new(serde_json::json!({})),
        hello: (Mutex::new(false), Condvar::new()),
    }));
    let t1 = t.clone();
    spawn(move || t1.run(reader, rx));
    let t2 = t.clone();
    spawn(move || t2.keepalive());
    let name = crate::util::machine_name().replace(['"', '\\'], "");
    t.send(HELLO, 0, format!("{{\"name\":\"{}\",\"v\":1}}", name).as_bytes())?;
    let (m, cv) = &t.0.hello;
    let g = cv.wait_timeout_while(m.lock().unwrap(), timeout, |got| !*got).unwrap().0;
    if !*g {
        drop(g);
        t.close("no hello");
        return Err(io::Error::new(io::ErrorKind::TimedOut, "the phone did not answer the hello"));
    }
    drop(g);
    Ok(t)
}

fn connect(host: &str, port: u16, timeout: Duration) -> io::Result<TcpStream> {
    let addr: SocketAddr = if host.contains(':') { format!("[{}]:{}", host.trim_matches(['[', ']']), port) } else { format!("{}:{}", host, port) }
        .parse().map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("not an address: {}", host)))?;
    let s = TcpStream::connect_timeout(&addr, timeout)
        .map_err(|e| if e.kind() == io::ErrorKind::TimedOut { io::Error::new(io::ErrorKind::TimedOut, format!("no answer in {} s", timeout.as_secs())) } else { e })?;
    s.set_nodelay(true)?;
    Ok(s)
}

/// Dials [host] and runs the handshake (version 2; a phone that closes without a word has the older
/// app, and gets version 1).
pub fn dial(host: &str, port: u16, tid: &[u8], psk: &[u8], timeout: Duration) -> io::Result<Tunnel> {
    match handshake(connect(host, port, timeout)?, tid, psk, 2, timeout) {
        Err(e) if e.kind() == io::ErrorKind::ConnectionAborted => handshake(connect(host, port, timeout)?, tid, psk, 1, timeout),
        r => r,
    }
}

/// Serves [get]'s tunnel on [listener]: each connection there becomes a stream to the phone's page.
pub fn serve(listener: TcpListener, get: fn() -> Option<Tunnel>) {
    spawn(move || {
        for c in listener.incoming() {
            let Ok(c) = c else { sleep(Duration::from_millis(200)); continue };
            let _ = c.set_nodelay(true);
            match get() {
                Some(t) if t.alive() => {
                    if t.open_stream(c, crate::util::PHONE_PORT).is_err() {
                        log("Tunnel: could not open a stream");
                    }
                }
                _ => drop(c),
            }
        }
    });
}

/// The handshake (version 2) over a connection made by someone else: the punched UDP path, which
/// punch.rs hands over as a local socket.
pub fn dial_over(s: TcpStream, tid: &[u8], psk: &[u8], timeout: Duration) -> io::Result<Tunnel> {
    handshake(s, tid, psk, 2, timeout)
}
