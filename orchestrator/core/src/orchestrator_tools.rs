//! The tools and prompts the orchestrator answers itself, rather than routing.
//!
//! # What belongs here
//!
//! Anything whose answer is *about* the set of instances rather than about one game. Listing what is
//! connected, moving focus, and — the ones that earn their keep once there really are several games
//! open — comparing two of them and reading their logs side by side.
//!
//! # What deliberately does not
//!
//! Approving a pairing, revoking one, and changing gating policy. Those decide what a model is
//! allowed to reach, so a model changing them defeats the point of asking in the first place. They
//! live behind [`crate::control::Authority`] and are reachable from the app and the CLI only. A test
//! in [`crate::router`] asserts that none of them ever appears in this list.

use serde_json::{Value, json};

/// Every tool the orchestrator answers itself.
pub const NAMES: &[&str] = &[
    "mcmcp_instances",
    "mcmcp_focus",
    "mcmcp_set_label",
    "mcmcp_compare_instances",
    "mcmcp_read_logs",
];

/// The prompt the orchestrator offers, for the workflow it exists to support.
pub const COMPARE_PROMPT: &str = "compare_instances";

/// Marks a tool as answered here rather than routed to a game.
///
/// Published in each tool's `_meta`, which MCP reserves for exactly this: information about a tool
/// that is not part of its contract. It exists because guessing has failed twice. A hardcoded list
/// went stale the moment a tool was added; the obvious replacement — "a tool with no `instance`
/// argument must be ours" — is wrong too, because `mcmcp_read_logs` takes an optional `instance` to
/// narrow to one game. The only reliable answer is the one the orchestrator gives directly.
pub const ANSWERED_BY_META: &str = "mcmcp/answeredBy";
pub const ANSWERED_BY_ORCHESTRATOR: &str = "orchestrator";

/// Stamps a definition as the orchestrator's own.
fn own(mut tool: Value) -> Value {
    if let Some(object) = tool.as_object_mut() {
        object.insert(
            "_meta".to_string(),
            json!({ ANSWERED_BY_META: ANSWERED_BY_ORCHESTRATOR }),
        );
    }
    tool
}

/// A `string` property naming an instance, with the known ones as an enum where there are any.
fn instance_property(addressable: &[String], description: &str) -> Value {
    let mut property = json!({ "type": "string", "description": description });
    if !addressable.is_empty() {
        // A model picks from a list far more reliably than it recalls an id from prose.
        property["enum"] = Value::Array(addressable.iter().map(|id| json!(id)).collect());
    }
    property
}

