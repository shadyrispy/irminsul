use async_trait::async_trait;
use pcap::{Capture, Linktype, Offline};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::capture::{CaptureBackend, CaptureError, PORT_RANGE, Result};

pub struct PcapFileBackend {
    packet_rx: UnboundedReceiver<Result<Vec<u8>>>,
}

impl PcapFileBackend {
    pub fn new(file_path: &str) -> Result<Self> {
        let capture = Capture::from_file(file_path)
            .map_err(|e| CaptureError::Capture {
                has_captured: false,
                error: e.into(),
            })?;

        let link_type = capture.get_datalink();
        tracing::info!("Pcap file link type: {:?} ({})", link_type, link_type.0);

        let (packet_tx, packet_rx) = mpsc::unbounded_channel();

        std::thread::spawn(move || Self::packet_loop(capture, packet_tx, link_type));

        Ok(Self { packet_rx })
    }

    fn packet_loop(
        mut capture: Capture<Offline>,
        packet_tx: UnboundedSender<Result<Vec<u8>>>,
        link_type: Linktype,
    ) {
        let mut has_captured = false;
        loop {
            match capture.next_packet() {
                Ok(packet) => {
                    let data = packet.data.to_vec();
                    if let Some(data) = Self::process_packet(&data, link_type) {
                        has_captured = true;
                        if packet_tx.send(Ok(data)).is_err() {
                            tracing::info!(
                                "Packet loop ending (has_captured: {}): channel closed",
                                has_captured
                            );
                            break;
                        }
                    }
                }
                Err(err) => {
                    tracing::info!(
                        "Packet loop ending (has_captured: {}): capture error: {}",
                        has_captured,
                        err
                    );
                    let _ = packet_tx.send(Err(CaptureError::Capture {
                        has_captured,
                        error: err.into(),
                    }));
                    break;
                }
            }
        }
    }

    fn process_packet(data: &[u8], link_type: Linktype) -> Option<Vec<u8>> {
        match link_type.0 {
            1 => {
                // Ethernet (EN10MB)
                Self::is_relevant_ethernet_packet(data).then(|| data.to_vec())
            }
            12 => {
                // Raw IP (RAW)
                Self::is_relevant_raw_ip_packet(data)
                    .then(|| Self::wrap_raw_ip_in_ethernet(data))
            }
            _ => {
                tracing::warn!("Unsupported pcap link type: {}. Trying as Ethernet.", link_type.0);
                Self::is_relevant_ethernet_packet(data).then(|| data.to_vec())
            }
        }
    }

    fn is_relevant_ethernet_packet(data: &[u8]) -> bool {
        if data.len() < 28 {
            return false;
        }

        let eth_type = u16::from_be_bytes([data[12], data[13]]);
        if eth_type != 0x0800 {
            return false;
        }

        let ip_proto = data[23];
        if ip_proto != 0x11 {
            return false;
        }

        let src_port = u16::from_be_bytes([data[20], data[21]]);
        let dst_port = u16::from_be_bytes([data[22], data[23]]);

        (src_port >= PORT_RANGE.0 && src_port <= PORT_RANGE.1)
            || (dst_port >= PORT_RANGE.0 && dst_port <= PORT_RANGE.1)
    }

    fn is_relevant_raw_ip_packet(data: &[u8]) -> bool {
        if data.len() < 20 {
            return false;
        }

        let version_ihl = data[0];
        let ihl = (version_ihl & 0x0f) as usize * 4;

        if data.len() < ihl + 4 {
            return false;
        }

        let ip_proto = data[9];
        if ip_proto != 0x11 {
            return false;
        }

        let src_port = u16::from_be_bytes([data[ihl], data[ihl + 1]]);
        let dst_port = u16::from_be_bytes([data[ihl + 2], data[ihl + 3]]);

        (src_port >= PORT_RANGE.0 && src_port <= PORT_RANGE.1)
            || (dst_port >= PORT_RANGE.0 && dst_port <= PORT_RANGE.1)
    }

    fn wrap_raw_ip_in_ethernet(data: &[u8]) -> Vec<u8> {
        let mut ethernet_frame = vec![0u8; 14 + data.len()];
        // Destination MAC (broadcast)
        ethernet_frame[0..6].copy_from_slice(&[0xff, 0xff, 0xff, 0xff, 0xff, 0xff]);
        // Source MAC (dummy)
        ethernet_frame[6..12].copy_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
        // EtherType: IPv4
        ethernet_frame[12..14].copy_from_slice(&[0x08, 0x00]);
        // Copy IP data
        ethernet_frame[14..].copy_from_slice(data);
        ethernet_frame
    }
}

#[async_trait]
impl CaptureBackend for PcapFileBackend {
    async fn next_packet(&mut self) -> Result<Vec<u8>> {
        match self.packet_rx.recv().await {
            Some(Ok(packet)) => Ok(packet),
            Some(Err(err)) => Err(err),
            None => Err(CaptureError::CaptureClosed),
        }
    }
}
