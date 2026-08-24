//! Which instances have been approved, and what they are called.
//!
//! Trust on first use: the first time an instance dials in, a human is asked whether they recognise
//! it. After that it is remembered, keyed by instance id, and authenticated by a hash of its secret.
//!
//! **The hash is what makes any of this mean something.** Remembering an id alone would let any
//! process on the machine open a link, claim an approved id, and inherit whatever access a person
//! granted the real instance. The secret is 256 bits of `SecureRandom` from the mod, so a plain
//! SHA-256 is the right tool — this is not a password and does not want a slow KDF, it wants a
//! constant-time comparison against a value that cannot be guessed.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// What an approval decision was.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Known id, matching secret, approved. Let it in.
    Approved,
    /// Never seen. A human has to decide.
    Unknown,
    /// Known id, wrong secret. Never resolves itself.
    SecretMismatch,
    /// Known id, explicitly revoked.
    Revoked,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApprovedInstance {
    pub id: String,
    /// Hex SHA-256 of the instance secret. The secret itself is never stored.
    pub secret_hash: String,
    pub label: String,
    #[serde(default)]
    pub game_directory: Option<String>,
    #[serde(default)]
    pub revoked: bool,
    #[serde(default)]
    pub approved_at: Option<String>,
    /// Whether an operator has renamed this instance here.
    ///
    /// Distinguishes "the label happens to equal what the game reported" from "somebody chose this",
    /// which decides whether a later change to `instanceName` in the game's config should be picked
    /// up or ignored.
    #[serde(default)]
    pub label_is_custom: bool,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct StoreFile {
    #[serde(default)]
    instances: BTreeMap<String, ApprovedInstance>,
    /// Require an explicit approval on every connection rather than only the first.
    #[serde(default)]
    strict_approval: bool,
}

/// The on-disk approval store.
#[derive(Debug)]
pub struct ApprovalStore {
    path: PathBuf,
    file: StoreFile,
}

impl ApprovalStore {
    /// Loads the store, treating an absent file as an empty one.
    ///
    /// A *corrupt* file is an error rather than a silent reset: quietly starting from empty would
    /// discard every approval and re-prompt for instances the operator already trusted, which is
    /// both alarming and exactly the moment to train someone to click through the prompt.
    pub fn load(path: impl Into<PathBuf>) -> Result<Self> {
        let path = path.into();
        let file = match std::fs::read_to_string(&path) {
            Ok(text) => serde_json::from_str(&text)
                .with_context(|| format!("reading the approval store at {}", path.display()))?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => StoreFile::default(),
            Err(error) => {
                return Err(error)
                    .with_context(|| format!("opening the approval store at {}", path.display()));
            }
        };
        Ok(Self { path, file })
    }

    pub fn save(&self) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent).with_context(|| format!("creating {}", parent.display()))?;
        }
        let text = serde_json::to_string_pretty(&self.file)?;
        // Write-then-rename, so a crash mid-write cannot leave a truncated store — which `load`
        // would refuse to parse, locking the operator out of every instance they had approved.
        let temporary = self.path.with_extension("json.tmp");
        std::fs::write(&temporary, text).with_context(|| format!("writing {}", temporary.display()))?;
        std::fs::rename(&temporary, &self.path)
            .with_context(|| format!("replacing {}", self.path.display()))?;
        Ok(())
    }

    pub fn strict_approval(&self) -> bool {
        self.file.strict_approval
    }

    pub fn set_strict_approval(&mut self, strict: bool) {
        self.file.strict_approval = strict;
    }

    pub fn get(&self, id: &str) -> Option<&ApprovedInstance> {
        self.file.instances.get(id)
    }

    pub fn all(&self) -> impl Iterator<Item = &ApprovedInstance> {
        self.file.instances.values()
    }

    /// Decides whether an instance presenting this id and secret may connect.
    pub fn evaluate(&self, id: &str, secret: &str) -> Verdict {
        let Some(known) = self.file.instances.get(id) else {
            return Verdict::Unknown;
        };
        if known.revoked && known.secret_hash.is_empty() {
            // Refused before it was ever approved — "never" on an approval prompt. There is no
            // secret on file to compare, so checking one first would report a mismatch and tell the
            // instance the wrong thing about why it was turned away.
            return Verdict::Revoked;
        }
        if !constant_time_eq(&known.secret_hash, &hash_secret(secret)) {
            // Checked before `revoked` on purpose: something presenting the wrong secret should be
            // told its secret is wrong, not handed the information that this id was revoked.
            return Verdict::SecretMismatch;
        }
        if known.revoked {
            return Verdict::Revoked;
        }
        Verdict::Approved
    }

    /// Records an approval, or re-approves an instance whose secret was rotated.
    ///
    /// A custom label survives re-approval. Somebody typed it; a rotated secret is not a reason to
    /// forget it.
    pub fn approve(&mut self, id: &str, secret: &str, label: &str, game_directory: Option<&str>, now: &str) {
        let existing = self.file.instances.get(id);
        let label_is_custom = existing.is_some_and(|instance| instance.label_is_custom);
        let label = if label_is_custom {
            existing
                .map(|instance| instance.label.clone())
                .unwrap_or_else(|| label.to_string())
        } else {
            label.to_string()
        };

        self.file.instances.insert(
            id.to_string(),
            ApprovedInstance {
                id: id.to_string(),
                secret_hash: hash_secret(secret),
                label,
                game_directory: game_directory.map(str::to_string),
                revoked: false,
                approved_at: Some(now.to_string()),
                label_is_custom,
            },
        );
    }

    /// Re-approves an instance from its stored hash, without needing the raw secret.
    ///
    /// What `mcmcp-orchestrator approve` uses. The CLI is a separate process and has no way to know
    /// an instance's secret — nor should it: the secret lives in the game's config and in the
    /// handshake, and a command-line tool that handled it would put it in shell history.
    pub fn approve_known(&mut self, id: &str, secret_hash: &str, label: &str, game_directory: Option<&str>) {
        let label_is_custom = self
            .file
            .instances
            .get(id)
            .is_some_and(|known| known.label_is_custom);
        self.file.instances.insert(
            id.to_string(),
            ApprovedInstance {
                id: id.to_string(),
                secret_hash: secret_hash.to_string(),
                label: label.to_string(),
                game_directory: game_directory.map(str::to_string),
                revoked: false,
                approved_at: Some(String::new()),
                label_is_custom,
            },
        );
    }

    /// Records an instance as refused, for one that was never approved in the first place.
    ///
    /// Needed because "never" on an approval prompt has nothing to mark: the instance is unknown by
    /// definition, so there is no record to revoke. Without a record it would be asked about again
    /// on the next launch, which is the opposite of what "never" means.
    ///
    /// The secret hash is deliberately empty. Nothing should ever authenticate against this record —
    /// it exists to be found and refused, and `evaluate` reports a mismatch before it reports a
    /// revocation, so an empty hash would answer the wrong question. `is_denied` is what the link
    /// checks.
    pub fn deny_forever(&mut self, id: &str, label: &str, game_directory: Option<&str>) {
        let existing = self.file.instances.get(id);
        let label_is_custom = existing.is_some_and(|known| known.label_is_custom);
        let secret_hash = existing
            .map(|known| known.secret_hash.clone())
            .unwrap_or_default();
        self.file.instances.insert(
            id.to_string(),
            ApprovedInstance {
                id: id.to_string(),
                secret_hash,
                label: label.to_string(),
                game_directory: game_directory.map(str::to_string),
                revoked: true,
                approved_at: None,
                label_is_custom,
            },
        );
    }

    /// Whether this id has been refused, whatever secret it presents.
    pub fn is_denied(&self, id: &str) -> bool {
        self.file
            .instances
            .get(id)
            .map(|known| known.revoked)
            .unwrap_or(false)
    }

    /// Marks an instance revoked, keeping the record so its secret hash still authenticates it.
    ///
    /// Deleting it instead would make the next connection look like a brand-new instance and
    /// re-prompt — turning "I do not want this connected" into "ask me again immediately".
    pub fn revoke(&mut self, id: &str) -> bool {
        match self.file.instances.get_mut(id) {
            Some(instance) => {
                instance.revoked = true;
                true
            }
            None => false,
        }
    }

    pub fn set_label(&mut self, id: &str, label: &str) -> bool {
        match self.file.instances.get_mut(id) {
            Some(instance) => {
                instance.label = label.to_string();
                instance.label_is_custom = true;
                true
            }
            None => false,
        }
    }

    /// Notes a directory an instance now reports from.
    ///
    /// Returns the previous directory when it differs, so the caller can log the change. A known id
    /// arriving from a new path is a *moved* instance, which is fine and stays approved — but it is
    /// also what a copied config looks like, so it is worth a line in the log rather than silence.
    pub fn note_directory(&mut self, id: &str, directory: Option<&str>) -> Option<String> {
        let instance = self.file.instances.get_mut(id)?;
        let previous = instance.game_directory.clone();
        if directory.is_some() && directory != previous.as_deref() {
            instance.game_directory = directory.map(str::to_string);
            return previous;
        }
        None
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

pub fn hash_secret(secret: &str) -> String {
    let digest = Sha256::digest(secret.as_bytes());
    let mut hex = String::with_capacity(digest.len() * 2);
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(hex, "{byte:02x}");
    }
    hex
}

