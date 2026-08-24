//! Which instances are connected right now, and which one is in focus.
//!
//! # Focus, and the failure it exists to prevent
//!
//! A model addresses an instance by name, but making it name one on every call is a lot of ceremony
//! for a session spent working in a single game. So focus is sticky: set it once, and calls that
//! omit `instance` go there.
//!
//! That has exactly one dangerous failure mode, and it is worth stating plainly because everything
//! about how focus is reported follows from it: **a human changes focus in the app, the model does
//! not know, and its next `client_move` drives the wrong game.** Silently. The mitigations live
//! elsewhere — every tool result echoes the instance it acted on, and a focus change emits a
//! notification — but they exist because of this.
//!
//! When exactly one instance is connected, it is implicitly in focus. Requiring a choice between one
//! option is ceremony with no purchase.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use crate::instance::Instance;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FocusResolution {
    /// The call named an instance, or focus resolved to exactly one.
    Resolved(String),
    /// Nothing is connected.
    NoInstances,
    /// More than one is connected and no focus is set. The caller must choose.
    Ambiguous(Vec<String>),
    /// A name was given that is not connected.
    Unknown(String),
}

#[derive(Default)]
pub struct Registry {
    instances: RwLock<HashMap<String, Arc<Instance>>>,
    focus: RwLock<Option<String>>,
}

