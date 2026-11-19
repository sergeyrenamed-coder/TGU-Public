//! Windows-scoped encrypted RNMD ID session storage.
//! Credential Manager only holds a 64-ASCII-character AES-256 key, never the
//! potentially oversized Supabase access/refresh tokens (Windows has a 2560-byte
//! credential blob limit; native keyring strings are UTF-16).
use aes_gcm::{aead::{Aead, KeyInit}, Aes256Gcm, Nonce};
use keyring::{Entry, Error as KeyringError};
use rand::RngCore;
use std::{fs, io::ErrorKind, path::PathBuf};
use tauri::{AppHandle, Manager};

const SERVICE: &str = "com.rnmdteam.tgu.desktop";
const KEY_ACCOUNT: &str = "rnmd-id-desktop-aes256-key-v2";
const LEGACY_ACCOUNT: &str = "rnmd-id-desktop-session-v1";
const FILE_NAME: &str = "rnmd-id-session-v2.enc";
const MAGIC: &[u8] = b"TGUID2\0";
const MAX_SESSION: usize = 64 * 1024;

fn credential(account: &str) -> Result<Entry, String> {
    Entry::new(SERVICE, account).map_err(|_| "Windows Credential Manager недоступен.".to_string())
}

fn path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|_| "Папка данных TGU недоступна.".to_string())?;
    fs::create_dir_all(&dir).map_err(|_| "Не удалось создать папку RNMD ID.".to_string())?;
    Ok(dir.join(FILE_NAME))
}

fn hex_encode(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{b:02x}")).collect() }

fn hex_decode_key(raw: &str) -> Result<[u8; 32], String> {
    if raw.len() != 64 || !raw.bytes().all(|x| x.is_ascii_hexdigit()) {
        return Err("Ключ RNMD ID повреждён.".into());
    }
    let mut key = [0u8; 32];
    for (i, byte) in key.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&raw[i * 2..i * 2 + 2], 16)
            .map_err(|_| "Ключ RNMD ID повреждён.".to_string())?;
    }
    Ok(key)
}

fn get_key() -> Result<[u8; 32], String> {
    let entry = credential(KEY_ACCOUNT)?;
    match entry.get_password() {
        Ok(raw) => hex_decode_key(&raw),
        Err(KeyringError::NoEntry) => {
            let mut key = [0u8; 32];
            rand::rngs::OsRng.fill_bytes(&mut key);
            entry.set_password(&hex_encode(&key))
                .map_err(|e| format!("Не удалось сохранить ключ RNMD ID в Windows Credential Manager: {e}"))?;
            Ok(key)
        }
        Err(e) => Err(format!("Не удалось прочитать ключ RNMD ID: {e}")),
    }
}

fn encrypt(key: &[u8; 32], data: &[u8]) -> Result<Vec<u8>, String> {
    if data.len() > MAX_SESSION { return Err("Ответ RNMD ID слишком велик.".into()); }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| "Ошибка шифрования RNMD ID.".to_string())?;
    let mut nonce = [0u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let ct = cipher.encrypt(Nonce::from_slice(&nonce), data)
        .map_err(|_| "Не удалось зашифровать сессию RNMD ID.".to_string())?;
    let mut result = Vec::with_capacity(MAGIC.len() + nonce.len() + ct.len());
    result.extend_from_slice(MAGIC);
    result.extend_from_slice(&nonce);
    result.extend_from_slice(&ct);
    Ok(result)
}

fn decrypt(key: &[u8; 32], body: &[u8]) -> Result<Vec<u8>, String> {
    let start = MAGIC.len();
    if !body.starts_with(MAGIC) || body.len() < start + 12 + 16 ||
        body.len() > start + 12 + 16 + MAX_SESSION {
        return Err("Зашифрованная сессия RNMD ID повреждена.".into());
    }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| "Ошибка расшифровки RNMD ID.".to_string())?;
    cipher.decrypt(Nonce::from_slice(&body[start..start + 12]), &body[start + 12..])
        .map_err(|_| "Не удалось расшифровать сессию RNMD ID.".into())
}

fn remove_credential(account: &str) -> Result<(), String> {
    match credential(account)?.delete_credential() {
        Ok(()) | Err(KeyringError::NoEntry) => Ok(()),
        Err(_) => Err("Не удалось очистить запись RNMD ID в Windows Credential Manager.".into()),
    }
}

pub(crate) fn load(app: &AppHandle) -> Result<Option<String>, String> {
    let file_path = path(app)?;
    let body = match fs::read(&file_path) {
        Ok(body) => body,
        Err(e) if e.kind() == ErrorKind::NotFound => {
            // One-time migration for previously successful v1 sessions.
            return match credential(LEGACY_ACCOUNT)?.get_password() {
                Ok(value) => { save(app, &value)?; Ok(Some(value)) }
                Err(KeyringError::NoEntry) => Ok(None),
                Err(_) => Err("Не удалось прочитать старую сессию RNMD ID.".into()),
            };
        },
        Err(_) => return Err("Не удалось прочитать файл сессии RNMD ID.".into()),
    };
    String::from_utf8(decrypt(&get_key()?, &body)?)
        .map(Some).map_err(|_| "Файл RNMD ID содержит повреждённые данные.".into())
}

pub(crate) fn save(app: &AppHandle, session_json: &str) -> Result<(), String> {
    let encrypted = encrypt(&get_key()?, session_json.as_bytes())?;
    let file_path = path(app)?;
    let temporary = file_path.with_extension("enc.tmp");
    fs::write(&temporary, encrypted)
        .map_err(|_| "Не удалось записать зашифрованную сессию RNMD ID.".to_string())?;
    fs::rename(&temporary, &file_path)
        .map_err(|_| "Не удалось завершить сохранение сессии RNMD ID.".to_string())?;
    // Old credentials may not exist. Failure to remove them does not invalidate
    // the new ciphertext. Never log tokens.
    let _ = remove_credential(LEGACY_ACCOUNT);
    Ok(())
}

pub(crate) fn delete(app: &AppHandle) -> Result<(), String> {
    match fs::remove_file(path(app)?) {
        Ok(()) => {},
        Err(e) if e.kind() == ErrorKind::NotFound => {},
        Err(_) => return Err("Не удалось удалить локальную сессию RNMD ID.".into()),
    }
    // Even if an encryption key remains, tokens are absent from disk.
    remove_credential(LEGACY_ACCOUNT)?;
    remove_credential(KEY_ACCOUNT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn large_session_survives_encryption() {
        let key = [42u8; 32];
        let raw = format!(r#"{{"access_token":"{}","refresh_token":"{}"}}"#,
            "a".repeat(5000), "b".repeat(1700));
        assert!(raw.len() > 2560);
        let data = encrypt(&key, raw.as_bytes()).unwrap();
        assert_ne!(data, raw.as_bytes());
        assert_eq!(decrypt(&key, &data).unwrap(), raw.as_bytes());
    }

    #[test]
    fn tampered_session_or_wrong_key_fails() {
        let key = [7u8; 32];
        let mut body = encrypt(&key, b"token").unwrap();
        let i = body.len() - 1;
        body[i] ^= 1;
        assert!(decrypt(&key, &body).is_err());
        let clean = encrypt(&key, b"token").unwrap();
        assert!(decrypt(&[8u8; 32], &clean).is_err());
    }

    #[test]
    fn key_stays_below_windows_credential_limit() {
        assert_eq!(hex_encode(&[1u8; 32]).len(), 64);
        assert_eq!(hex_decode_key(&hex_encode(&[1u8; 32])).unwrap(), [1u8; 32]);
        assert!(hex_decode_key("oops").is_err());
    }
}
