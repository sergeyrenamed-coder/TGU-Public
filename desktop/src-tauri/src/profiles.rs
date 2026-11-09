use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    fs,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Manager, State};
use keyring::Entry;

const VAULT_SERVICE: &str = "com.rnmdteam.tgu.desktop";
const VAULT_USER: &str = "encrypted-proxy-profile-vault-key";
const HISTORY_LIMIT: usize = 500;
const MAX_PROFILES: usize = 50;
const VAULT_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyProfile {
    pub id: String,
    pub name: String,
    pub protocol: String,
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub secret: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
}

impl ProxyProfile {
    pub fn endpoint(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.id.trim().is_empty() || self.id.len() > 128 {
            return Err("Invalid profile identifier.".into());
        }
        if self.name.trim().is_empty() || self.name.chars().count() > 48 {
            return Err("Profile name must contain 1–48 characters.".into());
        }
        if !crate::proxy::is_public_host(&self.host) {
            return Err("Enter a public hostname or IP address. Local/private addresses are blocked.".into());
        }
        if self.port == 0 {
            return Err("Port must be between 1 and 65535.".into());
        }
        match self.protocol.to_ascii_lowercase().as_str() {
            "mtproto" | "mt-proto" => {
                let value = self.secret.trim().to_ascii_lowercase();
                let (hex, padded) = if value.starts_with("dd") && value.len() == 34 {
                    (&value[2..], true)
                } else {
                    (value.as_str(), false)
                };
                let _ = padded;
                if hex.len() != 32 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return Err("Classic MTProto requires a 16-byte hexadecimal secret. FakeTLS/WEB secrets are not supported.".into());
                }
            }
            "socks5" | "socks" => {
                if self.username.is_empty() != self.password.is_empty() {
                    return Err("Enter both SOCKS5 username and password, or leave both empty.".into());
                }
                if self.username.as_bytes().len() > 255 || self.password.as_bytes().len() > 255 {
                    return Err("SOCKS5 username and password must be at most 255 UTF-8 bytes.".into());
                }
            }
            _ => return Err("Only classic MTProto and SOCKS5 profiles are currently supported.".into()),
        }
        Ok(())
    }

    pub(crate) fn to_engine_upstream(&self) -> crate::engine::EngineUpstream {
        crate::engine::EngineUpstream {
            id: self.id.clone(),
            kind: self.protocol.clone(),
            host: self.host.clone(),
            port: self.port,
            secret: self.secret.clone(),
            username: self.username.clone(),
            password: self.password.clone(),
        }
    }

    pub(crate) fn to_catalog_record(&self) -> crate::proxy::CatalogRecord {
        crate::proxy::CatalogRecord {
            id: self.id.clone(),
            kind: self.protocol.clone(),
            protocol: Some(self.protocol.clone()),
            server: self.host.clone(),
            port: self.port,
            secret: self.secret.clone(),
            username: self.username.clone(),
            password: self.password.clone(),
            enabled: true,
            country: None,
            rtt_ms: None,
            uptime: None,
            checks: None,
            source_tier: None,
            last_check: None,
            stale: false,
            probe_status: None,
            community_reports: 0,
            community_unique_reporters: 0,
            community_success_rate: None,
            community_median_rtt_ms: None,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct ProfileSnapshot {
    pub profiles: Vec<ProxyProfile>,
    pub selected_id: Option<String>,
    pub favorite_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct ProfileVault {
    version: u32,
    profiles: Vec<ProxyProfile>,
    selected_id: Option<String>,
    favorite_ids: HashSet<String>,
}

impl Default for ProfileVault {
    fn default() -> Self {
        Self {
            version: VAULT_VERSION,
            profiles: Vec::new(),
            selected_id: None,
            favorite_ids: HashSet::new(),
        }
    }
}

fn vault_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|e| format!("App data directory: {e}"))?;
    fs::create_dir_all(&dir).map_err(|e| format!("Create app data directory: {e}"))?;
    Ok(dir.join("proxy-profiles-v1.enc"))
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn hex_decode(value: &str) -> Result<Vec<u8>, String> {
    if value.len() % 2 != 0 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("Credential Manager returned an invalid encryption key.".into());
    }
    (0..value.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&value[i..i + 2], 16).map_err(|e| e.to_string()))
        .collect()
}

