//! Optional RU-cloud protocol evidence. Never treated as device-side availability.
use std::{collections::HashMap, fs, time::{Duration, SystemTime, UNIX_EPOCH}};
use tauri::{AppHandle, Manager};

const RU_HEALTH_URL: &str =
    "https://raw.githubusercontent.com/sergeyrenamed-coder/TGU-Proxy-Directory/main/data/ru-health.json";
const MAX_AGE_SECONDS: i64 = 24 * 60 * 60;
const MAX_FEED_BYTES: usize = 1_000_000;

fn unix_seconds() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64
}

fn valid_time(value: Option<&str>, now: i64) -> bool {
    chrono::DateTime::parse_from_rfc3339(value.unwrap_or(""))
        .map(|t| {
            let seconds = t.timestamp();
            seconds <= now && now - seconds < MAX_AGE_SECONDS
        })
        .unwrap_or(false)
}

pub fn parse(raw: &str, now: i64) -> Option<HashMap<String, u64>> {
    if raw.len() > MAX_FEED_BYTES { return None; }
    let root: serde_json::Value = serde_json::from_str(raw).ok()?;
    if root.get("schema_version")?.as_u64()? != 1
        || root.get("checker")?.as_str()? != "yandex_cloud_ru"
        || root.get("status")?.as_str()? != "active"
        || root.get("ttl_hours")?.as_u64()? != 24
        || !valid_time(root.get("generated_at")?.as_str(), now)
    {
        return None;
    }
    let rows = root.get("checks")?.as_array()?;
    let mut good = HashMap::new();
    for row in rows {
        let Some(id) = row.get("proxy_id").and_then(|v| v.as_str()) else { continue };
        if id.len() != 20 || !id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
            continue;
        }
        if row.get("status").and_then(|v| v.as_str()) != Some("working")
            || !valid_time(row.get("checked_at").and_then(|v| v.as_str()), now)
        {
            continue;
        }
        let Some(ms) = row.get("latency_ms").and_then(|v| v.as_u64()) else { continue };
        if ms <= 60_000 { good.insert(id.to_owned(), ms); }
    }
    Some(good)
}

fn cache_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    app.path().app_data_dir().ok().map(|dir| dir.join("ru-cloud-health-v1.json"))
}

pub fn cached(app: &AppHandle) -> HashMap<String, u64> {
    cache_path(app)
        .and_then(|path| fs::read_to_string(path).ok())
        .and_then(|raw| parse(&raw, unix_seconds()))
        .unwrap_or_default()
}

/// Retry is handled by the next catalog refresh; old evidence expires naturally.
pub async fn refresh(app: &AppHandle) {
    let Some(path) = cache_path(app) else { return };
    let Ok(client) = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(7))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent("TGU-Desktop/RU-checker")
        .build() else { return };
    let Ok(response) = client.get(RU_HEALTH_URL).send().await else { return };
    if !response.status().is_success() || response.url().host_str() != Some("raw.githubusercontent.com") {
        return;
    }
    let Ok(body) = response.text().await else { return };
    if parse(&body, unix_seconds()).is_none() { return; }
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::write(path, body);
}

/// Ten percent RU preference only after successful local probes.
pub fn scored_local_latency(latency: u64, ru_verified: bool) -> u64 {
    if ru_verified { latency.saturating_mul(9) / 10 } else { latency }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "400e228c511c694acb13";
    fn feed(status: &str, timestamp: &str) -> String {
        format!(r#"{{"schema_version":1,"checker":"yandex_cloud_ru",
          "status":"active","ttl_hours":24,"generated_at":"2026-11-19T01:00:00Z",
          "checks":[{{"proxy_id":"{ID}","status":"{status}",
          "latency_ms":203,"checked_at":"{timestamp}"}}]}}"#)
    }
    const NOW: i64 = 1_795_053_600; // 2026-11-19T02:00:00Z

    #[test]
    fn parses_recent_successful_ru_probe() {
        assert_eq!(parse(&feed("working", "2026-11-19T01:30:00Z"), NOW)
            .unwrap().get(ID).copied(), Some(203));
    }
    #[test]
    fn failed_or_stale_does_not_create_ru_evidence() {
        assert!(parse(&feed("failed", "2026-11-19T01:30:00Z"), NOW).unwrap().is_empty());
        assert!(parse(&feed("working", "2026-11-17T01:30:00Z"), NOW).unwrap().is_empty());
        assert!(parse(&feed("working", "2026-11-19T01:30:00Z").replace("yandex_cloud_ru","other"), NOW).is_none());
    }
    #[test]
    fn ru_is_only_a_modest_bias() {
        assert!(scored_local_latency(105, true) < scored_local_latency(100, false));
        assert!(scored_local_latency(200, true) > scored_local_latency(100, false));
    }
}
