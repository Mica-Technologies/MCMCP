//! Turning several instances' catalogues into one MCP surface.
//!
//! # The whole point, in one number
//!
//! Three instances behind three MCP entries means three copies of a 45-tool catalogue in every
//! request a model makes — the same tools, the same descriptions, distinguished only by a prefix the
//! host adds. Aggregating them into one catalogue with an `instance` argument is roughly a threefold
//! saving that grows with every instance added, and it is the reason this file exists.
//!
//! # Union, not intersection
//!
//! Tools are unioned by name. Two instances running the same mod version offer identical tools and
//! collapse to one entry; an instance with a third-party mod registering into MCMCP contributes
//! tools the others lack, and those are listed with a note saying where they work.
//!
//! Hiding them behind focus was the alternative and is worse: the tool surface would then change as
//! focus moves, which is a far more surprising thing for a model to experience than a description
//! line saying "available on: alpha".

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;
use std::sync::Arc;

use crate::instance::Catalogue;

/// The argument every routed tool gains.
pub const INSTANCE_ARGUMENT: &str = "instance";

/// Addresses every connected instance at once.
///
/// Offered **only on read-only tools**, and that restriction is the entire design. "What does each
/// game report" is a genuinely useful question and answering it one call at a time is tedious;
/// "move the player in all three games" is not a thing anybody means, and a fan-out that could do it
/// would eventually do it by accident. The enum is where the restriction lives, so a model never
/// sees the option on a tool where it would be wrong.
pub const ALL_INSTANCES: &str = "*";

/// One instance's contribution to the aggregate.
pub struct Contribution {
    pub id: String,
    pub label: String,
    pub catalogue: Arc<Catalogue>,
}

/// The aggregated view handed to an MCP client.
///
/// Serialisable because it is cached to disk: a client that connects before any game is up must
/// still see a real tool surface, and relaunching a game — constant in a mod-development loop —
/// must not empty and refill the catalogue each time.
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct Aggregate {
    pub tools: Vec<Value>,
    pub resources: Vec<Value>,
    pub resource_templates: Vec<Value>,
    pub prompts: Vec<Value>,
    /// Tool name to the instances offering it, for routing and for error messages that can say
    /// where a tool *would* work.
    #[serde(default)]
    pub tool_owners: BTreeMap<String, Vec<String>>,
}

impl Aggregate {
    pub fn instances_with_tool(&self, name: &str) -> &[String] {
        self.tool_owners.get(name).map(Vec::as_slice).unwrap_or(&[])
    }
}

/// Builds the aggregate.
///
/// `addressable` is the set of instance ids the `instance` argument offers as an enum. It is
/// deliberately wider than "currently connected": it includes instances seen recently but not
/// running, so that relaunching a game client — which happens constantly in a mod-development
/// loop — does not rewrite every tool schema and churn the catalogue.
pub fn aggregate(contributions: &[Contribution], addressable: &[String], focused: Option<&str>) -> Aggregate {
    let mut aggregate = Aggregate::default();
    let mut tools_by_name: BTreeMap<String, Value> = BTreeMap::new();

    for contribution in contributions {
        for tool in &contribution.catalogue.tools {
            let Some(name) = tool.get("name").and_then(Value::as_str) else {
                continue;
            };
            aggregate
                .tool_owners
                .entry(name.to_string())
                .or_default()
                .push(contribution.id.clone());
            // First definition wins. Two instances on the same mod version offer byte-identical
            // tools, and where they differ there is no basis for preferring the later one.
            tools_by_name
                .entry(name.to_string())
                .or_insert_with(|| tool.clone());
        }

        for resource in &contribution.catalogue.resources {
            if let Some(qualified) = qualify_resource(resource, &contribution.id, &contribution.label) {
                aggregate.resources.push(qualified);
            }
        }
        for template in &contribution.catalogue.resource_templates {
            if let Some(qualified) = qualify_template(template, &contribution.id, &contribution.label) {
                aggregate.resource_templates.push(qualified);
            }
        }
    }

    let total = contributions.len();
    for (name, mut tool) in tools_by_name {
        let owners = aggregate.instances_with_tool(&name);
        if owners.len() < total && total > 1 {
            annotate_availability(&mut tool, owners);
        }
        inject_instance_argument(&mut tool, addressable, focused);
        aggregate.tools.push(tool);
    }

    // Prompts are few and user-invoked. One copy, with the same `instance` argument tools get.
    let mut prompts_by_name: BTreeMap<String, Value> = BTreeMap::new();
    for contribution in contributions {
        for prompt in &contribution.catalogue.prompts {
            let Some(name) = prompt.get("name").and_then(Value::as_str) else {
                continue;
            };
            prompts_by_name.entry(name.to_string()).or_insert_with(|| {
                let mut prompt = prompt.clone();
                inject_instance_prompt_argument(&mut prompt, addressable);
                prompt
            });
        }
    }
    aggregate.prompts = prompts_by_name.into_values().collect();

    aggregate
}