fn vault_key() -> Result<[u8; 32], String> {
    let entry = Entry::new(VAULT_SERVICE, VAULT_USER)
        .map_err(|error| format!("Windows Credential Manager is unavailable: {error}"))?;

    match entry.get_password() {
        Ok(existing) => {
            let decoded = hex_decode(&existing)?;
            decoded.try_into().map_err(|_| "Stored profile encryption key has an invalid length.".into())
        }
        Err(keyring::Error::NoEntry) => {
            let mut key = [0u8; 32];
            rand::rngs::OsRng.fill_bytes(&mut key);
            entry.set_password(&hex_encode(&key))
                .map_err(|error| format!("Could not store the encrypted profile key in Windows Credential Manager: {error}"))?;
            Ok(key)
        }
        Err(error) => Err(format!("Could not read the profile encryption key from Windows Credential Manager: {error}")),
    }
}

fn encrypt_vault(plaintext: &[u8]) -> Result<Vec<u8>, String> {
    let key = vault_key()?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;
    let mut nonce_bytes = [0u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
    let ciphertext = cipher.encrypt(Nonce::from_slice(&nonce_bytes), plaintext)
        .map_err(|_| "Could not encrypt the local proxy profile vault.".to_string())?;
    let mut output = Vec::with_capacity(nonce_bytes.len() + ciphertext.len());
    output.extend_from_slice(&nonce_bytes);
    output.extend_from_slice(&ciphertext);
    Ok(output)
}

fn decrypt_vault(ciphertext: &[u8]) -> Result<Vec<u8>, String> {
    if ciphertext.len() <= 12 + 16 {
        return Err("Encrypted proxy profile vault is incomplete.".into());
    }
    let key = vault_key()?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| e.to_string())?;
    cipher.decrypt(Nonce::from_slice(&ciphertext[..12]), &ciphertext[12..])
        .map_err(|_| "Could not decrypt proxy profiles. The Windows encryption key may be unavailable.".into())
}

fn read_vault(app: &AppHandle) -> Result<ProfileVault, String> {
    let path = vault_path(app)?;
    if !path.exists() {
        return Ok(ProfileVault::default());
    }
    let ciphertext = fs::read(path).map_err(|e| format!("Read encrypted profile vault: {e}"))?;
    let plaintext = decrypt_vault(&ciphertext)?;
    let vault: ProfileVault = serde_json::from_slice(&plaintext)
        .map_err(|e| format!("Parse decrypted profile vault: {e}"))?;
    if vault.version != VAULT_VERSION {
        return Err(format!("Unsupported encrypted profile vault version: {}", vault.version));
    }
    if vault.profiles.len() > MAX_PROFILES {
        return Err("Profile vault exceeds the supported profile limit.".into());
    }
    Ok(vault)
}

fn write_vault(app: &AppHandle, vault: &ProfileVault) -> Result<(), String> {
    if vault.profiles.len() > MAX_PROFILES {
        return Err(format!("You can save up to {MAX_PROFILES} proxy profiles."));
    }
    let plaintext = serde_json::to_vec(vault).map_err(|e| format!("Serialize proxy profiles: {e}"))?;
    let ciphertext = encrypt_vault(&plaintext)?;
    let path = vault_path(app)?;
    let temporary = path.with_extension("enc.tmp");
    fs::write(&temporary, ciphertext).map_err(|e| format!("Write encrypted proxy profiles: {e}"))?;
    fs::rename(&temporary, &path).map_err(|e| format!("Commit encrypted proxy profiles: {e}"))?;
    Ok(())
}

