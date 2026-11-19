use serde::Serialize;
use std::{
    path::{Path, PathBuf},
    process::{Command, Stdio},
};
use tauri::{AppHandle, Manager};

#[derive(Debug, Clone, Serialize)]
pub struct DpiProbeStatus {
    pub platform: String,
    pub status: String,
    pub elevated: bool,
    pub winws2_found: bool,
    pub windivert_dll_found: bool,
    pub windivert_driver_found: bool,
    pub cygwin_runtime_found: bool,
    pub runtime_started: bool,
    pub resource_directory: String,
    pub message: String,
}

fn contains_required_files(root: &Path) -> (bool, bool, bool, bool) {
    (
        root.join("winws2.exe").is_file(),
        root.join("WinDivert.dll").is_file(),
        root.join("WinDivert64.sys").is_file(),
        root.join("cygwin1.dll").is_file(),
    )
}

#[cfg(windows)]
fn is_elevated() -> bool {
    Command::new("net")
        .arg("session")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

#[cfg(not(windows))]
fn is_elevated() -> bool {
    false
}

fn resource_candidates(app: &AppHandle) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(resource_dir) = app.path().resource_dir() {
        candidates.push(resource_dir.join("dpi").join("windows"));
        candidates.push(resource_dir.join("resources").join("dpi").join("windows"));
    }
    if let Ok(executable) = std::env::current_exe() {
        if let Some(parent) = executable.parent() {
            candidates.push(parent.join("dpi").join("windows"));
            candidates.push(parent.join("resources").join("dpi").join("windows"));
            candidates.push(parent.to_path_buf());
        }
    }
    candidates
}

#[tauri::command]
pub fn dpi_probe(app: AppHandle) -> DpiProbeStatus {
    #[cfg(windows)]
    {
        let candidates = resource_candidates(&app);
        let root = candidates
            .iter()
            .find(|path| {
                let (exe, dll, driver, cygwin) = contains_required_files(path);
                exe || dll || driver || cygwin
            })
            .cloned()
            .or_else(|| candidates.first().cloned())
            .unwrap_or_else(|| PathBuf::from("resources/dpi/windows"));

        let (winws2, windivert_dll, windivert_driver, cygwin) =
            contains_required_files(&root);
        let elevated = is_elevated();
        let files_ready = winws2 && windivert_dll && windivert_driver && cygwin;
        let status = if !files_ready {
            "dependencies_missing"
        } else if !elevated {
            "admin_required"
        } else {
            "ready_for_runtime"
        };
        let missing = [
            (!winws2).then_some("winws2.exe"),
            (!windivert_dll).then_some("WinDivert.dll"),
            (!windivert_driver).then_some("WinDivert64.sys"),
            (!cygwin).then_some("cygwin1.dll"),
        ]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>();

        let message = if !missing.is_empty() {
            format!(
                "PoC: отсутствуют компоненты {}. Перехват трафика не запускался.",
                missing.join(", ")
            )
        } else if !elevated {
            "Компоненты найдены. Для запуска WinDivert потребуется отдельный UAC-подтверждённый helper. Перехват трафика не запускался.".into()
        } else {
            "Компоненты найдены и права администратора доступны. Это только проверка готовности: DPI-процесс ещё не запускается.".into()
        };

        DpiProbeStatus {
            platform: "windows".into(),
            status: status.into(),
            elevated,
            winws2_found: winws2,
            windivert_dll_found: windivert_dll,
            windivert_driver_found: windivert_driver,
            cygwin_runtime_found: cygwin,
            runtime_started: false,
            resource_directory: root.display().to_string(),
            message,
        }
    }

    #[cfg(not(windows))]
    {
        let _ = app;
        DpiProbeStatus {
            platform: std::env::consts::OS.into(),
            status: "unsupported".into(),
            elevated: false,
            winws2_found: false,
            windivert_dll_found: false,
            windivert_driver_found: false,
            cygwin_runtime_found: false,
            runtime_started: false,
            resource_directory: String::new(),
            message: "Windows DPI adapter is only available in a Windows build.".into(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_runtime_files_are_not_reported_as_ready() {
        let root = std::env::temp_dir().join(format!("tgu-dpi-empty-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let (exe, dll, driver, cygwin) = contains_required_files(&root);
        assert!(!exe && !dll && !driver && !cygwin);
        let _ = std::fs::remove_dir_all(&root);
    }
}
