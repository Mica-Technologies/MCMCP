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

!macro NSIS_HOOK_PREINSTALL
  !insertmacro CheckIfAppIsRunning "mcmcp-orchestrator.exe" "the MCMCP Orchestrator shim"
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro CheckIfAppIsRunning "mcmcp-orchestrator.exe" "the MCMCP Orchestrator shim"
!macroend