fn snapshot(vault: ProfileVault) -> ProfileSnapshot {
    let mut profiles = vault.profiles;
    profiles.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    let mut favorite_ids: Vec<_> = vault.favorite_ids.into_iter().collect();
    favorite_ids.sort();
    ProfileSnapshot {
        profiles,
        selected_id: vault.selected_id,
        favorite_ids,
    }
}

pub(crate) fn save_catalog_profile(app: &AppHandle, record: &crate::proxy::CatalogRecord) -> Result<ProxyProfile, String> {
    let profile = ProxyProfile {
        id: record.id.clone(),
        name: format!("TGU Catalog · {}", record.server),
        protocol: record.kind.clone(),
        host: record.server.clone(),
        port: record.port,
        secret: record.secret.clone(),
        username: record.username.clone(),
        password: record.password.clone(),
    };
    profile.validate()?;
    let mut vault = read_vault(app)?;
    vault.profiles.retain(|existing| existing.id != profile.id);
    vault.profiles.push(profile.clone());
    write_vault(app, &vault)?;
    Ok(profile)
}

pub(crate) fn set_selected_id(app: &AppHandle, id: Option<String>) -> Result<(), String> {
    let mut vault = read_vault(app)?;
    if let Some(selected) = id.as_ref() {
        if !vault.profiles.iter().any(|profile| &profile.id == selected) {
            return Err("Selected profile does not exist in the encrypted profile vault.".into());
        }
    }
    vault.selected_id = id;
    write_vault(app, &vault)
}

pub(crate) fn selected_profile(app: &AppHandle) -> Result<Option<ProxyProfile>, String> {
    let vault = read_vault(app)?;
    Ok(vault.selected_id.and_then(|id| vault.profiles.into_iter().find(|profile| profile.id == id)))
}

#[tauri::command]
pub fn profiles_load(app: AppHandle) -> Result<ProfileSnapshot, String> {
    Ok(snapshot(read_vault(&app)?))
}

#[tauri::command]
pub fn profile_save(app: AppHandle, profile: ProxyProfile) -> Result<ProfileSnapshot, String> {
    profile.validate()?;
    let mut vault = read_vault(&app)?;
    let existed = vault.profiles.iter().any(|entry| entry.id == profile.id);
    if !existed && vault.profiles.len() >= MAX_PROFILES {
        return Err(format!("You can save up to {MAX_PROFILES} proxy profiles."));
    }
    vault.profiles.retain(|entry| entry.id != profile.id);
    vault.profiles.push(profile);
    write_vault(&app, &vault)?;
    Ok(snapshot(vault))
}

#[tauri::command]
pub async fn profile_test(app: AppHandle, profile_id: String) -> Result<crate::proxy::ProxyTestResult, String> {
    let vault = read_vault(&app)?;
    let profile = vault.profiles.into_iter().find(|profile| profile.id == profile_id)
        .ok_or_else(|| "Saved proxy profile was not found.".to_string())?;
    profile.validate()?;
    let record = profile.to_catalog_record();
    tokio::task::spawn_blocking(move || crate::proxy::run_proxy_test(record))
        .await.map_err(|e| format!("Profile verification task failed: {e}"))
}

#[tauri::command]
pub async fn profile_select(
    app: AppHandle,
    state: State<'_, crate::engine::EngineState>,
    profile_id: String,
) -> Result<crate::proxy::ProxyTestResult, String> {
    let vault = read_vault(&app)?;
    let profile = vault.profiles.into_iter().find(|profile| profile.id == profile_id)
        .ok_or_else(|| "Saved proxy profile was not found.".to_string())?;
    profile.validate()?;
    let record = profile.to_catalog_record();
    let test_record = record.clone();
    let result = tokio::task::spawn_blocking(move || crate::proxy::run_proxy_test(test_record))
        .await.map_err(|e| format!("Profile verification task failed: {e}"))?;
    if !result.success {
        return Err(format!("Профиль не прошёл проверку Telegram DC2: {}", result.detail));
    }
    state.select_upstream(profile.to_engine_upstream()).await?;
    set_selected_id(&app, Some(profile.id))?;
    Ok(result)
}

