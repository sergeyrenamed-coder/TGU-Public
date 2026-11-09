use aes::Aes256;
use ctr::cipher::{KeyIvInit, StreamCipher};
use futures_util::{stream::FuturesUnordered, SinkExt, StreamExt};
use rand::RngCore;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        Arc, RwLock,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
    collections::{HashMap, HashSet},
};
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    task::{JoinHandle, JoinSet},
    time::timeout,
};
use tokio_tungstenite::{
    client_async_tls_with_config,
    tungstenite::{client::IntoClientRequest, http::header::HeaderValue, Message},
    MaybeTlsStream, WebSocketStream,
};

const LOCAL_HOST: &str = "127.0.0.1";
const LOCAL_PORT: u16 = 1443;
const CREDENTIAL_SERVICE: &str = "com.rnmdteam.tgu.desktop";
const CREDENTIAL_USER: &str = "local-mtproto-secret";
const HANDSHAKE_LEN: usize = 64;
const TAG_ABRIDGED: u32 = 0xEFEFEFEF;
const TAG_INTERMEDIATE: u32 = 0xEEEEEEEE;
const TAG_PADDED_INTERMEDIATE: u32 = 0xDDDDDDDD;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_PACKET_SIZE: usize = 16 * 1024 * 1024;
const FAILOVER_PARALLELISM: usize = 24;
const FAILOVER_FINALISTS: usize = 5;
const FAILOVER_REPEAT_COUNT: usize = 2;
const AUTO_OPTIMIZE_INTERVAL: Duration = Duration::from_secs(30 * 60);
const RES_PQ_CONSTRUCTOR: u32 = 0x05162463;
type AesCtr = ctr::Ctr128BE<Aes256>;
type WsStream = WebSocketStream<MaybeTlsStream<TcpStream>>;

#[derive(Debug, Clone)]
pub(crate) struct EngineUpstream {
    pub(crate) id: String,
    pub(crate) kind: String,
    pub(crate) host: String,
    pub(crate) port: u16,
    pub(crate) secret: String,
    pub(crate) username: String,
    pub(crate) password: String,
}

impl EngineUpstream {
    fn is_mtproto(&self) -> bool {
        self.kind.eq_ignore_ascii_case("mtproto")
            || self.kind.eq_ignore_ascii_case("mt-proto")
    }

    fn is_socks5(&self) -> bool {
        self.kind.eq_ignore_ascii_case("socks5") || self.kind.eq_ignore_ascii_case("socks")
    }

    fn label(&self) -> String {
        format!("{} · {}:{}", self.kind.to_uppercase(), self.host, self.port)
    }
}

#[derive(Debug, Serialize, Clone)]
pub struct EngineStatus {
    pub engine_ready: bool,
    pub upstream_healthy: bool,
    pub telegram_connected: bool,
    pub endpoint: String,
    pub selected_upstream: Option<String>,
    pub current_route: String,
    pub active_connections: usize,
    pub last_error: Option<String>,
    pub connections_total: u64,
    pub websocket_connections: u64,
    pub cloudflare_connections: u64,
    pub tcp_fallback_connections: u64,
    pub failed_connections: u64,
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub uptime_seconds: u64,
    pub last_check_epoch_ms: Option<u64>,
    pub last_check_latency_ms: Option<u64>,
    pub failure_streak: usize,
    pub switch_count: u64,
}

#[derive(Default)]
struct EngineMetrics {
    running: AtomicBool,
    upstream_healthy: AtomicBool,
    telegram_connected: AtomicBool,
    active_connections: AtomicUsize,
    connections_total: AtomicU64,
    websocket_connections: AtomicU64,
    cloudflare_connections: AtomicU64,
    tcp_fallback_connections: AtomicU64,
    failed_connections: AtomicU64,
    bytes_up: AtomicU64,
    bytes_down: AtomicU64,
    started_at_epoch_ms: AtomicU64,
    last_check_epoch_ms: AtomicU64,
    last_check_latency_ms: AtomicU64,
    failure_streak: AtomicUsize,
    switch_count: AtomicU64,
    current_route: RwLock<String>,
    last_error: RwLock<Option<String>>,
}

fn now_epoch_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

impl EngineMetrics {
    fn reset_for_start(&self) {
        self.upstream_healthy.store(false, Ordering::Relaxed);
        self.telegram_connected.store(false, Ordering::Relaxed);
        self.active_connections.store(0, Ordering::Relaxed);
        self.connections_total.store(0, Ordering::Relaxed);
        self.websocket_connections.store(0, Ordering::Relaxed);
        self.cloudflare_connections.store(0, Ordering::Relaxed);
        self.tcp_fallback_connections.store(0, Ordering::Relaxed);
        self.failed_connections.store(0, Ordering::Relaxed);
        self.bytes_up.store(0, Ordering::Relaxed);
        self.bytes_down.store(0, Ordering::Relaxed);
        self.started_at_epoch_ms.store(now_epoch_ms(), Ordering::Relaxed);
        self.last_check_epoch_ms.store(0, Ordering::Relaxed);
        self.last_check_latency_ms.store(0, Ordering::Relaxed);
        self.failure_streak.store(0, Ordering::Relaxed);
        self.switch_count.store(0, Ordering::Relaxed);
        if let Ok(mut value) = self.current_route.write() {
            *value = "Ожидание Telegram".into();
        }
        if let Ok(mut value) = self.last_error.write() {
            *value = None;
        }
    }

    fn set_route(&self, route: &str) {
        if let Ok(mut value) = self.current_route.write() {
            *value = route.to_string();
        }
        self.upstream_healthy.store(true, Ordering::Release);
        if let Ok(mut value) = self.last_error.write() {
            *value = None;
        }
    }

    fn set_error(&self, message: impl Into<String>) {
        self.upstream_healthy.store(false, Ordering::Release);
        if let Ok(mut value) = self.last_error.write() {
            *value = Some(message.into());
        }
    }

    fn record_route(&self, route: &str) {
        let normalized = route.to_ascii_lowercase();
        if normalized.contains("websocket") {
            self.websocket_connections.fetch_add(1, Ordering::Relaxed);
        }
        if normalized.contains("cloudflare") {
            self.cloudflare_connections.fetch_add(1, Ordering::Relaxed);
        }
        if normalized.contains("direct tcp") {
            self.tcp_fallback_connections.fetch_add(1, Ordering::Relaxed);
        }
    }

    fn record_probe(&self, latency_ms: u64, failure_streak: usize) {
        self.last_check_epoch_ms.store(now_epoch_ms(), Ordering::Relaxed);
        self.last_check_latency_ms.store(latency_ms, Ordering::Relaxed);
        self.failure_streak.store(failure_streak, Ordering::Relaxed);
    }

    fn status(
        &self,
        selected_upstream: Option<String>,
        secret_ready: bool,
    ) -> EngineStatus {
        let started = self.started_at_epoch_ms.load(Ordering::Relaxed);
        let uptime = if started == 0 { 0 } else { now_epoch_ms().saturating_sub(started) / 1000 };
        let last_check = self.last_check_epoch_ms.load(Ordering::Relaxed);
        let last_latency = self.last_check_latency_ms.load(Ordering::Relaxed);
        EngineStatus {
            engine_ready: self.running.load(Ordering::Acquire) && secret_ready,
            upstream_healthy: self.upstream_healthy.load(Ordering::Acquire),
            telegram_connected: self.telegram_connected.load(Ordering::Acquire),
            endpoint: format!("{LOCAL_HOST}:{LOCAL_PORT}"),
            selected_upstream,
            current_route: self.current_route.read().map(|v| v.clone()).unwrap_or_default(),
            active_connections: self.active_connections.load(Ordering::Relaxed),
            last_error: self.last_error.read().ok().and_then(|v| v.clone()),
            connections_total: self.connections_total.load(Ordering::Relaxed),
            websocket_connections: self.websocket_connections.load(Ordering::Relaxed),
            cloudflare_connections: self.cloudflare_connections.load(Ordering::Relaxed),
            tcp_fallback_connections: self.tcp_fallback_connections.load(Ordering::Relaxed),
            failed_connections: self.failed_connections.load(Ordering::Relaxed),
            bytes_up: self.bytes_up.load(Ordering::Relaxed),
            bytes_down: self.bytes_down.load(Ordering::Relaxed),
            uptime_seconds: uptime,
            last_check_epoch_ms: if last_check == 0 { None } else { Some(last_check) },
            last_check_latency_ms: if last_check == 0 { None } else { Some(last_latency) },
            failure_streak: self.failure_streak.load(Ordering::Relaxed),
            switch_count: self.switch_count.load(Ordering::Relaxed),
        }
    }
}

