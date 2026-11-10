use aes::Aes256;
use futures_util::stream::FuturesUnordered;
use futures_util::StreamExt;
use ctr::cipher::{KeyIvInit, StreamCipher};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{self, Read, Write},
    net::{IpAddr, Ipv4Addr, Ipv6Addr, TcpStream, ToSocketAddrs},
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::{Mutex, Semaphore};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_opener::OpenerExt;

const CATALOG_URL: &str = "https://raw.githubusercontent.com/sergeyrenamed-coder/TGU-Proxy-Directory/main/data/proxies.json";
const COMMUNITY_API_BASE_URL: Option<&str> = option_env!("TGU_COMMUNITY_API_BASE_URL");
const TELEGRAM_DC2: &str = "149.154.167.51";
const TELEGRAM_PORT: u16 = 443;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const AUTO_CONNECT_TIMEOUT: Duration = Duration::from_millis(3_000);
const AUTO_IO_TIMEOUT: Duration = Duration::from_millis(3_500);
const VERIFY_CONNECT_TIMEOUT: Duration = Duration::from_millis(3_500);
const VERIFY_IO_TIMEOUT: Duration = Duration::from_millis(4_500);
const RES_PQ_CONSTRUCTOR: u32 = 0x05162463;
const REQ_PQ_MULTI_CONSTRUCTOR: u32 = 0xBE7E8EF1;
type Aes256Ctr = ctr::Ctr128BE<Aes256>;

#[derive(Debug, Clone)]
pub(crate) struct CatalogDocument {
    pub(crate) schema_version: u32,
    pub(crate) generated_at: String,
    pub(crate) source: String,
    pub(crate) proxies: Vec<CatalogRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct CatalogRecord {
    pub(crate) id: String,
    #[serde(rename = "type")]
    pub(crate) kind: String,
    #[serde(default)]
    pub(crate) protocol: Option<String>,
    pub(crate) server: String,
    pub(crate) port: u16,
    #[serde(default)]
    pub(crate) secret: String,
    #[serde(default)]
    pub(crate) username: String,
    #[serde(default)]
    pub(crate) password: String,
    #[serde(default = "default_enabled")]
    pub(crate) enabled: bool,
    #[serde(default)]
    pub(crate) country: Option<String>,
    #[serde(default)]
    pub(crate) rtt_ms: Option<u64>,
    #[serde(default)]
    pub(crate) uptime: Option<f64>,
    #[serde(default)]
    pub(crate) checks: Option<u32>,
    #[serde(default)]
    pub(crate) source_tier: Option<String>,
    #[serde(default)]
    pub(crate) last_check: Option<String>,
    #[serde(default)]
    pub(crate) stale: bool,
    #[serde(default)]
    pub(crate) probe_status: Option<String>,
    #[serde(default)]
    pub(crate) community_reports: u32,
    #[serde(default)]
    pub(crate) community_unique_reporters: u32,
    #[serde(default)]
    pub(crate) community_success_rate: Option<f64>,
    #[serde(default)]
    pub(crate) community_median_rtt_ms: Option<u64>,
}

fn default_enabled() -> bool { true }

#[derive(Debug, Serialize, Clone)]
pub struct ProxySummary {
    pub id: String,
    pub protocol: String,
    pub server: String,
    pub port: u16,
    pub country: Option<String>,
    pub source_rtt_ms: Option<u64>,
    pub uptime: Option<f64>,
    pub checks: Option<u32>,
    pub source_tier: Option<String>,
    pub last_check: Option<String>,
    pub source_verified: bool,
    pub supported: bool,
    pub unsupported_reason: Option<String>,
    pub community_reports: u32,
    pub community_unique_reporters: u32,
    pub community_success_rate: Option<f64>,
    pub community_median_rtt_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct CatalogResponse {
    pub count: usize,
    pub generated_at: String,
    pub source: String,
    pub from_cache: bool,
    pub warning: Option<String>,
    pub proxies: Vec<ProxySummary>,
}

#[derive(Debug, Serialize, Clone)]
pub struct ProxyTestResult {
    pub success: bool,
    pub stage: String,
    pub detail: String,
    pub elapsed_ms: u64,
}

#[derive(Debug, Serialize, Clone)]
pub struct CommunityFeedbackStatus {
    pub enabled: bool,
    pub attempted: usize,
    pub accepted: usize,
    pub rejected: usize,
    pub http_status: Option<u16>,
    pub message: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct AutoSelectionResult {
    pub selected: Option<ProxySummary>,
    pub total_candidates: usize,
    pub tested_candidates: usize,
    pub first_pass_available: usize,
    pub finalists_verified: usize,
    pub verified_latency_ms: Option<u64>,
    pub message: String,
    pub community_feedback: Option<CommunityFeedbackStatus>,
}

#[derive(Debug, Clone, Serialize)]
pub struct AutoSelectionProgress {
    pub is_running: bool,
    pub phase: String,
    pub total: usize,
    pub completed: usize,
    pub available: usize,
    pub finalists_verified: usize,
    pub current_endpoint: Option<String>,
    pub best_endpoint: Option<String>,
    pub best_latency_ms: Option<u64>,
    pub elapsed_ms: u64,
    pub message: Option<String>,
    #[serde(skip)]
    started_at: Instant,
}

const AUTO_PARALLELISM: usize = 48;
const AUTO_FINALISTS: usize = 5;
const AUTO_VERIFY_REPEATS: usize = 2;

#[derive(Debug, Clone, Serialize)]
struct CommunityFeedbackReport {
    proxy_id: String,
    proxy_type: String,
    outcome: String,
    latency_ms: Option<u64>,
    failure_code: String,
}

fn community_api_base_url() -> Option<String> {
    let base = COMMUNITY_API_BASE_URL?.trim().trim_end_matches('/');
    let url = url::Url::parse(base).ok()?;
    (url.scheme() == "https" && url.host_str().is_some()).then(|| base.to_string())
}

fn new_community_uuid() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0], bytes[1], bytes[2], bytes[3],
        bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11],
        bytes[12], bytes[13], bytes[14], bytes[15]
    )
}

fn community_instance_id(app: &AppHandle) -> Option<String> {
    let dir = app.path().app_data_dir().ok()?;
    fs::create_dir_all(&dir).ok()?;
    let path = dir.join("community-instance-id.txt");
    if let Ok(value) = fs::read_to_string(&path) {
        let value = value.trim();
        if value.len() == 36 && value.bytes().filter(|byte| *byte == b'-').count() == 4 {
            return Some(value.to_string());
        }
    }
    let value = new_community_uuid();
    fs::write(path, &value).ok()?;
    Some(value)
}

fn community_feedback_report(record: &CatalogRecord, result: &ProxyTestResult) -> Option<CommunityFeedbackReport> {
    if record.id.len() != 20 || !record.id.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    let proxy_type = match record.kind.to_ascii_lowercase().as_str() {
        "mtproto" | "mt-proto" => "MTProto",
        "socks5" | "socks" => "SOCKS5",
        _ => return None,
    };
    let detail = format!("{} {}", result.stage, result.detail).to_ascii_lowercase();
    let failure_code = if result.success {
        "none"
    } else if detail.contains("timeout") || detail.contains("timed out") {
        "timeout"
    } else if detail.contains("handshake") || detail.contains("secret") || detail.contains("respq") {
        "handshake"
    } else if detail.contains("protocol") || detail.contains("frame") || detail.contains("constructor") {
        "protocol"
    } else if detail.contains("connect") || detail.contains("refused") || detail.contains("unreachable") {
        "connect"
    } else {
        "unknown"
    };
    let outcome = if result.success {
        "working"
    } else if failure_code == "timeout" {
        "timeout"
    } else if failure_code == "handshake" || failure_code == "protocol" {
        "protocol_error"
    } else {
        "failed"
    };
    Some(CommunityFeedbackReport {
        proxy_id: record.id.clone(),
        proxy_type: proxy_type.to_string(),
        outcome: outcome.to_string(),
        latency_ms: (1..=60_000).contains(&result.elapsed_ms).then_some(result.elapsed_ms),
        failure_code: failure_code.to_string(),
    })
}

