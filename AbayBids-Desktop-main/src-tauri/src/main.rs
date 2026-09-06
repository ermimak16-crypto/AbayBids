// Prevents an extra console window from popping up on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs;
use std::process::Command;
use tauri::image::Image;
use tauri::webview::DownloadEvent;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

// The icon is embedded straight into the binary at compile time, so it's
// always available regardless of how/where the app is installed or run
// from, and is used explicitly for the window/taskbar icon below.
static APP_ICON_PNG: &[u8] = include_bytes!("../icons/icon.png");

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // If the user double-clicks the desktop shortcut while the app
            // is already running, just bring the existing window to front
            // instead of opening a second window.
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .setup(|app| {
            // Create the local folders the app uses for data, backups and
            // exported documents the first time it ever runs.
            // Windows: %APPDATA%\AbayBids
            let app_data_dir = app
                .path()
                .app_data_dir()
                .expect("could not resolve app data directory");

            for sub in ["data", "backups", "exports", "documents"] {
                let dir = app_data_dir.join(sub);
                if let Err(e) = fs::create_dir_all(&dir) {
                    eprintln!("Could not create {:?}: {}", dir, e);
                }
            }

            // ---- Desktop shortcut (self-healing, independent of the installer) ----
            // The NSIS installer already creates a Desktop shortcut, but in
            // case the app was copied/run outside of a fresh install, make
            // sure the shortcut exists too, the first time the app launches.
            let shortcut_marker = app_data_dir.join(".desktop_shortcut_created");
            if !shortcut_marker.exists() {
                if let Ok(exe_path) = std::env::current_exe() {
                    if let Some(desktop) = dirs_desktop() {
                        let shortcut_path = desktop.join("AbayBids.lnk");
                        let ps_script = format!(
                            "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('{lnk}'); \
                             $s.TargetPath = '{exe}'; \
                             $s.WorkingDirectory = '{dir}'; \
                             $s.IconLocation = '{exe},0'; \
                             $s.Save()",
                            lnk = shortcut_path.display(),
                            exe = exe_path.display(),
                            dir = exe_path.parent().map(|p| p.display().to_string()).unwrap_or_default(),
                        );
                        let result = Command::new("powershell")
                            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_script])
                            .output();
                        match result {
                            Ok(out) if out.status.success() => {
                                let _ = fs::write(&shortcut_marker, b"ok");
                            }
                            Ok(out) => eprintln!(
                                "Desktop shortcut script failed: {}",
                                String::from_utf8_lossy(&out.stderr)
                            ),
                            Err(e) => eprintln!("Could not run shortcut script: {}", e),
                        }
                    }
                }
            }

            // Build a single, plain, native application window: a normal
            // title bar with minimize/maximize/close, no browser chrome,
            // no address/url bar, no File/Edit/View menu bar.
            let icon = Image::from_bytes(APP_ICON_PNG).expect("invalid embedded app icon");

            WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("Abay Technical & Trading s.c | Smart Tender System")
                .inner_size(1400.0, 900.0)
                .min_inner_size(1024.0, 700.0)
                .resizable(true)
                .maximizable(true)
                .minimizable(true)
                .closable(true)
                .decorations(true)
                .center()
                .icon(icon)?
                // The dashboard exports PDFs/Excel/Word files via in-page
                // JS (jsPDF / xlsx / jszip). Allowing downloads here makes
                // those exports pop the normal Windows "Save As" dialog,
                // just like a real desktop app, instead of silently doing
                // nothing.
                .on_download(|_webview, event| {
                    matches!(
                        event,
                        DownloadEvent::Requested { .. } | DownloadEvent::Finished { .. }
                    )
                })
                .build()
                .expect("failed to build main window");

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running AbayBids");
}

/// Resolves the current user's Desktop folder on Windows without pulling in
/// an extra crate (reads the standard `USERPROFILE` env var, which is
/// always set on Windows).
fn dirs_desktop() -> Option<std::path::PathBuf> {
    std::env::var_os("USERPROFILE").map(|p| std::path::PathBuf::from(p).join("Desktop"))
}