#[derive(Default)]
struct EngineRuntime {
    listener_task: Option<JoinHandle<()>>,
    local_secret: Option<String>,
    selected_upstream: Arc<RwLock<Option<EngineUpstream>>>,
}

#[derive(Default)]
pub struct EngineState {
    runtime: tokio::sync::Mutex<EngineRuntime>,
    metrics: Arc<EngineMetrics>,
}

impl EngineState {
    pub(crate) async fn select_upstream(&self, profile: EngineUpstream) -> Result<(), String> {
        let mut runtime = self.runtime.lock().await;
        if self.metrics.running.load(Ordering::Acquire) {
            return Err("Сначала останови локальный движок, затем меняй upstream.".into());
        }
        *runtime.selected_upstream.write()
            .map_err(|_| "Upstream selection lock is poisoned.".to_string())? = Some(profile);
        Ok(())
    }

    pub async fn clear_upstream(&self) -> Result<(), String> {
        let mut runtime = self.runtime.lock().await;
        if self.metrics.running.load(Ordering::Acquire) {
            return Err("Сначала останови локальный движок.".into());
        }
        *runtime.selected_upstream.write()
            .map_err(|_| "Upstream selection lock is poisoned.".to_string())? = None;
        Ok(())
    }

    pub async fn status(&self) -> EngineStatus {
        let runtime = self.runtime.lock().await;
        let selected = runtime.selected_upstream.read()
            .ok()
            .and_then(|value| value.as_ref().map(EngineUpstream::label));
        self.metrics.status(selected, runtime.local_secret.is_some())
    }

    pub async fn start(&self, app: AppHandle) -> Result<EngineStatus, String> {
        let mut runtime = self.runtime.lock().await;
        if self.metrics.running.load(Ordering::Acquire) {
            let selected = runtime.selected_upstream.read()
                .ok()
                .and_then(|value| value.as_ref().map(EngineUpstream::label));
            return Ok(self.metrics.status(selected, runtime.local_secret.is_some()));
        }

        let has_selected = runtime.selected_upstream.read()
            .map(|value| value.is_some())
            .unwrap_or(false);
        if !has_selected {
            if let Some(saved) = crate::profiles::selected_profile(&app)? {
                *runtime.selected_upstream.write()
                    .map_err(|_| "Upstream selection lock is poisoned.".to_string())? =
                    Some(saved.to_engine_upstream());
            }
        }

        let listener = TcpListener::bind((LOCAL_HOST, LOCAL_PORT))
            .await
            .map_err(|error| {
                if error.kind() == std::io::ErrorKind::AddrInUse {
                    format!("Порт {LOCAL_PORT} уже занят. Закрой другую программу, использующую этот порт.")
                } else {
                    format!("Не удалось открыть локальный MTProto endpoint: {error}")
                }
            })?;

        let secret = match runtime.local_secret.clone() {
            Some(secret) => secret,
            None => {
                let secret = load_or_create_local_secret()?;
                runtime.local_secret = Some(secret.clone());
                secret
            }
        };
        let upstream = runtime.selected_upstream.clone();
        let secret_bytes = decode_hex(&secret).map_err(|error| format!("Local secret error: {error}"))?;
        self.metrics.reset_for_start();
        self.metrics.running.store(true, Ordering::Release);

        let metrics = self.metrics.clone();
        let task = tokio::spawn(async move {
            run_listener(listener, secret_bytes, upstream, metrics.clone(), app).await;
            metrics.running.store(false, Ordering::Release);
        });
        runtime.listener_task = Some(task);

        let selected = runtime.selected_upstream.read()
            .ok()
            .and_then(|value| value.as_ref().map(EngineUpstream::label));
        Ok(self.metrics.status(selected, true))
    }

    pub async fn stop(&self) -> EngineStatus {
        let mut runtime = self.runtime.lock().await;
        if let Some(task) = runtime.listener_task.take() {
            task.abort();
            let _ = task.await;
        }
        self.metrics.running.store(false, Ordering::Release);
        self.metrics.upstream_healthy.store(false, Ordering::Release);
        self.metrics.telegram_connected.store(false, Ordering::Release);
        self.metrics.active_connections.store(0, Ordering::Relaxed);
        if let Ok(mut route) = self.metrics.current_route.write() {
            *route = "Движок остановлен".into();
        }
        let selected = runtime.selected_upstream.read()
            .ok()
            .and_then(|value| value.as_ref().map(EngineUpstream::label));
        self.metrics.status(selected, runtime.local_secret.is_some())
    }

    pub async fn open_in_telegram(&self, app: &AppHandle) -> Result<(), String> {
        let runtime = self.runtime.lock().await;
        if !self.metrics.running.load(Ordering::Acquire) {
            return Err("Сначала запусти локальный движок TGU.".into());
        }
        let secret = runtime.local_secret.as_deref()
            .ok_or_else(|| "Локальный секрет ещё не создан.".to_string())?;
        open_local_proxy_in_telegram(app, secret)?;
        let fingerprint = local_proxy_link_fingerprint(secret);
        drop(runtime);

        let mut settings = crate::settings::load(app)?;
        settings.telegram_proxy_link_fingerprint = Some(fingerprint);
        crate::settings::save(app, &settings)
    }

    pub async fn open_in_telegram_if_needed(&self, app: &AppHandle) -> Result<bool, String> {
        let runtime = self.runtime.lock().await;
        if !self.metrics.running.load(Ordering::Acquire) {
            return Err("Сначала запусти локальный движок TGU.".into());
        }
        let secret = runtime.local_secret.as_deref()
            .ok_or_else(|| "Локальный секрет ещё не создан.".to_string())?;
        let fingerprint = local_proxy_link_fingerprint(secret);

        let mut settings = crate::settings::load(app)?;
        if settings.telegram_proxy_link_fingerprint.as_deref() == Some(fingerprint.as_str()) {
            return Ok(false);
        }

        open_local_proxy_in_telegram(app, secret)?;
        settings.telegram_proxy_link_fingerprint = Some(fingerprint);
        crate::settings::save(app, &settings)?;
        Ok(true)
    }
}

#[tauri::command]
pub async fn engine_status(state: State<'_, EngineState>) -> Result<EngineStatus, String> {
    Ok(state.status().await)
}

#[tauri::command]
pub async fn engine_start(
    app: AppHandle,
    state: State<'_, EngineState>,
) -> Result<EngineStatus, String> {
    state.start(app).await
}

#[tauri::command]
pub async fn engine_stop(state: State<'_, EngineState>) -> Result<EngineStatus, String> {
    Ok(state.stop().await)
}

#[tauri::command]
pub async fn engine_clear_upstream(
    app: AppHandle,
    state: State<'_, EngineState>,
) -> Result<(), String> {
    state.clear_upstream().await?;
    crate::profiles::set_selected_id(&app, None)
}

#[tauri::command]
pub async fn engine_open_in_telegram(
    app: AppHandle,
    state: State<'_, EngineState>,
) -> Result<(), String> {
    state.open_in_telegram(&app).await
}

#[tauri::command]
pub async fn engine_open_in_telegram_if_needed(
    app: AppHandle,
    state: State<'_, EngineState>,
) -> Result<bool, String> {
    state.open_in_telegram_if_needed(&app).await
}

fn local_proxy_link_fingerprint(secret: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(format!("{LOCAL_HOST}:{LOCAL_PORT}:{secret}").as_bytes());
    format!("{:x}", digest.finalize())
}

fn open_local_proxy_in_telegram(app: &AppHandle, secret: &str) -> Result<(), String> {
    let url = format!(
        "tg://proxy?server={LOCAL_HOST}&port={LOCAL_PORT}&secret=dd{secret}"
    );
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|error| format!("Не удалось передать локальный прокси в Telegram Desktop: {error}"))
}