fn annotate_availability(tool: &mut Value, owners: &[String]) {
    let existing = tool
        .get("description")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let note = format!("\n\nAvailable on: {}.", owners.join(", "));
    tool["description"] = json!(format!("{existing}{note}"));
}

/// Adds the `instance` property to a tool's input schema.
///
/// Optional, never required. A model that names an instance gets that instance; one that does not
/// gets the focused one, which is the whole reason focus exists. Making it required would put a
/// mandatory argument on all 45 tools to serve the case where more than one game is open.
pub fn inject_instance_argument(tool: &mut Value, addressable: &[String], focused: Option<&str>) {
    // Read-only tools may be fanned out; nothing else may. Decided from the tool's own annotations
    // before the schema is touched, so the option simply does not exist where it would be wrong.
    let fannable = crate::policy::Class::of(tool) == crate::policy::Class::ReadOnly;

    // Value has no entry(); Map does. Reaching the object first is the whole difference.
    let Some(tool) = tool.as_object_mut() else {
        return;
    };
    let schema = tool
        .entry("inputSchema")
        .or_insert_with(|| json!({"type": "object"}));
    if !schema.is_object() {
        return;
    }
    let schema = schema.as_object_mut().expect("checked above");
    schema.entry("type").or_insert_with(|| json!("object"));

    let properties = schema
        .entry("properties")
        .or_insert_with(|| Value::Object(Map::new()));
    let Some(properties) = properties.as_object_mut() else {
        return;
    };
    properties.insert(
        INSTANCE_ARGUMENT.to_string(),
        instance_property(addressable, focused, fannable),
    );
}

/// The `instance` property, written short on purpose.
///
/// This string is stamped onto every one of ~49 tools, so each sentence in it is paid for 49 times
/// in the catalogue a client holds — the long form ran to ~340 characters, some 4k tokens of
/// near-identical boilerplate across the surface. Everything cut from it is said once already, in
/// the server instructions the host puts in front of the tool list: that `instance` names which game
/// to act on, that omitting it uses focus, and that `mcmcp_instances` is how to see what is
/// connected. Repeating that per tool taught a model nothing it was not about to read anyway.
///
/// What stays is what is *specific to this tool and this moment* and appears nowhere else: which
/// instance focus currently points at, the enum of ids, and — on a read-only tool — that `"*"` is
/// accepted. With one instance addressable even focus is redundant, so the whole thing collapses to
/// a clause.
fn instance_property(addressable: &[String], focused: Option<&str>, fannable: bool) -> Value {
    let mut description = String::from("Which game to act on; omit for the focused one");
    match focused {
        Some(focused) if addressable.len() > 1 => description.push_str(&format!(" ({focused}).")),
        _ => description.push('.'),
    }
    if fannable && addressable.len() > 1 {
        description.push_str(" \"*\" runs it on every connected game.");
    }

    let mut property = json!({ "type": "string", "description": description });
    if !addressable.is_empty() {
        // An enum is worth a great deal here: a model picks from a list far more reliably than it
        // recalls an id from a description. It covers instances seen recently as well as connected
        // ones, so a relaunch does not rewrite every schema.
        let mut values: Vec<Value> = addressable.iter().map(|id| json!(id)).collect();
        if fannable {
            values.push(json!(ALL_INSTANCES));
        }
        property["enum"] = Value::Array(values);
    }
    property
}