async fn submit_community_feedback(app: AppHandle, reports: Vec<CommunityFeedbackReport>) -> CommunityFeedbackStatus {
    let attempted = reports.len();
    if attempted == 0 {
        return CommunityFeedbackStatus {
            enabled: true,
            attempted: 0,
            accepted: 0,
            rejected: 0,
            http_status: None,
            message: "CN: нет валидных результатов для отправки.".into(),
        };
    }

    let Some(base) = community_api_base_url() else {
        return CommunityFeedbackStatus {
            enabled: true,
            attempted,
            accepted: 0,
            rejected: attempted,
            http_status: None,
            message: "CN: адрес API не настроен в этой сборке.".into(),
        };
    };
    let Some(instance_id) = community_instance_id(&app) else {
        return CommunityFeedbackStatus {
            enabled: true,
            attempted,
            accepted: 0,
            rejected: attempted,
            http_status: None,
            message: "CN: не удалось создать локальный идентификатор установки.".into(),
        };
    };

    let client = match reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(8))
        .user_agent("TGU-Desktop/0.4")
        .build()
    {
        Ok(client) => client,
        Err(error) => {
            return CommunityFeedbackStatus {
                enabled: true,
                attempted,
                accepted: 0,
                rejected: attempted,
                http_status: None,
                message: format!("CN: не удалось создать HTTP-клиент: {error}"),
            };
        }
    };

    let scan_id = new_community_uuid();
    let mut accepted = 0usize;
    let mut rejected = 0usize;
    let mut last_http_status = None;
    let mut errors = Vec::new();

    for chunk in reports.chunks(100) {
        let payload = serde_json::json!({
            "client_instance_id": instance_id,
            "scan_id": scan_id,
            "client_platform": "windows",
            "app_version": env!("CARGO_PKG_VERSION"),
            "network_type": "unknown",
            "reports": chunk,
        });

        let response = client
            .post(format!("{base}/.netlify/functions/tgu-api?route=feedback"))
            .json(&payload)
            .send()
            .await;

        match response {
            Ok(response) => {
                let status = response.status();
                last_http_status = Some(status.as_u16());
                let body = response.json::<serde_json::Value>().await.unwrap_or(serde_json::Value::Null);

                if status.is_success() && body.get("ok").and_then(|value| value.as_bool()) == Some(true) {
                    accepted += body.get("accepted").and_then(|value| value.as_u64()).unwrap_or(0) as usize;
                    rejected += body.get("duplicates_or_rejected").and_then(|value| value.as_u64()).unwrap_or(0) as usize;
                } else {
                    rejected += chunk.len();
                    let reason = body.get("error").and_then(|value| value.as_str()).unwrap_or("invalid_response");
                    errors.push(format!("HTTP {} ({reason})", status.as_u16()));
                }
            }
            Err(error) => {
                rejected += chunk.len();
                errors.push(error.to_string());
            }
        }
    }

    let message = if errors.is_empty() && rejected == 0 {
        format!("CN: сервер принял {accepted}/{attempted} результатов.")
    } else if errors.is_empty() {
        format!("CN: сервер принял {accepted}/{attempted}; дубликатов или отклонённых: {rejected}.")
    } else {
        format!("CN: сервер принял {accepted}/{attempted}; ошибок/отклонений: {rejected}. {}", errors.join("; "))
    };

    CommunityFeedbackStatus {
        enabled: true,
        attempted,
        accepted,
        rejected,
        http_status: last_http_status,
        message,
    }
}

fn cache_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|e| format!("App data directory: {e}"))?;
    fs::create_dir_all(&dir).map_err(|e| format!("Create app data directory: {e}"))?;
    Ok(dir.join("proxy-catalog-v1.json"))
}

fn json_string(object: &serde_json::Map<String, serde_json::Value>, names: &[&str]) -> Option<String> {
    for name in names {
        let Some(value) = object.get(*name) else { continue };
        let candidate = match value {
            serde_json::Value::String(value) => value.clone(),
            serde_json::Value::Number(value) => value.to_string(),
            serde_json::Value::Bool(value) => value.to_string(),
            _ => continue,
        };
        let candidate = candidate.trim();
        if !candidate.is_empty() && candidate != "null" {
            return Some(candidate.to_string());
        }
    }
    None
}

fn json_bool(object: &serde_json::Map<String, serde_json::Value>, name: &str, default: bool) -> bool {
    object.get(name).and_then(serde_json::Value::as_bool).unwrap_or(default)
}

fn json_number(object: &serde_json::Map<String, serde_json::Value>, names: &[&str]) -> Option<f64> {
    json_string(object, names)?.parse::<f64>().ok().filter(|value| value.is_finite() && *value >= 0.0)
}

fn stable_catalog_id(parts: &[&str]) -> String {
    let input = parts.join("|");
    let digest = Sha256::digest(input.as_bytes());
    format!("fc-{}", digest[..12].iter().map(|byte| format!("{byte:02x}")).collect::<String>())
}

fn parse_telegram_link(link: &str, last_check: Option<String>) -> Option<CatalogRecord> {
    let url = url::Url::parse(link).ok()?;
    let authority = url.host_str().unwrap_or_default().to_ascii_lowercase();
    let is_telegram_link = url.scheme().eq_ignore_ascii_case("tg")
        || (url.scheme().eq_ignore_ascii_case("https") && authority == "t.me");
    if !is_telegram_link { return None; }

    let values: std::collections::HashMap<String, String> = url.query_pairs()
        .map(|(key, value)| (key.to_ascii_lowercase(), value.into_owned()))
        .collect();
    let host = values.get("server")?.trim().to_string();
    let port = values.get("port")?.parse::<u16>().ok()?;
    let secret = values.get("secret").cloned().unwrap_or_default();
    let username = values.get("user").or_else(|| values.get("username")).cloned().unwrap_or_default();
    let password = values.get("pass").or_else(|| values.get("password")).cloned().unwrap_or_default();
    let path = url.path().to_ascii_lowercase();
    let is_socks = authority == "socks" || path.contains("socks") || secret.is_empty();
    let kind = if is_socks { "SOCKS5" } else if secret.starts_with("ee") { "WEB" } else { "MTProto" };
    let id = stable_catalog_id(&[kind, &host, &port.to_string(), &secret, &username, &password]);
    Some(CatalogRecord {
        id, kind: kind.into(), protocol: Some(kind.into()), server: host, port, secret,
        username, password, enabled: true, country: None, rtt_ms: None, uptime: None,
        checks: None, source_tier: None, last_check, stale: false, probe_status: None,
        community_reports: 0, community_unique_reporters: 0,
        community_success_rate: None, community_median_rtt_ms: None,
    })
}