fn generate_local_secret() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn load_or_create_local_secret() -> Result<String, String> {
    let entry = keyring::Entry::new(CREDENTIAL_SERVICE, CREDENTIAL_USER)
        .map_err(|error| format!("Windows Credential Manager is unavailable: {error}"))?;

    if let Ok(existing) = entry.get_password() {
        let valid = existing.len() == 32
            && existing.bytes().all(|byte| byte.is_ascii_hexdigit());
        if valid {
            return Ok(existing);
        }
    }

    let generated = generate_local_secret();
    entry
        .set_password(&generated)
        .map_err(|error| format!("Could not persist the local MTProto secret in Windows Credential Manager: {error}"))?;
    Ok(generated)
}

fn decode_hex(value: &str) -> Result<Vec<u8>, String> {
    if value.len() % 2 != 0 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("Expected an even-length hexadecimal value.".into());
    }
    (0..value.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&value[index..index + 2], 16).map_err(|e| e.to_string()))
        .collect()
}

fn sha256_key(prekey: &[u8], secret: &[u8]) -> [u8; 32] {
    let mut digest = Sha256::new();
    digest.update(prekey);
    digest.update(secret);
    digest.finalize().into()
}

fn make_cipher(key: [u8; 32], iv: [u8; 16]) -> AesCtr {
    AesCtr::new(&key.into(), &iv.into())
}

struct ClientCrypto {
    proto_tag: u32,
    dc_index: i16,
    decryptor: AesCtr,
    encryptor: AesCtr,
}

fn parse_client_handshake(init: &[u8; HANDSHAKE_LEN], secret: &[u8]) -> Result<ClientCrypto, String> {
    let prekey: [u8; 32] = init[8..40].try_into().map_err(|_| "Invalid prekey length")?;
    let iv: [u8; 16] = init[40..56].try_into().map_err(|_| "Invalid IV length")?;
    let key = sha256_key(&prekey, secret);
    let mut cipher = make_cipher(key, iv);
    let mut decrypted = *init;
    cipher.apply_keystream(&mut decrypted);

    let proto_tag = u32::from_le_bytes(decrypted[56..60].try_into().map_err(|_| "Invalid protocol tag")?);
    if !matches!(proto_tag, TAG_ABRIDGED | TAG_INTERMEDIATE | TAG_PADDED_INTERMEDIATE) {
        return Err("Telegram sent an unsupported MTProto transport tag.".into());
    }
    let dc_index = i16::from_le_bytes(decrypted[60..62].try_into().map_err(|_| "Invalid DC index")?);
    let abs_dc = dc_index.unsigned_abs() as i32;
    if !matches!(abs_dc, 1 | 2 | 3 | 4 | 5 | 203) {
        return Err(format!("Unsupported Telegram DC index: {dc_index}."));
    }

    let mut client_decryptor = make_cipher(key, iv);
    let mut skip = [0u8; HANDSHAKE_LEN];
    client_decryptor.apply_keystream(&mut skip);

    let reversed: Vec<u8> = init[8..56].iter().rev().copied().collect();
    let reverse_prekey: [u8; 32] = reversed[0..32].try_into().map_err(|_| "Invalid reverse key")?;
    let reverse_iv: [u8; 16] = reversed[32..48].try_into().map_err(|_| "Invalid reverse IV")?;
    let client_encryptor = make_cipher(sha256_key(&reverse_prekey, secret), reverse_iv);

    Ok(ClientCrypto { proto_tag, dc_index, decryptor: client_decryptor, encryptor: client_encryptor })
}

struct RelayCrypto {
    init_packet: Vec<u8>,
    encryptor: AesCtr,
    decryptor: AesCtr,
}

fn generate_relay_crypto(proto_tag: u32, dc_index: i16, secret: Option<&[u8]>) -> RelayCrypto {
    let mut rng = rand::thread_rng();
    loop {
        let mut init = [0u8; HANDSHAKE_LEN];
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

        let prekey: [u8; 32] = init[8..40].try_into().expect("fixed prekey");
        let iv: [u8; 16] = init[40..56].try_into().expect("fixed IV");
        let outbound_key = secret.map(|value| sha256_key(&prekey, value)).unwrap_or(prekey);
        let mut initial_cipher = make_cipher(outbound_key, iv);
        let mut encrypted_init = init;
        initial_cipher.apply_keystream(&mut encrypted_init);

        let mut tail = [0u8; 8];
        tail[0..4].copy_from_slice(&proto_tag.to_le_bytes());
        tail[4..6].copy_from_slice(&dc_index.to_le_bytes());
        rng.fill_bytes(&mut tail[6..8]);
        for index in 0..8 {
            let keystream = encrypted_init[56 + index] ^ init[56 + index];
            init[56 + index] = tail[index] ^ keystream;
        }

        let mut encryptor = make_cipher(outbound_key, iv);
        let mut outbound_skip = [0u8; HANDSHAKE_LEN];
        encryptor.apply_keystream(&mut outbound_skip);

        let reversed: Vec<u8> = init[8..56].iter().rev().copied().collect();
        let reverse_prekey: [u8; 32] = reversed[0..32].try_into().expect("fixed reverse key");
        let reverse_iv: [u8; 16] = reversed[32..48].try_into().expect("fixed reverse IV");
        let inbound_key = secret.map(|value| sha256_key(&reverse_prekey, value)).unwrap_or(reverse_prekey);
        let decryptor = make_cipher(inbound_key, reverse_iv);

        return RelayCrypto { init_packet: init.to_vec(), encryptor, decryptor };
    }
}

struct MtProtoSplitter {
    proto_tag: u32,
    encrypted: Vec<u8>,
    plaintext: Vec<u8>,
    disabled: bool,
}

impl MtProtoSplitter {
    fn new(proto_tag: u32) -> Self {
        Self { proto_tag, encrypted: Vec::new(), plaintext: Vec::new(), disabled: false }
    }

    fn push(&mut self, encrypted: &[u8], plaintext: &[u8]) -> Result<Vec<Vec<u8>>, String> {
        if encrypted.len() != plaintext.len() {
            return Err("MTProto splitter received mismatched encrypted/plain buffers.".into());
        }
        if encrypted.is_empty() {
            return Ok(Vec::new());
        }
        if self.disabled {
            return Ok(vec![encrypted.to_vec()]);
        }
        self.encrypted.extend_from_slice(encrypted);
        self.plaintext.extend_from_slice(plaintext);
        let mut parts = Vec::new();
        let mut offset = 0usize;

        while offset < self.plaintext.len() {
            let remaining = self.plaintext.len() - offset;
            let first = self.packet_length(offset)?;
            let packet_len = match first {
                None => break,
                Some(0) => {
                    parts.push(self.encrypted[offset..].to_vec());
                    self.encrypted.clear();
                    self.plaintext.clear();
                    self.disabled = true;
                    return Ok(parts);
                }
                Some(value) => value,
            };
            if remaining < packet_len {
                break;
            }
            parts.push(self.encrypted[offset..offset + packet_len].to_vec());
            offset += packet_len;
        }

        if offset > 0 {
            self.encrypted.drain(0..offset);
            self.plaintext.drain(0..offset);
        }
        if self.encrypted.len() > MAX_PACKET_SIZE + 4 {
            return Err("MTProto packet splitter buffer exceeded 16 MiB.".into());
        }
        Ok(parts)
    }

    fn flush(&mut self) -> Vec<Vec<u8>> {
        if self.encrypted.is_empty() {
            return Vec::new();
        }
        let tail = std::mem::take(&mut self.encrypted);
        self.plaintext.clear();
        vec![tail]
    }

    fn packet_length(&self, offset: usize) -> Result<Option<usize>, String> {
        let remaining = self.plaintext.len().saturating_sub(offset);
        match self.proto_tag {
            TAG_ABRIDGED => {
                if remaining < 1 {
                    return Ok(None);
                }
                let first = self.plaintext[offset];
                let (header, payload) = if first == 0x7F || first == 0xFF {
                    if remaining < 4 {
                        return Ok(None);
                    }
                    let length = (self.plaintext[offset + 1] as usize)
                        | ((self.plaintext[offset + 2] as usize) << 8)
                        | ((self.plaintext[offset + 3] as usize) << 16);
                    (4, length.saturating_mul(4))
                } else {
                    (1, ((first & 0x7F) as usize).saturating_mul(4))
                };
                if payload == 0 {
                    return Ok(Some(0));
                }
                if payload > MAX_PACKET_SIZE {
                    return Err("Abridged MTProto packet exceeds 16 MiB.".into());
                }
                Ok(Some(header + payload))
            }
            TAG_INTERMEDIATE | TAG_PADDED_INTERMEDIATE => {
                if remaining < 4 {
                    return Ok(None);
                }
                let raw = u32::from_le_bytes(self.plaintext[offset..offset + 4].try_into().unwrap());
                let payload = (raw & 0x7FFF_FFFF) as usize;
                if payload == 0 {
                    return Ok(Some(0));
                }
                if payload > MAX_PACKET_SIZE {
                    return Err("Intermediate MTProto packet exceeds 16 MiB.".into());
                }
                Ok(Some(4 + payload))
            }
            _ => Ok(Some(0)),
        }
    }
}

