//! The smallest HTTP/1.1 that serves a local debugging tool.
//!
//! Hand-rolled rather than a framework on purpose: what this binary owes a browser
//! is one request, one response, and a stream that never closes. A runtime big
//! enough to need `--locked` review for would be more surface to keep buildable
//! than the whole viewer is worth.
//!
//! Only `Content-Length` bodies are read. A POST without one has no body, which
//! is what `fetch("/api/stop", {method:"POST"})` sends; chunked bodies would have
//! to be declared by a client that this tool's own page does not use.

use std::collections::HashMap;
use std::io::{self, BufRead, Write};

#[derive(Debug)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub query: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn param(&self, key: &str) -> Option<&str> {
        self.query.get(key).map(String::as_str)
    }
}

/// A request this server will not act on, with the status that says why.
///
/// Errors are answered rather than dropped because the failure *is* the thing
/// being looked at: a viewer that shows nothing after an upload cannot tell a
/// bad file from a dead server.
#[derive(Debug)]
pub struct Reject {
    pub status: u16,
    pub message: String,
}

impl Reject {
    fn malformed(message: impl Into<String>) -> Self {
        Self {
            status: 400,
            message: message.into(),
        }
    }

    fn too_big() -> Self {
        Self {
            status: 413,
            message: format!(
                "body larger than the {} MiB upload limit",
                MAX_BODY / 1024 / 1024
            ),
        }
    }
}

impl From<io::Error> for Reject {
    fn from(error: io::Error) -> Self {
        Self::malformed(format!("unreadable request: {error}"))
    }
}

/// The largest accepted upload. A day of capture is tens of megabytes, so this
/// only catches a mistaken `curl` of something enormous.
const MAX_BODY: u64 = 512 * 1024 * 1024;

/// `None` for a connection the peer closed before asking anything — the normal
/// end of a health check, not an error to answer.
pub fn read_request(stream: &mut impl BufRead) -> Option<Result<Request, Reject>> {
    let mut head = String::new();
    let mut head_bytes = 0usize;
    loop {
        let line = match read_line(stream) {
            Err(e) => return Some(Err(e.into())),
            Ok(None) => return None,
            Ok(Some(line)) => line,
        };
        head_bytes += line.len() + 2;
        if line.is_empty() {
            break;
        }
        head.push_str(&line);
        head.push('\n');
        if head_bytes > 64 * 1024 {
            return Some(Err(Reject::malformed("request headers too large")));
        }
    }

    let mut lines = head.lines();
    let Some(request_line) = lines.next() else {
        return None;
    };
    let mut parts = request_line.split_whitespace();
    let (Some(method), Some(target)) = (parts.next(), parts.next()) else {
        return Some(Err(Reject::malformed(format!(
            "unparsable request line: {request_line}"
        ))));
    };

    let (path, query) = match target.split_once('?') {
        Some((path, query)) => (path, query),
        None => (target, ""),
    };

    let mut body = Vec::new();
    let method = method.to_ascii_uppercase();
    if method == "POST" || method == "PUT" {
        // No `Content-Length` means no body: `fetch("/api/stop", {method:"POST"})`
        // sends exactly that, and refusing it would make the button do nothing.
        let length = header(&head, "content-length").unwrap_or(0);
        if length > MAX_BODY {
            return Some(Err(Reject::too_big()));
        }
        body = vec![0u8; length as usize];
        if length > 0 {
            if let Err(e) = stream.read_exact(&mut body) {
                return Some(Err(e.into()));
            }
        }
    }

    Some(Ok(Request {
        method,
        path: path.to_string(),
        query: parse_query(query),
        body,
    }))
}

/// One header line at a time, so a body's bytes are never mistaken for headers.
fn read_line(stream: &mut impl BufRead) -> io::Result<Option<String>> {
    let mut line = Vec::new();
    let read = stream.read_until(b'\n', &mut line)?;
    if read == 0 {
        return Ok(None);
    }
    if line.last() != Some(&b'\n') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "request line not newline terminated",
        ));
    }
    line.pop();
    if line.last() == Some(&b'\r') {
        line.pop();
    }
    Ok(Some(String::from_utf8_lossy(&line).into_owned()))
}

/// Header names are case-insensitive, and browsers do not agree on a spelling.
fn header(head: &str, name: &str) -> Option<u64> {
    head.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.eq_ignore_ascii_case(name)
            .then(|| value.trim().parse().ok())
            .flatten()
    })
}

