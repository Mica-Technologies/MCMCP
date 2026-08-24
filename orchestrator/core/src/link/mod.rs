//! The link: how a running game instance reaches this orchestrator.
//!
//! The instance dials out. Nothing here listens on the game's behalf, which is what makes running
//! several instances at once stop being a port-allocation exercise, and what makes presence a fact
//! rather than an inference — an instance is alive because its socket is open.

pub mod framing;
pub mod listener;
pub mod protocol;
