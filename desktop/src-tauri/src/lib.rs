mod engine;
mod proxy;
mod ru_health;
mod rnmd_id;
mod warm_standby;
mod profiles;
mod settings;
mod diagnostics;
mod dpi;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(engine::EngineState::default())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            proxy::catalog_load,
            proxy::catalog_refresh,
            proxy::proxy_test,
            proxy::engine_select_upstream,
            proxy::engine_auto_select,
            profiles::profiles_load,
            profiles::profile_save,
            profiles::profile_test,
            profiles::profile_select,
            profiles::profile_clear,
            profiles::profile_delete,
            profiles::favorite_toggle,
            profiles::history_load,
            profiles::history_clear,
            settings::settings_load,
            settings::settings_save,
            diagnostics::diagnostics_run,
            dpi::dpi_probe,
            engine::engine_status,
            engine::engine_start,
            engine::engine_stop,
            engine::engine_clear_upstream,
            engine::engine_open_in_telegram,
            engine::engine_open_in_telegram_if_needed,
            rnmd_id::rnmd_login,
            rnmd_id::rnmd_status,
            rnmd_id::rnmd_logout
        ])
        .run(tauri::generate_context!())
        .expect("error while running TGU Desktop");
}