fn inject_instance_prompt_argument(prompt: &mut Value, addressable: &[String]) {
    let Some(prompt) = prompt.as_object_mut() else {
        return;
    };
    let arguments = prompt
        .entry("arguments")
        .or_insert_with(|| Value::Array(Vec::new()));
    let Some(arguments) = arguments.as_array_mut() else {
        return;
    };
    let already_present = arguments
        .iter()
        .any(|argument| argument.get("name").and_then(Value::as_str) == Some(INSTANCE_ARGUMENT));
    if already_present {
        return;
    }
    let known = if addressable.is_empty() {
        String::new()
    } else {
        format!(" Known instances: {}.", addressable.join(", "))
    };
    arguments.push(json!({
        "name": INSTANCE_ARGUMENT,
        "description": format!("Which game instance to gather state from. \
            Omit it to use the focused instance.{known}"),
        "required": false,
    }));
}

// ------------------------------------------------------------------
// Resource URIs
// ------------------------------------------------------------------

/// Rewrites an instance's resource URI so it names the instance.
///
/// `minecraft://game/mods` on instance `alpha` becomes `minecraft://alpha/game/mods`.
///
/// The instance is inserted as the authority and the original authority becomes the first path
/// segment, which is exactly reversible and stays readable — a model reading a URI list can see
/// which game each resource belongs to without being told.
///
/// Returns `None` for a URI with no `://`, which cannot be rewritten reversibly. Nothing MCMCP ships
/// looks like that; a third-party registration might, and dropping it with a warning beats exposing
/// a URI that cannot be routed back.
pub fn qualify_uri(instance: &str, uri: &str) -> Option<String> {
    let (scheme, rest) = uri.split_once("://")?;
    Some(format!("{scheme}://{instance}/{rest}"))
}

/// The inverse of [`qualify_uri`]: splits a qualified URI back into instance and original.
pub fn unqualify_uri(uri: &str) -> Option<(String, String)> {
    let (scheme, rest) = uri.split_once("://")?;
    let (instance, original) = rest.split_once('/')?;
    if instance.is_empty() || original.is_empty() {
        return None;
    }
    Some((instance.to_string(), format!("{scheme}://{original}")))
}

fn qualify_resource(resource: &Value, instance: &str, label: &str) -> Option<Value> {
    let uri = resource.get("uri").and_then(Value::as_str)?;
    let qualified = qualify_uri(instance, uri)?;
    let mut resource = resource.clone();
    resource["uri"] = json!(qualified);
    prefix_name(&mut resource, label);
    Some(resource)
}

fn qualify_template(template: &Value, instance: &str, label: &str) -> Option<Value> {
    let uri = template.get("uriTemplate").and_then(Value::as_str)?;
    let qualified = qualify_uri(instance, uri)?;
    let mut template = template.clone();
    template["uriTemplate"] = json!(qualified);
    prefix_name(&mut template, label);
    Some(template)
}

/// Puts the instance's label in front of a resource's human-readable name.
///
/// Resources are unioned across instances without collapsing — three games each have their own
/// player state, and they are genuinely different resources. Three entries all called "Loaded mods"
/// is a list nobody can use.
fn prefix_name(resource: &mut Value, label: &str) {
    if let Some(title) = resource.get("title").and_then(Value::as_str) {
        resource["title"] = json!(format!("{label}: {title}"));
    }
    if let Some(name) = resource.get("name").and_then(Value::as_str) {
        resource["name"] = json!(format!("{label}: {name}"));
    }
}