fn parse_catalog_record(value: &serde_json::Value) -> Option<CatalogRecord> {
    let object = value.as_object()?;
    if !json_bool(object, "enabled", true) || json_bool(object, "stale", false) {
        return None;
    }

    let probe_status = json_string(object, &["probe_status", "probeStatus"]);
    if object.contains_key("probe_status") || object.contains_key("probeStatus") {
        if !probe_status.as_deref().map(|s| s.eq_ignore_ascii_case("working")).unwrap_or(false) {
            return None;
        }
    }

    let last_check = json_string(object, &[
        "last_check", "last_checked", "lastChecked", "last_checked_at",
        "lastCheckedAt", "checked_at", "checkedAt", "updated_at"
    ]);
    for nested in object.values() {
        if let Some(link) = nested.as_str() {
            if link.starts_with("tg://") || link.starts_with("https://t.me/") {
                if let Some(record) = parse_telegram_link(link, last_check.clone()) {
                    return Some(record);
                }
            }
        }
    }

    let hint = json_string(object, &[
        "type", "proxy_type", "proxyType", "kind", "protocol", "protocol_type", "scheme"
    ]).unwrap_or_default().to_ascii_lowercase();
    let server = json_string(object, &["server", "host", "ip", "address", "hostname", "domain"])?;
    let port = json_string(object, &["port", "server_port", "serverPort"])?.parse::<u16>().ok()?;
    let secret = json_string(object, &["secret", "mtproto_secret", "mtprotoSecret", "hash"]).unwrap_or_default();
    let username = json_string(object, &["username", "user", "login"]).unwrap_or_default();
    let password = json_string(object, &["password", "pass"]).unwrap_or_default();

    let is_web = hint.contains("web") || secret.starts_with("ee");
    let is_socks = hint.contains("socks");
    let is_mtproto = hint.contains("mt") || hint.contains("telegram") || !secret.is_empty();
    if !is_web && !is_socks && !is_mtproto { return None; }

    let kind = if is_web { "WEB" } else if is_socks { "SOCKS5" } else { "MTProto" };
    let protocol = json_string(object, &["protocol", "protocol_type", "protocolType"])
        .unwrap_or_else(|| kind.to_string());
    let id = json_string(object, &["id", "proxy_id", "proxyId"])
        .unwrap_or_else(|| stable_catalog_id(&[kind, &server, &port.to_string(), &secret, &username, &password]));

    Some(CatalogRecord {
        id,
        kind: kind.into(),
        protocol: Some(protocol),
        server,
        port,
        secret,
        username,
        password,
        enabled: true,
        country: json_string(object, &["country", "country_code", "countryCode"]),
        rtt_ms: json_number(object, &["rtt_ms", "latency_ms", "rtt"]).map(|v| v as u64),
        uptime: json_number(object, &["uptime"]),
        checks: json_number(object, &["checks", "check_count"]).map(|v| v.min(u32::MAX as f64) as u32),
        source_tier: json_string(object, &["source_tier", "sourceTier", "tier"]),
        last_check,
        stale: false,
        probe_status,
        community_reports: json_number(object, &["community_reports"]).map(|v| v.min(u32::MAX as f64) as u32).unwrap_or(0),
        community_unique_reporters: json_number(object, &["community_unique_reporters"]).map(|v| v.min(u32::MAX as f64) as u32).unwrap_or(0),
        community_success_rate: json_number(object, &["community_success_rate"]).filter(|v| *v <= 100.0),
        community_median_rtt_ms: json_number(object, &["community_median_rtt_ms"]).map(|v| v.min(u64::MAX as f64) as u64),
    })
}

fn collect_catalog_records(value: &serde_json::Value, records: &mut std::collections::BTreeMap<String, CatalogRecord>) {
    match value {
        serde_json::Value::Object(object) => {
            if let Some(record) = parse_catalog_record(value) {
                records.insert(record.id.clone(), record);
            }
            for nested in object.values() {
                collect_catalog_records(nested, records);
            }
        }
        serde_json::Value::Array(values) => {
            for nested in values {
                collect_catalog_records(nested, records);
            }
        }
        _ => {}
    }
}

fn parse_document(raw: &str, source: &str) -> Result<CatalogDocument, String> {
    if raw.len() > 16 * 1024 * 1024 {
        return Err("Catalog response exceeds the 16 MiB safety limit.".into());
    }
    let root: serde_json::Value = serde_json::from_str(raw)
        .map_err(|e| format!("Invalid catalog JSON: {e}"))?;
    if let Some(version) = root.get("schema_version").and_then(serde_json::Value::as_u64) {
        if version != 1 {
            return Err(format!("Unsupported catalog schema version: {version}"));
        }
    }
    let generated_at = root.get("generated_at")
        .or_else(|| root.get("generatedAt"))
        .or_else(|| root.get("updated_at"))
        .or_else(|| root.get("updatedAt"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
        .to_string();

    let mut records = std::collections::BTreeMap::new();
    collect_catalog_records(&root, &mut records);
    let proxies: Vec<_> = records.into_values()
        .filter(|record| record.enabled && !record.stale && record.port > 0 && is_public_host(&record.server))
        .collect();
    if proxies.is_empty() {
        return Err("No complete public MTProto/SOCKS5 records were found in this catalog.".into());
    }

    Ok(CatalogDocument { schema_version: 1, generated_at, source: source.to_string(), proxies })
}

fn is_hex_secret(secret: &str) -> bool {
    secret.len() == 32 && secret.bytes().all(|b| b.is_ascii_hexdigit())
}

pub(crate) fn is_supported_profile_record(record: &CatalogRecord) -> bool {
    is_supported_record(record).0
}

fn is_supported_record(record: &CatalogRecord) -> (bool, Option<String>) {
    match record.kind.to_ascii_lowercase().as_str() {
        "mtproto" | "mt-proto" => {
            let value = record.secret.trim().to_ascii_lowercase();
            let valid = is_hex_secret(&value)
                || (value.starts_with("dd")
                    && value.len() == 34
                    && is_hex_secret(&value[2..]));
            if valid {
                (true, None)
            } else if value.starts_with("ee") {
                (false, Some("FakeTLS/WEB MTProto is not supported by this Desktop transport yet.".into()))
            } else {
                (false, Some("Invalid MTProto secret format.".into()))
            }
        }
        "socks5" | "socks" => (true, None),
        _ => (false, Some("Unsupported proxy protocol.".into())),
    }
}

fn is_public_ipv4(address: Ipv4Addr) -> bool {
    let [a, b, c, _] = address.octets();
    if address.is_private()
        || address.is_loopback()
        || address.is_link_local()
        || address.is_unspecified()
        || address.is_multicast()
        || address.is_broadcast()
    {
        return false;
    }

                                                                                       
    if a == 0
        || a >= 224
        || (a == 100 && (64..=127).contains(&b))
        || (a == 192 && b == 0 && (c == 0 || c == 2))
        || (a == 192 && b == 88 && c == 99)
        || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
        || (a == 203 && b == 0 && c == 113)
    {
        return false;
    }
    true
}

pub(crate) fn is_public_ip(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(ip) => is_public_ipv4(ip),
        IpAddr::V6(ip) => {
            let segments = ip.segments();
                                                                         
            if segments[0..5] == [0, 0, 0, 0, 0] && segments[5] == 0xffff {
                let v4 = Ipv4Addr::new(
                    (segments[6] >> 8) as u8,
                    segments[6] as u8,
                    (segments[7] >> 8) as u8,
                    segments[7] as u8,
                );
                return is_public_ipv4(v4);
            }
            !ip.is_loopback()
                && !ip.is_unspecified()
                && !ip.is_multicast()
                && !ip.is_unique_local()
                && !ip.is_unicast_link_local()
        }
    }
}

fn valid_catalog_domain(host: &str) -> bool {
    if host.is_empty() || host.len() > 253 || host.starts_with('.') || host.ends_with('.') {
        return false;
    }
    let labels: Vec<_> = host.split('.').collect();
    labels.len() >= 2 && labels.iter().all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    })
}

