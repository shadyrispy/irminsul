//! Where frames come from, and the shape they need before the sniffer sees them.

use std::fs::File;
use std::io::{self, BufReader, Read};
use std::path::{Path, PathBuf};

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

/// A classic pcap file replayed one prepared frame at a time.
///
/// This is a hand-rolled reader rather than `libpcap` on purpose: replaying a file
/// is how this stack gets debugged, and it should need no system library, no admin
/// rights and no capture device.
#[derive(Debug)]
pub struct PcapFrames {
    file: BufReader<File>,
    path: PathBuf,
    /// `false` when the file's magic says its integers are big-endian.
    little_endian: bool,
    linktype: u32,
    done: bool,
}

impl PcapFrames {
    pub fn open(path: impl AsRef<Path>) -> io::Result<Self> {
        let path = path.as_ref().to_path_buf();
        let mut file = BufReader::new(File::open(&path)?);
        let header = Self::read_array(&mut file, 24)?;

        let magic = u32::from_le_bytes(header[0..4].try_into().unwrap());
        let little_endian = match magic {
            0xa1b2c3d4 | 0xa1b23c4d => true,
            0xd4c3b2a1 | 0x4d3cb2a1 => false,
            other => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("{} is not a classic pcap file (magic {other:#x})", path.display()),
                ));
            }
        };

        let linktype = if little_endian {
            u32::from_le_bytes(header[20..24].try_into().unwrap())
        } else {
            u32::from_be_bytes(header[20..24].try_into().unwrap())
        };

        Ok(Self {
            file,
            path,
            little_endian,
            linktype,
            done: false,
        })
    }

    /// The link-layer type the file's records start at. `101` is raw IP, which
    /// [`prepare_frame`] wraps; `1` is Ethernet, which it passes through.
    pub fn linktype(&self) -> u32 {
        self.linktype
    }

    fn read_array(file: &mut BufReader<File>, len: usize) -> io::Result<Vec<u8>> {
        let mut buf = vec![0u8; len];
        file.read_exact(&mut buf)?;
        Ok(buf)
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

impl Iterator for PcapFrames {
    /// A frame plus the file's own timestamp for it, in milliseconds.
    type Item = io::Result<(Vec<u8>, u64)>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.done {
            return None;
        }
        let header = match Self::read_array(&mut self.file, 16) {
            Ok(header) => header,
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => {
                self.done = true;
                return None;
            }
            Err(e) => return Some(Err(e)),
        };
        let seconds = self.u32_at(&header[0..4]);
        let fraction = self.u32_at(&header[4..8]);
        let captured = self.u32_at(&header[8..12]);

        let mut record = match Self::read_array(&mut self.file, captured as usize) {
            Ok(record) => record,
            Err(e) => {
                self.done = true;
                return Some(Err(io::Error::new(
                    e.kind(),
                    format!(
                        "{} ends mid-record: wanted {captured} bytes, {}",
                        self.path.display(),
                        e
                    ),
                )));
            }
        };

        // Raw and Ethernet capture lengths are all this pipeline distinguishes;
        // a truncated record is padded so the sniffer's bounds checks stay honest.
        if record.len() < captured as usize {
            record.resize(captured as usize, 0);
        }

        // Records are handed over as read: [`Session::feed`](crate::Session::feed)
        // normalises frames from either source, so prepending the link layer here
        // would make it happen twice for a live tunnel's worth of difference.
        Some(Ok((record, seconds as u64 * 1000 + fraction as u64 / 1000)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_pcap(path: &Path, records: &[&[u8]]) {
        let mut file = std::fs::File::create(path).unwrap();
        file.write_all(
            &[
                0xd4, 0xc3, 0xb2, 0xa1, // little-endian magic
                0x02, 0x00, 0x04, 0x00, // version 2.4
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // thiszone, sigts
                0xff, 0xff, 0x00, 0x00, // snaplen
                101, 0x00, 0x00, 0x00, // linktype: raw IP
            ],
        )
            .unwrap();
        for record in records {
            let len = (record.len() as u32).to_le_bytes();
            file.write_all(&[0x11, 0x00, 0x00, 0x00]).unwrap(); // ts_sec
            file.write_all(&[0x22, 0x00, 0x00, 0x00]).unwrap(); // ts_usec
            file.write_all(&len).unwrap();
            file.write_all(&len).unwrap();
            file.write_all(record).unwrap();
        }
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
        let mut file = std::fs::File::create(&path).unwrap();
        let header = [
            0xd4, 0xc3, 0xb2, 0xa1, 0x02, 0x00, 0x04, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff,
            0x00, 0x00, 101, 0, 0, 0,
        ];
        file.write_all(&header).unwrap();
        file.write_all(&[0, 0, 0, 0, 0, 0, 0, 0]).unwrap();
        file.write_all(&(500u32.to_le_bytes())).unwrap();
        file.write_all(&(500u32.to_le_bytes())).unwrap();
        file.write_all(&[0x45; 3]).unwrap();

        let mut frames = PcapFrames::open(&path).unwrap();
        let error = frames.next().unwrap().unwrap_err();
        assert!(
            error.to_string().contains("wanted 500 bytes"),
            "unexpected message: {error}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