enum ConnectedUpstream {
    Tcp(TcpStream),
    WebSocket(WsStream),
}

async fn run_listener(
    listener: TcpListener,
    local_secret: Vec<u8>,
    selected_upstream: Arc<RwLock<Option<EngineUpstream>>>,
    metrics: Arc<EngineMetrics>,
    app: AppHandle,
) {
    let domains = Arc::new(RwLock::new(default_cf_domains()));
    let refresh_domains = domains.clone();
    let refresh_metrics = metrics.clone();
    let _refresh_task = AbortOnDrop(tokio::spawn(async move {
        refresh_cf_domains(refresh_domains, refresh_metrics).await;
    }));

    let failover_upstream = selected_upstream.clone();
    let failover_metrics = metrics.clone();
    let failover_app = app.clone();
    let _failover_task = AbortOnDrop(tokio::spawn(async move {
        monitor_selected_upstream(failover_app, failover_upstream, failover_metrics).await;
    }));

    let mut clients = JoinSet::new();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                match accepted {
                    Ok((socket, address)) => {
                        let secret = local_secret.clone();
                        let upstream = selected_upstream.clone();
                        let client_metrics = metrics.clone();
                        let client_domains = domains.clone();
                        let client_settings = crate::settings::load(&app).unwrap_or_default();
                        clients.spawn(async move {
                            handle_client(
                                socket,
                                address.to_string(),
                                secret,
                                upstream,
                                client_domains,
                                client_settings,
                                client_metrics,
                            ).await;
                        });
                    }
                    Err(error) => {
                        metrics.set_error(format!("Local listener accept failed: {error}"));
                        break;
                    }
                }
            }
            Some(_) = clients.join_next(), if !clients.is_empty() => {}
        }
    }

    clients.abort_all();
    while clients.join_next().await.is_some() {}
}

async fn handle_client(
    mut client: TcpStream,
    client_address: String,
    local_secret: Vec<u8>,
    selected_upstream: Arc<RwLock<Option<EngineUpstream>>>,
    cf_domains: Arc<RwLock<Vec<String>>>,
    settings: crate::settings::DesktopSettings,
    metrics: Arc<EngineMetrics>,
) {
    let _ = client.set_nodelay(true);
    metrics.active_connections.fetch_add(1, Ordering::Relaxed);
    metrics.connections_total.fetch_add(1, Ordering::Relaxed);
    let result = async {
        let mut init = [0u8; HANDSHAKE_LEN];
        timeout(HANDSHAKE_TIMEOUT, client.read_exact(&mut init))
            .await
            .map_err(|_| "Timed out waiting for the MTProto obfuscated2 handshake.".to_string())?
            .map_err(|error| format!("Could not read MTProto handshake: {error}"))?;

        let client_crypto = parse_client_handshake(&init, &local_secret)
            .map_err(|error| format!("Rejected client {client_address}: {error}"))?;
        let dc_index = client_crypto.dc_index;
        let dc = dc_index.unsigned_abs() as i32;
        let media = dc_index < 0;
        let selected_upstream = selected_upstream.read()
            .ok()
            .and_then(|current| current.clone());
        let upstream_secret = match selected_upstream.as_ref() {
            Some(profile) if profile.is_mtproto() => Some(mtproto_secret(&profile.secret)?.0),
            _ => None,
        };
        let mut relay_crypto = generate_relay_crypto(
            client_crypto.proto_tag,
            dc_index,
            upstream_secret.as_deref(),
        );

        let (mut upstream, route) = connect_upstream(
            dc,
            media,
            selected_upstream.as_ref(),
            &mut relay_crypto,
            cf_domains.clone(),
            &settings,
        )
        .await?;

        metrics.set_route(&route);
        metrics.record_route(&route);
        if let ConnectedUpstream::WebSocket(ws) = &mut upstream {
            ws.send(Message::Binary(relay_crypto.init_packet.clone().into()))
                .await
                .map_err(|error| format!("Could not send MTProto handshake over WebSocket: {error}"))?;
        }

        match upstream {
            ConnectedUpstream::Tcp(socket) => {
                bridge_tcp(client, socket, client_crypto, relay_crypto, metrics.clone()).await
            }
            ConnectedUpstream::WebSocket(socket) => {
                bridge_websocket(
                    client,
                    socket,
                    client_crypto,
                    relay_crypto,
                    settings.websocket_ping_interval_seconds,
                    metrics.clone(),
                ).await
            }
        }
    }
    .await;

    metrics.active_connections.fetch_sub(1, Ordering::Relaxed);
    if let Err(error) = result {
        // A disconnected Telegram session is normal; expose only the current
        // actionable failure and never log secrets or full proxy URLs.
        metrics.failed_connections.fetch_add(1, Ordering::Relaxed);
        metrics.set_error(error);
    }
}

fn profile_as_catalog_record(profile: &EngineUpstream) -> crate::proxy::CatalogRecord {
    crate::proxy::CatalogRecord {
        id: profile.id.clone(),
        kind: profile.kind.clone(),
        protocol: None,
        server: profile.host.clone(),
        port: profile.port,
        secret: profile.secret.clone(),
        username: profile.username.clone(),
        password: profile.password.clone(),
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
    }
}

fn profile_from_catalog_record(record: crate::proxy::CatalogRecord) -> EngineUpstream {
    EngineUpstream {
        id: record.id,
        kind: record.kind,
        host: record.server,
        port: record.port,
        secret: record.secret,
        username: record.username,
        password: record.password,
    }
}

async fn find_failover_candidate(
    app: &AppHandle,
    excluded_ids: &HashSet<String>,
) -> Result<Option<(crate::proxy::CatalogRecord, u64)>, String> {
    let mut records = crate::proxy::cached_records(app)?;
    records.retain(|record| {
        !excluded_ids.contains(&record.id) &&
            crate::proxy::is_supported_profile_record(record)
    });
    records.sort_by(|left, right| {
        right.source_tier.as_deref().unwrap_or("").cmp(left.source_tier.as_deref().unwrap_or(""))
            .then_with(|| right.uptime.unwrap_or(0.0).total_cmp(&left.uptime.unwrap_or(0.0)))
            .then_with(|| left.rtt_ms.unwrap_or(u64::MAX).cmp(&right.rtt_ms.unwrap_or(u64::MAX)))
    });

    let semaphore = Arc::new(tokio::sync::Semaphore::new(FAILOVER_PARALLELISM));
    let mut probes = FuturesUnordered::new();
    for record in records {
        let semaphore = semaphore.clone();
        probes.push(async move {
            let permit = semaphore.acquire_owned().await.ok()?;
            let probe_record = record.clone();
            let result = tokio::task::spawn_blocking(move || crate::proxy::run_proxy_test(probe_record))
                .await.ok()?;
            drop(permit);
            result.success.then_some((record, result.elapsed_ms))
        });
    }

    let mut available = Vec::new();
    while let Some(candidate) = probes.next().await {
        if let Some(candidate) = candidate {
            available.push(candidate);
        }
    }
    available.sort_by_key(|(_, latency)| *latency);
    available.truncate(FAILOVER_FINALISTS);

    let mut verified = Vec::new();
    for (record, first_latency) in available {
        let mut timings = vec![first_latency];
        let mut successes = 1usize;
        for _ in 0..FAILOVER_REPEAT_COUNT {
            let probe_record = record.clone();
            let result = tokio::task::spawn_blocking(move || crate::proxy::run_proxy_test(probe_record))
                .await
                .map_err(|e| format!("Failover finalist verification task failed: {e}"))?;
            if result.success {
                successes += 1;
                timings.push(result.elapsed_ms);
            }
        }
        if successes >= 2 {
            timings.sort_unstable();
            let median = timings[timings.len() / 2];
            verified.push((record, median));
        }
    }

    verified.sort_by_key(|(_, latency)| *latency);
    Ok(verified.into_iter().next())
}

