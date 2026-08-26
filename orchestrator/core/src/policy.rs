//! Which tool calls are allowed through, and which need a person.
//!
//! # What this gates, and what it does not
//!
//! The mod's own `permissions` config decides what a game instance is *capable* of — turn off
//! `allowWorldEdits` and the block-writing tools return an error naming the setting. This gates
//! *calls*, per instance, on their way past the orchestrator.
//!
//! Keeping those apart is deliberate and the reason this file is not simply a remote control for the
//! mod's config. Two sources of truth for "may this tool run" is precisely the bug that is
//! impossible to diagnose from inside a game: a tool refuses, the config says it is allowed, and
//! nothing in the game knows that something a process away decided otherwise. So: capability lives
//! in the game and is the game's answer, permission to *route* lives here and says so in its own
//! words.
//!
//! # Three states, not two
//!
//! `Allow` and `Deny` are obvious. `Ask` is the one worth having, and it is the reason a GUI is
//! worth building — the mod's config can only offer a boolean, and "let me see this one first" is
//! the answer people actually want for a destructive tool on a world they care about.
//!
//! **In a headless process, `Ask` denies.** There is nothing on screen to ask, and the alternative —
//! allowing what someone asked to be prompted about — fails in the direction that loses work.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// What to do with a call.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Rule {
    /// The default, and deliberately so — see [`ClassRules`].
    #[default]
    Allow,
    /// Put it to a human. Denied where nobody can be asked.
    Ask,
    Deny,
}

/// The classes a tool can fall into, taken from its own MCP annotations.
///
/// Annotation-driven rather than a list of names: a policy written against names would not cover a
/// tool another mod registered five minutes ago, and the tools that most want gating are exactly the
/// ones nobody enumerated in advance.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Class {
    /// `readOnlyHint: true` — cannot change game state.
    ReadOnly,
    /// `destructiveHint: true` and not read-only.
    Destructive,
    /// Everything else: it writes something, but not destructively.
    Mutating,
}

impl Class {
    /// Classifies a tool from its MCP annotations.
    ///
    /// A tool with no annotations at all is treated as `Mutating`, not `ReadOnly`. Guessing safe
    /// would mean a third-party tool that reformats a world sails through a policy set to gate
    /// exactly that.
    pub fn of(tool: &serde_json::Value) -> Self {
        let annotations = tool.get("annotations");
        let hint = |name: &str| {
            annotations
                .and_then(|annotations| annotations.get(name))
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false)
        };
        if hint("readOnlyHint") {
            Class::ReadOnly
        } else if hint("destructiveHint") {
            Class::Destructive
        } else {
            Class::Mutating
        }
    }
}

/// What the policy decided, and enough about why to tell somebody.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    Allow,
    Ask { reason: String },
    Deny { reason: String },
}

/// Per-instance and default rules.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Policy {
    /// Applied where an instance has no rule of its own.
    #[serde(default)]
    pub defaults: ClassRules,
    #[serde(default)]
    pub per_instance: BTreeMap<String, ClassRules>,
    /// Require calls to destructive tools to name their instance rather than following focus.
    ///
    /// Off by default. On, it closes the gap focus leaves open: a human moves focus, the model does
    /// not know, and its next destructive call lands somewhere it did not intend. Everything else
    /// makes that *visible*; this makes it impossible.
    #[serde(default)]
    pub require_explicit_instance_for_destructive: bool,
}

/// What each class of tool may do on one instance.
///
/// **Everything defaults to allowed**, and pretending otherwise would be worse than useless: the
/// orchestrator is not a security boundary — an MCP client is already trusted to drive the game —
/// and a default that blocked things would teach people to turn the whole feature off rather than
/// use it. Gating is for the sessions where somebody wants it.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ClassRules {
    #[serde(default)]
    pub read_only: Rule,
    #[serde(default)]
    pub mutating: Rule,
    #[serde(default)]
    pub destructive: Rule,
}

impl ClassRules {
    pub fn rule_for(&self, class: Class) -> Rule {
        match class {
            Class::ReadOnly => self.read_only,
            Class::Mutating => self.mutating,
            Class::Destructive => self.destructive,
        }
    }

    pub fn set(&mut self, class: Class, rule: Rule) {
        match class {
            Class::ReadOnly => self.read_only = rule,
            Class::Mutating => self.mutating = rule,
            Class::Destructive => self.destructive = rule,
        }
    }
}

impl Policy {
    /// The rules for one endpoint, falling back to its game and then to the defaults.
    ///
    /// Two levels rather than one, because the two ids mean different things to the person writing
    /// a rule. `atm9-3f2a1c.client` is "this game's client, which has the camera and the keyboard";
    /// `atm9-3f2a1c` is "this Minecraft install, whichever half of it a call lands on". Both are
    /// worth being able to say, and the more specific one wins.
    ///
    /// It is also what stops a policy file written before endpoints were addressable from quietly
    /// ceasing to apply. Those files are keyed by the game id, and a lookup that only tried the
    /// endpoint id would miss every one of them and fall through to the defaults — silently
    /// widening what a model may do, which is the one direction this must never fail in.
    pub fn rules_for(&self, instance: &str, game: &str) -> &ClassRules {
        self.per_instance
            .get(instance)
            .or_else(|| self.per_instance.get(game))
            .unwrap_or(&self.defaults)
    }

