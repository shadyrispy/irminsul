//! Irminsul Parser - 基于 auto-artifactarium 的原神数据包解析库
//!
//! 本库封装了 auto-artifactarium 的功能，提供 JNI 接口给 Android 使用

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;
use std::sync::Mutex;

// 导出 auto-artifactarium 的类型
use auto_artifactarium::{
    GamePacket, GameSniffer,
    matches_avatar_packet, matches_item_packet, matches_achievement_packet,
};

// 线程安全的 Sniffer 存储 - 使用 Mutex 包装
static SNIFFER: Mutex<Option<GameSniffer>> = Mutex::new(None);

/// 初始化解析器
/// key_data: 初始密钥数据 (JSON 格式的 HashMap<u16, Vec<u8>>)
#[no_mangle]
pub extern "C" fn init_parser(key_data: *const c_char) -> bool {
    let mut sniffer = GameSniffer::new();

    // 如果有密钥数据，尝试解析并设置
    if !key_data.is_null() {
        let key_str = unsafe {
            match CStr::from_ptr(key_data).to_str() {
                Ok(s) => s,
                Err(_) => return false,
            }
        };

        // 尝试解析 JSON
        let initial_keys: HashMap<u16, Vec<u8>> = match serde_json::from_str(key_str) {
            Ok(keys) => keys,
            Err(e) => {
                eprintln!("[Irminsul] Failed to parse key data: {}", e);
                // 使用默认配置，不设置密钥
                HashMap::new()
            }
        };

        sniffer = GameSniffer::new().set_initial_keys(initial_keys);
    }

    // 存储到全局变量
    let mut guard = SNIFFER.lock().unwrap();
    *guard = Some(sniffer);
    true
}

/// 解析单个数据包
/// packet_data: 原始数据包字节数组
/// 返回: JSON 格式的解析结果，或空指针
#[no_mangle]
pub unsafe extern "C" fn parse_packet(packet_data: *const u8, data_len: usize) -> *mut c_char {
    if packet_data.is_null() || data_len == 0 {
        return ptr::null_mut();
    }

    // 获取 sniffer 引用
    let mut guard = SNIFFER.lock().unwrap();
    let sniffer = match guard.as_mut() {
        Some(s) => s,
        None => {
            eprintln!("[Irminsul] Sniffer not initialized");
            return ptr::null_mut();
        }
    };

    // 复制数据
    let data = std::slice::from_raw_parts(packet_data, data_len).to_vec();

    // 解析数据包
    let results = match sniffer.receive_packet(data) {
        Some(GamePacket::Commands(commands)) => {
            let mut results = Vec::new();

            for cmd in commands {
                // 尝试解析角色数据
                if let Some(_avatars) = matches_avatar_packet(&cmd) {
                    results.push(serde_json::json!({
                        "type": "avatars",
                        "command_id": cmd.command_id,
                        "count": _avatars.len()
                    }));
                }

                // 尝试解析物品数据
                if let Some(_items) = matches_item_packet(&cmd) {
                    results.push(serde_json::json!({
                        "type": "items",
                        "command_id": cmd.command_id,
                        "count": _items.len()
                    }));
                }

                // 尝试解析成就数据
                if let Some(_achievements) = matches_achievement_packet(&cmd) {
                    results.push(serde_json::json!({
                        "type": "achievements",
                        "command_id": cmd.command_id,
                        "count": _achievements.len()
                    }));
                }

                // 如果没有匹配到特定类型
                if results.is_empty() || !matches!(results.last(), Some(j) if j["type"] != "unknown") {
                    results.push(serde_json::json!({
                        "type": "unknown",
                        "command_id": cmd.command_id,
                        "data_len": cmd.data_len
                    }));
                }
            }

            results
        }
        Some(GamePacket::Connection(_conn)) => {
            let conn_type = match _conn {
                auto_artifactarium::ConnectionPacket::HandshakeRequested => "handshake_requested",
                auto_artifactarium::ConnectionPacket::HandshakeEstablished => "handshake_established",
                auto_artifactarium::ConnectionPacket::Disconnected => "disconnected",
                auto_artifactarium::ConnectionPacket::SegmentData(_, _) => "segment_data",
            };

            vec![serde_json::json!({
                "type": "connection",
                "status": conn_type
            })]
        }
        None => {
            // 没有解析出有效数据
            vec![serde_json::json!({
                "type": "none"
            })]
        }
    };

    let json_str = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());

    // 存储结果并返回指针
    if let Ok(c_string) = CString::new(json_str) {
        c_string.into_raw()
    } else {
        ptr::null_mut()
    }
}