pub fn definitions(addressable: &[String]) -> Vec<Value> {
    let target = instance_property(
        addressable,
        "The instance id, as reported by mcmcp_instances. Ids are '<game>.client' or \
         '<game>.server' — one game open in singleplayer is two of them.",
    );

    vec![
        own(json!({
            "name": "mcmcp_instances",
            "title": "List Minecraft instances",
            "description": "List every Minecraft game connected to this orchestrator, with its id, \
                label, which side it is (client or server), what it is running, and which one is \
                currently focused. Call this before acting when more than one game may be open — the \
                games are usually different worlds with different mods, and acting on the wrong one \
                is rarely harmless. Also lists instances this orchestrator knows but that are not \
                running right now.\n\nEach entry is one ENDPOINT, addressed as '<game>.client' or \
                '<game>.server', and its 'game' field says which game it belongs to. A singleplayer \
                world is two entries sharing one game: the client has the camera, the input and the \
                screenshots, and the server has authoritative world state and commands. Two entries \
                with the same 'game' are one running game, not two. A game this orchestrator saw \
                stop carries 'lastExit', naming any crash report or JVM error log it left. An entry \
                with 'responding': false has a game thread that stopped finishing frames \
                'stalledSeconds' ago: calls that need it will time out, and its game_health says why.",
            "inputSchema": { "type": "object", "properties": {} },
            "annotations": { "readOnlyHint": true, "destructiveHint": false, "idempotentHint": true },
        })),
        own(json!({
            "name": "mcmcp_focus",
            "title": "Get or set the focused instance",
            "description": "Read or change which instance tool calls go to when they do not name one. \
                Call with no arguments to read the current focus. Setting focus is a convenience, not \
                a lock: a human can change it at any time from the orchestrator, so always trust the \
                instance named in a tool result over your memory of what you focused.",
            "inputSchema": {
                "type": "object",
                "properties": { "target": target.clone() },
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true },
        })),
        own(json!({
            "name": "mcmcp_set_label",
            "title": "Rename an instance",
            "description": "Give an instance a human-readable label, so it can be told apart from the \
                others in later calls and in the orchestrator's own roster. Names the instance for \
                everyone, not just this session. The label belongs to the GAME, so renaming through \
                either endpoint renames both.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "target": target.clone(),
                    "label": { "type": "string", "description": "The new label, e.g. 'mymod dev'." },
                },
                "required": ["target", "label"],
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true },
        })),
        own(json!({
            "name": "mcmcp_compare_instances",
            "title": "Compare what the connected games are running",
            "description": "Report what is DIFFERENT between the connected instances: which mods each \
                one has that the others do not, their Minecraft and MCMCP versions, and which tools \
                only some of them offer. \
                \n\nUse this when you have more than one game open and need to know which is which — \
                the mods unique to an instance are usually the answer to 'which one is the mod I am \
                working on'. Also worth calling before an A/B comparison, so you know what actually \
                differs between the two worlds before attributing a behaviour difference to your \
                change.",
            "inputSchema": { "type": "object", "properties": {} },
            "annotations": { "readOnlyHint": true, "destructiveHint": false, "idempotentHint": true },
        })),
        own(json!({
            "name": "mcmcp_read_logs",
            "title": "Read several games' logs together",
            "description": "Read the tail of latest.log from every connected instance and interleave \
                the lines in time order, each tagged with the game it came from. \
                \n\nThis is what to reach for when something happened across two games and you need \
                to see the order it happened in — a crash in one right after an action in the other, \
                or the same error appearing in both. Reading each game's log separately gives you two \
                lists you then have to merge by eye.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "lines": {
                        "type": "integer",
                        "description": "Lines to read from each instance before merging.",
                        "minimum": 1,
                        "maximum": 500,
                        "default": 60,
                    },
                    "filter": {
                        "type": "string",
                        "description": "Only keep lines containing this text, applied per instance \
                            before merging.",
                    },
                    "instance": instance_property(
                        addressable,
                        "Read only this instance. Omit to read every connected one, which is the \
                         point of this tool.",
                    ),
                },
            },
            "annotations": { "readOnlyHint": true, "destructiveHint": false, "idempotentHint": true },
        })),
    ]
}

/// The orchestrator's own prompt.
///
/// One prompt, for the one workflow that is genuinely hard to drive without being told how: running
/// the same thing in two games and attributing the difference correctly. The trap it exists to avoid
/// is a model comparing two worlds that differ in ways nobody accounted for and confidently blaming
/// the change under test.
pub fn prompt_definition(addressable: &[String]) -> Value {
    let known = if addressable.is_empty() {
        String::new()
    } else {
        format!(" Known instances: {}.", addressable.join(", "))
    };
    json!({
        "name": COMPARE_PROMPT,
        "title": "Compare two instances",
        "description": "Run the same action in two games and report what differs — the shape of \
            testing a mod against a control.",
        "arguments": [
            {
                "name": "action",
                "description": "What to do in both games, in plain words. For example: 'place a \
                    redstone torch next to a lamp and see whether it lights'.",
                "required": true,
            },
            {
                "name": "first",
                "description": format!("The instance to treat as the one under test.{known}"),
                "required": false,
            },
            {
                "name": "second",
                "description": "The instance to treat as the control.",
                "required": false,
            },
        ],
    })
}