async fn monitor_selected_upstream(
    app: AppHandle,
    selected_upstream: Arc<RwLock<Option<EngineUpstream>>>,
    metrics: Arc<EngineMetrics>,
) {
    let mut consecutive_failures = 0usize;
    let mut active_profile_id: Option<String> = None;
    let mut failed_profile_until = HashMap::<String, u64>::new();
    let mut next_switch_attempt_at = 0u64;
    let mut last_catalog_refresh_at = 0u64;
    let mut last_optimization_at = 0u64;

    loop {
        let settings = crate::settings::load(&app).unwrap_or_default();
        let now = now_epoch_ms();
        failed_profile_until.retain(|_, expires_at| *expires_at > now);

        if settings.auto_failover_enabled {
            let refresh_interval_ms = settings.catalog_refresh_minutes.saturating_mul(60_000);
            if last_catalog_refresh_at == 0 || now.saturating_sub(last_catalog_refresh_at) >= refresh_interval_ms {
                last_catalog_refresh_at = now;
                if let Err(error) = crate::proxy::catalog_refresh(app.clone()).await {
                    metrics.set_error(format!("Catalog refresh failed; keeping cached records: {error}"));
                }
            }

            let active = selected_upstream.read()
                .ok()
                .and_then(|current| current.clone());
            if let Some(active) = active {
                if active_profile_id.as_deref() != Some(active.id.as_str()) {
                    active_profile_id = Some(active.id.clone());
                    consecutive_failures = 0;
                    metrics.failure_streak.store(0, Ordering::Relaxed);
                }

                let record = profile_as_catalog_record(&active);
                let probe = tokio::task::spawn_blocking(move || crate::proxy::run_proxy_test(record)).await;
                let (healthy, latency) = match probe {
                    Ok(result) => (result.success, result.elapsed_ms),
                    Err(_) => (false, 0),
                };
                metrics.record_probe(latency, if healthy { 0 } else { consecutive_failures + 1 });

                if healthy {
                    consecutive_failures = 0;
                    metrics.failure_streak.store(0, Ordering::Relaxed);
                    metrics.set_route(&format!("{} · upstream rechecked", active.label()));

                    // Only auto-managed catalogue profiles participate in periodic
                    // optimization. Manually named profiles are never replaced.
                    let auto_managed = crate::profiles::selected_profile(&app)
                        .ok()
                        .flatten()
                        .map(|profile| profile.name.starts_with("TGU Catalog · "))
                        .unwrap_or(false);
                    let optimize_interval_ms = AUTO_OPTIMIZE_INTERVAL.as_millis() as u64;
                    if settings.auto_optimize_connection
                        && auto_managed
                        && now.saturating_sub(last_optimization_at) >= optimize_interval_ms
                    {
                        last_optimization_at = now;
                        let mut excluded = HashSet::new();
                        excluded.insert(active.id.clone());
                        match find_failover_candidate(&app, &excluded).await {
                            Ok(Some((candidate, candidate_latency))) => {
                                let meaningfully_faster =
                                    candidate_latency.saturating_add(10) <= latency
                                    || (candidate_latency as u128 * 6) <= (latency as u128 * 5);
                                if meaningfully_faster {
                                    let replacement = profile_from_catalog_record(candidate.clone());
                                    if let Ok(mut current) = selected_upstream.write() {
                                        *current = Some(replacement.clone());
                                        metrics.set_route(&format!(
                                            "Auto-optimized → {} · {} ms",
                                            replacement.label(),
                                            candidate_latency
                                        ));
                                    }
                                    if let Ok(saved) = crate::profiles::save_catalog_profile(&app, &candidate) {
                                        let _ = crate::profiles::set_selected_id(&app, Some(saved.id.clone()));
                                    }
                                    let _ = crate::profiles::history_append(
                                        &app,
                                        &candidate.server,
                                        &candidate.kind,
                                        "AUTO_OPTIMIZED",
                                        true,
                                        candidate_latency,
                                        "Automatically optimized an Android-style catalogue-managed route after a significant local latency improvement.",
                                    );
                                    metrics.switch_count.fetch_add(1, Ordering::Relaxed);
                                    active_profile_id = Some(replacement.id.clone());
                                }
                            }
                            Ok(None) => {}
                            Err(error) => metrics.set_error(format!("Auto-optimization search failed: {error}")),
                        }
                    }
                } else {
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    metrics.failure_streak.store(consecutive_failures, Ordering::Relaxed);
                    metrics.set_error(format!(
                        "Upstream {} failed health check {consecutive_failures}/{}.",
                        active.label(),
                        settings.failure_threshold
                    ));
                }

                if !healthy
                    && consecutive_failures >= settings.failure_threshold
                    && now >= next_switch_attempt_at
                {
                    next_switch_attempt_at = now.saturating_add(
                        settings.switch_cooldown_seconds.saturating_mul(1_000)
                    );
                    failed_profile_until.insert(
                        active.id.clone(),
                        now.saturating_add(settings.failed_profile_cooldown_seconds.saturating_mul(1_000))
                    );
                    let excluded: HashSet<String> = failed_profile_until.keys().cloned().collect();

                    match find_failover_candidate(&app, &excluded).await {
                        Ok(Some((replacement_record, replacement_latency))) => {
                            let replacement = profile_from_catalog_record(replacement_record.clone());
                            if let Ok(mut current) = selected_upstream.write() {
                                *current = Some(replacement.clone());
                                metrics.set_route(&format!(
                                    "Failover → {} · {} ms",
                                    replacement.label(),
                                    replacement_latency
                                ));
                            }
                            if let Ok(saved) = crate::profiles::save_catalog_profile(&app, &replacement_record) {
                                let _ = crate::profiles::set_selected_id(&app, Some(saved.id.clone()));
                            }
                            let _ = crate::profiles::history_append(
                                &app,
                                &replacement_record.server,
                                &replacement_record.kind,
                                "AUTO_FAILOVER",
                                true,
                                replacement_latency,
                                "Selected after the active upstream failed its configured health-check threshold.",
                            );
                            metrics.switch_count.fetch_add(1, Ordering::Relaxed);
                            consecutive_failures = 0;
                            active_profile_id = Some(replacement.id.clone());
                            metrics.failure_streak.store(0, Ordering::Relaxed);
                        }
                        Ok(None) => {
                            metrics.set_error(format!(
                                "Upstream failed {} checks; no alternative in the cached catalog passed Telegram DC verification.",
                                settings.failure_threshold
                            ));
                        }
                        Err(error) => {
                            metrics.set_error(format!("Failover catalog lookup failed: {error}"));
                        }
                    }
                }
            } else {
                active_profile_id = None;
                consecutive_failures = 0;
                metrics.failure_streak.store(0, Ordering::Relaxed);
            }
        }

        tokio::time::sleep(Duration::from_secs(settings.check_interval_seconds)).await;
    }
}