pub(crate) fn is_public_host(host: &str) -> bool {
    let normalized = host.trim().trim_end_matches('.').to_ascii_lowercase();
    if normalized.is_empty() || normalized.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return false;
    }
    if let Ok(address) = normalized.parse::<IpAddr>() {
        return is_public_ip(address);
    }
    if !valid_catalog_domain(&normalized) {
        return false;
    }
    ![".localhost", ".local", ".internal", ".lan", ".home.arpa"]
        .iter()
        .any(|suffix| normalized.ends_with(suffix))
}

fn is_usable_record(record: &CatalogRecord) -> bool {
    record.enabled
        && !record.stale
        && record.port > 0
        && is_public_host(&record.server)
        && record.server.len() <= 253
        && record
            .probe_status
            .as_deref()
            .map(|status| status.eq_ignore_ascii_case("working"))
            .unwrap_or(true)
}

fn summarize(record: &CatalogRecord) -> ProxySummary {
    let (supported, unsupported_reason) = is_supported_record(record);
    let protocol = record
        .protocol
        .clone()
        .unwrap_or_else(|| record.kind.clone());
    ProxySummary {
        id: record.id.clone(),
        protocol,
        server: record.server.clone(),
        port: record.port,
        country: record.country.clone(),
        source_rtt_ms: record.rtt_ms,
        uptime: record.uptime,
        checks: record.checks,
        source_tier: record.source_tier.clone(),
        last_check: record.last_check.clone(),
        source_verified: record
            .probe_status
            .as_deref()
            .map(|s| s.eq_ignore_ascii_case("working"))
            .unwrap_or(false),
        supported,
        unsupported_reason,
        community_reports: record.community_reports,
        community_unique_reporters: record.community_unique_reporters,
        community_success_rate: record.community_success_rate,
        community_median_rtt_ms: record.community_median_rtt_ms,
    }
}

fn response_from_document(
    document: CatalogDocument,
    from_cache: bool,
    warning: Option<String>,
) -> CatalogResponse {
    let proxies: Vec<_> = document
        .proxies
        .iter()
        .filter(|record| is_usable_record(record))
        .map(summarize)
        .collect();
    CatalogResponse {
        count: proxies.len(),
        generated_at: document.generated_at,
        source: document.source,
        from_cache,
        warning,
        proxies,
    }
}

fn load_cached_document(app: &AppHandle) -> Result<CatalogDocument, String> {
    let path = cache_path(app)?;
    let raw = fs::read_to_string(&path).map_err(|e| format!("No local catalog cache: {e}"))?;
    let source_path = path.with_file_name("proxy-catalog-source.txt");
    let source = fs::read_to_string(source_path).unwrap_or_else(|_| CATALOG_URL.to_string());
    parse_document(&raw, &source)
}

#[tauri::command]
pub async fn catalog_load(app: AppHandle) -> Result<CatalogResponse, String> {
    match load_cached_document(&app) {
        Ok(document) => Ok(response_from_document(
            document,
            true,
            Some("Показана последняя сохранённая копия. Обнови каталог для свежих данных.".into()),
        )),
        Err(_) => catalog_refresh(app).await,
    }
}

#[tauri::command]
pub async fn catalog_refresh(app: AppHandle) -> Result<CatalogResponse, String> {
    const FALLBACK_URL: &str = "https://freedomchecker.ateo.digital/api/snapshot.json";
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(25))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent("TGU-Desktop/0.4")
        .build()
        .map_err(|e| format!("Create catalog HTTP client: {e}"))?;

    let mut sources = Vec::new();
    if let Some(base) = community_api_base_url() {
        sources.push(format!("{base}/api/tgu/catalog"));
    }
    sources.push(CATALOG_URL.to_string());
    sources.push(FALLBACK_URL.to_string());

    let mut failures = Vec::new();
    for source in sources {
        let attempt = async {
            let response = client.get(&source).send().await
                .map_err(|e| format!("Catalog request failed: {e}"))?;
            if response.status().is_redirection() {
                return Err("Catalog redirects were rejected to keep source hosts pinned.".to_string());
            }
            let response = response.error_for_status()
                .map_err(|e| format!("Catalog HTTP response: {e}"))?;
            let expected_host = url::Url::parse(&source).ok()
                .and_then(|url| url.host_str().map(str::to_string))
                .unwrap_or_default();
            if response.url().host_str().unwrap_or_default() != expected_host {
                return Err("Catalog response host did not match the configured source.".to_string());
            }
            let raw = response.text().await
                .map_err(|e| format!("Read catalog response: {e}"))?;
            let document = parse_document(&raw, &source)?;
            let path = cache_path(&app)?;
            fs::write(&path, raw).map_err(|e| format!("Save catalog cache: {e}"))?;
            fs::write(path.with_file_name("proxy-catalog-source.txt"), &source)
                .map_err(|e| format!("Save catalog source: {e}"))?;
            Ok::<CatalogDocument, String>(document)
        }.await;

        match attempt {
            Ok(document) => return Ok(response_from_document(document, false, None)),
            Err(error) => failures.push(format!("{source}: {error}")),
        }
    }

    let error = failures.join("; ");
    match load_cached_document(&app) {
        Ok(document) => Ok(response_from_document(
            document,
            true,
            Some(format!("{error} Используется последний успешный кэш.")),
        )),
        Err(cache_error) => Err(format!("{error}; {cache_error}")),
    }
}

pub(crate) fn find_record(app: &AppHandle, proxy_id: &str) -> Result<CatalogRecord, String> {
    let document = load_cached_document(app)?;
    document
        .proxies
        .into_iter()
        .find(|record| record.id == proxy_id && is_usable_record(record))
        .ok_or_else(|| "Proxy is not present in the current catalog cache.".to_string())
}

#[tauri::command]
pub async fn engine_select_upstream(
    app: AppHandle,
    state: State<'_, crate::engine::EngineState>,
    proxy_id: String,
) -> Result<ProxyTestResult, String> {
    let record = find_record(&app, &proxy_id)?;
    let (supported, reason) = is_supported_record(&record);
    if !supported {
        return Err(reason.unwrap_or_else(|| "Unsupported proxy protocol.".into()));
    }

    let test_record = record.clone();
    let result = tokio::task::spawn_blocking(move || run_proxy_test(test_record))
        .await
        .map_err(|error| format!("Upstream verification task failed: {error}"))?;
    if !result.success {
        return Err(format!("Upstream не прошёл повторную проверку Telegram DC: {}", result.detail));
    }

    let saved = crate::profiles::save_catalog_profile(&app, &record)?;
    let profile = saved.to_engine_upstream();
    state.select_upstream(profile).await?;
    crate::profiles::set_selected_id(&app, Some(saved.id.clone()))?;
    let _ = crate::profiles::history_append(
        &app,
        &saved.endpoint(),
        &saved.protocol,
        "UPSTREAM_SELECTED",
        true,
        result.elapsed_ms,
        &result.detail,
    );
    Ok(result)
}


type SharedAutoProgress = Arc<Mutex<AutoSelectionProgress>>;

fn auto_endpoint(record: &CatalogRecord) -> String {
    format!("{}:{} · {}", record.server, record.port, record.kind.to_uppercase())
}

async fn publish_auto_progress(app: &AppHandle, progress: &SharedAutoProgress) {
    let snapshot = progress.lock().await.clone();
    let _ = app.emit("auto-selection-progress", snapshot);
}

