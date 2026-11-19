//! Keep the Rust relay and failover monitor alive when the main window is closed.
//! Exit is an explicit menu action, not a side effect of clicking the titlebar X.
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    App, AppHandle, Manager, Window, WindowEvent,
};

const TRAY_ID: &str = "tgu-desktop-tray";
const OPEN_ID: &str = "tgu-tray-open";
const QUIT_ID: &str = "tgu-tray-quit";

#[derive(Debug, PartialEq, Eq)]
enum MenuAction {
    Open,
    Quit,
    None,
}

fn menu_action(id: &str) -> MenuAction {
    match id {
        OPEN_ID => MenuAction::Open,
        QUIT_ID => MenuAction::Quit,
        _ => MenuAction::None,
    }
}

fn restore_main_window(app: &AppHandle) {
    // Reuse the existing WebView, preserving selection, login and monitor state.
    // Never create a new window/process on a tray click.
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn quit_gracefully(app: &AppHandle) {
    // Do not stop the upstream on hide. Only the explicit tray Quit action
    // stops the listener, letting the process terminate normally afterward.
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        app.state::<crate::engine::EngineState>().stop().await;
        app.exit(0);
    });
}

pub(crate) fn install(app: &mut App) -> Result<(), Box<dyn std::error::Error>> {
    let open = MenuItem::with_id(app, OPEN_ID, "Открыть TGU", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, QUIT_ID, "Полностью выйти", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &quit])?;

    // Reuse the actual bundled TGU icon; never leave the window hidden when
    // the system tray is unavailable or its icon cannot be constructed.
    let icon = app.default_window_icon().cloned().ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::NotFound, "TGU tray icon is missing")
    })?;

    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .tooltip("TGU Desktop — работает в фоне")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match menu_action(event.id().as_ref()) {
            MenuAction::Open => restore_main_window(app),
            MenuAction::Quit => quit_gracefully(app),
            MenuAction::None => {}
        })
        .on_tray_icon_event(|tray, event| match event {
            TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            }
            | TrayIconEvent::DoubleClick {
                button: MouseButton::Left,
                ..
            } => restore_main_window(tray.app_handle()),
            _ => {}
        })
        .build(app)?;
    Ok(())
}

/// Tauri's native CloseRequested is also emitted by the custom React titlebar
/// close() button. Prevent destruction and hide instead: Rust network tasks
/// keep running and Telegram retains its local proxy connection.
pub(crate) fn on_window_event(window: &Window, event: &WindowEvent) {
    if window.label() == "main" {
        if let WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            let _ = window.hide();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn menu_actions_do_not_confuse_hide_with_exit() {
        assert_eq!(menu_action(OPEN_ID), MenuAction::Open);
        assert_eq!(menu_action(QUIT_ID), MenuAction::Quit);
        assert_eq!(menu_action("unknown"), MenuAction::None);
    }
}