async fn connect_upstream(
    dc: i32,
    media: bool,
    selected: Option<&EngineUpstream>,
    relay: &mut RelayCrypto,
    cf_domains: Arc<RwLock<Vec<String>>>,
    settings: &crate::settings::DesktopSettings,
) -> Result<(ConnectedUpstream, String), String> {
    // A user-selected proxy is exclusive: do not silently route its session
    // directly if the selected external upstream is unavailable.
    if let Some(profile) = selected {
        if profile.is_socks5() {
            let target = dc_ip(dc).ok_or_else(|| format!("No Telegram DC{dc} address is configured."))?;
            let mut socket = connect_tcp(&profile.host, profile.port).await
                .map_err(|error| format!("SOCKS5 upstream connection failed: {error}"))?;
            socks5_connect(&mut socket, profile, target, 443).await?;
            socket.write_all(&relay.init_packet).await
                .map_err(|error| format!("Could not send relay handshake through SOCKS5: {error}"))?;
            return Ok((ConnectedUpstream::Tcp(socket), profile.label()));
        }

        if profile.is_mtproto() {
            let (secret, _) = mtproto_secret(&profile.secret)?;
            let mut socket = connect_tcp(&profile.host, profile.port).await
                .map_err(|error| format!("MTProto upstream connection failed: {error}"))?;
            if secret.is_empty() {
                return Err("Selected MTProto upstream secret is empty.".into());
            }
            socket.write_all(&relay.init_packet).await
                .map_err(|error| format!("Could not send obfuscated2 handshake to MTProxy: {error}"))?;
            return Ok((ConnectedUpstream::Tcp(socket), profile.label()));
        }
        return Err("Selected upstream uses an unsupported protocol.".into());
    }

    let try_direct = || async { try_direct_websocket(dc, media).await };
    let try_cloudflare = || async {
        let domains = cf_domains.read().map(|value| value.clone()).unwrap_or_default();
        for domain in domains {
            let ws_dc = if dc == 203 { 2 } else { dc };
            let host = format!("kws{ws_dc}.{domain}");
            if let Ok(socket) = connect_websocket_host(&host, None).await {
                return Some((socket, format!("Cloudflare WebSocket · {domain}")));
            }
        }
        None
    };

    if settings.direct_websocket_first {
        if let Some((socket, route)) = try_direct().await {
            return Ok((ConnectedUpstream::WebSocket(socket), route));
        }
    }

    if settings.cloudflare_websocket_enabled {
        if let Some((socket, route)) = try_cloudflare().await {
            return Ok((ConnectedUpstream::WebSocket(socket), route));
        }
    }

    if !settings.direct_websocket_first {
        if let Some((socket, route)) = try_direct().await {
            return Ok((ConnectedUpstream::WebSocket(socket), route));
        }
    }

    let target = dc_ip(dc).ok_or_else(|| format!("No Telegram DC{dc} address is configured."))?;
    let mut socket = connect_tcp(target, 443).await
        .map_err(|error| format!("Direct Telegram DC{dc} TCP fallback failed: {error}"))?;
    socket.write_all(&relay.init_packet).await
        .map_err(|error| format!("Could not send MTProto relay handshake to DC{dc}: {error}"))?;
    Ok((ConnectedUpstream::Tcp(socket), format!("Direct TCP · DC{dc}")))
}

async fn connect_tcp(host: &str, port: u16) -> Result<TcpStream, String> {
    if !crate::proxy::is_public_host(host) {
        return Err(format!("Refusing to connect to non-public upstream host {host}."));
    }
    let addresses = tokio::net::lookup_host((host, port))
        .await
        .map_err(|error| format!("DNS lookup for {host} failed: {error}"))?;
    let public_addresses: Vec<SocketAddr> = addresses
        .filter(|address| crate::proxy::is_public_ip(address.ip()))
        .collect();
    if public_addresses.is_empty() {
        return Err(format!("DNS for {host} returned no public addresses."));
    }

    let mut last_error = None;
    for address in public_addresses {
        match timeout(CONNECT_TIMEOUT, TcpStream::connect(address)).await {
            Ok(Ok(socket)) => {
                socket.set_nodelay(true).map_err(|error| error.to_string())?;
                return Ok(socket);
            }
            Ok(Err(error)) => last_error = Some(error.to_string()),
            Err(_) => last_error = Some("connection timed out".into()),
        }
    }
    Err(format!(
        "Could not connect to public upstream {host}:{port}: {}",
        last_error.unwrap_or_else(|| "no usable address".into())
    ))
}

async fn try_direct_websocket(dc: i32, media: bool) -> Option<(WsStream, String)> {
    let redirect = match dc {
        2 | 4 => "149.154.167.220",
        _ => return None,
    };
    let ws_dc = if dc == 203 { 2 } else { dc };
    let mut hosts = Vec::new();
    if media {
        hosts.push(format!("kws{ws_dc}-1.web.telegram.org"));
    }
    hosts.push(format!("kws{ws_dc}.web.telegram.org"));

    for host in hosts {
        if let Ok(socket) = connect_websocket_host(&host, Some(redirect)).await {
            return Some((socket, format!("Telegram WebSocket · DC{dc}")));
        }
    }
    None
}

async fn connect_websocket_host(host: &str, connect_ip: Option<&str>) -> Result<WsStream, String> {
    if !is_valid_domain(host) {
        return Err("WebSocket target host is not a valid public DNS name.".into());
    }

    let socket = if let Some(ip) = connect_ip {
        connect_tcp(ip, 443).await?
    } else {
        match connect_tcp(host, 443).await {
            Ok(socket) => socket,
            Err(first_error) => {
                let ip = resolve_doh_ipv4(host).await
                    .ok_or_else(|| format!("System DNS failed ({first_error}); no public DoH address for {host}."))?;
                connect_tcp(&ip.to_string(), 443).await
                    .map_err(|error| format!("DoH-resolved WebSocket TCP connect to {ip} failed: {error}"))?
            }
        }
    };

    let mut request = format!("wss://{host}/apiws")
        .into_client_request()
        .map_err(|error| format!("Invalid WebSocket request: {error}"))?;
    request.headers_mut().insert(
        "Sec-WebSocket-Protocol",
        HeaderValue::from_static("binary"),
    );
    request.headers_mut().insert(
        "User-Agent",
        HeaderValue::from_static("TGU-Desktop/0.3"),
    );
    let connected = timeout(
        CONNECT_TIMEOUT,
        client_async_tls_with_config(request, socket, None, None),
    )
    .await
    .map_err(|_| "WebSocket TLS/upgrade timed out.".to_string())?
    .map_err(|error| format!("WebSocket TLS/upgrade failed: {error}"))?;
    if connected.1.status().as_u16() != 101 {
        return Err(format!("WebSocket returned HTTP {}.", connected.1.status()));
    }
    Ok(connected.0)
}

async fn resolve_doh_ipv4(host: &str) -> Option<Ipv4Addr> {
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(5))
        .redirect(reqwest::redirect::Policy::none())
        .resolve("cloudflare-dns.com", SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 443))
        .resolve("dns.google", SocketAddr::new(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 443))
        .build()
        .ok()?;

    for url in [
        format!("https://cloudflare-dns.com/dns-query?name={host}&type=A"),
        format!("https://dns.google/resolve?name={host}&type=A"),
    ] {
        let response = match client
            .get(url)
            .header("Accept", "application/dns-json")
            .send()
            .await
        {
            Ok(response) if response.status().is_success() => response,
            _ => continue,
        };
        let value: serde_json::Value = match response.json().await {
            Ok(value) => value,
            Err(_) => continue,
        };
        let Some(answers) = value.get("Answer").and_then(|item| item.as_array()) else {
            continue;
        };
        for answer in answers {
            if answer.get("type").and_then(|item| item.as_u64()) != Some(1) {
                continue;
            }
            let Some(address) = answer.get("data").and_then(|item| item.as_str()) else {
                continue;
            };
            if let Ok(ip) = address.parse::<Ipv4Addr>() {
                if crate::proxy::is_public_ip(IpAddr::V4(ip)) {
                    return Some(ip);
                }
            }
        }
    }
    None
}