/// Builds the prompt's messages, with the live roster already folded in.
///
/// The roster is gathered here rather than left for the model to fetch, which is the point of a
/// prompt over a paragraph of instructions: it arrives already knowing what is connected.
pub fn compare_prompt_messages(
    action: &str,
    first: Option<&str>,
    second: Option<&str>,
    roster: &[Value],
) -> Value {
    let mut text = String::new();

    text.push_str("Compare two running Minecraft instances by doing the same thing in each.\n\n");
    text.push_str(&format!("What to do in both: {action}\n\n"));

    if roster.is_empty() {
        text.push_str(
            "No game is connected right now, so this cannot be run yet. Say so rather than \
             guessing at an answer.\n",
        );
        return json!({
            "description": "Compare two instances",
            "messages": [{ "role": "user", "content": { "type": "text", "text": text } }],
        });
    }

    text.push_str("Connected right now:\n");
    for entry in roster {
        let id = entry.get("instance").and_then(Value::as_str).unwrap_or("?");
        let label = entry.get("label").and_then(Value::as_str).unwrap_or(id);
        let side = entry.get("side").and_then(Value::as_str).unwrap_or("?");
        let directory = entry.get("gameDirectory").and_then(Value::as_str).unwrap_or("");
        text.push_str(&format!("  - {id} — \"{label}\" ({side}) {directory}\n"));
    }
    text.push('\n');

    match (first, second) {
        (Some(first), Some(second)) => {
            text.push_str(&format!(
                "Treat {first} as the one under test and {second} as the control.\n\n"
            ));
        }
        _ => {
            text.push_str(
                "Decide which is the one under test and which is the control before you start, \
                 and say which you chose. mcmcp_compare_instances shows what differs between \
                 them — the mods unique to an instance are usually the answer.\n\n",
            );
        }
    }

    text.push_str(
        "How to run it:\n\
         1. Call mcmcp_compare_instances first. Know what already differs between the two worlds \
            before you attribute anything to the change under test — different mods, different \
            Minecraft versions, and different tool surfaces all produce behaviour differences that \
            have nothing to do with what you are testing.\n\
         2. Read the starting state of both, naming each instance explicitly rather than relying \
            on focus. Passing `instance` on every call is worth the extra argument here: a human \
            can move focus at any time, and a comparison that silently ran twice in the same game \
            is worse than no comparison.\n\
         3. Do the action in each, one at a time, in the same order and from a comparable starting \
            position. Read the result after each rather than doing both and then looking.\n\
         4. Read the state again in both, and mcmcp_read_logs to see whether either logged \
            anything while you were working.\n\n\
         What to report:\n\
         - What each game did, separately, before any interpretation.\n\
         - What differed, and what was the same.\n\
         - Whether the difference is explained by something mcmcp_compare_instances already showed \
           — a mod only one of them has, say — rather than by the behaviour under test.\n\
         - Say plainly if the two worlds were not comparable enough for the result to mean \
           anything. That is a real outcome and a useful one.\n",
    );

    json!({
        "description": "Compare two instances",
        "messages": [{ "role": "user", "content": { "type": "text", "text": text } }],
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_named_tool_has_a_definition() {
        // The two lists are used for different things — one routes, one is published — and a name in
        // one but not the other is either an unroutable tool or an invisible one.
        let defined: Vec<String> = definitions(&[])
            .iter()
            .map(|tool| tool["name"].as_str().unwrap().to_string())
            .collect();

        assert_eq!(defined, NAMES);
    }

    #[test]
    fn every_definition_says_the_orchestrator_answers_it() {
        // Guessing has failed twice: a hardcoded list went stale, and "no instance argument means
        // ours" is wrong because mcmcp_read_logs takes an optional one. This is the authoritative
        // answer, and every tool has to carry it or the guessing comes back.
        for tool in definitions(&[]) {
            assert_eq!(
                tool["_meta"][ANSWERED_BY_META], ANSWERED_BY_ORCHESTRATOR,
                "{} does not say who answers it",
                tool["name"]
            );
        }
    }

    #[test]
    fn nothing_here_can_widen_what_a_model_reaches() {
        // The authorisation boundary, asserted rather than assumed.
        for name in NAMES {
            for forbidden in ["approve", "revoke", "policy", "strict", "gate", "trust"] {
                assert!(
                    !name.contains(forbidden),
                    "{name} looks like it changes trust, which is human-only"
                );
            }
        }
    }

    #[test]
    fn the_reading_tools_are_annotated_read_only() {
        // Gating classifies by annotation, and a read that claimed to mutate would be blocked by a
        // policy nobody meant to apply to it.
        for tool in definitions(&[]) {
            let name = tool["name"].as_str().unwrap();
            if name == "mcmcp_instances" || name == "mcmcp_compare_instances" || name == "mcmcp_read_logs" {
                assert_eq!(tool["annotations"]["readOnlyHint"], true, "{name}");
            }
        }
    }

    #[test]
    fn known_instances_appear_as_enums() {
        let tools = definitions(&["alpha".into(), "beta".into()]);
        let focus = tools.iter().find(|tool| tool["name"] == "mcmcp_focus").unwrap();
        let logs = tools
            .iter()
            .find(|tool| tool["name"] == "mcmcp_read_logs")
            .unwrap();

        assert_eq!(
            focus["inputSchema"]["properties"]["target"]["enum"],
            json!(["alpha", "beta"])
        );
        assert_eq!(
            logs["inputSchema"]["properties"]["instance"]["enum"],
            json!(["alpha", "beta"])
        );
    }

    #[test]
    fn the_compare_prompt_folds_in_the_live_roster() {
        // The reason to be a prompt rather than a paragraph of advice: it arrives already knowing
        // what is connected.
        let roster = vec![json!({
            "instance": "modb-dev",
            "label": "modB dev",
            "side": "client",
            "gameDirectory": "E:\\instances\\modB",
        })];

        let prompt = compare_prompt_messages("place a torch", None, None, &roster);
        let text = prompt["messages"][0]["content"]["text"].as_str().unwrap();

        assert!(text.contains("place a torch"));
        assert!(text.contains("modb-dev"));
        assert!(text.contains("modB dev"));
    }

    #[test]
    fn the_compare_prompt_tells_the_model_to_address_instances_explicitly() {
        // The failure this exists to prevent: a comparison that silently ran twice in the same game
        // because focus moved underneath it.
        let roster = vec![json!({"instance": "a"}), json!({"instance": "b"})];

        let prompt = compare_prompt_messages("do the thing", Some("a"), Some("b"), &roster);
        let text = prompt["messages"][0]["content"]["text"].as_str().unwrap();

        assert!(text.contains("naming each instance explicitly"));
        assert!(text.contains("a as the one under test"));
        assert!(text.contains("b as the control"));
    }

    #[test]
    fn the_compare_prompt_says_so_when_there_is_nothing_to_compare() {
        // Better than a set of instructions the model cannot follow and might improvise around.
        let prompt = compare_prompt_messages("do the thing", None, None, &[]);
        let text = prompt["messages"][0]["content"]["text"].as_str().unwrap();

        assert!(text.contains("No game is connected"));
        assert!(text.contains("rather than guessing"));
    }

    #[test]
    fn the_compare_prompt_warns_against_attributing_a_pre_existing_difference() {
        // The trap the whole prompt exists to avoid.
        let roster = vec![json!({"instance": "a"}), json!({"instance": "b"})];

        let text = compare_prompt_messages("x", None, None, &roster)["messages"][0]["content"]["text"]
            .as_str()
            .unwrap()
            .to_string();

        assert!(text.contains("before you attribute anything"));
        assert!(text.contains("not comparable enough"));
    }
}