/// 解析数据包 (简化版本，接收字节数组的副本)
#[no_mangle]
pub extern "C" fn parse_packet_vec(packet_data: *const u8, data_len: usize) -> *mut c_char {
    if packet_data.is_null() || data_len == 0 {
        return ptr::null_mut();
    }

    // 获取 sniffer 引用
    let mut guard = SNIFFER.lock().unwrap();
    let sniffer = match guard.as_mut() {
        Some(s) => s,
        None => {
            eprintln!("[Irminsul] Sniffer not initialized");
            return ptr::null_mut();
        }
    };

    // 复制数据
    let data = unsafe {
        std::slice::from_raw_parts(packet_data, data_len).to_vec()
    };

    // 解析数据包
    let results = match sniffer.receive_packet(data) {
        Some(GamePacket::Commands(commands)) => {
            let mut results = Vec::new();

            for cmd in commands {
                // 尝试解析角色数据
                if let Some(_avatars) = matches_avatar_packet(&cmd) {
                    results.push(serde_json::json!({
                        "type": "avatars",
                        "command_id": cmd.command_id,
                        "count": _avatars.len()
                    }));
                }

                // 尝试解析物品数据
                if let Some(_items) = matches_item_packet(&cmd) {
                    results.push(serde_json::json!({
                        "type": "items",
                        "command_id": cmd.command_id,
                        "count": _items.len()
                    }));
                }

                // 尝试解析成就数据
                if let Some(_achievements) = matches_achievement_packet(&cmd) {
                    results.push(serde_json::json!({
                        "type": "achievements",
                        "command_id": cmd.command_id,
                        "count": _achievements.len()
                    }));
                }

                // 如果没有匹配到特定类型
                if results.is_empty() {
                    results.push(serde_json::json!({
                        "type": "unknown",
                        "command_id": cmd.command_id,
                        "data_len": cmd.data_len
                    }));
                }
            }

            results
        }
        Some(GamePacket::Connection(_conn)) => {
            let conn_type = match _conn {
                auto_artifactarium::ConnectionPacket::HandshakeRequested => "handshake_requested",
                auto_artifactarium::ConnectionPacket::HandshakeEstablished => "handshake_established",
                auto_artifactarium::ConnectionPacket::Disconnected => "disconnected",
                auto_artifactarium::ConnectionPacket::SegmentData(_, _) => "segment_data",
            };

            vec![serde_json::json!({
                "type": "connection",
                "status": conn_type
            })]
        }
        None => {
            vec![serde_json::json!({
                "type": "none"
            })]
        }
    };

    let json_str = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());

    if let Ok(c_string) = CString::new(json_str) {
        c_string.into_raw()
    } else {
        ptr::null_mut()
    }
}

/// 获取解析器版本
#[no_mangle]
pub extern "C" fn get_version() -> *mut c_char {
    let version = env!("CARGO_PKG_VERSION");
    if let Ok(c_string) = CString::new(format!("Irminsul Parser v{} (powered by auto-artifactarium)", version)) {
        c_string.into_raw()
    } else {
        ptr::null_mut()
    }
}

/// 释放由 Rust 分配的字符串
#[no_mangle]
pub extern "C" fn free_string(str_ptr: *mut c_char) {
    if !str_ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(str_ptr);
        }
    }
}

/// 测试函数: 创建一个测试用数据包并解析
#[no_mangle]
pub extern "C" fn test_parse() -> *mut c_char {
    // 初始化 sniffer
    let _ = init_parser(ptr::null());

    // 返回测试结果
    let json = serde_json::json!({
        "type": "test",
        "status": "ok",
        "version": env!("CARGO_PKG_VERSION"),
        "message": "Parser initialized successfully"
    });

    let json_str = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());

    if let Ok(c_string) = CString::new(json_str) {
        c_string.into_raw()
    } else {
        ptr::null_mut()
    }
}

/// 测试函数: 解析示例 UDP 数据包
#[no_mangle]
pub extern "C" fn test_parse_sample_packet() -> *mut c_char {
    // 初始化 sniffer
    let _ = init_parser(ptr::null());

    // 创建一个示例数据包 (模拟 UDP 数据)
    let sample_data: Vec<u8> = vec![
        0x00, 0x00, 0x00, 0x00, // IP header placeholder
        0x00, 0x00, 0x00, 0x00,
    ];

    // 获取 sniffer 引用
    let mut guard = SNIFFER.lock().unwrap();
    let sniffer = match guard.as_mut() {
        Some(s) => s,
        None => {
            let json = serde_json::json!({
                "type": "test_error",
                "error": "Sniffer not initialized"
            });
            let json_str = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());
            return if let Ok(c_string) = CString::new(json_str) {
                c_string.into_raw()
            } else {
                ptr::null_mut()
            };
        }
    };

    match sniffer.receive_packet(sample_data) {
        Some(GamePacket::Commands(commands)) => {
            let json = serde_json::json!({
                "type": "test_result",
                "commands_count": commands.len(),
                "message": "Sample packet parsed (but it's empty data)"
            });
            let json_str = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());
            if let Ok(c_string) = CString::new(json_str) {
                c_string.into_raw()
            } else {
                ptr::null_mut()
            }
        }
        Some(GamePacket::Connection(_conn)) => {
            let conn_type = match _conn {
                auto_artifactarium::ConnectionPacket::HandshakeRequested => "handshake_requested",
                auto_artifactarium::ConnectionPacket::HandshakeEstablished => "handshake_established",
                auto_artifactarium::ConnectionPacket::Disconnected => "disconnected",
                auto_artifactarium::ConnectionPacket::SegmentData(_, _) => "segment_data",
            };
            let json = serde_json::json!({
                "type": "test_connection",
                "status": conn_type,
                "message": "Sample packet contains connection data"
            });
            let json_str = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());
            if let Ok(c_string) = CString::new(json_str) {
                c_string.into_raw()
            } else {
                ptr::null_mut()
            }
        }
        None => {
            let json = serde_json::json!({
                "type": "test_no_data",
                "status": "ok",
                "message": "No valid packet parsed (expected for empty sample data)"
            });
            let json_str = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());
            if let Ok(c_string) = CString::new(json_str) {
                c_string.into_raw()
            } else {
                ptr::null_mut()
            }
        }
    }
}