    pub fn set_rule(&mut self, instance: Option<&str>, class: Class, rule: Rule) {
        match instance {
            Some(instance) => {
                self.per_instance
                    .entry(instance.to_string())
                    .or_default()
                    .set(class, rule);
            }
            None => self.defaults.set(class, rule),
        }
    }

    /// Decides one call.
    ///
    /// `instance_was_named` is whether the caller said which instance to act on, rather than letting
    /// focus decide. It only matters for destructive tools, and only when the corresponding setting
    /// is on.
    pub fn evaluate(
        &self,
        instance: &str,
        game: &str,
        tool: &serde_json::Value,
        instance_was_named: bool,
    ) -> Decision {
        let name = tool
            .get("name")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("this tool");
        let class = Class::of(tool);

        if class == Class::Destructive
            && self.require_explicit_instance_for_destructive
            && !instance_was_named
        {
            return Decision::Deny {
                reason: format!(
                    "'{name}' can change the world irreversibly, and this orchestrator is set to \
                     require destructive calls to name their instance rather than following focus. \
                     Pass instance explicitly — focus is currently {instance}, but a human can move \
                     it at any time."
                ),
            };
        }

        match self.rules_for(instance, game).rule_for(class) {
            Rule::Allow => Decision::Allow,
            Rule::Ask => Decision::Ask {
                reason: format!("'{name}' needs approval on instance {instance}"),
            },
            Rule::Deny => Decision::Deny {
                reason: format!(
                    "'{name}' is blocked on instance {instance} by this orchestrator's gating policy."
                ),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn tool(name: &str, annotations: serde_json::Value) -> serde_json::Value {
        json!({ "name": name, "annotations": annotations })
    }

    #[test]
    fn classifies_from_annotations_rather_than_a_list_of_names() {
        // A policy written against names cannot cover a tool another mod registered five minutes
        // ago, and those are exactly the ones most worth gating.
        assert_eq!(
            Class::of(&tool("client_look", json!({"readOnlyHint": true}))),
            Class::ReadOnly
        );
        assert_eq!(
            Class::of(&tool("server_set_block", json!({"destructiveHint": true}))),
            Class::Destructive
        );
        assert_eq!(Class::of(&tool("client_move", json!({}))), Class::Mutating);
    }

    #[test]
    fn an_unannotated_tool_is_treated_as_mutating_not_read_only() {
        // Guessing safe would let a third-party tool that reformats a world sail through a policy
        // set to gate exactly that.
        assert_eq!(Class::of(&json!({"name": "mymod_mystery"})), Class::Mutating);
    }

    #[test]
    fn read_only_beats_destructive_when_a_tool_claims_both() {
        // Contradictory annotations exist. Reading is the claim that constrains behaviour, so it
        // wins; the alternative is gating a tool that cannot change anything.
        let confused = tool("odd", json!({"readOnlyHint": true, "destructiveHint": true}));

        assert_eq!(Class::of(&confused), Class::ReadOnly);
    }

    #[test]
    fn everything_is_allowed_by_default() {
        // The orchestrator is not a security boundary — an MCP client is already trusted to drive
        // the game — and a default that blocked things would teach people to turn it off entirely.
        let policy = Policy::default();

        assert_eq!(
            policy.evaluate(
                "alpha",
                "alpha",
                &tool("server_set_block", json!({"destructiveHint": true})),
                true
            ),
            Decision::Allow
        );
    }

    #[test]
    fn a_per_instance_rule_overrides_the_default() {
        let mut policy = Policy::default();
        policy.set_rule(None, Class::Destructive, Rule::Deny);
        policy.set_rule(Some("scratch"), Class::Destructive, Rule::Allow);
        let destructive = tool("server_set_block", json!({"destructiveHint": true}));

        assert!(matches!(
            policy.evaluate("production", "production", &destructive, true),
            Decision::Deny { .. }
        ));
        assert_eq!(
            policy.evaluate("scratch", "scratch", &destructive, true),
            Decision::Allow
        );
    }

    #[test]
    fn a_rule_on_a_game_covers_both_of_its_endpoints() {
        // What keeps a policy file written before endpoints were addressable working. Those files
        // are keyed by the game id, and a lookup that only tried the endpoint id would miss every
        // one of them and fall through to the defaults — silently widening what a model may do.
        let mut policy = Policy::default();
        policy.set_rule(Some("atm9-3f2a1c"), Class::Destructive, Rule::Deny);
        let destructive = tool("server_set_block", json!({"destructiveHint": true}));

        for endpoint in ["atm9-3f2a1c.client", "atm9-3f2a1c.server"] {
            assert!(
                matches!(
                    policy.evaluate(endpoint, "atm9-3f2a1c", &destructive, true),
                    Decision::Deny { .. }
                ),
                "{endpoint} should inherit its game's rule"
            );
        }
    }

    #[test]
    fn a_rule_on_one_endpoint_beats_its_games_rule() {
        // The two ids mean different things to whoever wrote the rule: one names this game's
        // client, which has the camera and the keyboard; the other names the whole install.
        let mut policy = Policy::default();
        policy.set_rule(Some("atm9-3f2a1c"), Class::Mutating, Rule::Deny);
        policy.set_rule(Some("atm9-3f2a1c.client"), Class::Mutating, Rule::Allow);
        let mutating = tool("client_move", json!({}));

        assert_eq!(
            policy.evaluate("atm9-3f2a1c.client", "atm9-3f2a1c", &mutating, true),
            Decision::Allow
        );
        assert!(matches!(
            policy.evaluate("atm9-3f2a1c.server", "atm9-3f2a1c", &mutating, true),
            Decision::Deny { .. }
        ));
    }

    #[test]
    fn gating_one_class_leaves_the_others_alone() {
        let mut policy = Policy::default();
        policy.set_rule(None, Class::Destructive, Rule::Ask);

        assert_eq!(
            policy.evaluate(
                "alpha",
                "alpha",
                &tool("client_look", json!({"readOnlyHint": true})),
                true
            ),
            Decision::Allow
        );
        assert!(matches!(
            policy.evaluate(
                "alpha",
                "alpha",
                &tool("server_set_block", json!({"destructiveHint": true})),
                true
            ),
            Decision::Ask { .. }
        ));
    }

    #[test]
    fn a_denial_says_which_tool_and_which_instance() {
        // It comes back to the model as a tool error, so it has to be readable enough to act on.
        let mut policy = Policy::default();
        policy.set_rule(Some("alpha"), Class::Mutating, Rule::Deny);

        let Decision::Deny { reason } =
            policy.evaluate("alpha", "alpha", &tool("client_move", json!({})), true)
        else {
            panic!("expected a denial");
        };
        assert!(reason.contains("client_move"));
        assert!(reason.contains("alpha"));
    }

    #[test]
    fn requiring_an_explicit_instance_only_bites_destructive_calls() {
        // The setting closes the gap focus leaves open, without taxing the reads and moves that make
        // up nearly every call.
        let policy = Policy {
            require_explicit_instance_for_destructive: true,
            ..Policy::default()
        };

        assert_eq!(
            policy.evaluate(
                "alpha",
                "alpha",
                &tool("client_look", json!({"readOnlyHint": true})),
                false
            ),
            Decision::Allow
        );
        assert_eq!(
            policy.evaluate("alpha", "alpha", &tool("client_move", json!({})), false),
            Decision::Allow
        );

        let destructive = tool("server_set_block", json!({"destructiveHint": true}));
        assert!(matches!(
            policy.evaluate("alpha", "alpha", &destructive, false),
            Decision::Deny { .. }
        ));
        // Naming the instance satisfies it.
        assert_eq!(
            policy.evaluate("alpha", "alpha", &destructive, true),
            Decision::Allow
        );
    }

    #[test]
    fn the_explicit_instance_denial_explains_what_to_do() {
        let policy = Policy {
            require_explicit_instance_for_destructive: true,
            ..Policy::default()
        };

        let Decision::Deny { reason } = policy.evaluate(
            "alpha",
            "alpha",
            &tool("server_set_block", json!({"destructiveHint": true})),
            false,
        ) else {
            panic!("expected a denial");
        };
        assert!(reason.contains("Pass instance explicitly"));
        assert!(
            reason.contains("alpha"),
            "it should say where focus currently points"
        );
    }

    #[test]
    fn survives_a_round_trip_through_json() {
        let mut policy = Policy::default();
        policy.set_rule(Some("alpha"), Class::Destructive, Rule::Ask);
        policy.require_explicit_instance_for_destructive = true;

        let text = serde_json::to_string(&policy).unwrap();
        let restored: Policy = serde_json::from_str(&text).unwrap();

        assert_eq!(restored.rules_for("alpha", "alpha").destructive, Rule::Ask);
        assert!(restored.require_explicit_instance_for_destructive);
        assert_eq!(restored.rules_for("beta", "beta").destructive, Rule::Allow);
    }

    #[test]
    fn a_policy_file_missing_fields_still_loads() {
        // Hand-edited, and an older orchestrator's file should not fail to parse in a newer one.
        let restored: Policy = serde_json::from_str("{}").unwrap();

        assert_eq!(restored.defaults.destructive, Rule::Allow);
        assert!(!restored.require_explicit_instance_for_destructive);
    }
}
