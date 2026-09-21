//! Where frames come from, and the shape they need before the sniffer sees them.

use std::fs::File;
use std::io::{self, BufRead, BufReader, Read};
use std::path::Path;

/// PCAPdroid appends a 32-byte trailer to each packet it reports.
const PCAPDROID_TRAILER_SIZE: usize = 32;

/// Turn a captured IP packet into the link-layer framed packet the sniffer expects.
///
/// Frames can arrive with no link layer at all (the VPN hands over a bare IP
/// packet), with a PCAPdroid trailer glued on, or already framed, so all three are
/// normalised here — the sniffer only ever sees the third.
pub fn prepare_frame(data: &[u8]) -> Option<Vec<u8>> {
    if data.is_empty() {
        return None;
    }

    let mut packet_data = data;

    if packet_data.len() >= PCAPDROID_TRAILER_SIZE {
        let trailer_start = packet_data.len() - PCAPDROID_TRAILER_SIZE;
        let trailer = &packet_data[trailer_start..];
        if trailer[0] == 0x01 && trailer[1] == 0x00 {
            packet_data = &packet_data[..trailer_start];
        }
    }

    if packet_data.is_empty() {
        return None;
    }

    let first_byte = packet_data[0];

    // A zeroed 14-byte header plus the real EtherType is all the sniffer's IP
    // parser needs; nothing reads the MACs.
    let ether_type = match first_byte >> 4 {
        0x04 => Some([0x08, 0x00]),
        0x06 if (first_byte & 0xF0) == 0x60 => Some([0x86, 0xDD]),
        _ => None,
    };
    let Some(ether_type) = ether_type else {
        return Some(packet_data.to_vec());
    };

    let mut frame = Vec::with_capacity(14 + packet_data.len());
    frame.extend_from_slice(&[0x00; 12]);
    frame.extend_from_slice(&ether_type);
    frame.extend_from_slice(packet_data);
    Some(frame)
}

/// A classic pcap byte stream replayed one record at a time.
///
/// This is a hand-rolled reader rather than `libpcap` on purpose: replaying a
/// capture is how this stack gets debugged, and it should need no system library,
/// no admin rights and no capture device.
///
/// It reads anything buffered rather than only a file, because a capture reaches
/// this code from several kinds of place: a dumped file, a pipe from another
/// process, the standard input. Only the header needs reading ahead of the rest,
/// so a live stream works exactly as well as a file as long as whoever writes it
/// flushes per record (`tcpdump -U`).
///
/// Frames are handed over as the stream holds them, without [`prepare_frame`]:
/// [`Session::feed`](crate::Session::feed) normalises them, and wrapping here too
/// would prepend the link layer twice.
#[derive(Debug)]
pub struct PcapFrames<R: BufRead = BufReader<File>> {
    reader: R,
    /// Where the bytes come from, spelled out in every error this reader raises.
    source: String,
    /// `false` when the stream's magic says its integers are big-endian.
    little_endian: bool,
    linktype: u32,
    done: bool,
}

/// A reader whose source is not a file — a pipe, standard input, a child process.
pub type AnyFrames = PcapFrames<Box<dyn BufRead + Send>>;

impl PcapFrames<BufReader<File>> {
    pub fn open(path: impl AsRef<Path>) -> io::Result<Self> {
        let path = path.as_ref();
        let source = path.display().to_string();
        let file = File::open(path)?;
        Self::from_read(BufReader::new(file), source)
    }
}

impl<R: BufRead> PcapFrames<R> {
    /// Read from a stream already open, calling the thing `source` in errors.
    pub fn from_read(mut reader: R, source: impl Into<String>) -> io::Result<Self> {
        let source = source.into();
        let header = read_array(&mut reader, 24, &source).map_err(|e| {
            if e.kind() == io::ErrorKind::UnexpectedEof {
                io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    format!("{source} is too short to hold a pcap header"),
                )
            } else {
                e
            }
        })?;

        let magic = u32::from_le_bytes(header[0..4].try_into().unwrap());
        let little_endian = match magic {
            0xa1b2c3d4 | 0xa1b23c4d => true,
            0xd4c3b2a1 | 0x4d3cb2a1 => false,
            other => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("{source} is not a classic pcap file (magic {other:#x})"),
                ));
            }
        };

        let linktype = if little_endian {
            u32::from_le_bytes(header[20..24].try_into().unwrap())
        } else {
            u32::from_be_bytes(header[20..24].try_into().unwrap())
        };

        Ok(Self {
            reader,
            source,
            little_endian,
            linktype,
            done: false,
        })
    }

    /// Forget the reader's type, keeping only the guarantee the replay thread
    /// needs: that it can be moved to another thread and read from there.
    pub fn boxed(self) -> AnyFrames
    where
        R: Send + 'static,
    {
        let Self {
            reader,
            source,
            little_endian,
            linktype,
            done,
        } = self;
        AnyFrames {
            reader: Box::new(reader),
            source,
            little_endian,
            linktype,
            done,
        }
    }

    /// The link-layer type the stream's records start at. `101` is raw IP, which
    /// [`prepare_frame`] wraps; `1` is Ethernet, which it passes through.
    pub fn linktype(&self) -> u32 {
        self.linktype
    }

    fn u32_at(&self, bytes: &[u8]) -> u32 {
        let word = bytes.try_into().expect("four bytes were read");
        if self.little_endian {
            u32::from_le_bytes(word)
        } else {
            u32::from_be_bytes(word)
        }
    }
}