async fn start_auto_candidate(
    app: &AppHandle,
    progress: &SharedAutoProgress,
    endpoint: &str,
    verification: bool,
) {
    {
        let mut snapshot = progress.lock().await;
        snapshot.current_endpoint = Some(endpoint.to_string());
        snapshot.phase = if verification {
            "Повторно проверяю быстрые серверы".into()
        } else {
            "Проверяю серверы по текущей сети".into()
        };
        snapshot.message = None;
    }
    publish_auto_progress(app, progress).await;
}

async fn finish_auto_candidate(
    app: &AppHandle,
    progress: &SharedAutoProgress,
    endpoint: &str,
    result: &ProxyTestResult,
    verification: bool,
) {
    {
        let mut snapshot = progress.lock().await;
        snapshot.completed = snapshot.completed.saturating_add(1);
        if verification {
                                                                                     
        } else if result.success {
            snapshot.available = snapshot.available.saturating_add(1);
            let is_better = snapshot.best_latency_ms
                .map(|current| result.elapsed_ms < current)
                .unwrap_or(true);
            if is_better {
                snapshot.best_endpoint = Some(endpoint.to_string());
                snapshot.best_latency_ms = Some(result.elapsed_ms);
            }
        }
        snapshot.elapsed_ms = snapshot.started_at.elapsed().as_millis() as u64;
    }
    publish_auto_progress(app, progress).await;
}

async fn set_auto_progress(
    app: &AppHandle,
    progress: &SharedAutoProgress,
    phase: &str,
    is_running: bool,
    message: Option<String>,
) {
    {
        let mut snapshot = progress.lock().await;
        snapshot.phase = phase.to_string();
        snapshot.is_running = is_running;
        snapshot.current_endpoint = None;
        snapshot.message = message;
        snapshot.elapsed_ms = snapshot.started_at.elapsed().as_millis() as u64;
    }
    publish_auto_progress(app, progress).await;
}

async fn probe_all_catalog_candidates(
    app: AppHandle,
    records: Vec<CatalogRecord>,
    progress: SharedAutoProgress,
) -> Result<(Vec<(CatalogRecord, u64)>, Vec<CommunityFeedbackReport>), String> {
    let semaphore = Arc::new(Semaphore::new(AUTO_PARALLELISM));
    let mut probes = FuturesUnordered::new();

    for record in records {
        let semaphore = semaphore.clone();
        let app = app.clone();
        let progress = progress.clone();
        probes.push(async move {
            let permit = semaphore.acquire_owned().await
                .map_err(|error| format!("Auto-selector semaphore failed: {error}"))?;

            let endpoint = auto_endpoint(&record);
            start_auto_candidate(&app, &progress, &endpoint, false).await;

            let probe_record = record.clone();
            let joined = tokio::task::spawn_blocking(move || {
                run_proxy_test_with_timeouts(probe_record, AUTO_CONNECT_TIMEOUT, AUTO_IO_TIMEOUT)
            })
                .await
                .map_err(|error| format!("Proxy probe task failed: {error}"));
            drop(permit);

            let result = joined?;
            finish_auto_candidate(&app, &progress, &endpoint, &result, false).await;
            let report = community_feedback_report(&record, &result);
            Ok::<_, String>((record, result, report))
        });
    }

    let mut available = Vec::new();
    let mut community_reports = Vec::new();
    while let Some(result) = probes.next().await {
        let (record, probe, report) = result?;
        if let Some(report) = report {
            community_reports.push(report);
        }
        if probe.success {
            available.push((record, probe.elapsed_ms));
        }
    }
    Ok((available, community_reports))
}

async fn verify_auto_finalists(
    app: AppHandle,
    candidates: Vec<(CatalogRecord, u64)>,
    progress: SharedAutoProgress,
) -> Result<Vec<(CatalogRecord, u64)>, String> {
    let finalist_count = candidates.len();
    {
        let mut snapshot = progress.lock().await;
        snapshot.phase = "Повторно проверяю до пяти лучших кандидатов".into();
        snapshot.total = snapshot.completed.saturating_add(finalist_count * AUTO_VERIFY_REPEATS);
        snapshot.current_endpoint = None;
        snapshot.finalists_verified = 0;
        snapshot.message = None;
    }
    publish_auto_progress(&app, &progress).await;

    let mut verifications = FuturesUnordered::new();
    for (record, first_latency) in candidates {
        let app = app.clone();
        let progress = progress.clone();
        verifications.push(async move {
            let endpoint = auto_endpoint(&record);
            let mut timings = vec![first_latency];
            let mut successful_checks = 1usize;

            for _ in 0..AUTO_VERIFY_REPEATS {
                start_auto_candidate(&app, &progress, &endpoint, true).await;
                let probe_record = record.clone();
                let joined = tokio::task::spawn_blocking(move || {
                    run_proxy_test_with_timeouts(probe_record, VERIFY_CONNECT_TIMEOUT, VERIFY_IO_TIMEOUT)
                })
                    .await
                    .map_err(|error| format!("Finalist verification task failed: {error}"))?;
                finish_auto_candidate(&app, &progress, &endpoint, &joined, true).await;
                if joined.success {
                    successful_checks += 1;
                    timings.push(joined.elapsed_ms);
                }
            }

            if successful_checks < 2 {
                return Ok::<_, String>(None);
            }

            {
                let mut snapshot = progress.lock().await;
                snapshot.finalists_verified = snapshot.finalists_verified.saturating_add(1);
            }
            publish_auto_progress(&app, &progress).await;

            timings.sort_unstable();
            let median = timings.get(timings.len() / 2).copied().unwrap_or(u64::MAX);
            Ok(Some((record, median)))
        });
    }

    let mut verified = Vec::new();
    while let Some(result) = verifications.next().await {
        if let Some(candidate) = result? {
            verified.push(candidate);
        }
    }
    verified.sort_by_key(|(_, latency)| *latency);
    Ok(verified)
}

