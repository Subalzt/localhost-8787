//! A small HTTP/1.1 client for talking to the phone: plain requests, and long-lived streams (the
//! control stream, the live events) read line by line as they come. Never through a proxy: the
//! phone is always on a link of its own (the cable, the same Wi-Fi, the tunnel's local port).

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Response {
    #[allow(dead_code)]
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
    /// The phone's session cookie, when it set one.
    pub fn session_cookie(&self) -> Option<String> {
        self.headers.iter()
            .filter(|(k, _)| k.eq_ignore_ascii_case("set-cookie"))
            .find_map(|(_, v)| {
                let i = v.find("xoosh_session=")?;
                let rest = &v[i + 14..];
                let end = rest.find(|c: char| c == ';' || c == ',' || c.is_whitespace()).unwrap_or(rest.len());
                Some(format!("xoosh_session={}", &rest[..end]))
            })
    }
}

fn connect(host: &str, port: u16, timeout: Duration) -> io::Result<TcpStream> {
    let target = if host.contains(':') && !host.starts_with('[') { format!("[{}]:{}", host, port) } else { format!("{}:{}", host, port) };
    let addrs: Vec<SocketAddr> = target.to_socket_addrs()?.collect();
    let mut last = io::Error::new(io::ErrorKind::NotFound, "no address");
    for a in addrs {
        match TcpStream::connect_timeout(&a, timeout) {
            Ok(s) => {
                let _ = s.set_nodelay(true);
                return Ok(s);
            }
            Err(e) => last = e,
        }
    }
    Err(last)
}

fn write_request(s: &mut TcpStream, method: &str, host: &str, path: &str, headers: &[(&str, String)], body: &[u8]) -> io::Result<()> {
    let mut req = format!("{} {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: {}\r\nConnection: close\r\n", method, path, host, crate::util::user_agent());
    for (k, v) in headers {
        req.push_str(&format!("{}: {}\r\n", k, v));
    }
    if method != "GET" || !body.is_empty() {
        req.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes())?;
    if !body.is_empty() {
        s.write_all(body)?;
    }
    s.flush()
}

fn read_head(r: &mut impl BufRead) -> io::Result<(u16, Vec<(String, String)>)> {
    let mut line = String::new();
    r.read_line(&mut line)?;
    let status: u16 = line.split_whitespace().nth(1).and_then(|s| s.parse().ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("not HTTP: {:?}", line.trim())))?;
    let mut headers = Vec::new();
    loop {
        line.clear();
        if r.read_line(&mut line)? == 0 { break; }
        let l = line.trim_end();
        if l.is_empty() { break; }
        if let Some((k, v)) = l.split_once(':') {
            headers.push((k.trim().to_string(), v.trim().to_string()));
        }
    }
    Ok((status, headers))
}

/// The body as it arrives: chunked (the phone's streams) or to the end of the connection.
pub struct Body {
    inner: BufReader<TcpStream>,
    chunked: bool,
    left: usize,
    done: bool,
    length: Option<usize>,
}

impl Read for Body {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.done || buf.is_empty() { return Ok(0); }
        if !self.chunked {
            if let Some(l) = self.length {
                if l == 0 { self.done = true; return Ok(0); }
                let m = buf.len().min(l);
                let n = self.inner.read(&mut buf[..m])?;
                self.length = Some(l - n);
                return Ok(n);
            }
            return self.inner.read(buf);
        }
        if self.left == 0 {
            let mut size = String::new();
            if self.inner.read_line(&mut size)? == 0 { self.done = true; return Ok(0); }
            let size = size.trim();
            if size.is_empty() {
                // The end of the chunk before.
                let mut s2 = String::new();
                if self.inner.read_line(&mut s2)? == 0 { self.done = true; return Ok(0); }
                return self.read_after(s2.trim(), buf);
            }
            return self.read_after(size, buf);
        }
        self.read_chunk(buf)
    }
}

impl Body {
    fn read_after(&mut self, size: &str, buf: &mut [u8]) -> io::Result<usize> {
        let n = usize::from_str_radix(size.split(';').next().unwrap_or("0").trim(), 16)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad chunk"))?;
        if n == 0 { self.done = true; return Ok(0); }
        self.left = n;
        self.read_chunk(buf)
    }
    fn read_chunk(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let want = buf.len().min(self.left);
        let n = self.inner.read(&mut buf[..want])?;
        if n == 0 { return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "cut off")); }
        self.left -= n;
        if self.left == 0 {
            let mut crlf = [0u8; 2];
            self.inner.read_exact(&mut crlf)?;
        }
        Ok(n)
    }
}

pub struct Stream {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: BufReader<Body>,
    #[allow(dead_code)]
    pub socket: TcpStream,
}

/// A request whose body is read as it comes ([read_timeout]: silence that counts as the phone gone).
pub fn open(host: &str, port: u16, method: &str, path: &str, headers: &[(&str, String)], body: &[u8], connect_timeout: Duration, read_timeout: Option<Duration>) -> io::Result<Stream> {
    let mut s = connect(host, port, connect_timeout)?;
    s.set_read_timeout(read_timeout)?;
    s.set_write_timeout(Some(Duration::from_secs(20)))?;
    write_request(&mut s, method, host, path, headers, body)?;
    let socket = s.try_clone()?;
    let mut r = BufReader::new(s);
    let (status, hs) = read_head(&mut r)?;
    let chunked = hs.iter().any(|(k, v)| k.eq_ignore_ascii_case("transfer-encoding") && v.to_ascii_lowercase().contains("chunked"));
    let length = hs.iter().find(|(k, _)| k.eq_ignore_ascii_case("content-length")).and_then(|(_, v)| v.parse().ok());
    let body = Body { inner: r, chunked, left: 0, done: false, length };
    Ok(Stream { status, headers: hs, body: BufReader::with_capacity(64 * 1024, body), socket })
}

/// A whole request and response.
pub fn request(host: &str, port: u16, method: &str, path: &str, headers: &[(&str, String)], body: &[u8], timeout: Duration) -> io::Result<Response> {
    let mut st = open(host, port, method, path, headers, body, timeout, Some(timeout))?;
    let mut out = Vec::new();
    st.body.read_to_end(&mut out)?;
    Ok(Response { status: st.status, headers: st.headers, body: out })
}

/// A POST whose body ([size] bytes) is read from [src] as it goes, for files of any size.
pub fn post_from(host: &str, port: u16, path: &str, headers: &[(&str, String)], size: u64, src: &mut impl Read, timeout: Duration) -> io::Result<Response> {
    let mut s = connect(host, port, timeout)?;
    s.set_read_timeout(Some(Duration::from_secs(120)))?;
    s.set_write_timeout(Some(Duration::from_secs(120)))?;
    let mut req = format!("POST {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: {}\r\nConnection: close\r\nContent-Length: {}\r\n", path, host, crate::util::user_agent(), size);
    for (k, v) in headers {
        req.push_str(&format!("{}: {}\r\n", k, v));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes())?;
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = src.read(&mut buf)?;
        if n == 0 { break; }
        s.write_all(&buf[..n])?;
    }
    s.flush()?;
    let mut r = BufReader::new(s);
    let (status, headers) = read_head(&mut r)?;
    let mut body = Vec::new();
    let _ = r.read_to_end(&mut body);
    Ok(Response { status, headers, body })
}

/// Text made safe for a URL's query or path.
pub fn quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}
