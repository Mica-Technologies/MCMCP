//! The operations a person can perform, and where they live.
//!
//! # Why this is files and a watcher, not a control socket
//!
//! The plan said "control API on a separate loopback socket". Building it revealed that a socket was
//! solving a problem that does not exist here:
//!
//! - The **desktop app** links this crate directly. It has never needed IPC to reach the core; it
//!   calls the same functions, and the authority argument is what separates it from the model. A
//!   socket would have it talking to itself through a loopback connection.
//! - The **CLI** is a separate process, so it does need something. But everything it changes is
//!   *persistent state* — approvals, labels, policy — which already lives in files that the
//!   orchestrator owns. Writing the file is the message.
//! - A socket would have needed its own listener, its own token, its own auth, and its own
//!   cross-platform story (named pipes on Windows, Unix sockets elsewhere). All of that to deliver
//!   changes that end up in the same two files either way.
//!
//! What a socket *would* have bought is immediacy, and that is bought here instead by
//! [`ControlWatcher`]: the running orchestrator re-reads both files on a short timer and acts on
//! what changed. A revocation therefore disconnects a live instance rather than waiting for it to
//! reconnect — which was the one thing files alone would have got wrong.
//!
//! The rule the design is built on survives intact, and is arguably better served: the GUI, the CLI
//! and the model all go through the same operations, and the seam between them is authority.
//!
//! # Authority
//!
//! [`Authority::Model`] may read, set focus, and rename. It may **not** approve a pairing, revoke
//! one, or change gating policy — a model that can approve an instance can widen its own reach,
//! which is exactly what an approval prompt exists to prevent. That is enforced here, once, rather
//! than by each caller remembering.

use anyhow::{Result, bail};
use std::path::{Path, PathBuf};

use crate::policy::Policy;

/// Who is asking.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Authority {
    /// A person, through the desktop app or the CLI. May do anything.
    Human,
    /// The MCP client, and behind it a model. Read and steer; never widen.
    Model,
}

impl Authority {
    /// Whether this authority may change who is trusted, or what is gated.
    pub fn may_change_trust(self) -> bool {
        matches!(self, Authority::Human)
    }

    /// Refuses in a sentence that says what to do instead, rather than just "denied".
    pub fn require_human(self, operation: &str) -> Result<()> {
        if self.may_change_trust() {
            return Ok(());
        }
        bail!(
            "{operation} can only be done by a person, in the orchestrator's app or with the \
             mcmcp-orchestrator command. It is deliberately not available as a tool: approving or \
             gating an instance decides what a model is allowed to reach, so a model changing it \
             would defeat the point."
        )
    }
}

/// Loads a policy file, treating an absent one as the default.
///
/// A *corrupt* one is an error, unlike the catalogue cache. Silently falling back to defaults would
/// turn every gate somebody configured into an allow, which is the wrong direction to fail.
pub fn load_policy(path: &Path) -> Result<Policy> {
    match std::fs::read_to_string(path) {
        Ok(text) => Ok(serde_json::from_str(&text)?),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Policy::default()),
        Err(error) => Err(error.into()),
    }
}

pub fn save_policy(path: &Path, policy: &Policy) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let text = serde_json::to_string_pretty(policy)?;
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, text)?;
    std::fs::rename(&temporary, path)?;
    Ok(())
}

pub fn policy_path(state_directory: &Path) -> PathBuf {
    state_directory.join("policy.json")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{Class, Rule};

    #[test]
    fn a_model_may_not_change_who_is_trusted() {
        // Enforced here, once, rather than by each call site remembering. The failure mode of the
        // alternative is one forgotten check that quietly hands a model the approval switch.
        assert!(!Authority::Model.may_change_trust());
        assert!(Authority::Model.require_human("approving an instance").is_err());
    }

    #[test]
    fn a_person_may() {
        assert!(Authority::Human.may_change_trust());
        assert!(Authority::Human.require_human("approving an instance").is_ok());
    }

    #[test]
    fn the_refusal_says_where_the_operation_does_live() {
        // It reaches a model as a tool error. "Denied" would leave it guessing; naming the app and
        // the command lets it tell the person what to do.
        let error = Authority::Model
            .require_human("revoking an instance")
            .unwrap_err()
            .to_string();

        assert!(error.contains("revoking an instance"));
        assert!(error.contains("mcmcp-orchestrator"));
    }

    #[test]
    fn an_absent_policy_file_loads_as_the_default() {
        let missing = std::env::temp_dir().join("mcmcp-no-such-policy.json");
        let _ = std::fs::remove_file(&missing);

        let policy = load_policy(&missing).expect("an absent policy should be the default");

        assert_eq!(policy.rules_for("anything").destructive, Rule::Allow);
    }

    #[test]
    fn a_corrupt_policy_file_is_an_error_rather_than_a_silent_allow() {
        // The catalogue cache may fall back; this may not. Quietly defaulting would turn every gate
        // somebody configured into an allow, which is the wrong direction to fail.
        let directory = std::env::temp_dir().join(format!("mcmcp-policy-bad-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("policy.json");
        std::fs::write(&path, "{ not json").unwrap();

        assert!(load_policy(&path).is_err());
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn a_policy_survives_a_round_trip_through_disk() {
        let directory = std::env::temp_dir().join(format!("mcmcp-policy-{}", std::process::id()));
        let path = policy_path(&directory);
        let mut policy = Policy::default();
        policy.set_rule(Some("alpha"), Class::Destructive, Rule::Ask);

        save_policy(&path, &policy).expect("policy should save");
        let restored = load_policy(&path).expect("policy should load");

        assert_eq!(restored.rules_for("alpha").destructive, Rule::Ask);
        let _ = std::fs::remove_dir_all(&directory);
    }
}
