//! Directories a person has approved ahead of time (#155).
//!
//! Approving a pairing is a human decision, and stays one: nothing here is reachable from MCP. What
//! this adds is a way to make that decision *before* the instance arrives. An agent that starts a
//! dev client in a fresh git worktree gets a new instance id from a new path, and with nobody at the
//! orchestrator's window it sat in `pendingApproval` until the agent gave up on its in-game checks.
//! A trusted root says "anything launched from under here is mine": an unknown instance whose game
//! directory is inside one is approved on its first connection, logged, and remembered exactly as a
//! clicked approval would be — so it shows in the roster and can be revoked like any other.
//!
//! Strict approval ("ask before every connection") overrides it; that setting exists for people who
//! do not want anything approved without a click.
//!
//! Kept in its own file rather than the approval store. The CLI and the app are separate processes,
//! and the app holds the store in memory and saves it whole: a root the CLI added there would be
//! overwritten by the app's next save. This file is read fresh whenever an unknown instance knocks,
//! which is rare enough to cost nothing and makes `trust-root` take effect at once.
//!
//! # Comparing paths
//!
//! Done on text, lexically, because the directory a game reports may not exist on this side (a game
//! on another machine) and canonicalising would follow links an attacker could plant. Separators are
//! unified, `.` and `..` are resolved, the Windows verbatim prefix is dropped, and on Windows the
//! comparison ignores case. A root matches itself and what is below it at a separator boundary, so
//! `E:\repo` never covers `E:\repo2`.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// The file the roots live in, inside the state directory.
pub fn trusted_roots_path(state_directory: &Path) -> PathBuf {
    state_directory.join("trusted-roots.json")
}

/// Whether paths on this platform compare without regard to case.
///
/// Windows only. macOS volumes are usually case-insensitive too, but not always, and a comparison
/// that is stricter than the file system only ever fails to trust something; one that is looser
/// trusts something it should not.
pub const CASE_INSENSITIVE: bool = cfg!(windows);

#[derive(Debug, Default, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TrustedRoots {
    /// As the person gave them, made absolute. Compared through [`normalise`], never directly.
    #[serde(default)]
    pub roots: Vec<String>,
}

impl TrustedRoots {
    /// Reads the file, treating an absent one as no roots.
    pub fn load(path: &Path) -> Result<Self> {
        match std::fs::read_to_string(path) {
            Ok(text) => serde_json::from_str(&text).with_context(|| format!("reading {}", path.display())),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(error) => Err(error).with_context(|| format!("opening {}", path.display())),
        }
    }

    /// Writes the file, through a temporary so a crash cannot leave half of it.
    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).with_context(|| format!("creating {}", parent.display()))?;
        }
        let temporary = path.with_extension("json.tmp");
        std::fs::write(&temporary, serde_json::to_string_pretty(self)?)
            .with_context(|| format!("writing {}", temporary.display()))?;
        std::fs::rename(&temporary, path).with_context(|| format!("replacing {}", path.display()))?;
        Ok(())
    }

    /// Adds a root. Returns false when an equal one (after normalising) is already there.
    pub fn add(&mut self, root: &str) -> bool {
        let key = normalise(root, CASE_INSENSITIVE);
        if key.is_empty()
            || self
                .roots
                .iter()
                .any(|known| normalise(known, CASE_INSENSITIVE) == key)
        {
            return false;
        }
        self.roots.push(root.to_string());
        true
    }

    /// Removes every root equal to this one after normalising. Returns whether any was.
    pub fn remove(&mut self, root: &str) -> bool {
        let key = normalise(root, CASE_INSENSITIVE);
        let before = self.roots.len();
        self.roots
            .retain(|known| normalise(known, CASE_INSENSITIVE) != key);
        self.roots.len() != before
    }

    /// The root that covers this game directory, if any.
    pub fn covering(&self, game_directory: &str) -> Option<&str> {
        self.roots
            .iter()
            .find(|root| is_under(game_directory, root, CASE_INSENSITIVE))
            .map(String::as_str)
    }
}

/// A path reduced to a form two spellings of the same directory share.
///
/// Forward slashes, no repeated or trailing separator, `.` dropped and `..` applied, no `\\?\`
/// prefix, and lower case when `case_insensitive`. A `..` that would climb above the start is kept,
/// so a relative path never silently becomes a different one.
pub fn normalise(path: &str, case_insensitive: bool) -> String {
    let mut text = path.trim().replace('\\', "/");
    for prefix in ["//?/UNC/", "//?/"] {
        if let Some(rest) = text.strip_prefix(prefix) {
            text = if prefix.ends_with("UNC/") {
                format!("//{rest}")
            } else {
                rest.to_string()
            };
            break;
        }
    }
    if case_insensitive {
        text = text.to_lowercase();
    }

    // A UNC share keeps its leading pair; a Unix root keeps its one slash.
    let (lead, body) = if let Some(rest) = text.strip_prefix("//") {
        ("//", rest.to_string())
    } else if let Some(rest) = text.strip_prefix('/') {
        ("/", rest.to_string())
    } else {
        ("", text)
    };

    let mut parts: Vec<&str> = Vec::new();
    for part in body.split('/') {
        match part {
            "" | "." => {}
            ".." => match parts.last() {
                // Never above a drive or past the start.
                Some(last) if *last != ".." && !last.ends_with(':') => {
                    parts.pop();
                }
                last if lead.is_empty() && last.is_none_or(|last| *last == "..") => parts.push(".."),
                _ => {}
            },
            other => parts.push(other),
        }
    }
    format!("{lead}{}", parts.join("/"))
}