#[tauri::command]
pub async fn profile_clear(
    app: AppHandle,
    state: State<'_, crate::engine::EngineState>,
) -> Result<(), String> {
    state.clear_upstream().await?;
    set_selected_id(&app, None)
}

#[tauri::command]
pub async fn profile_delete(
    app: AppHandle,
    state: State<'_, crate::engine::EngineState>,
    profile_id: String,
) -> Result<ProfileSnapshot, String> {
    let mut vault = read_vault(&app)?;
    vault.profiles.retain(|profile| profile.id != profile_id);
    vault.favorite_ids.remove(&profile_id);
    if vault.selected_id.as_deref() == Some(profile_id.as_str()) {
        state.clear_upstream().await?;
        vault.selected_id = None;
    }
    write_vault(&app, &vault)?;
    Ok(snapshot(vault))
}

#[tauri::command]
pub fn favorite_toggle(app: AppHandle, proxy_id: String) -> Result<ProfileSnapshot, String> {
    let document = crate::proxy::find_record(&app, &proxy_id)?;
    let mut vault = read_vault(&app)?;
    if !vault.favorite_ids.insert(proxy_id.clone()) {
        vault.favorite_ids.remove(&proxy_id);
    }
    let _ = document;
    write_vault(&app, &vault)?;
    Ok(snapshot(vault))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyHistoryEntry {
    pub id: String,
    pub timestamp_ms: u64,
    pub endpoint: String,
    pub protocol: String,
    pub action: String,
    pub success: bool,
    pub elapsed_ms: u64,
    pub detail: String,
}

fn history_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|e| format!("App data directory: {e}"))?;
    fs::create_dir_all(&dir).map_err(|e| format!("Create app data directory: {e}"))?;
    Ok(dir.join("proxy-history-v1.json"))
}

pub(crate) fn history_append(
    app: &AppHandle,
    endpoint: &str,
    protocol: &str,
    action: &str,
    success: bool,
    elapsed_ms: u64,
    detail: &str,
) -> Result<(), String> {
    let path = history_path(app)?;
    let existing = fs::read_to_string(&path).unwrap_or_else(|_| "[]".into());
    let mut entries: Vec<ProxyHistoryEntry> = serde_json::from_str(&existing).unwrap_or_default();
    let timestamp_ms = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
    entries.insert(0, ProxyHistoryEntry {
        id: format!("{}-{timestamp_ms}", entries.len()),
        timestamp_ms,
        endpoint: endpoint.to_string(),
        protocol: protocol.to_string(),
        action: action.to_string(),
        success,
        elapsed_ms,
        detail: detail.chars().take(300).collect(),
    });
    entries.truncate(HISTORY_LIMIT);
    let serialized = serde_json::to_vec_pretty(&entries).map_err(|e| e.to_string())?;
    let temporary = path.with_extension("json.tmp");
    fs::write(&temporary, serialized).map_err(|e| format!("Write proxy history: {e}"))?;
    fs::rename(&temporary, path).map_err(|e| format!("Commit proxy history: {e}"))
}

#[tauri::command]
pub fn history_load(app: AppHandle) -> Result<Vec<ProxyHistoryEntry>, String> {
    let path = history_path(&app)?;
    if !path.exists() { return Ok(Vec::new()); }
    let raw = fs::read_to_string(path).map_err(|e| e.to_string())?;
    serde_json::from_str(&raw).map_err(|e| format!("Parse proxy history: {e}"))
}

#[tauri::command]
pub fn history_clear(app: AppHandle) -> Result<(), String> {
    let path = history_path(&app)?;
    if path.exists() { fs::remove_file(path).map_err(|e| e.to_string())?; }
    Ok(())
}