// ------------------------------------------------------------------
// Persistence
// ------------------------------------------------------------------

/// Reads a cached aggregate, treating anything unreadable as absent.
///
/// A corrupt cache is not worth failing over, unlike a corrupt approval store: the worst outcome is
/// an empty tool list until the first game connects, which is a few seconds of inconvenience rather
/// than a lost security decision.
pub fn load_cache(path: &std::path::Path) -> Option<Aggregate> {
    let text = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

pub fn save_cache(path: &std::path::Path, aggregate: &Aggregate) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let text = serde_json::to_string(aggregate).map_err(std::io::Error::other)?;
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, text)?;
    std::fs::rename(&temporary, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn catalogue(tools: Vec<&str>) -> Arc<Catalogue> {
        Arc::new(Catalogue {
            tools: tools
                .into_iter()
                .map(|name| {
                    json!({
                        "name": name,
                        "description": "does a thing",
                        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}}},
                    })
                })
                .collect(),
            ..Catalogue::default()
        })
    }

    fn contribution(id: &str, tools: Vec<&str>) -> Contribution {
        Contribution {
            id: id.into(),
            label: id.into(),
            catalogue: catalogue(tools),
        }
    }

    fn names(aggregate: &Aggregate) -> Vec<String> {
        aggregate
            .tools
            .iter()
            .map(|tool| tool["name"].as_str().unwrap().to_string())
            .collect()
    }

    #[test]
    fn identical_catalogues_collapse_to_one_copy() {
        // The entire point: three instances of the same mod version must not cost three copies of
        // the catalogue in a model's context.
        let contributions = vec![
            contribution("alpha", vec!["client_move"]),
            contribution("beta", vec!["client_move"]),
        ];

        let aggregate = aggregate(&contributions, &["alpha".into(), "beta".into()], None);

        assert_eq!(names(&aggregate), vec!["client_move".to_string()]);
        assert_eq!(aggregate.instances_with_tool("client_move"), ["alpha", "beta"]);
    }

    #[test]
    fn a_tool_only_one_instance_has_is_listed_and_says_where_it_works() {
        let contributions = vec![
            contribution("alpha", vec!["client_move", "mymod_reactor"]),
            contribution("beta", vec!["client_move"]),
        ];

        let aggregate = aggregate(&contributions, &[], None);

        assert_eq!(
            names(&aggregate),
            vec!["client_move".to_string(), "mymod_reactor".to_string()]
        );
        let reactor = aggregate
            .tools
            .iter()
            .find(|t| t["name"] == "mymod_reactor")
            .unwrap();
        assert!(
            reactor["description"]
                .as_str()
                .unwrap()
                .contains("Available on: alpha")
        );
    }

    #[test]
    fn a_tool_every_instance_has_carries_no_availability_note() {
        // Noise on 45 tools for a fact that is always true would be worse than useless.
        let contributions = vec![
            contribution("alpha", vec!["client_move"]),
            contribution("beta", vec!["client_move"]),
        ];

        let aggregate = aggregate(&contributions, &[], None);

        assert!(
            !aggregate.tools[0]["description"]
                .as_str()
                .unwrap()
                .contains("Available on")
        );
    }

    #[test]
    fn every_tool_gains_an_optional_instance_argument() {
        let aggregate = aggregate(
            &[contribution("alpha", vec!["client_move"])],
            &["alpha".into()],
            None,
        );

        let schema = &aggregate.tools[0]["inputSchema"];
        assert_eq!(schema["properties"]["instance"]["type"], "string");
        assert_eq!(schema["properties"]["instance"]["enum"], json!(["alpha"]));
        // Optional, never required: a mandatory argument on all 45 tools to serve the multi-game
        // case would tax the single-game case that is most of the usage.
        assert!(
            schema.get("required").is_none()
                || !schema["required"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|r| r == "instance")
        );
        // And the tool's own arguments survive.
        assert_eq!(schema["properties"]["x"]["type"], "integer");
    }

    #[test]
    fn only_read_only_tools_offer_the_fan_out_option() {
        // The restriction lives in the enum so a model never sees the option where it would be
        // wrong. "What does each game report" is useful; "move the player in all three" is not
        // something anybody means, and an option that could do it would eventually do it.
        let mut reader = json!({
            "name": "client_player_state",
            "annotations": {"readOnlyHint": true},
            "inputSchema": {"type": "object"},
        });
        inject_instance_argument(&mut reader, &["alpha".into()], None);
        let values = reader["inputSchema"]["properties"]["instance"]["enum"]
            .as_array()
            .unwrap();
        assert!(values.iter().any(|value| value == "*"));

        let mut mover = json!({
            "name": "client_move",
            "annotations": {"readOnlyHint": false},
            "inputSchema": {"type": "object"},
        });
        inject_instance_argument(&mut mover, &["alpha".into()], None);
        let values = mover["inputSchema"]["properties"]["instance"]["enum"]
            .as_array()
            .unwrap();
        assert!(!values.iter().any(|value| value == "*"));
    }

    #[test]
    fn an_unannotated_tool_does_not_offer_fan_out() {
        // Class::of treats an unannotated tool as mutating, and this inherits that caution: a
        // third-party tool nobody annotated must not become fannable by omission.
        let mut tool = json!({"name": "mymod_mystery", "inputSchema": {"type": "object"}});

        inject_instance_argument(&mut tool, &["alpha".into()], None);

        let values = tool["inputSchema"]["properties"]["instance"]["enum"]
            .as_array()
            .unwrap();
        assert_eq!(values.len(), 1);
    }

    #[test]
    fn a_tool_with_no_arguments_still_gets_the_instance_property() {
        // MCMCP has several no-argument tools whose schema is bare. Injecting into a schema with no
        // `properties` at all has to create one rather than quietly doing nothing.
        let mut tool = json!({"name": "mcmcp_endpoint_info", "inputSchema": {"type": "object"}});

        inject_instance_argument(&mut tool, &["alpha".into()], None);

        assert_eq!(tool["inputSchema"]["properties"]["instance"]["type"], "string");
    }

    #[test]
    fn the_instance_description_names_the_focused_instance() {
        // A model reading the schema should not have to make a second call to learn where an
        // omitted `instance` would land.
        let mut tool = json!({"name": "t", "inputSchema": {"type": "object"}});

        inject_instance_argument(&mut tool, &["alpha".into(), "beta".into()], Some("alpha"));

        let description = tool["inputSchema"]["properties"]["instance"]["description"]
            .as_str()
            .unwrap();
        assert!(description.contains("alpha"), "got: {description}");
    }

    #[test]
    fn one_addressable_instance_leaves_nothing_to_say_about_focus() {
        // The description is stamped onto every tool, so anything true-but-useless in it is paid
        // for ~49 times. With a single instance, naming the focused one names the only one.
        let mut tool = json!({"name": "t", "inputSchema": {"type": "object"}});

        inject_instance_argument(&mut tool, &["alpha".into()], Some("alpha"));

        let description = tool["inputSchema"]["properties"]["instance"]["description"]
            .as_str()
            .unwrap();
        assert!(!description.contains("alpha"), "got: {description}");
        assert!(description.len() < 60, "got {} chars", description.len());
    }

    #[test]
    fn the_enum_covers_addressable_instances_not_just_connected_ones() {
        // Relaunching a client is constant in a mod-development loop. If the enum tracked only live
        // instances, every relaunch would rewrite all 45 schemas and churn the catalogue.
        let aggregate = aggregate(
            &[contribution("alpha", vec!["client_move"])],
            &["alpha".into(), "beta".into()],
            None,
        );

        assert_eq!(
            aggregate.tools[0]["inputSchema"]["properties"]["instance"]["enum"],
            json!(["alpha", "beta"])
        );
    }

    #[test]
    fn lists_tools_in_a_stable_order() {
        let contributions = vec![contribution("alpha", vec!["zulu_tool", "alpha_tool"])];

        let aggregate = aggregate(&contributions, &[], None);

        assert_eq!(
            names(&aggregate),
            vec!["alpha_tool".to_string(), "zulu_tool".to_string()]
        );
    }

    #[test]
    fn qualifies_a_resource_uri_with_the_instance_and_reverses_exactly() {
        let qualified = qualify_uri("alpha", "minecraft://game/mods").unwrap();

        assert_eq!(qualified, "minecraft://alpha/game/mods");
        assert_eq!(
            unqualify_uri(&qualified),
            Some(("alpha".to_string(), "minecraft://game/mods".to_string()))
        );
    }

    #[test]
    fn round_trips_a_uri_with_several_path_segments() {
        let original = "minecraft://client/player/state";
        let qualified = qualify_uri("modb-dev", original).unwrap();

        assert_eq!(qualified, "minecraft://modb-dev/client/player/state");
        assert_eq!(unqualify_uri(&qualified).unwrap().1, original);
    }

    #[test]
    fn refuses_a_uri_it_cannot_rewrite_reversibly() {
        // Nothing MCMCP ships looks like this; a third-party registration might. Dropping it with a
        // warning beats exposing a URI that cannot be routed back to an instance.
        assert_eq!(qualify_uri("alpha", "urn:isbn:0451450523"), None);
        assert_eq!(unqualify_uri("minecraft://alpha"), None);
        assert_eq!(unqualify_uri("not-a-uri"), None);
    }

    #[test]
    fn resources_are_labelled_per_instance_rather_than_collapsed() {
        // Three games each have their own player state; they are genuinely different resources.
        // Three entries all called "Loaded mods" is a list nobody can use.
        let mut catalogue = Catalogue::default();
        catalogue.resources.push(json!({
            "uri": "minecraft://game/mods", "name": "loaded-mods", "title": "Loaded mods",
        }));
        let contributions = vec![
            Contribution {
                id: "alpha".into(),
                label: "modB dev".into(),
                catalogue: Arc::new(catalogue.clone()),
            },
            Contribution {
                id: "beta".into(),
                label: "control".into(),
                catalogue: Arc::new(catalogue),
            },
        ];

        let aggregate = aggregate(&contributions, &[], None);

        assert_eq!(aggregate.resources.len(), 2);
        assert_eq!(aggregate.resources[0]["uri"], "minecraft://alpha/game/mods");
        assert_eq!(aggregate.resources[0]["title"], "modB dev: Loaded mods");
        assert_eq!(aggregate.resources[1]["title"], "control: Loaded mods");
    }

    #[test]
    fn prompts_appear_once_with_an_instance_argument() {
        let mut catalogue = Catalogue::default();
        catalogue.prompts.push(json!({
            "name": "survey_surroundings",
            "arguments": [{"name": "radius", "required": false}],
        }));
        let shared = Arc::new(catalogue);
        let contributions = vec![
            Contribution {
                id: "alpha".into(),
                label: "alpha".into(),
                catalogue: Arc::clone(&shared),
            },
            Contribution {
                id: "beta".into(),
                label: "beta".into(),
                catalogue: shared,
            },
        ];

        let aggregate = aggregate(&contributions, &["alpha".into(), "beta".into()], None);

        assert_eq!(aggregate.prompts.len(), 1);
        let arguments = aggregate.prompts[0]["arguments"].as_array().unwrap();
        assert_eq!(arguments.len(), 2);
        assert!(arguments.iter().any(|a| a["name"] == "instance"));
    }

    #[test]
    fn an_empty_aggregate_is_valid_rather_than_an_error() {
        // Nothing connected is a normal state, not a failure. The router serves a cached catalogue
        // in that case, and this is what it falls back to when there is no cache either.
        let aggregate = aggregate(&[], &[], None);

        assert!(aggregate.tools.is_empty());
        assert!(aggregate.resources.is_empty());
    }
}