/// Whether `path` is `root` or inside it, compared at separator boundaries.
pub fn is_under(path: &str, root: &str, case_insensitive: bool) -> bool {
    let path = normalise(path, case_insensitive);
    let root = normalise(root, case_insensitive);
    if root.is_empty() || path.is_empty() {
        return false;
    }
    if path == root {
        return true;
    }
    // A root that already ends in a separator — "/" or "//server" — is followed directly.
    let boundary = if root.ends_with('/') {
        root.clone()
    } else {
        format!("{root}/")
    };
    path.starts_with(&boundary)
}

/// The root that would cover this game directory and every agent worktree of its repository.
///
/// For `<repo>/run` that is `<repo>/.claude/worktrees`; for a game already inside a worktree,
/// `<repo>/.claude/worktrees/<name>/run`, it is the same directory. What the approval dialog's
/// "also trust worktrees" box adds, and what the `pendingApproval` hint suggests.
pub fn worktrees_root(game_directory: &str) -> Option<String> {
    let unified = game_directory.trim().replace('\\', "/");
    let separator = if game_directory.contains('\\') { "\\" } else { "/" };
    let lowered = unified.to_lowercase();
    let repository = match lowered.find("/.claude/worktrees/") {
        Some(index) => unified[..index].to_string(),
        None => {
            let trimmed = unified.trim_end_matches('/');
            let (parent, _) = trimmed.rsplit_once('/')?;
            if parent.is_empty() || parent.ends_with(':') {
                // A game at the top of a drive or of `/` has no repository above it worth trusting.
                return None;
            }
            parent.to_string()
        }
    };
    Some(format!("{repository}/.claude/worktrees").replace('/', separator))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_directory_inside_a_root_is_trusted_whatever_the_separators_and_case_on_windows() {
        assert!(is_under(
            r"E:\repo\.claude\worktrees\agent-1\run",
            "E:/Repo/.claude/worktrees",
            true
        ));
        assert!(is_under(r"e:\REPO\run\", r"E:\repo", true));
        assert!(is_under(r"\\?\E:\repo\run", r"E:\repo", true));
    }

    #[test]
    fn a_sibling_whose_name_starts_with_the_root_is_not_inside_it() {
        assert!(!is_under(r"E:\repo2\run", r"E:\repo", true));
        assert!(!is_under(r"E:\repository", r"E:\repo\", true));
        assert!(!is_under("/home/a/repo-old/run", "/home/a/repo", false));
    }

    #[test]
    fn a_root_covers_itself() {
        assert!(is_under(r"E:\repo", r"E:\repo\", true));
        assert!(is_under("/srv/mc", "/srv/mc", false));
    }

    #[test]
    fn dot_dot_cannot_climb_out_of_a_root_into_its_neighbour() {
        assert!(!is_under(r"E:\repo\..\other\run", r"E:\repo", true));
        assert!(is_under(r"E:\other\..\repo\run", r"E:\repo", true));
        assert!(!is_under("/srv/mc/../../etc", "/srv", false));
    }

    #[test]
    fn case_matters_where_the_platform_says_it_does() {
        assert!(!is_under("/srv/MC/run", "/srv/mc", false));
        assert!(is_under("/srv/MC/run", "/srv/mc", true));
    }

    #[test]
    fn an_empty_root_trusts_nothing() {
        assert!(!is_under(r"E:\repo", "", true));
        assert!(!is_under(r"E:\repo", "   ", true));
    }

    #[test]
    fn a_unc_share_is_compared_as_a_share() {
        assert!(is_under(r"\\server\share\mc\run", r"\\SERVER\share", true));
        assert!(!is_under(r"\\server\share2\run", r"\\server\share", true));
        assert!(is_under(r"\\?\UNC\server\share\run", r"\\server\share", true));
    }

    #[test]
    fn the_same_root_spelled_twice_is_kept_once_and_removed_by_either_spelling() {
        let mut roots = TrustedRoots::default();
        assert!(roots.add("/srv/mc/"));
        assert!(!roots.add("/srv//mc"));
        assert_eq!(roots.roots.len(), 1);
        assert_eq!(roots.covering("/srv/mc/run"), Some("/srv/mc/"));
        assert!(roots.remove("/srv/mc"));
        assert!(roots.covering("/srv/mc/run").is_none());
        assert!(!roots.remove("/srv/mc"));
    }

    #[test]
    fn worktrees_of_a_repository_share_one_root_wherever_the_game_ran_from() {
        assert_eq!(
            worktrees_root(r"E:\repo\run").as_deref(),
            Some(r"E:\repo\.claude\worktrees")
        );
        assert_eq!(
            worktrees_root(r"E:\repo\.claude\worktrees\agent-a1\run").as_deref(),
            Some(r"E:\repo\.claude\worktrees")
        );
        assert_eq!(
            worktrees_root("/home/a/repo/run/").as_deref(),
            Some("/home/a/repo/.claude/worktrees")
        );
        assert_eq!(worktrees_root(r"E:\run"), None);
    }

    #[test]
    fn roots_survive_a_save_and_an_absent_file_is_none() {
        let directory = std::env::temp_dir().join(format!("mcmcp-trust-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        let path = trusted_roots_path(&directory);
        assert_eq!(TrustedRoots::load(&path).unwrap(), TrustedRoots::default());

        let mut roots = TrustedRoots::default();
        roots.add(r"E:\repo");
        roots.save(&path).unwrap();
        assert_eq!(TrustedRoots::load(&path).unwrap(), roots);
        let _ = std::fs::remove_dir_all(&directory);
    }
}
