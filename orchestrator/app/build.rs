//! Build script for the desktop app.
//!
//! # Why this does more than call `tauri_build::build()`
//!
//! `tauri.conf.json` declares the headless binary as an `externalBin`, so the app ships the shim an
//! MCP client spawns. That declaration turns the sidecar into a **compile-time** requirement rather
//! than a bundling one: `tauri-build` resolves it while the app crate is being built, and fails with
//! `resource path ... doesn't exist` if it is absent.
//!
//! Which means a plain `cargo test --workspace` — in CI, or in a fresh clone — cannot build the
//! workspace at all unless somebody remembered to stage a binary first. That is a miserable thing to
//! hand a newcomer, and it is exactly how this broke on the first push.
//!
//! So a placeholder is created when the real binary is not there. The release build stages the real
//! one before bundling (`.github/scripts/stage-orchestrator-sidecar.sh`, an explicit step that fails
//! loudly if the binary has not been built), so the placeholder never reaches an installer. When it
//! is used, `cargo` prints a warning saying so.

use std::path::PathBuf;

fn main() {
    if let Err(error) = ensure_sidecar() {
        // Not fatal: let tauri-build produce its own, clearer error about the missing resource
        // rather than failing here with something about file permissions.
        println!("cargo:warning=could not prepare a placeholder sidecar: {error}");
    }
    tauri_build::build()
}

/// Makes sure `binaries/mcmcp-orchestrator-<triple><ext>` exists, one way or another.
fn ensure_sidecar() -> std::io::Result<()> {
    // TARGET is what cargo is building for, which is the suffix tauri expects — and it is correct
    // for a cross build, where the host triple would not be.
    let triple = std::env::var("TARGET").unwrap_or_default();
    if triple.is_empty() {
        return Ok(());
    }
    let extension = if triple.contains("windows") { ".exe" } else { "" };

    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap_or_default());
    let destination = manifest
        .join("binaries")
        .join(format!("mcmcp-orchestrator-{triple}{extension}"));

    println!("cargo:rerun-if-changed={}", destination.display());
    if destination.exists() {
        return Ok(());
    }

    std::fs::create_dir_all(destination.parent().expect("binaries directory"))?;
    std::fs::write(
        &destination,
        b"mcmcp-orchestrator sidecar placeholder\n\
          \n\
          This is not the shim. It exists so the workspace builds without one, because declaring\n\
          the shim as a Tauri externalBin makes it a compile-time requirement of this crate.\n\
          \n\
          A release stages the real binary over this file before bundling. If you are reading this\n\
          inside an installed copy of the app, that staging did not happen and the installer is\n\
          broken: run .github/scripts/stage-orchestrator-sidecar.sh before `cargo tauri build`.\n",
    )?;

    println!(
        "cargo:warning=no shim staged at {}; wrote a placeholder. Fine for tests and a dev run; \
         run .github/scripts/stage-orchestrator-sidecar.sh before bundling a release.",
        destination.display()
    );
    Ok(())
}
