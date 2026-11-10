mod engine;
mod proxy;
mod profiles;
mod settings;
mod diagnostics;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(engine::EngineState::default())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            use tauri::{
                menu::{Menu, MenuItem},
                tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
                Manager,
            };

            let show_item = MenuItem::with_id(app, "show", "Открыть TGU", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Полностью выйти", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_item, &quit_item])?;

            let mut tray = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("TGU Desktop")
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.unminimize();
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.unminimize();
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                });

            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }

            tray.build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                                                                           
                                                                    
                api.prevent_close();
                let _ = window.hide();
            }
        })
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
            engine::engine_status,
            engine::engine_start,
            engine::engine_stop,
            engine::engine_clear_upstream,
            engine::engine_open_in_telegram,
            engine::engine_open_in_telegram_if_needed
        ])
        .run(tauri::generate_context!())
        .expect("error while running TGU Desktop");
}