impl Registry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds a connected instance, replacing any earlier connection under the same id.
    ///
    /// Replacement is the common case, not an edge one: relaunching a game client is what a mod
    /// developer does all day, and the new link routinely arrives before the old socket has finished
    /// dying. The displaced instance is marked closed so anything still waiting on it fails now
    /// rather than at a timeout.
    pub fn insert(&self, instance: Arc<Instance>) -> Option<Arc<Instance>> {
        let id = instance.id();
        let displaced = self
            .instances
            .write()
            .expect("registry lock")
            .insert(id.clone(), instance);
        if let Some(old) = &displaced {
            old.mark_closed();
        }

        // First instance to connect takes focus. Nothing else would, and a model whose very first
        // call needs a focus it was never given has to spend a turn discovering that.
        let mut focus = self.focus.write().expect("focus lock");
        if focus.is_none() {
            *focus = Some(id);
        }
        displaced
    }

    /// Removes an instance, but only if it is still the one under that id.
    ///
    /// The guard matters during a fast relaunch: the old link's cleanup can run *after* the new link
    /// has registered, and an unguarded remove would delete the live connection.
    pub fn remove(&self, id: &str, only_if: &Arc<Instance>) -> bool {
        let mut instances = self.instances.write().expect("registry lock");
        match instances.get(id) {
            Some(current) if Arc::ptr_eq(current, only_if) => {
                instances.remove(id);
            }
            _ => return false,
        }
        drop(instances);

        // Focus follows: pointing at something gone would make every unqualified call fail with
        // "unknown instance" until somebody noticed.
        let mut focus = self.focus.write().expect("focus lock");
        if focus.as_deref() == Some(id) {
            *focus = self
                .instances
                .read()
                .expect("registry lock")
                .keys()
                .next()
                .cloned();
        }
        true
    }

    pub fn get(&self, id: &str) -> Option<Arc<Instance>> {
        self.instances.read().expect("registry lock").get(id).cloned()
    }

    pub fn all(&self) -> Vec<Arc<Instance>> {
        let mut instances: Vec<_> = self
            .instances
            .read()
            .expect("registry lock")
            .values()
            .cloned()
            .collect();
        // Sorted so `mcmcp_instances` and the tool schema's `instance` enum are stable between
        // calls. An order that shuffles per call makes for a needlessly noisy tool catalogue.
        instances.sort_by_key(|instance| instance.id());
        instances
    }

    pub fn ids(&self) -> Vec<String> {
        let mut ids: Vec<_> = self
            .instances
            .read()
            .expect("registry lock")
            .keys()
            .cloned()
            .collect();
        ids.sort();
        ids
    }

    pub fn count(&self) -> usize {
        self.instances.read().expect("registry lock").len()
    }

    pub fn focus(&self) -> Option<String> {
        self.focus.read().expect("focus lock").clone()
    }

    /// Sets focus, refusing an instance that is not connected.
    pub fn set_focus(&self, id: &str) -> bool {
        if self.get(id).is_none() {
            return false;
        }
        *self.focus.write().expect("focus lock") = Some(id.to_string());
        true
    }

    /// Works out which instance a call is for.
    pub fn resolve(&self, requested: Option<&str>) -> FocusResolution {
        if let Some(name) = requested {
            return match self.get(name) {
                Some(_) => FocusResolution::Resolved(name.to_string()),
                None => FocusResolution::Unknown(name.to_string()),
            };
        }

        let ids = self.ids();
        match ids.len() {
            0 => FocusResolution::NoInstances,
            // Exactly one connected: it is implicitly in focus, whatever focus says. Requiring a
            // choice between one option is ceremony with no purchase.
            1 => FocusResolution::Resolved(ids.into_iter().next().expect("one id")),
            _ => match self.focus() {
                Some(focused) if self.get(&focused).is_some() => FocusResolution::Resolved(focused),
                _ => FocusResolution::Ambiguous(ids),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::InstanceInfo;
    use crate::link::protocol::Side;
    use tokio::sync::mpsc;

    fn instance(id: &str) -> Arc<Instance> {
        let (sender, receiver) = mpsc::channel(4);
        // Kept alive so the channel does not close and make the instance look dead.
        Box::leak(Box::new(receiver));
        Arc::new(Instance::new(
            InstanceInfo {
                id: id.into(),
                label: id.into(),
                side: Side::Client,
                game_directory: None,
                mod_version: "test".into(),
                minecraft_version: "1.12.2".into(),
                endpoint_url: None,
            },
            sender,
        ))
    }

    #[test]
    fn the_first_instance_to_connect_takes_focus() {
        // Otherwise nothing would, and a model whose first call needs a focus it was never given
        // has to spend a turn discovering that.
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert_eq!(registry.focus().as_deref(), Some("alpha"));
    }

    #[test]
    fn a_second_instance_does_not_steal_focus() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));
        registry.insert(instance("beta"));

        assert_eq!(registry.focus().as_deref(), Some("alpha"));
    }

    #[test]
    fn one_connected_instance_resolves_without_focus_or_a_name() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert_eq!(registry.resolve(None), FocusResolution::Resolved("alpha".into()));
    }

    #[test]
    fn two_instances_with_no_focus_are_ambiguous_rather_than_guessed() {
        // Guessing is the one thing that must not happen: the wrong guess silently drives the wrong
        // game, and the caller has no way to tell.
        let registry = Registry::new();
        registry.insert(instance("alpha"));
        registry.insert(instance("beta"));
        *registry.focus.write().unwrap() = None;

        assert_eq!(
            registry.resolve(None),
            FocusResolution::Ambiguous(vec!["alpha".into(), "beta".into()])
        );
    }

    #[test]
    fn an_explicit_name_beats_focus() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));
        registry.insert(instance("beta"));

        assert_eq!(
            registry.resolve(Some("beta")),
            FocusResolution::Resolved("beta".into())
        );
    }

    #[test]
    fn a_name_that_is_not_connected_is_reported_rather_than_falling_back_to_focus() {
        // Falling back would run the call somewhere the caller did not ask for. A typo would then
        // move the wrong player, and the result would look like it worked.
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert_eq!(
            registry.resolve(Some("typo")),
            FocusResolution::Unknown("typo".into())
        );
    }

    #[test]
    fn nothing_connected_is_its_own_answer() {
        assert_eq!(Registry::new().resolve(None), FocusResolution::NoInstances);
    }

    #[test]
    fn reconnecting_replaces_the_old_link_and_closes_it() {
        // Relaunching a client is constant in a mod-development loop, and the new link routinely
        // arrives before the old socket has finished dying.
        let registry = Registry::new();
        let first = instance("alpha");
        registry.insert(Arc::clone(&first));
        let second = instance("alpha");

        let displaced = registry
            .insert(Arc::clone(&second))
            .expect("the old link is displaced");

        assert!(Arc::ptr_eq(&displaced, &first));
        assert!(
            !first.is_alive(),
            "the displaced link must be closed, not left waiting"
        );
        assert_eq!(registry.count(), 1);
    }

    #[test]
    fn a_late_cleanup_cannot_remove_the_link_that_replaced_it() {
        // The old link's cleanup can run after the new one registered. An unguarded remove would
        // delete the live connection and leave the instance invisible until it reconnected again.
        let registry = Registry::new();
        let first = instance("alpha");
        registry.insert(Arc::clone(&first));
        let second = instance("alpha");
        registry.insert(Arc::clone(&second));

        assert!(!registry.remove("alpha", &first), "the stale handle must not win");
        assert_eq!(registry.count(), 1);
        assert!(registry.remove("alpha", &second));
        assert_eq!(registry.count(), 0);
    }

    #[test]
    fn focus_moves_on_when_the_focused_instance_disconnects() {
        let registry = Registry::new();
        let alpha = instance("alpha");
        registry.insert(Arc::clone(&alpha));
        registry.insert(instance("beta"));
        assert_eq!(registry.focus().as_deref(), Some("alpha"));

        registry.remove("alpha", &alpha);

        assert_eq!(registry.focus().as_deref(), Some("beta"));
    }

    #[test]
    fn focus_cannot_be_set_to_something_that_is_not_connected() {
        let registry = Registry::new();
        registry.insert(instance("alpha"));

        assert!(!registry.set_focus("ghost"));
        assert_eq!(registry.focus().as_deref(), Some("alpha"));
    }

    #[test]
    fn lists_instances_in_a_stable_order() {
        // The `instance` enum in every tool schema is built from this. An order that shuffles per
        // call would churn the tool catalogue for no reason.
        let registry = Registry::new();
        registry.insert(instance("zulu"));
        registry.insert(instance("alpha"));

        assert_eq!(registry.ids(), vec!["alpha".to_string(), "zulu".to_string()]);
    }
}