impl AnyFrames {
    /// A live stream: buffered, sendable, and boxed so a caller can hold several
    /// kinds of source in one variable.
    pub fn from_stream(reader: impl BufRead + Send + 'static, source: impl Into<String>) -> io::Result<Self> {
        PcapFrames::from_read(reader, source).map(PcapFrames::boxed)
    }
}

fn read_array(reader: &mut impl Read, len: usize, source: &str) -> io::Result<Vec<u8>> {
    let mut buf = vec![0u8; len];
    reader.read_exact(&mut buf).map_err(|e| match e.kind() {
        // "Ran out of bytes" needs the reader's own words to mean anything, so
        // this keeps the kind and lets the caller name the thing that ended.
        io::ErrorKind::UnexpectedEof => io::Error::new(
            io::ErrorKind::UnexpectedEof,
            format!("{source} ended early"),
        ),
        _ => e,
    })?;
    Ok(buf)
}

impl<R: BufRead> Iterator for PcapFrames<R> {
    /// A frame plus the stream's own timestamp for it, in milliseconds.
    type Item = io::Result<(Vec<u8>, u64)>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.done {
            return None;
        }
        let header = match read_array(&mut self.reader, 16, &self.source) {
            Ok(header) => header,
            // A clean end is a short read of the record header: nothing left, not
            // something broken.
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => {
                self.done = true;
                return None;
            }
            Err(e) => return Some(Err(e)),
        };
        let seconds = self.u32_at(&header[0..4]);
        let fraction = self.u32_at(&header[4..8]);
        let captured = self.u32_at(&header[8..12]);

        let mut record = match read_array(&mut self.reader, captured as usize, &self.source) {
            Ok(record) => record,
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => {
                self.done = true;
                return Some(Err(io::Error::new(
                    e.kind(),
                    format!("{} ends mid-record: wanted {captured} bytes", self.source),
                )));
            }
            Err(e) => return Some(Err(e)),
        };

        // Raw and Ethernet capture lengths are all this pipeline distinguishes;
        // a truncated record is padded so the sniffer's bounds checks stay honest.
        if record.len() < captured as usize {
            record.resize(captured as usize, 0);
        }

