//! The link wire vocabulary, mirroring `LinkProtocol.java` in the mod.
//!
//! **These two files are one protocol with two implementations, and nothing but discipline keeps
//! them in step.** The integration test drives the real mod against this code for exactly that
//! reason; a mismatch here does not fail to compile on either side, it fails at runtime as a
//! handshake that never completes.
//!
//! Two kinds of frame travel over the link, told apart by which field they carry: control frames
//! have `type`, MCP frames have `jsonrpc`. Distinguishing them by field rather than by position is
//! what leaves room for a control frame *after* the handshake without another protocol version.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// Must equal `LinkProtocol.VERSION` in the mod.
pub const VERSION: i64 = 1;

pub const TYPE_HELLO: &str = "hello";
pub const TYPE_WELCOME: &str = "welcome";
pub const TYPE_REJECTED: &str = "rejected";

/// Retryable. The instance reached us and a human has been asked to approve it.
pub const REASON_PENDING_APPROVAL: &str = "pending-approval";
/// Not retryable. The id is known and the secret is wrong.
pub const REASON_SECRET_MISMATCH: &str = "secret-mismatch";
/// Not retryable. One side is older than the other.
pub const REASON_UNSUPPORTED_PROTOCOL: &str = "unsupported-protocol";
/// Not retryable. The hello could not be read.
pub const REASON_MALFORMED_HELLO: &str = "malformed-hello";
/// Not retryable. A human revoked this instance.
pub const REASON_REVOKED: &str = "revoked";

/// Which endpoint inside a game instance a link belongs to.
///
/// A singleplayer world runs both at once, in one process, over two links. They are separate
/// addressable instances because their tool surfaces genuinely differ — only the client has a camera
/// and input — but they are the same game, and the roster groups them so a model can see that.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Side {
    Client,
    Server,
}

impl Side {
    pub fn as_str(self) -> &'static str {
        match self {
            Side::Client => "client",
            Side::Server => "server",
        }
    }
}

/// The opening frame from an instance.
///
/// Everything past `instance_id` and `instance_secret` exists so a human can answer the only
/// question an approval prompt really asks — *which game is this?* "Instance modb-dev-3f2a1c wants
/// to connect" is unanswerable; the directory, side and version make it answerable.
#[derive(Debug, Clone, Deserialize)]
pub struct Hello {
    #[serde(rename = "linkProtocol")]
    pub link_protocol: i64,
    #[serde(rename = "instanceId")]
    pub instance_id: String,
    #[serde(rename = "instanceSecret")]
    pub instance_secret: String,
    #[serde(rename = "instanceName", default)]
    pub instance_name: String,
    pub side: Side,
    #[serde(rename = "gameDirectory", default)]
    pub game_directory: Option<String>,
    #[serde(rename = "modVersion", default)]
    pub mod_version: String,
    #[serde(rename = "minecraftVersion", default)]
    pub minecraft_version: String,
    #[serde(rename = "endpointUrl", default)]
    pub endpoint_url: Option<String>,
}

impl Hello {
    /// Checks the parts a protocol-level reader can check on its own.
    ///
    /// Deliberately not an authorisation decision — that belongs to the approval store. This is the
    /// "is this frame even coherent" pass, and its failures all map to `malformed-hello`.
    pub fn validate(&self) -> Result<(), String> {
        if self.link_protocol != VERSION {
            return Err(format!(
                "instance speaks link protocol {} and this orchestrator speaks {}",
                self.link_protocol, VERSION
            ));
        }
        if self.instance_id.trim().is_empty() {
            return Err("hello carried no instanceId".into());
        }
        // 256 bits, hex. A short or absent secret is not a cosmetic problem: the secret is the only
        // thing standing between an approved id and any other process on the machine claiming it.
        if self.instance_secret.len() != 64
            || !self.instance_secret.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err(format!(
                "hello carried a malformed instanceSecret ({} characters)",
                self.instance_secret.len()
            ));
        }
        Ok(())
    }

    /// The label to show for this instance before an operator has renamed it.
    pub fn fallback_label(&self) -> String {
        if self.instance_name.trim().is_empty() {
            self.instance_id.clone()
        } else {
            self.instance_name.trim().to_string()
        }
    }
}

/// Builds the frame that accepts a link.
///
/// `assigned_name` is how a label typed into the roster reaches the instance. The orchestrator owns
/// that label, so it is sent every time rather than written back into the game's config — two places
/// to change one name is how they end up disagreeing.
pub fn welcome(orchestrator_version: &str, assigned_name: Option<&str>) -> Value {
    let mut frame = json!({
        "type": TYPE_WELCOME,
        "linkProtocol": VERSION,
        "orchestrator": { "name": "MCMCP Orchestrator", "version": orchestrator_version },
    });
    if let Some(name) = assigned_name {
        frame["instanceName"] = json!(name);
    }
    frame
}

