//! One MCP endpoint for every linked Minecraft instance.
//!
//! # What this is
//!
//! A **proxy**, not a tool server. Claude connects to it once; several game instances dial into it;
//! it presents their union as a single MCP surface and routes each call to the instance the caller
//! named. That framing decides nearly every design choice in here — most of all the refusal to
//! deserialise MCP messages into typed structs, since a proxy forwards messages it did not author
//! between two peers that may both be newer than it is.
//!
//! # Layout
//!
//! - [`jsonrpc`] — envelope handling, on untyped `Value`s
//! - [`link`] — the wire protocol game instances speak, mirroring the mod's `link` package
//! - [`instance`] — one connected game, and how to ask it things
//! - [`store`] — which instances have been approved, keyed by id and authenticated by secret hash
//! - [`paths`] — where this orchestrator keeps its state on each platform

pub mod catalogue;
pub mod control;
pub mod crash;
pub mod events;
pub mod instance;
pub mod jsonrpc;
pub mod link;
pub mod logging;
pub mod mcp_socket;
pub mod orchestrator_tools;
pub mod paths;
pub mod policy;
pub mod registry;
pub mod router;
pub mod stdio;
pub mod store;

/// The orchestrator's version, from Cargo. Deliberately never a git tag — see the workspace
/// manifest for why a tag here would rewrite the *mod's* version.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