        Some(Ok((record, seconds as u64 * 1000 + fraction as u64 / 1000)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    use std::path::PathBuf;

    /// A little-endian raw-IP pcap holding `records`, each stamped 0x11.000022.
    fn pcap_bytes(records: &[&[u8]]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&[
            0xd4, 0xc3, 0xb2, 0xa1, // little-endian magic
            0x02, 0x00, 0x04, 0x00, // version 2.4
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // thiszone, sigts
            0xff, 0xff, 0x00, 0x00, // snaplen
            101, 0x00, 0x00, 0x00, // linktype: raw IP
        ]);
        for record in records {
            let len = (record.len() as u32).to_le_bytes();
            out.extend_from_slice(&[0x11, 0x00, 0x00, 0x00]); // ts_sec
            out.extend_from_slice(&[0x22, 0x00, 0x00, 0x00]); // ts_usec
            out.extend_from_slice(&len);
            out.extend_from_slice(&len);
            out.extend_from_slice(record);
        }
        out
    }

    fn write_pcap(path: &Path, records: &[&[u8]]) {
        std::fs::File::create(path)
            .unwrap()
            .write_all(&pcap_bytes(records))
            .unwrap();
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("irminsul-decode-{name}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_bare_ipv4_packet_gets_a_link_layer_header() {
        let prepared = prepare_frame(&[0x45, 0x00, 0x00, 0x14]).unwrap();
        assert_eq!(prepared.len(), 14 + 4);
        assert_eq!(&prepared[12..14], &[0x08, 0x00]);
        assert_eq!(&prepared[14..], &[0x45, 0x00, 0x00, 0x14]);
    }

    #[test]
    fn an_ipv6_packet_gets_the_ipv6_ethertype() {
        let prepared = prepare_frame(&[0x60, 0x00, 0x00, 0x00]).unwrap();
        assert_eq!(&prepared[12..14], &[0x86, 0xDD]);
    }

    #[test]
    fn a_pcapdroid_trailer_is_stripped_before_wrapping() {
        let mut packet = vec![0x45, 0x01, 0x02];
        let mut trailer = vec![0u8; PCAPDROID_TRAILER_SIZE];
        trailer[0] = 0x01;
        packet.extend_from_slice(&trailer);

        let prepared = prepare_frame(&packet).unwrap();
        assert_eq!(prepared.len(), 14 + 3);
        assert_eq!(&prepared[14..], &[0x45, 0x01, 0x02]);
    }

    #[test]
    fn an_already_framed_packet_is_left_alone() {
        let framed = vec![0x00; 20];
        assert_eq!(prepare_frame(&framed).unwrap(), framed);
    }

    #[test]
    fn a_pcap_file_yields_records_with_their_own_timestamps() {
        let dir = scratch("pcap_reads");
        let path = dir.join("c.pcap");
        write_pcap(&path, &[&[0x45, 0xaa, 0xbb], &[0x45, 0xcc, 0xdd]]);

        let mut frames = PcapFrames::open(&path).unwrap();
        assert_eq!(frames.linktype(), 101);

        let (first, ts) = frames.next().unwrap().unwrap();
        assert_eq!(first, &[0x45, 0xaa, 0xbb]);
        assert_eq!(ts, 0x11 * 1000 + 0x22 / 1000);

        let (second, _) = frames.next().unwrap().unwrap();
        assert_eq!(second, &[0x45, 0xcc, 0xdd]);
        assert!(frames.next().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_non_pcap_file_is_rejected_by_name() {
        let dir = scratch("pcap_rejects");
        let path = dir.join("nope.pcap");
        std::fs::write(&path, b"not a pcap file at all, really").unwrap();

        let error = PcapFrames::open(&path).unwrap_err();
        assert!(
            error.to_string().contains("nope.pcap"),
            "the message should name the file: {error}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_ending_mid_record_reports_where_it_stopped() {
        let dir = scratch("pcap_truncated");
        let path = dir.join("cut.pcap");
        let mut bytes = pcap_bytes(&[&[0x45; 500]]);
        bytes.truncate(bytes.len() - 200);
        std::fs::write(&path, bytes).unwrap();

        let mut frames = PcapFrames::open(&path).unwrap();
        let error = frames.next().unwrap().unwrap_err();
        assert!(
            error.to_string().contains("wanted 500 bytes"),
            "unexpected message: {error}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_stream_reads_exactly_like_a_file() {
        let bytes = pcap_bytes(&[&[0x45, 0xaa, 0xbb], &[0x45, 0xcc, 0xdd]]);
        let mut frames = PcapFrames::from_read(Cursor::new(bytes), "memory").unwrap();

        assert_eq!(frames.linktype(), 101);
        let (first, ts) = frames.next().unwrap().unwrap();
        assert_eq!(first, &[0x45, 0xaa, 0xbb]);
        assert_eq!(ts, 0x11 * 1000 + 0x22 / 1000);
        assert_eq!(frames.next().unwrap().unwrap().0, &[0x45, 0xcc, 0xdd]);
        assert!(frames.next().is_none());
    }

    #[test]
    fn a_stream_that_is_not_a_file_is_still_named_in_errors() {
        let error = PcapFrames::from_read(Cursor::new(b"pipe noise, definitely not pcap".to_vec()), "stdin")
            .unwrap_err();
        assert!(
            error.to_string().contains("stdin"),
            "the message should name the source: {error}"
        );

        let mut bytes = pcap_bytes(&[&[0x45; 40]]);
        bytes.truncate(bytes.len() - 10);
        let mut frames = PcapFrames::from_read(Cursor::new(bytes), "tcpdump -U -w -").unwrap();
        let error = frames.next().unwrap().unwrap_err();
        assert!(
            error.to_string().contains("tcpdump -U -w - ends mid-record"),
            "unexpected message: {error}"
        );
    }

    #[test]
    fn a_stream_can_be_boxed_and_moved_to_another_thread() {
        fn reads_on_a_thread(reader: AnyFrames) -> Vec<Vec<u8>> {
            std::thread::spawn(move || reader.map_while(Result::ok).map(|(frame, _)| frame).collect())
                .join()
                .unwrap()
        }

        let frames = AnyFrames::from_stream(Cursor::new(pcap_bytes(&[&[0x45, 0x01], &[0x45, 0x02]])), "pipe")
            .unwrap();
        assert_eq!(reads_on_a_thread(frames), vec![vec![0x45, 0x01], vec![0x45, 0x02]]);
    }
}