/// Compares two hex digests without leaking where they first differ through timing.
///
/// Both inputs are hex of a fixed length here, so this is belt-and-braces rather than the linchpin —
/// but a comparison that returns early is the kind of thing that gets copied somewhere it matters.
fn constant_time_eq(left: &str, right: &str) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut difference = 0u8;
    for (a, b) in left.bytes().zip(right.bytes()) {
        difference |= a ^ b;
    }
    difference == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    const OTHER_SECRET: &str = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    fn store() -> ApprovalStore {
        ApprovalStore {
            path: PathBuf::from("unused.json"),
            file: StoreFile::default(),
        }
    }

    #[test]
    fn an_unseen_instance_is_unknown_rather_than_refused() {
        assert_eq!(store().evaluate("modb-dev", SECRET), Verdict::Unknown);
    }

    #[test]
    fn an_approved_instance_with_its_secret_is_let_in() {
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", None, "now");

        assert_eq!(store.evaluate("modb-dev", SECRET), Verdict::Approved);
    }

    #[test]
    fn a_known_id_with_the_wrong_secret_is_a_mismatch_not_an_unknown() {
        // The distinction is the whole point of storing a hash. Reporting this as "unknown" would
        // put an approval prompt on screen for something impersonating an instance the operator
        // already trusts — training them to approve exactly the wrong thing.
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", None, "now");

        assert_eq!(store.evaluate("modb-dev", OTHER_SECRET), Verdict::SecretMismatch);
    }

    #[test]
    fn never_stores_the_secret_itself() {
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", None, "now");

        let stored = serde_json::to_string(&store.file).unwrap();
        assert!(
            !stored.contains(SECRET),
            "the approval store must never contain a raw secret"
        );
        assert!(stored.contains(&hash_secret(SECRET)));
    }

    #[test]
    fn a_revoked_instance_stays_authenticated_so_it_is_not_re_prompted() {
        // Deleting the record instead would make the next connection look brand new, turning "I do
        // not want this connected" into "ask me again immediately".
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", None, "now");
        assert!(store.revoke("modb-dev"));

        assert_eq!(store.evaluate("modb-dev", SECRET), Verdict::Revoked);
        assert_eq!(store.evaluate("modb-dev", OTHER_SECRET), Verdict::SecretMismatch);
    }

    #[test]
    fn an_instance_refused_before_approval_reports_revoked_not_a_mismatch() {
        // "Never" on an approval prompt has no secret on file to compare against. Reporting a
        // mismatch would tell the instance its secret was wrong, which is both untrue and
        // unactionable.
        let mut store = store();
        store.deny_forever("modb-dev", "modB dev", None);

        assert_eq!(store.evaluate("modb-dev", SECRET), Verdict::Revoked);
        assert!(store.is_denied("modb-dev"));
    }

    #[test]
    fn a_wrong_secret_beats_a_revocation_in_the_answer_given() {
        // Something presenting the wrong secret should learn its secret is wrong, not that this id
        // exists and was revoked.
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", None, "now");
        store.revoke("modb-dev");

        assert_eq!(store.evaluate("modb-dev", OTHER_SECRET), Verdict::SecretMismatch);
    }

    #[test]
    fn re_approving_after_a_rotated_secret_keeps_a_label_somebody_typed() {
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", None, "now");
        store.set_label("modb-dev", "the control one");

        store.approve("modb-dev", OTHER_SECRET, "modB dev", None, "later");

        assert_eq!(store.get("modb-dev").unwrap().label, "the control one");
        assert_eq!(store.evaluate("modb-dev", OTHER_SECRET), Verdict::Approved);
    }

    #[test]
    fn re_approving_updates_a_label_nobody_customised() {
        let mut store = store();
        store.approve("modb-dev", SECRET, "old folder name", None, "now");

        store.approve("modb-dev", SECRET, "new folder name", None, "later");

        assert_eq!(store.get("modb-dev").unwrap().label, "new folder name");
    }

    #[test]
    fn reports_a_directory_change_so_a_copied_config_is_visible() {
        // A known id from a new path is a moved instance, which is fine. It is also what a copied
        // config looks like, so it earns a log line rather than silence.
        let mut store = store();
        store.approve("modb-dev", SECRET, "modB dev", Some("E:\\old"), "now");

        assert_eq!(
            store.note_directory("modb-dev", Some("E:\\new")).as_deref(),
            Some("E:\\old")
        );
        assert_eq!(store.note_directory("modb-dev", Some("E:\\new")), None);
    }

    #[test]
    fn survives_a_round_trip_through_disk() {
        let directory = std::env::temp_dir().join(format!("mcmcp-store-test-{}", std::process::id()));
        let path = directory.join("instances.json");
        let mut store = ApprovalStore::load(&path).expect("an absent store should load as empty");
        store.approve("modb-dev", SECRET, "modB dev", Some("E:\\instances\\modB"), "now");
        store.set_strict_approval(true);
        store.save().expect("store should save");

        let reloaded = ApprovalStore::load(&path).expect("store should reload");

        assert!(reloaded.strict_approval());
        assert_eq!(reloaded.evaluate("modb-dev", SECRET), Verdict::Approved);
        assert_eq!(reloaded.get("modb-dev").unwrap().label, "modB dev");
        let _ = std::fs::remove_dir_all(&directory);
    }

    #[test]
    fn refuses_to_start_from_empty_when_the_store_is_corrupt() {
        // Silently resetting would discard every approval and re-prompt for instances the operator
        // already trusted — alarming, and exactly the moment to teach someone to click through.
        let directory = std::env::temp_dir().join(format!("mcmcp-store-bad-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("instances.json");
        std::fs::write(&path, "{ this is not json").unwrap();

        assert!(ApprovalStore::load(&path).is_err());
        let _ = std::fs::remove_dir_all(&directory);
    }
}
