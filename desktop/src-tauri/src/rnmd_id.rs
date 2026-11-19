//! RNMD ID / Telegram OIDC desktop login using Supabase Auth PKCE.
//! Tokens never reach the WebView. An AES-GCM file stores the session; Windows Credential Manager stores only its short encryption key.
//! The loopback callback must be allowlisted in Supabase Auth Redirect URLs.
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::AppHandle;
use tauri_plugin_opener::OpenerExt;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpListener};
use url::Url;

const SUPABASE_URL: &str = "https://hscsswcevngohjdyetjo.supabase.co";
const PUBLISHABLE_KEY: &str = "sb_publishable_tTZW6Omtev4wdH8TvJ6DTQ_SV-XCh6G";
const REDIRECT: &str = "http://127.0.0.1:18427/callback";
const KEYRING_SERVICE: &str = "com.rnmdteam.tgu.desktop";
const KEYRING_ACCOUNT: &str = "rnmd-id-desktop-session-v1";

#[derive(Clone, Debug, Deserialize, Serialize)]
struct Session {
    access_token: String,
    refresh_token: String,
    expires_at: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct AccountDevice {
    pub id: String,
    pub device_name: String,
    pub platform: String,
    pub last_seen_at: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct AccountOrder {
    pub id: String,
    pub product_code: String,
    pub status: String,
    pub currency: String,
    pub amount_minor: i64,
    pub created_at: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct Account {
    pub user_id: String,
    pub display_name: String,
    pub telegram_username: Option<String>,
    pub avatar_url: Option<String>,
    pub devices: Vec<AccountDevice>,
    pub orders: Vec<AccountOrder>,
    pub has_tgu_plus: bool,
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

fn load_session(app: &AppHandle) -> Result<Option<Session>, String> {
    crate::secure_session::load(app)?
        .map(|data| serde_json::from_str::<Session>(&data)
            .map_err(|_| "Сохранённая сессия RNMD ID повреждена.".to_string()))
        .transpose()
}

fn save_session(app: &AppHandle, session: &Session) -> Result<(), String> {
    let data = serde_json::to_string(session)
        .map_err(|_| "Не удалось подготовить сессию RNMD ID.".to_string())?;
    crate::secure_session::save(app, &data)
}

fn delete_session(app: &AppHandle) -> Result<(), String> {
    crate::secure_session::delete(app)
}

fn base64url(bytes: &[u8]) -> String {
    const ALPH: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut encoded = String::new();
    for chunk in bytes.chunks(3) {
        let b0 = chunk[0];
        let b1 = *chunk.get(1).unwrap_or(&0);
        let b2 = *chunk.get(2).unwrap_or(&0);
        encoded.push(ALPH[(b0 >> 2) as usize] as char);
        encoded.push(ALPH[(((b0 & 3) << 4) | (b1 >> 4)) as usize] as char);
        if chunk.len() > 1 { encoded.push(ALPH[(((b1 & 15) << 2) | (b2 >> 6)) as usize] as char); }
        if chunk.len() > 2 { encoded.push(ALPH[(b2 & 63) as usize] as char); }
    }
    encoded
}

fn random_urlsafe() -> String {
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    base64url(&bytes)
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| "Не удалось создать клиент RNMD ID.".to_string())
}

async fn exchange_code(code: &str, verifier: &str) -> Result<Session, String> {
    let response = client()?.post(format!("{SUPABASE_URL}/auth/v1/token?grant_type=pkce"))
        .header("apikey", PUBLISHABLE_KEY)
        .json(&json!({"auth_code": code, "code_verifier": verifier}))
        .send().await.map_err(|_| "RNMD ID недоступен при обмене кода.".to_string())?;
    if !response.status().is_success() {
        return Err(format!("RNMD ID отклонил вход (HTTP {}). Проверь разрешённый адрес возврата.", response.status().as_u16()));
    }
    let payload: Value = response.json().await.map_err(|_| "RNMD ID вернул некорректную сессию.".to_string())?;
    from_token_response(&payload)
}

fn from_token_response(value: &Value) -> Result<Session, String> {
    let access_token = value["access_token"].as_str().unwrap_or_default();
    let refresh_token = value["refresh_token"].as_str().unwrap_or_default();
    let expires_in = value["expires_in"].as_u64().unwrap_or(0);
    if access_token.is_empty() || refresh_token.is_empty() || !(30..=2_592_000).contains(&expires_in) {
        return Err("RNMD ID вернул неполную или некорректную сессию.".into());
    }
    Ok(Session {
        access_token: access_token.to_owned(),
        refresh_token: refresh_token.to_owned(),
        expires_at: now().saturating_add(expires_in),
    })
}

async fn active_session(app: &AppHandle) -> Result<Option<Session>, String> {
    let Some(mut session) = load_session(app)? else { return Ok(None) };
    if session.expires_at > now().saturating_add(90) { return Ok(Some(session)); }
    let response = client()?.post(format!("{SUPABASE_URL}/auth/v1/token?grant_type=refresh_token"))
        .header("apikey", PUBLISHABLE_KEY)
        .json(&json!({"refresh_token": session.refresh_token}))
        .send().await.map_err(|_| "Не удалось обновить сессию RNMD ID. Проверь интернет.".to_string())?;
    if !response.status().is_success() {
        delete_session(app)?;
        return Ok(None);
    }
    let body: Value = response.json().await.map_err(|_| "Некорректный ответ RNMD ID.".to_string())?;
    session = from_token_response(&body)?;
    save_session(app, &session)?;
    Ok(Some(session))
}

async fn get_account(session: &Session) -> Result<Account, String> {
    let http = client()?;
    let user = http.get(format!("{SUPABASE_URL}/auth/v1/user"))
        .header("apikey", PUBLISHABLE_KEY).bearer_auth(&session.access_token)
        .send().await.map_err(|_| "Не удалось проверить RNMD ID.".to_string())?;
    if !user.status().is_success() { return Err("Сессия RNMD ID больше не действительна.".into()); }
    let user: Value = user.json().await.map_err(|_| "Некорректный профиль RNMD ID.".to_string())?;
    let id = user["id"].as_str().filter(|s| s.len() == 36 && s.bytes().all(|c| c.is_ascii_hexdigit() || c == b'-'))
        .ok_or_else(|| "RNMD ID вернул некорректный идентификатор.".to_string())?;

    let get = |endpoint: &'static str, select: &'static str, additional: &'static str| {
        let http = http.clone();
        let bearer = session.access_token.clone();
        let identity = id.to_string();
        async move {
            let mut url = Url::parse(&format!("{SUPABASE_URL}/rest/v1/{endpoint}")).unwrap();
            url.query_pairs_mut().append_pair("select", select)
                .append_pair("user_id", &format!("eq.{identity}"));
            if !additional.is_empty() {
                for (name, value) in url::form_urlencoded::parse(additional.as_bytes()) {
                    url.query_pairs_mut().append_pair(&name, &value);
                }
            }
            let response = http.get(url).header("apikey", PUBLISHABLE_KEY)
                .bearer_auth(bearer).send().await.ok()?;
            if !response.status().is_success() { return None; }
            response.json::<Vec<Value>>().await.ok()
        }
    };

    // Profile rows use "id", not "user_id".
    let mut url = Url::parse(&format!("{SUPABASE_URL}/rest/v1/profiles")).unwrap();
    url.query_pairs_mut().append_pair("select", "id,telegram_username,display_name,avatar_url")
        .append_pair("id", &format!("eq.{id}")).append_pair("limit", "1");
    let profile_rows = http.get(url).header("apikey", PUBLISHABLE_KEY)
        .bearer_auth(&session.access_token).send().await.ok()
        .and_then(|response| response.error_for_status().ok());
    let profile = match profile_rows {
        Some(response) => response.json::<Vec<Value>>().await.unwrap_or_default().into_iter().next(),
        None => None,
    };
    let profile = profile.unwrap_or(Value::Null);
    let fallback = user["user_metadata"]["full_name"].as_str()
        .or_else(|| user["user_metadata"]["name"].as_str())
        .unwrap_or("Пользователь RNMD");
    let display_name = profile["display_name"].as_str()
        .filter(|s| !s.trim().is_empty()).unwrap_or(fallback).to_string();
    let telegram_username = profile["telegram_username"].as_str().map(str::to_string);
    let avatar_url = profile["avatar_url"].as_str()
        .filter(|url| url.starts_with("https://")).map(str::to_string);

    let devices = get("devices", "id,device_name,platform,last_seen_at",
        "revoked_at=is.null&order=last_seen_at.desc&limit=50").await
        .unwrap_or_default().into_iter()
        .filter_map(|row| serde_json::from_value::<AccountDevice>(row).ok()).collect();
    let orders = get("orders", "id,product_code,status,currency,amount_minor,created_at",
        "order=created_at.desc&limit=20").await
        .unwrap_or_default().into_iter()
        .filter_map(|row| serde_json::from_value::<AccountOrder>(row).ok()).collect();
    let has_tgu_plus = get("entitlements", "product_code,expires_at",
        "status=eq.active&limit=50").await.unwrap_or_default().iter().any(|row|
        row["product_code"].as_str() == Some("TGU_PLUS_LIFETIME") &&
            row["expires_at"].as_str().map(|date|
                chrono::DateTime::parse_from_rfc3339(date)
                    .map(|exp| exp.timestamp() > now() as i64).unwrap_or(false)
            ).unwrap_or(true));

    Ok(Account {
        user_id: id.to_string(),
        display_name,
        telegram_username,
        avatar_url,
        devices,
        orders,
        has_tgu_plus,
    })
}

async fn read_callback(listener: TcpListener, expected_state: String) -> Result<String, String> {
    let task = async {
        loop {
            let (mut socket, addr) = listener.accept().await
                .map_err(|_| "Не удалось получить ответ браузера.".to_string())?;
            if !addr.ip().is_loopback() { continue; }
            let mut buf = [0u8; 8192];
            let n = match tokio::time::timeout(Duration::from_secs(5), socket.read(&mut buf)).await {
                Ok(Ok(n)) if n > 0 && n < buf.len() => n,
                _ => continue,
            };
            let request = String::from_utf8_lossy(&buf[..n]);
            let Some(first) = request.lines().next() else { continue };
            let mut parts = first.split_ascii_whitespace();
            if parts.next() != Some("GET") { continue; }
            let Some(target) = parts.next() else { continue };
            if !target.starts_with("/callback?") || target.len() > 4096 { continue; }
            let url = match Url::parse(&format!("http://127.0.0.1:18427{target}")) {
                Ok(url) => url, Err(_) => continue,
            };
            let args: std::collections::HashMap<String, String> =
                url.query_pairs().into_owned().collect();
            // Supabase Auth may omit the app's state on redirect. When omitted,
            // the single-use PKCE verifier still binds the code to this process.
            if args.get("state").is_some_and(|state| state != &expected_state) { continue; }
            let code = args.get("code").cloned().filter(|x| !x.is_empty() && x.len() < 2048);
            let response = if code.is_some() {
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n<!doctype html><meta charset=utf-8><title>RNMD ID</title><body style='font-family:sans-serif;background:#111;color:white;padding:32px'>RNMD ID: вход подтверждён. Вернись в TGU Desktop.</body>"
            } else {
                "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\nRNMD ID: вход отменён. Вернись в TGU Desktop."
            };
            let _ = socket.write_all(response.as_bytes()).await;
            let _ = socket.shutdown().await;
            return code.ok_or_else(|| "Вход через Telegram отменён.".to_string());
        }
    };
    tokio::time::timeout(Duration::from_secs(180), task).await
        .map_err(|_| "Время ожидания входа RNMD ID истекло.".to_string())?
}

#[tauri::command]
pub async fn rnmd_login(app: AppHandle) -> Result<Account, String> {
    // Bind BEFORE opening browser; prevent two overlapping login attempts.
    let listener = TcpListener::bind(("127.0.0.1", 18427)).await
        .map_err(|_| "Порт 18427 занят. Закрой второй вход RNMD ID и попробуй снова.".to_string())?;
    let verifier = random_urlsafe();
    let challenge = base64url(&Sha256::digest(verifier.as_bytes()));
    let state = random_urlsafe();
    let mut url = Url::parse(&format!("{SUPABASE_URL}/auth/v1/authorize")).unwrap();
    url.query_pairs_mut().append_pair("provider", "custom:telegram")
        .append_pair("redirect_to", REDIRECT)
        .append_pair("code_challenge", &challenge)
        .append_pair("code_challenge_method", "s256")
        .append_pair("state", &state);
    app.opener().open_url(url.as_str(), None::<&str>)
        .map_err(|_| "Не удалось открыть браузер для RNMD ID.".to_string())?;
    let code = read_callback(listener, state).await?;
    let session = exchange_code(&code, &verifier).await?;
    let account = get_account(&session).await?;
    save_session(&app, &session)?;
    Ok(account)
}

#[tauri::command]
pub async fn rnmd_status(app: AppHandle) -> Result<Option<Account>, String> {
    let Some(session) = active_session(&app).await? else { return Ok(None) };
    get_account(&session).await.map(Some)
}

#[tauri::command]
pub async fn rnmd_logout(app: AppHandle) -> Result<(), String> {
    let session = load_session(&app)?;
    delete_session(&app)?; // Clear local tokens even if network logout fails.
    if let Some(session) = session {
        let _ = client()?.post(format!("{SUPABASE_URL}/auth/v1/logout"))
            .header("apikey", PUBLISHABLE_KEY)
            .bearer_auth(session.access_token)
            .send().await;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn base64url_matches_rfc4648() {
        assert_eq!(base64url(b"f"), "Zg");
        assert_eq!(base64url(b"fo"), "Zm8");
        assert_eq!(base64url(b"foo"), "Zm9v");
        assert_eq!(base64url(b"\x00\xff"), "AP8");
        assert_eq!(random_urlsafe().len(), 43);
    }
    #[test]
    fn rejects_empty_sessions() {
        assert!(from_token_response(&json!({"access_token":"","refresh_token":"x","expires_in":3600})).is_err());
    }
}
