# AbayBids Desktop (Tauri)

This turns the "Windows" HTML dashboard from the bundle into a real desktop
app: one plain OS window (title bar, minimize/maximize/close — no address
bar, no tabs, no File/Edit/View menu), installed via a Windows installer
that creates its data folders and a Desktop shortcut automatically.

- `dist/index.html` — the app UI (the existing self-contained dashboard,
  unchanged — same Firebase sync, same login).
- `src-tauri/` — the Rust/Tauri shell that hosts it as a native window.
- `.github/workflows/build-windows.yml` — builds the installer for you in
  the cloud, so you don't need Rust installed anywhere.

## Fastest path: build the installer with GitHub Actions (no local setup)

1. Create a new **empty** GitHub repo (don't add a README/license there).
2. Push this folder to it:
   ```bash
   cd AbayBids-Desktop
   git init
   git add .
   git commit -m "AbayBids desktop app"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. On GitHub, open the **Actions** tab of the repo. The
   "Build Windows installer" workflow starts automatically on that push
   (or click **Run workflow** to trigger it manually).
4. When it finishes (a few minutes), open the run → **Artifacts** →
   download **AbayBids-Windows-Installer**. Inside is `AbayBids_1.0.0_x64-setup.exe`.

That `.exe` is the real installer: running it installs AbayBids, adds a
Start Menu entry, adds a **Desktop shortcut**, and creates its data folders
(`%APPDATA%\AbayBids\...` and `Documents\AbayBids`) — no manual setup needed
by whoever installs it.

## Building locally instead (on a Windows PC)

If you'd rather build on your own Windows machine:

1. Install [Node.js](https://nodejs.org) and [Rust](https://rustup.rs).
2. In this folder:
   ```powershell
   npm install
   npm run build
   ```
3. The installer appears at:
   `src-tauri\target\release\bundle\nsis\AbayBids_1.0.0_x64-setup.exe`

`npm run dev` will launch the app in a live window for testing without
building a full installer.

## What changed vs. the old "Windows/AbayBids-Windows.html"

- Same dashboard code (data, styling, Firebase sync, login) — untouched.
- It now runs inside a proper native window instead of a browser tab, so
  there's no address bar, no browser bookmarks/tabs, and no accidental
  "View source" / "Edit" browser menu — it behaves like a normal installed
  app.
- Exporting PDFs/Excel/Word from the dashboard now pops the normal Windows
  "Save As" dialog instead of relying on browser downloads.
- On first launch (and again at install time) it creates:
  - `%APPDATA%\AbayBids\data`
  - `%APPDATA%\AbayBids\backups`
  - `%APPDATA%\AbayBids\exports`
  - `%APPDATA%\AbayBids\documents`
  - `Documents\AbayBids`
- The installer adds a **Desktop shortcut** and a Start Menu folder named
  **AbayBids**.
- Opening the app while it's already running just focuses the existing
  window instead of opening a second copy.

## Android app

`android/` is the native Android app (Kotlin, Room, Material 3 — **not** a
WebView wrapper; every screen is a real native Activity). It already builds
straight to APK via GitHub Actions, no local Android Studio needed.

- Push to `main`/`master` (touching `android/**`) or use **Run workflow** on
  **Build Android APK** in the Actions tab.
- When it's green, download the **AbayBids-Android-APK** artifact →
  unzip → install the `.apk` on a device (enable "install unknown apps" for
  your file manager/browser first).

This build is **debug-signed** (installable immediately, no keystore setup
needed) — fine for internal testing and side-loading. For a Play Store /
production release, add a real signing keystore as GitHub secrets and switch
the workflow's `assembleDebug` to `assembleRelease` with a signing config;
ask me and I'll wire that up.

### Native-feel polish included
- Real bottom tab bar (Home / Tenders / Add / Calendar / More) — the same
  navigation pattern Facebook/Instagram use — instead of relying on a
  drawer as primary navigation. The drawer still exists for the full menu,
  reached via the **More** tab, exactly like Instagram's "Menu" tab.
- A new app icon shared across Windows and Android: the actual Abay
  mountain mark (pulled straight from the dashboard's own logo) on the
  brand's navy background with a gold accent bar — not a generic stock
  icon.
- Android 12+ splash screen (brand background + logo) instead of a blank
  flash on launch.
- Material You dynamic color: on Android 12+, buttons/FAB/selected nav items
  pick up tones from the user's wallpaper, like other native apps on the
  device; falls back to the fixed brand gold on older Android.
- Predictive back gesture support (Android 13+), migrated off the deprecated
  `onBackPressed()` API so the nav-drawer-closes-first behavior still works
  correctly under it.



- The dashboard still loads a few libraries from CDNs at runtime (Chart.js,
  jsPDF, xlsx, jszip, Google Fonts, Firebase) exactly as it did in the
  browser version, so an internet connection is needed for those features
  and for Firebase sync — this matches the original app's behavior.
- To rebrand the icon, replace `src-tauri/icons/icon.png` (512×512) and
  re-run the generation, or just swap in your own `.ico`/`.png` set.