#[tauri::command]
pub async fn engine_auto_select(
    app: AppHandle,
    state: State<'_, crate::engine::EngineState>,
) -> Result<AutoSelectionResult, String> {
    let mut records = cached_records(&app)?;
    records.retain(|record| is_supported_record(record).0);
    records.sort_by(|left, right| {
        right.source_tier.as_deref().unwrap_or("").cmp(left.source_tier.as_deref().unwrap_or(""))
            .then_with(|| right.uptime.unwrap_or(0.0).total_cmp(&left.uptime.unwrap_or(0.0)))
            .then_with(|| left.rtt_ms.unwrap_or(u64::MAX).cmp(&right.rtt_ms.unwrap_or(u64::MAX)))
    });
    let total_candidates = records.len();
    let progress = Arc::new(Mutex::new(AutoSelectionProgress {
        is_running: true,
        phase: "Подготавливаю список поддерживаемых прокси".into(),
        total: total_candidates,
        completed: 0,
        available: 0,
        finalists_verified: 0,
        current_endpoint: None,
        best_endpoint: None,
        best_latency_ms: None,
        elapsed_ms: 0,
        message: None,
        started_at: Instant::now(),
    }));
    publish_auto_progress(&app, &progress).await;

    if records.is_empty() {
        let message = "В локальном кэше нет поддерживаемых MTProto/SOCKS5 узлов. Обнови каталог.".to_string();
        set_auto_progress(&app, &progress, "Подходящие прокси не найдены", false, Some(message.clone())).await;
        return Ok(AutoSelectionResult {
            selected: None,
            total_candidates: 0,
            tested_candidates: 0,
            first_pass_available: 0,
            finalists_verified: 0,
            verified_latency_ms: None,
            message,
            community_feedback: None,
        });
    }

                                                                          
                                                                            
    let (available, community_reports) = match probe_all_catalog_candidates(app.clone(), records, progress.clone()).await {
        Ok(result) => result,
        Err(error) => {
            set_auto_progress(&app, &progress, "Автоподбор завершился ошибкой", false, Some(error.clone())).await;
            return Err(error);
        }
    };

    let feedback_enabled = crate::settings::load(&app)
        .map(|settings| settings.community_feedback_enabled)
        .unwrap_or(true);
    let feedback_status = if feedback_enabled {
        submit_community_feedback(app.clone(), community_reports).await
    } else {
        CommunityFeedbackStatus {
            enabled: false,
            attempted: community_reports.len(),
            accepted: 0,
            rejected: 0,
            http_status: None,
            message: "CN: отправка отключена в настройках.".into(),
        }
    };

    let first_pass_available = available.len();
    let selected: Option<(CatalogRecord, u64)>;

    if available.is_empty() {
        let message = format!(
            "Проверено {total_candidates} из {total_candidates}; доступно 0. Ни один узел не прошёл проверку Telegram DC2 из этой сети."
        );
        set_auto_progress(&app, &progress, "Подходящий прокси не найден", false, Some(message.clone())).await;
        return Ok(AutoSelectionResult {
            selected: None,
            total_candidates,
            tested_candidates: total_candidates,
            first_pass_available: 0,
            finalists_verified: 0,
            verified_latency_ms: None,
            message,
            community_feedback: Some(feedback_status.clone()),
        });
    }

    let mut finalists = available;
    finalists.sort_by_key(|(_, latency)| *latency);
    finalists.truncate(AUTO_FINALISTS);

    let verified = match verify_auto_finalists(app.clone(), finalists, progress.clone()).await {
        Ok(verified) => verified,
        Err(error) => {
            set_auto_progress(&app, &progress, "Повторная проверка завершилась ошибкой", false, Some(error.clone())).await;
            return Err(error);
        }
    };
    let verified_count = verified.len();
    selected = verified.into_iter().next();

    let Some((record, latency)) = selected else {
        let tested = progress.lock().await.completed;
        let message = format!(
            "Проверено {tested} из {}; доступно в первом проходе: {first_pass_available}. Лидеры не прошли повторную проверку.",
            progress.lock().await.total
        );
        set_auto_progress(&app, &progress, "Стабильный прокси не найден", false, Some(message.clone())).await;
        return Ok(AutoSelectionResult {
            selected: None,
            total_candidates,
            tested_candidates: tested,
            first_pass_available,
            finalists_verified: verified_count,
            verified_latency_ms: None,
            message,
            community_feedback: Some(feedback_status.clone()),
        });
    };

    let summary = summarize(&record);
    let saved = crate::profiles::save_catalog_profile(&app, &record)?;
    state.select_upstream(saved.to_engine_upstream()).await?;
    crate::profiles::set_selected_id(&app, Some(saved.id.clone()))?;

    let tested = progress.lock().await.completed;
    let message = format!(
        "Проверено {tested} из {}; доступно {}; лучший сервер подтверждён повторно.",
        progress.lock().await.total,
        first_pass_available
    );
    set_auto_progress(&app, &progress, "Лучший сервер найден", false, Some(message.clone())).await;
    let _ = crate::profiles::history_append(
        &app,
        &saved.endpoint(),
        &saved.protocol,
        "AUTO_SELECTED",
        true,
        latency,
        &message,
    );

    Ok(AutoSelectionResult {
        selected: Some(summary),
        total_candidates,
        tested_candidates: tested,
        first_pass_available,
        finalists_verified: verified_count,
        verified_latency_ms: Some(latency),
        message,
        community_feedback: Some(feedback_status.clone()),
    })
}

#[tauri::command]
pub async fn proxy_test(app: AppHandle, proxy_id: String) -> Result<ProxyTestResult, String> {
    let record = find_record(&app, &proxy_id)?;
    let endpoint = format!("{}:{}", record.server, record.port);
    let protocol = record.kind.clone();
    let result = tokio::task::spawn_blocking(move || run_proxy_test(record))
        .await
        .map_err(|e| format!("Proxy test task failed: {e}"))?;
    let _ = crate::profiles::history_append(
        &app,
        &endpoint,
        &protocol,
        "MANUAL_CHECK",
        result.success,
        result.elapsed_ms,
        &result.detail,
    );
    Ok(result)
}

#[tauri::command]
pub fn proxy_open_in_telegram(app: AppHandle, proxy_id: String) -> Result<(), String> {
    let record = find_record(&app, &proxy_id)?;
    let (supported, reason) = is_supported_record(&record);
    if !supported {
        return Err(reason.unwrap_or_else(|| "Unsupported proxy protocol.".into()));
    }
    let kind = record.kind.to_ascii_lowercase();
    let mut query = url::form_urlencoded::Serializer::new(String::new());
    query.append_pair("server", &record.server);
    query.append_pair("port", &record.port.to_string());
    let scheme = if kind.contains("socks") {
        if !record.username.is_empty() {
            query.append_pair("user", &record.username);
        }
        if !record.password.is_empty() {
            query.append_pair("pass", &record.password);
        }
        "tg://socks?"
    } else {
        query.append_pair("secret", record.secret.trim());
        "tg://proxy?"
    };
    let url = format!("{scheme}{}", query.finish());
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|e| format!("Could not open Telegram proxy link: {e}"))
}

struct RelayCrypto {
    init_packet: Vec<u8>,
    encryptor: Aes256Ctr,
    decryptor: Aes256Ctr,
}

fn sha256_key(prekey: &[u8], secret: Option<&[u8]>) -> [u8; 32] {
    let mut digest = Sha256::new();
    digest.update(prekey);
    if let Some(secret) = secret {
        digest.update(secret);
    }
    digest.finalize().into()
}

fn make_cipher(key: [u8; 32], iv: [u8; 16]) -> Aes256Ctr {
    Aes256Ctr::new(&key.into(), &iv.into())
}

fn generate_relay_crypto(secret: Option<&[u8]>, padded: bool) -> RelayCrypto {
    let mut rng = rand::thread_rng();
    loop {
        let mut init = [0u8; 64];
        rng.fill_bytes(&mut init);
        if init[0] == 0xEF
            || init[0..4] == *b"HEAD"
            || init[0..4] == *b"POST"
            || init[0..4] == *b"GET "
            || init[0..4] == *b"OPTI"
            || init[0..4] == [0xEE, 0xEE, 0xEE, 0xEE]
            || init[0..4] == [0xDD, 0xDD, 0xDD, 0xDD]
            || init[0..4] == [0x16, 0x03, 0x01, 0x02]
            || init[4..8] == [0, 0, 0, 0]
        {
            continue;
        }

        let prekey: [u8; 32] = init[8..40].try_into().expect("fixed prekey range");
        let iv: [u8; 16] = init[40..56].try_into().expect("fixed IV range");
        let outbound_key = sha256_key(&prekey, secret);
        let mut initial_cipher = make_cipher(outbound_key, iv);
        let mut encrypted_init = init;
        initial_cipher.apply_keystream(&mut encrypted_init);

        let mut tail_plain = [0u8; 8];
        let tag = if padded { 0xDDDD_DDDD_u32 } else { 0xEEEE_EEEE_u32 };
        tail_plain[0..4].copy_from_slice(&tag.to_le_bytes());
        tail_plain[4..6].copy_from_slice(&2_i16.to_le_bytes());
        rng.fill_bytes(&mut tail_plain[6..8]);

        for index in 0..8 {
            let keystream = encrypted_init[56 + index] ^ init[56 + index];
            init[56 + index] = tail_plain[index] ^ keystream;
        }

        let mut encryptor = make_cipher(outbound_key, iv);
        let mut skip = [0u8; 64];
        encryptor.apply_keystream(&mut skip);

        let reversed: Vec<u8> = init[8..56].iter().rev().copied().collect();
        let inbound_key = sha256_key(&reversed[0..32], secret);
        let inbound_iv: [u8; 16] = reversed[32..48].try_into().expect("fixed IV range");
                                                                             
                                                                            
                                                                           
        let decryptor = make_cipher(inbound_key, inbound_iv);

        return RelayCrypto {
            init_packet: init.to_vec(),
            encryptor,
            decryptor,
        };
    }
}