async fn socks5_connect(
    socket: &mut TcpStream,
    profile: &EngineUpstream,
    target_host: &str,
    target_port: u16,
) -> Result<(), String> {
    let has_credentials = !profile.username.is_empty() || !profile.password.is_empty();
    if has_credentials {
        if profile.username.len() > 255 || profile.password.len() > 255 {
            return Err("SOCKS5 username or password exceeds 255 bytes.".into());
        }
        socket.write_all(&[5, 2, 0, 2]).await.map_err(|e| e.to_string())?;
    } else {
        socket.write_all(&[5, 1, 0]).await.map_err(|e| e.to_string())?;
    }

    let mut greeting = [0u8; 2];
    timeout(HANDSHAKE_TIMEOUT, socket.read_exact(&mut greeting))
        .await.map_err(|_| "SOCKS5 method negotiation timed out.".to_string())?
        .map_err(|e| format!("SOCKS5 method negotiation failed: {e}"))?;
    if greeting[0] != 5 {
        return Err("SOCKS5 upstream returned an invalid version.".into());
    }
    match greeting[1] {
        0 => {}
        2 if has_credentials => {
            socket.write_all(&[1, profile.username.len() as u8]).await.map_err(|e| e.to_string())?;
            socket.write_all(profile.username.as_bytes()).await.map_err(|e| e.to_string())?;
            socket.write_all(&[profile.password.len() as u8]).await.map_err(|e| e.to_string())?;
            socket.write_all(profile.password.as_bytes()).await.map_err(|e| e.to_string())?;
            let mut auth = [0u8; 2];
            socket.read_exact(&mut auth).await.map_err(|e| format!("SOCKS5 auth response failed: {e}"))?;
            if auth != [1, 0] {
                return Err("SOCKS5 upstream rejected username/password authentication.".into());
            }
        }
        2 => return Err("SOCKS5 upstream requires credentials not present in the catalog.".into()),
        0xFF => return Err("SOCKS5 upstream rejected the offered auth methods.".into()),
        _ => return Err("SOCKS5 upstream selected an unsupported auth method.".into()),
    }

    let ip = target_host.parse::<Ipv4Addr>()
        .map_err(|_| "SOCKS5 target must be a valid IPv4 Telegram DC address.".to_string())?;
    let mut request = vec![5, 1, 0, 1];
    request.extend_from_slice(&ip.octets());
    request.extend_from_slice(&target_port.to_be_bytes());
    socket.write_all(&request).await.map_err(|e| format!("SOCKS5 CONNECT request failed: {e}"))?;

    let mut header = [0u8; 4];
    timeout(HANDSHAKE_TIMEOUT, socket.read_exact(&mut header))
        .await.map_err(|_| "SOCKS5 CONNECT response timed out.".to_string())?
        .map_err(|e| format!("SOCKS5 CONNECT response failed: {e}"))?;
    if header[0] != 5 {
        return Err("SOCKS5 CONNECT response has an invalid version.".into());
    }
    let address_len = match header[3] {
        1 => 4,
        3 => {
            let mut size = [0u8; 1];
            socket.read_exact(&mut size).await.map_err(|e| e.to_string())?;
            size[0] as usize
        }
        4 => 16,
        _ => return Err("SOCKS5 returned an invalid address type.".into()),
    };
    let mut bound = vec![0u8; address_len + 2];
    socket.read_exact(&mut bound).await.map_err(|e| format!("SOCKS5 bound address failed: {e}"))?;
    if header[1] != 0 {
        return Err(format!("SOCKS5 CONNECT to Telegram DC failed (reply {}).", header[1]));
    }
    Ok(())
}

fn mtproto_secret(value: &str) -> Result<(Vec<u8>, bool), String> {
    let normalized = value.trim().to_ascii_lowercase();
    if normalized.starts_with("ee") {
        return Err("FakeTLS/WEB MTProto upstream is not supported yet.".into());
    }
    let (hex, padded) = if normalized.starts_with("dd") && normalized.len() == 34 {
        (&normalized[2..], true)
    } else {
        (normalized.as_str(), false)
    };
    if hex.len() != 32 || !hex.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("MTProto upstream requires a classic 16-byte hexadecimal secret.".into());
    }
    Ok((decode_hex(hex)?, padded))
}

fn dc_ip(dc: i32) -> Option<&'static str> {
    match dc {
        1 => Some("149.154.175.50"),
        2 => Some("149.154.167.51"),
        3 => Some("149.154.175.100"),
        4 => Some("149.154.167.91"),
        5 => Some("149.154.171.5"),
        203 => Some("91.105.192.100"),
        _ => None,
    }
}

async fn bridge_tcp(
    client: TcpStream,
    upstream: TcpStream,
    client_crypto: ClientCrypto,
    relay_crypto: RelayCrypto,
    metrics: Arc<EngineMetrics>,
) -> Result<(), String> {
    let (mut client_read, mut client_write) = client.into_split();
    let (mut upstream_read, mut upstream_write) = upstream.into_split();
    let mut client_decryptor = client_crypto.decryptor;
    let mut client_encryptor = client_crypto.encryptor;
    let mut telegram_encryptor = relay_crypto.encryptor;
    let mut telegram_decryptor = relay_crypto.decryptor;
    let outbound = async {
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let read = client_read.read(&mut buffer).await.map_err(|e| format!("Local Telegram read failed: {e}"))?;
            if read == 0 {
                return Ok::<(), String>(());
            }
            let mut payload = buffer[..read].to_vec();
            client_decryptor.apply_keystream(&mut payload);
            telegram_encryptor.apply_keystream(&mut payload);
            upstream_write.write_all(&payload).await.map_err(|e| format!("Upstream write failed: {e}"))?;
            metrics.bytes_up.fetch_add(read as u64, Ordering::Relaxed);
        }
    };
    let inbound = async {
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let read = upstream_read.read(&mut buffer).await.map_err(|e| format!("Telegram upstream read failed: {e}"))?;
            if read == 0 {
                return Ok::<(), String>(());
            }
            let mut payload = buffer[..read].to_vec();
            telegram_decryptor.apply_keystream(&mut payload);
            client_encryptor.apply_keystream(&mut payload);
            client_write.write_all(&payload).await.map_err(|e| format!("Local Telegram response write failed: {e}"))?;
            metrics.bytes_down.fetch_add(read as u64, Ordering::Relaxed);
            metrics.telegram_connected.store(true, Ordering::Release);
        }
    };
    tokio::select! {
        result = outbound => result,
        result = inbound => result,
    }
}

async fn bridge_websocket(
    client: TcpStream,
    websocket: WsStream,
    client_crypto: ClientCrypto,
    relay_crypto: RelayCrypto,
    ping_interval_seconds: u64,
    metrics: Arc<EngineMetrics>,
) -> Result<(), String> {
    let (mut client_read, mut client_write) = client.into_split();
    let (ws_sink, mut ws_stream) = websocket.split();
    let ws_sink = Arc::new(tokio::sync::Mutex::new(ws_sink));
    let mut client_decryptor = client_crypto.decryptor;
    let mut client_encryptor = client_crypto.encryptor;
    let mut telegram_encryptor = relay_crypto.encryptor;
    let mut telegram_decryptor = relay_crypto.decryptor;
    let proto_tag = client_crypto.proto_tag;

    let outbound_sink = ws_sink.clone();
    let outbound = async {
        let mut splitter = MtProtoSplitter::new(proto_tag);
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let read = client_read.read(&mut buffer).await
                .map_err(|e| format!("Local Telegram read failed: {e}"))?;
            if read == 0 {
                for tail in splitter.flush() {
                    outbound_sink.lock().await.send(Message::Binary(tail.into())).await
                        .map_err(|e| format!("WebSocket MTProto flush failed: {e}"))?;
                }
                return Ok::<(), String>(());
            }
            let mut plaintext = buffer[..read].to_vec();
            client_decryptor.apply_keystream(&mut plaintext);
            let mut encrypted = plaintext.clone();
            telegram_encryptor.apply_keystream(&mut encrypted);
            for packet in splitter.push(&encrypted, &plaintext)? {
                let sent = packet.len() as u64;
                outbound_sink.lock().await.send(Message::Binary(packet.into())).await
                    .map_err(|e| format!("WebSocket send failed: {e}"))?;
                metrics.bytes_up.fetch_add(sent, Ordering::Relaxed);
            }
        }
    };

    let inbound_sink = ws_sink.clone();
    let inbound = async {
        while let Some(message) = ws_stream.next().await {
            match message.map_err(|e| format!("Telegram WebSocket read failed: {e}"))? {
                Message::Binary(data) => {
                    let mut payload = data.to_vec();
                    telegram_decryptor.apply_keystream(&mut payload);
                    client_encryptor.apply_keystream(&mut payload);
                    client_write.write_all(&payload).await
                        .map_err(|e| format!("Local Telegram response write failed: {e}"))?;
                    metrics.bytes_down.fetch_add(data.len() as u64, Ordering::Relaxed);
                    metrics.telegram_connected.store(true, Ordering::Release);
                }
                Message::Ping(payload) => {
                    inbound_sink.lock().await.send(Message::Pong(payload)).await
                        .map_err(|e| format!("Telegram WebSocket pong failed: {e}"))?;
                }
                Message::Pong(_) => {}
                Message::Close(_) => break,
                Message::Text(_) | Message::Frame(_) => {}
            }
        }
        Ok::<(), String>(())
    };

    let ping_sink = ws_sink.clone();
    let mut heartbeat = tokio::time::interval(Duration::from_secs(ping_interval_seconds.clamp(30, 600)));
    heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    tokio::select! {
        result = outbound => result,
        result = inbound => result,
        _ = heartbeat.tick() => {
            ping_sink.lock().await.send(Message::Ping(Vec::new().into())).await
                .map_err(|error| format!("Telegram WebSocket heartbeat failed: {error}"))?;
            Ok(())
        }
    }
}