pub fn parse_query(query: &str) -> HashMap<String, String> {
    query
        .split('&')
        .filter(|pair| !pair.is_empty())
        .filter_map(|pair| pair.split_once('='))
        .map(|(key, value)| (key.to_string(), percent_decode(value)))
        .collect()
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok();
                match hex.and_then(|h| u8::from_str_radix(h, 16).ok()) {
                    Some(byte) => {
                        out.push(byte);
                        i += 3;
                    }
                    None => {
                        out.push(b'%');
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            byte => {
                out.push(byte);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub struct Response {
    pub status: u16,
    pub content_type: &'static str,
    pub body: Vec<u8>,
}

impl Response {
    pub fn json(status: u16, value: serde_json::Value) -> Self {
        Self {
            status,
            content_type: "application/json",
            body: value.to_string().into_bytes(),
        }
    }

    pub fn text(status: u16, body: impl Into<String>) -> Self {
        Self {
            status,
            content_type: "text/plain; charset=utf-8",
            body: body.into().into_bytes(),
        }
    }

    /// The page is compiled in, so the binary stays a single file to copy onto a
    /// machine and run with no network.
    pub fn html(body: &'static [u8]) -> Self {
        Self {
            status: 200,
            content_type: "text/html; charset=utf-8",
            body: body.to_vec(),
        }
    }

    pub fn write_to(self, stream: &mut impl Write) -> io::Result<()> {
        write_head(stream, self.status, self.content_type, "close")?;
        stream.write_all(b"Content-Length: ")?;
        stream.write_all(self.body.len().to_string().as_bytes())?;
        stream.write_all(b"\r\n\r\n")?;
        stream.write_all(&self.body)?;
        stream.flush()
    }
}

/// Headers for a response whose body the server keeps writing (SSE).
pub fn write_stream_head(stream: &mut impl Write) -> io::Result<()> {
    write_head(stream, 200, "text/event-stream", "keep-alive")?;
    stream.write_all(b"Cache-Control: no-cache\r\n\r\n")?;
    stream.flush()
}

/// One SSE frame. `kind` is the event name, so a front end can tell a packet
/// payload from a system message without parsing to find out.
pub fn event_frame(kind: &str, data: &str) -> String {
    format!("event: {kind}\ndata: {data}\n\n")
}

fn write_head(
    stream: &mut impl Write,
    status: u16,
    content_type: &str,
    connection: &str,
) -> io::Result<()> {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        404 => "Not Found",
        405 => "Method Not Allowed",
        411 => "Length Required",
        413 => "Content Too Large",
        500 => "Internal Server Error",
        _ => "Unknown",
    };
    stream.write_all(format!("HTTP/1.1 {status} {reason}\r\n").as_bytes())?;
    stream.write_all(format!("Content-Type: {content_type}\r\n").as_bytes())?;
    stream.write_all(format!("Connection: {connection}\r\n").as_bytes())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn get(target: &str) -> Request {
        let raw = format!("GET {target} HTTP/1.1\r\nHost: x\r\n\r\n");
        read_request(&mut Cursor::new(raw.into_bytes()))
            .expect("a request")
            .expect("no rejection")
    }

    fn post(raw: &[u8]) -> Option<Result<Request, Reject>> {
        read_request(&mut Cursor::new(raw.to_vec()))
    }

    #[test]
    fn a_bare_connection_closes_without_a_request() {
        assert!(read_request(&mut Cursor::new(Vec::new())).is_none());
    }

    #[test]
    fn a_path_and_its_query_split_apart() {
        let request = get("/api/body?packet=12&index=3");
        assert_eq!(request.method, "GET");
        assert_eq!(request.path, "/api/body");
        assert_eq!(request.param("packet"), Some("12"));
        assert_eq!(request.param("index"), Some("3"));
        assert_eq!(request.param("missing"), None);
    }

    #[test]
    fn a_post_body_is_read_by_length_not_by_luck() {
        let request = post(b"POST /api/upload HTTP/1.1\r\nContent-Length: 5\r\n\r\nabcde")
            .unwrap()
            .unwrap();
        assert_eq!(request.body, b"abcde");

        let short = post(b"POST /api/upload HTTP/1.1\r\nContent-Length: 3\r\n\r\nab")
            .unwrap()
            .unwrap_err();
        assert_eq!(
            short.status, 400,
            "a truncated body must not be mistaken for a complete request: {short:?}"
        );
    }

    #[test]
    fn a_header_name_may_arrive_in_any_case() {
        let request = post(b"POST /api/upload HTTP/1.1\r\ncontent-LENGTH: 2\r\n\r\nhi")
            .unwrap()
            .unwrap();
        assert_eq!(request.body, b"hi");
    }

    #[test]
    fn a_post_with_no_body_is_a_request_not_a_refusal() {
        // What `fetch("/api/stop", {method:"POST"})` sends.
        let request = post(b"POST /api/stop HTTP/1.1\r\nHost: x\r\n\r\n")
            .unwrap()
            .unwrap();
        assert_eq!(request.method, "POST");
        assert!(request.body.is_empty());
    }

    #[test]
    fn a_request_line_that_is_not_two_words_is_named() {
        let reject = post(b"WAT\r\n\r\n").unwrap().unwrap_err();
        assert_eq!(reject.status, 400);
        assert!(reject.message.contains("WAT"), "{reject:?}");
    }

    #[test]
    fn a_percent_encoded_path_survives_the_trip() {
        assert_eq!(percent_decode("/tmp/a%20b.pcap"), "/tmp/a b.pcap");
        assert_eq!(percent_decode("%zz"), "%zz", "an invalid escape stays literal");
    }

    #[test]
    fn an_sse_frame_ends_with_the_blank_line_eventsource_wants() {
        assert_eq!(
            event_frame("packet", "{\"packet_id\":7}"),
            "event: packet\ndata: {\"packet_id\":7}\n\n"
        );
    }
}