fn send_req_pq(stream: &mut TcpStream, crypto: &mut RelayCrypto, padded: bool) -> Result<[u8; 16], String> {
    let mut rng = rand::thread_rng();
    let mut nonce = [0u8; 16];
    rng.fill_bytes(&mut nonce);

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let message_id = ((now.as_secs() << 32)
        | ((u64::from(now.subsec_millis()) * 4_294_967) / 1_000))
        & !3_u64;

    let mut request = [0u8; 40];
    request[8..16].copy_from_slice(&message_id.to_le_bytes());
    request[16..20].copy_from_slice(&20_u32.to_le_bytes());
    request[20..24].copy_from_slice(&REQ_PQ_MULTI_CONSTRUCTOR.to_le_bytes());
    request[24..40].copy_from_slice(&nonce);

    let padding_len = if padded { (rng.next_u32() % 16) as usize } else { 0 };
    let mut envelope = vec![0u8; 4 + request.len() + padding_len];
    let payload_len = (request.len() + padding_len) as u32;
    envelope[0..4].copy_from_slice(&payload_len.to_le_bytes());
    envelope[4..44].copy_from_slice(&request);
    if padding_len > 0 {
        rng.fill_bytes(&mut envelope[44..]);
    }
    crypto.encryptor.apply_keystream(&mut envelope);

    stream
        .write_all(&crypto.init_packet)
        .and_then(|_| stream.write_all(&envelope))
        .map_err(|e| format!("Send MTProto probe: {e}"))?;
    stream.flush().map_err(|e| format!("Flush MTProto probe: {e}"))?;
    Ok(nonce)
}

fn verify_res_pq(stream: &mut TcpStream, crypto: &mut RelayCrypto, nonce: &[u8; 16]) -> Result<(), String> {
    let mut encrypted_len = [0u8; 4];
    stream
        .read_exact(&mut encrypted_len)
        .map_err(|e| format!("Wait for Telegram DC response: {e}"))?;
    crypto.decryptor.apply_keystream(&mut encrypted_len);
    let frame_len = (u32::from_le_bytes(encrypted_len) & 0x7FFF_FFFF) as usize;
    if !(4..=4096).contains(&frame_len) {
        return Err(format!("Telegram DC returned an invalid frame length: {frame_len}."));
    }

    let mut encrypted_body = vec![0u8; frame_len];
    stream
        .read_exact(&mut encrypted_body)
        .map_err(|e| format!("Read Telegram DC response: {e}"))?;
    crypto.decryptor.apply_keystream(&mut encrypted_body);

    if frame_len == 4 {
        let transport_error = u32::from_le_bytes(encrypted_body[..4].try_into().unwrap());
        return Err(format!("Telegram transport returned error {transport_error}."));
    }
    if encrypted_body.len() < 40 || encrypted_body[0..8] != [0; 8] {
        return Err("Telegram DC did not return an unauthenticated resPQ packet.".into());
    }
    let body_len = u32::from_le_bytes(encrypted_body[16..20].try_into().unwrap()) as usize;
    if body_len < 20 || body_len > encrypted_body.len().saturating_sub(20) {
        return Err("Telegram DC response body length is invalid.".into());
    }
    let constructor = u32::from_le_bytes(encrypted_body[20..24].try_into().unwrap());
    if constructor != RES_PQ_CONSTRUCTOR {
        return Err(format!("Unexpected Telegram MTProto constructor: 0x{constructor:08x}."));
    }
    if encrypted_body[24..40] != nonce[..] {
        return Err("Telegram DC did not echo the probe nonce.".into());
    }
    Ok(())
}

fn mtproto_secret(value: &str) -> Result<(Vec<u8>, bool), String> {
    let normalized = value.trim().to_ascii_lowercase();
    if normalized.starts_with("ee") {
        return Err("FakeTLS/WEB MTProto is not supported by this transport yet.".into());
    }
    let (hex_secret, padded) = if normalized.starts_with("dd") && normalized.len() == 34 {
        (&normalized[2..], true)
    } else {
        (normalized.as_str(), false)
    };
    if !is_hex_secret(hex_secret) {
        return Err("MTProto secret must be 32 hexadecimal characters, optionally prefixed by dd.".into());
    }
    let bytes = (0..hex_secret.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex_secret[i..i + 2], 16).expect("validated hexadecimal"))
        .collect();
    Ok((bytes, padded))
}

fn connect_host(
    host: &str,
    port: u16,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> io::Result<TcpStream> {
    if !is_public_host(host) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "Proxy host is not a public address."));
    }
    let addresses = (host, port).to_socket_addrs()?;
    let public_addresses: Vec<_> = addresses.filter(|address| is_public_ip(address.ip())).collect();
    if public_addresses.is_empty() {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "Proxy DNS resolved only to non-public addresses."));
    }
    let mut last_error = None;
    for address in public_addresses {
        match TcpStream::connect_timeout(&address, connect_timeout) {
            Ok(stream) => {
                stream.set_nodelay(true)?;
                stream.set_read_timeout(Some(io_timeout))?;
                stream.set_write_timeout(Some(io_timeout))?;
                return Ok(stream);
            }
            Err(error) => last_error = Some(error),
        }
    }
    Err(last_error.unwrap_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "No address resolved for proxy host.")))
}

