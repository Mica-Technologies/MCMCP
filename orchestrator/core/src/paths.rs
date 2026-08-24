//! Where the orchestrator keeps its state on each platform.
//!
//! Hand-rolled rather than pulling in a directories crate. It is about thirty lines, it is one fewer
//! dependency to keep current, and the conventions it encodes are stable enough that the crate would
//! only be re-stating them.
//!
//! The approval store lives here, and it holds **hashes** of instance secrets — never the secrets
//! themselves, which stay in each game's own config. That is deliberate: an orchestrator that
//! collected the real secrets of every instance on the machine would be a far more interesting file
//! to steal than one that cannot authenticate as anything.

use anyhow::{Context, Result, bail};
use std::path::PathBuf;

/// The directory holding the approval store, the catalogue cache and the event log.
///
/// - Windows: `%LOCALAPPDATA%\mcmcp-orchestrator`
/// - macOS: `~/Library/Application Support/mcmcp-orchestrator`
/// - everywhere else: `$XDG_STATE_HOME/mcmcp-orchestrator`, or `~/.local/state/mcmcp-orchestrator`
///
/// `XDG_STATE_HOME` rather than `XDG_CONFIG_HOME`: this is state the program maintains, not
/// configuration a person edits. The distinction matters to anyone who backs up one and not the
/// other, and an approval store restored onto a different machine would be actively wrong.
pub fn state_directory() -> Result<PathBuf> {
    if let Some(base) = platform_state_base()? {
        return Ok(base.join("mcmcp-orchestrator"));
    }
    bail!("could not determine a state directory: set MCMCP_ORCHESTRATOR_HOME to choose one")
}

fn platform_state_base() -> Result<Option<PathBuf>> {
    // An explicit override first, so a portable install or a test can put state wherever it likes
    // without the platform rules getting a vote.
    if let Some(override_path) = non_empty_env("MCMCP_ORCHESTRATOR_HOME") {
        return Ok(Some(PathBuf::from(override_path)));
    }

    if cfg!(windows) {
        return Ok(non_empty_env("LOCALAPPDATA").map(PathBuf::from));
    }

    if cfg!(target_os = "macos") {
        return Ok(home_directory().map(|home| home.join("Library").join("Application Support")));
    }

    if let Some(state_home) = non_empty_env("XDG_STATE_HOME") {
        return Ok(Some(PathBuf::from(state_home)));
    }
    Ok(home_directory().map(|home| home.join(".local").join("state")))
}

fn home_directory() -> Option<PathBuf> {
    non_empty_env("HOME").or_else(|| non_empty_env("USERPROFILE")).map(PathBuf::from)
}

/// An environment variable that is set *and* not empty.
///
/// Not the same test. An empty `LOCALAPPDATA` is set, and joining a path onto it silently produces a
/// relative path — state written into whatever directory the process happened to start in.
fn non_empty_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.trim().is_empty())
}

pub fn approval_store_path() -> Result<PathBuf> {
    Ok(state_directory()?.join("instances.json"))
}

/// The last tool catalogue seen, served when no instance is connected.
///
/// Without it, relaunching a game client — which in a mod-development loop happens constantly —
/// would empty and refill the tool surface every time, and a client connecting before any game is up
/// would see nothing but the roster tool.
pub fn catalogue_cache_path() -> Result<PathBuf> {
    Ok(state_directory()?.join("catalogue-cache.json"))
}

pub fn event_log_path() -> Result<PathBuf> {
    Ok(state_directory()?.join("events.jsonl"))
}

pub fn ensure_state_directory() -> Result<PathBuf> {
    let directory = state_directory()?;
    std::fs::create_dir_all(&directory)
        .with_context(|| format!("creating {}", directory.display()))?;
    Ok(directory)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex, MutexGuard};

    /// Serialises every test that touches the process environment.
    ///
    /// Rust runs tests in parallel threads by default, and the environment is process-wide: two
    /// tests setting and restoring `MCMCP_ORCHESTRATOR_HOME` at once will interleave, and one will
    /// restore the other's value. That is not a flake to re-run past — it is the reason
    /// `std::env::set_var` became `unsafe` in edition 2024, because a concurrent reader can observe
    /// a torn value rather than merely a stale one.
    static ENVIRONMENT: Mutex<()> = Mutex::new(());

    /// Sets a variable for the life of the guard, restoring whatever was there before.
    struct EnvGuard {
        name: &'static str,
        previous: Option<String>,
        _lock: MutexGuard<'static, ()>,
    }

    impl EnvGuard {
        fn set(name: &'static str, value: &str) -> Self {
            // A poisoned lock here means another env test panicked. The environment is already
            // suspect at that point, but failing every subsequent test with a poison error hides
            // the original failure, so take the guard anyway.
            let lock = ENVIRONMENT.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            let previous = std::env::var(name).ok();
            // SAFETY: the mutex above is the only thing that writes the environment in this test
            // binary, and it is held for as long as this guard lives.
            unsafe { std::env::set_var(name, value) };
            Self { name, previous, _lock: lock }
        }
    }

    impl Drop for EnvGuard {
        fn drop(&mut self) {
            // SAFETY: as above — the guard still holds the lock while this runs.
            unsafe {
                match self.previous.take() {
                    Some(value) => std::env::set_var(self.name, value),
                    None => std::env::remove_var(self.name),
                }
            }
        }
    }

    #[test]
    fn an_explicit_override_wins_over_every_platform_rule() {
        // Guarded rather than assumed: a portable install and the integration tests both need to
        // put state somewhere of their choosing.
        let _guard = EnvGuard::set("MCMCP_ORCHESTRATOR_HOME", "/tmp/mcmcp-test-home");

        let directory = state_directory().expect("an override should always resolve");

        assert!(directory.ends_with("mcmcp-orchestrator"));
        assert!(directory.starts_with("/tmp/mcmcp-test-home"));
    }

    #[test]
    fn treats_an_empty_variable_as_unset() {
        // An empty LOCALAPPDATA is *set*. Joining onto it yields a relative path, and state would be
        // written into whatever directory the process happened to start in.
        let _guard = EnvGuard::set("MCMCP_TEST_EMPTY", "   ");

        assert_eq!(non_empty_env("MCMCP_TEST_EMPTY"), None);
    }

    #[test]
    fn every_state_file_sits_under_one_directory() {
        let _guard = EnvGuard::set("MCMCP_ORCHESTRATOR_HOME", "/tmp/mcmcp-test-home");

        let root = state_directory().unwrap();

        for path in [
            approval_store_path().unwrap(),
            catalogue_cache_path().unwrap(),
            event_log_path().unwrap(),
        ] {
            assert!(path.starts_with(&root), "{} escaped the state directory", path.display());
        }
    }
}