/// Builds the frame that refuses a link.
///
/// The `reason` is load-bearing on the far side: the mod keeps retrying a `pending-approval` and
/// stops dead on a `secret-mismatch`. Reaching for a vague reason here turns a fixable problem into
/// one that retries forever, or a transient one into an instance that never comes back.
pub fn rejected(reason: &str, message: &str) -> Value {
    json!({
        "type": TYPE_REJECTED,
        "linkProtocol": VERSION,
        "reason": reason,
        "message": message,
    })
}

/// Whether a frame is a link control frame rather than an MCP message.
pub fn is_control_frame(frame: &Value) -> bool {
    frame.get("type").is_some()
}

/// Whether a frame is an MCP message rather than a link control frame.
pub fn is_mcp_message(frame: &Value) -> bool {
    frame.get("jsonrpc").is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hello_json() -> Value {
        json!({
            "type": "hello",
            "linkProtocol": 1,
            "instanceId": "modb-dev-3f2a1c",
            "instanceSecret": "0".repeat(64),
            "instanceName": "modB dev",
            "side": "client",
            "gameDirectory": "E:\\instances\\modB",
            "modVersion": "2026.08.24",
            "minecraftVersion": "1.12.2",
        })
    }

    #[test]
    fn reads_the_hello_the_mod_actually_sends() {
        let hello: Hello = serde_json::from_value(hello_json()).expect("hello should parse");

        assert_eq!(hello.instance_id, "modb-dev-3f2a1c");
        assert_eq!(hello.side, Side::Client);
        assert_eq!(hello.game_directory.as_deref(), Some("E:\\instances\\modB"));
        assert!(hello.validate().is_ok());
    }

    #[test]
    fn accepts_a_hello_missing_its_optional_fields() {
        // A dedicated server has no endpoint URL to advertise when its port was taken, and a game
        // directory can fail to resolve. Neither should fail the handshake.
        let mut frame = hello_json();
        let object = frame.as_object_mut().unwrap();
        object.remove("gameDirectory");
        object.remove("endpointUrl");

        let hello: Hello = serde_json::from_value(frame).expect("hello should parse");
        assert!(hello.validate().is_ok());
        assert_eq!(hello.game_directory, None);
    }

    #[test]
    fn refuses_a_hello_whose_secret_is_not_256_bits_of_hex() {
        let mut frame = hello_json();
        frame["instanceSecret"] = json!("tooshort");
        let hello: Hello = serde_json::from_value(frame).unwrap();

        let error = hello.validate().expect_err("a short secret must be refused");
        assert!(error.contains("instanceSecret"));
    }

    #[test]
    fn refuses_a_hello_whose_secret_is_the_right_length_but_not_hex() {
        // Right shape, wrong alphabet. Worth catching separately: a secret that is 64 characters of
        // something else is far more likely to be a bug on the far side than an attack.
        let mut frame = hello_json();
        frame["instanceSecret"] = json!("z".repeat(64));
        let hello: Hello = serde_json::from_value(frame).unwrap();

        assert!(hello.validate().is_err());
    }

    #[test]
    fn refuses_a_protocol_version_it_does_not_speak() {
        let mut frame = hello_json();
        frame["linkProtocol"] = json!(99);
        let hello: Hello = serde_json::from_value(frame).unwrap();

        let error = hello.validate().expect_err("an unknown protocol must be refused");
        assert!(error.contains("99"));
    }

    #[test]
    fn falls_back_to_the_id_when_an_instance_has_no_name() {
        let mut frame = hello_json();
        frame["instanceName"] = json!("   ");
        let hello: Hello = serde_json::from_value(frame).unwrap();

        assert_eq!(hello.fallback_label(), "modb-dev-3f2a1c");
    }

    #[test]
    fn tells_control_frames_apart_from_mcp_messages() {
        assert!(is_control_frame(&hello_json()));
        assert!(!is_mcp_message(&hello_json()));

        let mcp = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"});
        assert!(is_mcp_message(&mcp));
        assert!(!is_control_frame(&mcp));
    }

    #[test]
    fn a_welcome_carries_an_assigned_name_only_when_there_is_one() {
        assert!(welcome("0.1.0", None).get("instanceName").is_none());
        assert_eq!(welcome("0.1.0", Some("control")).get("instanceName").unwrap(), "control");
    }
}