fn test_mtproto(
    record: &CatalogRecord,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> Result<(), String> {
    let (secret, padded) = mtproto_secret(&record.secret)?;
    let mut stream = connect_host(&record.server, record.port, connect_timeout, io_timeout)
        .map_err(|e| format!("MTProxy TCP connection failed: {e}"))?;
    let mut crypto = generate_relay_crypto(Some(&secret), padded);
    let nonce = send_req_pq(&mut stream, &mut crypto, padded)?;
    verify_res_pq(&mut stream, &mut crypto, &nonce)
        .map_err(|e| format!("MTProto Telegram DC verification failed: {e}"))
}

fn socks5_connect(stream: &mut TcpStream, record: &CatalogRecord) -> Result<(), String> {
    let has_credentials = !record.username.is_empty() || !record.password.is_empty();
    if has_credentials {
        if record.username.len() > 255 || record.password.len() > 255 {
            return Err("SOCKS5 username or password exceeds 255 bytes.".into());
        }
        stream
            .write_all(&[5, 2, 0, 2])
            .map_err(|e| format!("SOCKS5 greeting failed: {e}"))?;
    } else {
        stream
            .write_all(&[5, 1, 0])
            .map_err(|e| format!("SOCKS5 greeting failed: {e}"))?;
    }

    let mut greeting = [0u8; 2];
    stream
        .read_exact(&mut greeting)
        .map_err(|e| format!("SOCKS5 method negotiation failed: {e}"))?;
    if greeting[0] != 5 {
        return Err("SOCKS5 upstream returned an invalid protocol version.".into());
    }
    match greeting[1] {
        0 => {}
        2 if has_credentials => {
            stream
                .write_all(&[1, record.username.len() as u8])
                .and_then(|_| stream.write_all(record.username.as_bytes()))
                .and_then(|_| stream.write_all(&[record.password.len() as u8]))
                .and_then(|_| stream.write_all(record.password.as_bytes()))
                .map_err(|e| format!("SOCKS5 authentication request failed: {e}"))?;
            let mut auth = [0u8; 2];
            stream
                .read_exact(&mut auth)
                .map_err(|e| format!("SOCKS5 authentication response failed: {e}"))?;
            if auth != [1, 0] {
                return Err("SOCKS5 upstream rejected username/password authentication.".into());
            }
        }
        2 => return Err("SOCKS5 upstream requires credentials that are not in the catalog.".into()),
        0xFF => return Err("SOCKS5 upstream rejected the offered authentication methods.".into()),
        _ => return Err("SOCKS5 upstream selected an unsupported authentication method.".into()),
    }

    let ip = TELEGRAM_DC2
        .parse::<std::net::Ipv4Addr>()
        .expect("constant IPv4 address");
    let mut request = vec![5, 1, 0, 1];
    request.extend_from_slice(&ip.octets());
    request.extend_from_slice(&TELEGRAM_PORT.to_be_bytes());
    stream
        .write_all(&request)
        .map_err(|e| format!("SOCKS5 CONNECT request failed: {e}"))?;

    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .map_err(|e| format!("SOCKS5 CONNECT response failed: {e}"))?;
    if header[0] != 5 {
        return Err("SOCKS5 CONNECT response has an invalid version.".into());
    }
    let address_len = match header[3] {
        1 => 4,
        3 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).map_err(|e| e.to_string())?;
            len[0] as usize
        }
        4 => 16,
        _ => return Err("SOCKS5 CONNECT response has an unknown address type.".into()),
    };
    let mut address_and_port = vec![0u8; address_len + 2];
    stream
        .read_exact(&mut address_and_port)
        .map_err(|e| format!("SOCKS5 CONNECT response address failed: {e}"))?;
    if header[1] != 0 {
        return Err(format!("SOCKS5 CONNECT to Telegram DC was rejected (reply code {}).", header[1]));
    }
    Ok(())
}

fn test_socks5(
    record: &CatalogRecord,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> Result<(), String> {
    let mut stream = connect_host(&record.server, record.port, connect_timeout, io_timeout)
        .map_err(|e| format!("SOCKS5 TCP connection failed: {e}"))?;
    socks5_connect(&mut stream, record)?;
    let mut crypto = generate_relay_crypto(None, false);
    let nonce = send_req_pq(&mut stream, &mut crypto, false)?;
    verify_res_pq(&mut stream, &mut crypto, &nonce)
        .map_err(|e| format!("Telegram DC through SOCKS5 failed: {e}"))
}

pub(crate) fn run_proxy_test(record: CatalogRecord) -> ProxyTestResult {
    run_proxy_test_with_timeouts(record, CONNECT_TIMEOUT, IO_TIMEOUT)
}

fn run_proxy_test_with_timeouts(
    record: CatalogRecord,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> ProxyTestResult {
    let started = Instant::now();
    let (supported, reason) = is_supported_record(&record);
    let (stage, result) = if !supported {
        ("Protocol support".to_string(), Err(reason.unwrap_or_else(|| "Unsupported proxy.".into())))
    } else {
        match record.kind.to_ascii_lowercase().as_str() {
            "mtproto" | "mt-proto" => (
                "MTProto + Telegram DC".into(),
                test_mtproto(&record, connect_timeout, io_timeout),
            ),
            "socks5" | "socks" => (
                "SOCKS5 + Telegram DC".into(),
                test_socks5(&record, connect_timeout, io_timeout),
            ),
            _ => ("Protocol support".into(), Err("Unsupported proxy protocol.".into())),
        }
    };
    match result {
        Ok(()) => ProxyTestResult {
            success: true,
            stage,
            detail: "Telegram DC2 accepted the protocol handshake and returned a matching resPQ response. This verifies the proxy route, not a Telegram account session.".into(),
            elapsed_ms: started.elapsed().as_millis() as u64,
        },
        Err(detail) => ProxyTestResult {
            success: false,
            stage,
            detail,
            elapsed_ms: started.elapsed().as_millis() as u64,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_private_and_local_catalog_proxy_destinations() {
        assert!(!is_public_host("127.0.0.1"));
        assert!(!is_public_host("10.0.0.12"));
        assert!(!is_public_host("192.168.1.20"));
        assert!(!is_public_host("printer.local"));
        assert!(!is_public_host("service.internal"));
        assert!(is_public_host("208.87.242.223"));
        assert!(is_public_host("proxy.example.org"));
    }

    #[test]
    fn accepts_classic_and_dd_mtproto_secrets_but_rejects_fake_tls() {
        assert!(mtproto_secret("0123456789abcdef0123456789abcdef").is_ok());
        assert!(mtproto_secret("dd0123456789abcdef0123456789abcdef").unwrap().1);
        assert!(mtproto_secret("ee0123456789abcdef0123456789abcdef").is_err());
    }

    #[test]
    fn relay_response_cipher_starts_at_stream_offset_zero_like_android() {
        let secret = [0x37u8; 16];
        let relay = generate_relay_crypto(Some(&secret), false);

        let reversed: Vec<u8> = relay.init_packet[8..56].iter().rev().copied().collect();
        let response_key = sha256_key(&reversed[0..32], Some(&secret));
        let response_iv: [u8; 16] = reversed[32..48].try_into().unwrap();

        let expected = b"Telegram resPQ frame starts at incoming stream offset zero.";
        let mut encrypted = expected.to_vec();
        let mut server_cipher = make_cipher(response_key, response_iv);
        server_cipher.apply_keystream(&mut encrypted);

        let mut actual = encrypted;
        let mut relay_decryptor = relay.decryptor;
        relay_decryptor.apply_keystream(&mut actual);
        assert_eq!(actual, expected);
    }

    #[test]
    fn constructs_only_telegram_proxy_schemes() {
        let mut record = CatalogRecord {
            id: "test".into(),
            kind: "MTProto".into(),
            protocol: None,
            server: "proxy.example.org".into(),
            port: 443,
            secret: "0123456789abcdef0123456789abcdef".into(),
            username: String::new(),
            password: String::new(),
            enabled: true,
            country: None,
            rtt_ms: None,
            uptime: None,
            checks: None,
            source_tier: None,
            last_check: None,
            stale: false,
            probe_status: Some("working".into()),
            community_reports: 0,
            community_unique_reporters: 0,
            community_success_rate: None,
            community_median_rtt_ms: None,
        };
        let (supported, _) = is_supported_record(&record);
        assert!(supported);
        record.kind = "SOCKS5".into();
        let (supported, _) = is_supported_record(&record);
        assert!(supported);
    }
}


pub(crate) fn cached_records(app: &AppHandle) -> Result<Vec<CatalogRecord>, String> {
    let document = load_cached_document(app)?;
    Ok(document.proxies.into_iter().filter(is_usable_record).collect())
}

pub(crate) fn record_summary(record: &CatalogRecord) -> ProxySummary {
    summarize(record)
}
