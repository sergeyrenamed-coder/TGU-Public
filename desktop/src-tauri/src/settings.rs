use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf};
use tauri::{AppHandle, Manager};

fn default_feedback_enabled() -> bool { true }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DesktopSettings {
    pub auto_failover_enabled: bool,
    pub check_interval_seconds: u64,
    pub failure_threshold: usize,
    pub catalog_refresh_minutes: u64,
    pub failed_profile_cooldown_seconds: u64,
    pub switch_cooldown_seconds: u64,
    pub auto_optimize_connection: bool,
    pub cloudflare_websocket_enabled: bool,
    pub direct_websocket_first: bool,
    pub websocket_ping_interval_seconds: u64,
    #[serde(default = "default_feedback_enabled")]
    pub community_feedback_enabled: bool,
    #[serde(default)]
    pub community_feedback_preference_set: bool,
    #[serde(default)]
    pub telegram_proxy_link_fingerprint: Option<String>,
}

impl Default for DesktopSettings {
    fn default() -> Self {
        Self {
            auto_failover_enabled: true,
            check_interval_seconds: 60,
            failure_threshold: 3,
            catalog_refresh_minutes: 5,
            failed_profile_cooldown_seconds: 600,
            switch_cooldown_seconds: 90,
            auto_optimize_connection: true,
            cloudflare_websocket_enabled: true,
            direct_websocket_first: true,
            websocket_ping_interval_seconds: 180,
            community_feedback_enabled: true,
            community_feedback_preference_set: true,
            telegram_proxy_link_fingerprint: None,
        }
    }
}

impl DesktopSettings {
    pub fn validate(&self) -> Result<(), String> {
        if !(30..=180).contains(&self.check_interval_seconds) {
            return Err("Health-check interval must be between 30 and 180 seconds.".into());
        }
        if !(2..=5).contains(&self.failure_threshold) {
            return Err("Failure threshold must be between 2 and 5.".into());
        }
        if !(2..=30).contains(&self.catalog_refresh_minutes) {
            return Err("Catalog refresh interval must be between 2 and 30 minutes.".into());
        }
        if !(120..=3600).contains(&self.failed_profile_cooldown_seconds) {
            return Err("Failed-proxy cooldown must be between 2 and 60 minutes.".into());
        }
        if !(30..=600).contains(&self.switch_cooldown_seconds) {
            return Err("Failover cooldown must be between 30 and 600 seconds.".into());
        }
        if !(30..=600).contains(&self.websocket_ping_interval_seconds) {
            return Err("WebSocket ping interval must be between 30 and 600 seconds.".into());
        }
        Ok(())
    }
}

fn settings_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|e| format!("App data directory: {e}"))?;
    fs::create_dir_all(&dir).map_err(|e| format!("Create app data directory: {e}"))?;
    Ok(dir.join("desktop-settings-v1.json"))
}

pub fn load(app: &AppHandle) -> Result<DesktopSettings, String> {
    let path = settings_path(app)?;
    if !path.exists() {
        return Ok(DesktopSettings::default());
    }
    let raw = fs::read_to_string(path).map_err(|e| format!("Read desktop settings: {e}"))?;
    let mut settings: DesktopSettings = serde_json::from_str(&raw)
        .map_err(|e| format!("Parse desktop settings: {e}"))?;
    let mut needs_save = false;

    // Older releases defaulted feedback to off and did not distinguish a
    // default value from an explicit user choice. Enable it once on upgrade;
    // after this migration, the user's opt-out is preserved.
    if !settings.community_feedback_preference_set {
        settings.community_feedback_enabled = true;
        settings.community_feedback_preference_set = true;
        needs_save = true;
    }

    // Values written by an older/newer build are always validated before use.
    if settings.validate().is_err() {
        settings = DesktopSettings::default();
        needs_save = true;
    }

    if needs_save {
        save(app, &settings)?;
    }
    Ok(settings)
}

pub fn save(app: &AppHandle, settings: &DesktopSettings) -> Result<(), String> {
    settings.validate()?;
    let path = settings_path(app)?;
    let temporary = path.with_extension("json.tmp");
    let serialized = serde_json::to_vec_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(&temporary, serialized).map_err(|e| format!("Write desktop settings: {e}"))?;
    fs::rename(&temporary, path).map_err(|e| format!("Commit desktop settings: {e}"))
}

#[tauri::command]
pub fn settings_load(app: AppHandle) -> Result<DesktopSettings, String> {
    load(&app)
}

#[tauri::command]
pub fn settings_save(app: AppHandle, mut settings: DesktopSettings) -> Result<DesktopSettings, String> {
    settings.community_feedback_preference_set = true;
    save(&app, &settings)?;
    Ok(settings)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_android_monitoring_policy() {
        let settings = DesktopSettings::default();
        assert!(settings.auto_failover_enabled);
        assert_eq!(settings.check_interval_seconds, 60);
        assert_eq!(settings.failure_threshold, 3);
        assert_eq!(settings.catalog_refresh_minutes, 5);
        assert!(settings.validate().is_ok());
    }

    #[test]
    fn rejects_out_of_range_monitoring_values() {
        let mut settings = DesktopSettings::default();
        settings.failure_threshold = 9;
        assert!(settings.validate().is_err());
        settings = DesktopSettings::default();
        settings.check_interval_seconds = 1;
        assert!(settings.validate().is_err());
    }
}