fn decode_cf_domain(encoded: &str) -> Option<String> {
    let value = encoded.trim().to_ascii_lowercase();
    if let Some(prefix) = value.strip_suffix(".com") {
        if prefix.is_empty() || !prefix.chars().all(|c| c.is_ascii_lowercase()) {
            return None;
        }
        // Match the Android decoder: shift by the count of alphabetic
        // characters before the encoded .com suffix.
        let shift = prefix.chars().filter(|c| c.is_ascii_alphabetic()).count() as i32;
        let decoded: String = prefix.chars().map(|character| {
            let index = character as i32 - 'a' as i32;
            (('a' as i32 + (index - shift).rem_euclid(26)) as u8) as char
        }).collect();
        return Some(format!("{decoded}.co.uk"));
    }

    if is_valid_domain(&value) {
        Some(value)
    } else {
        None
    }
}

fn is_valid_domain(domain: &str) -> bool {
    if domain.is_empty() || domain.len() > 253 || domain.starts_with('.') || domain.ends_with('.') {
        return false;
    }
    let labels: Vec<_> = domain.split('.').collect();
    labels.len() >= 2 && labels.iter().all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    })
}

fn default_cf_domains() -> Vec<String> {
    [
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com",
        "twdmbzcm.com", "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com",
        "bxaxtxmrw.com", "dmohrsgmohcrwb.com", "vwbmtmoi.com", "khgrre.com",
        "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com", "orgcnunpj.com",
        "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
    ]
    .iter()
    .filter_map(|entry| decode_cf_domain(entry))
    .collect()
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

async fn refresh_cf_domains(domains: Arc<RwLock<Vec<String>>>, metrics: Arc<EngineMetrics>) {
    let client = match reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(12))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent("TGU-Desktop/0.3")
        .build()
    {
        Ok(client) => client,
        Err(error) => {
            metrics.set_error(format!("Cloudflare domain refresh client failed: {error}"));
            return;
        }
    };
    loop {
        if let Ok(response) = client
            .get("https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt")
            .send()
            .await
        {
            if response.status().is_success() {
                if let Ok(body) = response.text().await {
                    let fetched: Vec<String> = body
                        .lines()
                        .filter_map(decode_cf_domain)
                        .filter(|domain| domain.len() < 120 && domain.contains('.'))
                        .collect();
                    if fetched.len() >= 3 {
                        if let Ok(mut current) = domains.write() {
                            *current = fetched;
                        }
                    }
                }
            }
        }
        tokio::time::sleep(Duration::from_secs(60 * 60)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synthetic_client_handshake(secret: &[u8], dc: i16, tag: u32) -> [u8; HANDSHAKE_LEN] {
        let mut init = [0u8; HANDSHAKE_LEN];
        rand::thread_rng().fill_bytes(&mut init);
        let prekey: [u8; 32] = init[8..40].try_into().unwrap();
        let iv: [u8; 16] = init[40..56].try_into().unwrap();
        let mut cipher = make_cipher(sha256_key(&prekey, secret), iv);
        let mut keystream = [0u8; HANDSHAKE_LEN];
        cipher.apply_keystream(&mut keystream);
        let mut tail = [0u8; 8];
        tail[..4].copy_from_slice(&tag.to_le_bytes());
        tail[4..6].copy_from_slice(&dc.to_le_bytes());
        for index in 0..8 {
            init[56 + index] = tail[index] ^ keystream[56 + index];
        }
        init
    }

    #[test]
    fn parses_obfuscated2_client_handshake_and_extracts_dc_and_media_flag() {
        let secret = [0x42u8; 16];
        let init = synthetic_client_handshake(&secret, -2, TAG_PADDED_INTERMEDIATE);
        let parsed = parse_client_handshake(&init, &secret).expect("valid client handshake");
        assert_eq!(parsed.proto_tag, TAG_PADDED_INTERMEDIATE);
        assert_eq!(parsed.dc_index, -2);
    }

    #[test]
    fn relay_init_has_expected_dc_and_transport_tag_for_direct_route() {
        let relay = generate_relay_crypto(TAG_INTERMEDIATE, 4, None);
        let prekey: [u8; 32] = relay.init_packet[8..40].try_into().unwrap();
        let iv: [u8; 16] = relay.init_packet[40..56].try_into().unwrap();
        let mut cipher = make_cipher(prekey, iv);
        let mut decoded: [u8; HANDSHAKE_LEN] = relay.init_packet.clone().try_into().unwrap();
        cipher.apply_keystream(&mut decoded);
        assert_eq!(u32::from_le_bytes(decoded[56..60].try_into().unwrap()), TAG_INTERMEDIATE);
        assert_eq!(i16::from_le_bytes(decoded[60..62].try_into().unwrap()), 4);
    }

    #[test]
    fn relay_init_uses_selected_mtproto_secret_for_both_obfuscated_directions() {
        let secret = [0x19u8; 16];
        let relay = generate_relay_crypto(TAG_PADDED_INTERMEDIATE, -3, Some(&secret));
        let prekey: [u8; 32] = relay.init_packet[8..40].try_into().unwrap();
        let iv: [u8; 16] = relay.init_packet[40..56].try_into().unwrap();
        let mut cipher = make_cipher(sha256_key(&prekey, &secret), iv);
        let mut decoded: [u8; HANDSHAKE_LEN] = relay.init_packet.clone().try_into().unwrap();
        cipher.apply_keystream(&mut decoded);
        assert_eq!(u32::from_le_bytes(decoded[56..60].try_into().unwrap()), TAG_PADDED_INTERMEDIATE);
        assert_eq!(i16::from_le_bytes(decoded[60..62].try_into().unwrap()), -3);
    }

    #[test]
    fn relay_response_cipher_starts_at_stream_offset_zero() {
        let secret = [0x37u8; 16];
        let relay = generate_relay_crypto(TAG_INTERMEDIATE, 2, Some(&secret));

        let reversed: Vec<u8> = relay.init_packet[8..56].iter().rev().copied().collect();
        let reverse_prekey: [u8; 32] = reversed[0..32].try_into().unwrap();
        let reverse_iv: [u8; 16] = reversed[32..48].try_into().unwrap();
        let response_key = sha256_key(&reverse_prekey, &secret);

        let expected = b"Telegram response begins immediately after the obfuscated2 header.";
        let mut ciphertext = expected.to_vec();
        let mut server_cipher = make_cipher(response_key, reverse_iv);
        server_cipher.apply_keystream(&mut ciphertext);

        let mut actual = ciphertext;
        let mut relay_decryptor = relay.decryptor;
        relay_decryptor.apply_keystream(&mut actual);
        assert_eq!(actual, expected);
    }

    #[test]
    fn intermediate_splitter_preserves_packets_across_partial_reads() {
        let first = vec![3, 0, 0, 0, 1, 2, 3];
        let second = vec![2, 0, 0, 0, 4, 5];
        let mut combined = first.clone();
        combined.extend_from_slice(&second);
        let mut splitter = MtProtoSplitter::new(TAG_INTERMEDIATE);

        let partial = splitter.push(&combined[..5], &combined[..5]).unwrap();
        assert!(partial.is_empty());
        let complete = splitter.push(&combined[5..], &combined[5..]).unwrap();
        assert_eq!(complete, vec![first, second]);
    }

    #[test]
    fn classic_and_dd_mtproto_secrets_are_accepted_but_fake_tls_is_rejected() {
        assert!(mtproto_secret("0123456789abcdef0123456789abcdef").is_ok());
        assert!(mtproto_secret("dd0123456789abcdef0123456789abcdef").unwrap().1);
        assert!(mtproto_secret("ee0123456789abcdef0123456789abcdef").is_err());
    }

    #[test]
    fn decodes_the_android_cloudflare_domain_pool_format() {
        assert_eq!(decode_cf_domain("virkgj.com").as_deref(), Some("pclead.co.uk"));
        assert!(default_cf_domains().len() >= 10);
    }

    #[test]
    fn accepts_only_supported_dc_numbers_and_transport_tags() {
        assert_eq!(TAG_ABRIDGED, 0xEFEFEFEF);
        assert!(dc_ip(2).is_some());
        assert!(dc_ip(203).is_some());
        assert!(dc_ip(6).is_none());
    }

    #[test]
    fn local_secret_is_32_hex_characters() {
        let secret = generate_local_secret();
        assert_eq!(secret.len(), 32);
        assert!(secret.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }
}
