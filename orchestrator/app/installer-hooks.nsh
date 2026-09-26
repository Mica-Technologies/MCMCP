; Installer hooks for the MCMCP Orchestrator.
;
; Tauri's own installer already deals with the app being open: it finds mcmcp-orchestrator-app.exe,
; asks whether it may close it, and stops if the answer is no. What it does not know about is the
; **shim** — mcmcp-orchestrator.exe, shipped beside the app as a sidecar and spawned by whatever MCP
; client is configured against it.
;
; That is the process that actually blocks an upgrade in practice. The app is something a person
; opened and can see; the shim is started on their behalf by an editor or a desktop client, often
; without them realising it is running at all. It holds an open handle on the file the installer is
; about to overwrite, so without this the upgrade fails on a locked file naming a program the user
; does not remember starting.
;
; CheckIfAppIsRunning is Tauri's own macro, from utils.nsh, and is already included by the point a
; hook is inserted. Reusing it rather than writing another kill loop means the prompt, the silent and
; passive install paths, the per-user versus per-machine process lookup, and the localised strings
; all behave exactly as they do for the app itself — including that a person who answers "cancel"
; cancels the install rather than having their session killed anyway.
;
; # Killing is not enough: the shim comes back
;
; An MCP client whose server exits starts it again, within milliseconds. So a shim killed here can be
; running again from the same path by the time the files are copied, and a running executable cannot
; be overwritten. A silent install skips the failed copy and still exits 0, which left the previous
; shim installed under the new version number: the upgrade reported success and the fix was not in.
;
; A running executable can be renamed, though. So each binary is moved aside before the copy: whatever
; is running keeps running from the .old file, the new binary lands at the real path, and every later
; launch starts it. The .old files are deleted after the install, or by the next one if still in use.
; Both binaries, not only the shim: a relaunched shim starts the app when the app is not running.

!macro MCMCP_MOVE_ASIDE NAME
  Delete "$INSTDIR\${NAME}.old"
  ${If} ${FileExists} "$INSTDIR\${NAME}"
    Rename "$INSTDIR\${NAME}" "$INSTDIR\${NAME}.old"
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro CheckIfAppIsRunning "mcmcp-orchestrator.exe" "the MCMCP Orchestrator shim"
  !insertmacro MCMCP_MOVE_ASIDE "mcmcp-orchestrator.exe"
  !insertmacro MCMCP_MOVE_ASIDE "mcmcp-orchestrator-app.exe"
!macroend

!macro NSIS_HOOK_POSTINSTALL
  Delete "$INSTDIR\mcmcp-orchestrator.exe.old"
  Delete "$INSTDIR\mcmcp-orchestrator-app.exe.old"
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro CheckIfAppIsRunning "mcmcp-orchestrator.exe" "the MCMCP Orchestrator shim"
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  Delete "$INSTDIR\mcmcp-orchestrator.exe.old"
  Delete "$INSTDIR\mcmcp-orchestrator-app.exe.old"
!macroend
