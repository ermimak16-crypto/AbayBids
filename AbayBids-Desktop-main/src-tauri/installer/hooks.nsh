; Custom install/uninstall steps for AbayBids.
; Tauri's NSIS bundler calls these macros automatically if they are defined.
; See: bundle.windows.nsis.installerHooks in tauri.conf.json

!macro NSIS_HOOK_POSTINSTALL
  ; Per-user data folders the app reads/writes at runtime.
  ; (Same folders the app also creates itself on first launch via Rust,
  ; this just guarantees they exist immediately after install.)
  CreateDirectory "$APPDATA\AbayBids"
  CreateDirectory "$APPDATA\AbayBids\data"
  CreateDirectory "$APPDATA\AbayBids\backups"
  CreateDirectory "$APPDATA\AbayBids\exports"
  CreateDirectory "$APPDATA\AbayBids\documents"

  ; A friendlier folder in the user's Documents for exported bids/reports.
  CreateDirectory "$DOCUMENTS\AbayBids"

  ; Belt-and-braces desktop shortcut (createDesktopShortcut in
  ; tauri.conf.json already does this on current Tauri releases; this
  ; line makes sure it happens even if that option is unavailable).
  CreateShortcut "$DESKTOP\AbayBids.lnk" "$INSTDIR\abaybids.exe" "" "$INSTDIR\abaybids.exe" 0
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  Delete "$DESKTOP\AbayBids.lnk"
  ; NOTE: we deliberately do NOT delete $APPDATA\AbayBids or
  ; $DOCUMENTS\AbayBids on uninstall, so the user's tender/bid data
  ; survives an uninstall/reinstall. Remove manually if a full wipe
  ; is ever needed.
!macroend
