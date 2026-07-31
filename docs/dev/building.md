# Building

MCMCP uses the [GregTechCEu Buildscripts](https://github.com/GregTechCEu/Buildscripts) wrapper around
[RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle). `build.gradle` is that
buildscript verbatim — **do not edit it**. Project-specific configuration lives in
`buildscript.properties`, `gradle.properties`, `dependencies.gradle`, `repositories.gradle` and
`addon.gradle`.

## JDK requirements

Set `JAVA_HOME` before each `./gradlew` invocation. Nothing is on `PATH` by design; see
[`jdk-gradle-setup`](https://github.com/Mica-Technologies/dev-configurations/blob/main/jdk-gradle-setup/README.md)
for how JDKs are managed across machines.

**Java 21 is the target.** RFG requires the Gradle process to run on 21+ (older is deprecated and
slated for removal), the pinned Gradle 8.9 officially supports running on ≤ 22, and CI uses 21.

Java 17 works today and only produces the RFG deprecation notice. Newer JDKs (23–26) compile the mod
correctly via Jabel but run Gradle past its supported ceiling.

Either way, **the compiler and mod code target Java 8**. Only the JVM running Gradle changes.

```bash
# Windows
export JAVA_HOME="C:/Users/<user>/.jdks/azul-21.0.x"

# macOS
export JAVA_HOME="/Users/<user>/Library/Java/JavaVirtualMachines/azul-21.0.x/Contents/Home"
```

## Commands

```bash
# First time, or after clean
./gradlew setupDecompWorkspace

# Build and test
./gradlew build

# Tests only
./gradlew test

# Single test class
./gradlew test --tests '*McpSessionTest'

# Dev client — MCP endpoints on 25585 (client) and 25586 (integrated server)
./gradlew runClient

# Dev server — MCP endpoints on 25587 and 25588
./gradlew runServer

# Apple Silicon: arm64-native via lwjgl3ify (window currently broken on macOS)
./gradlew runClient17

# Apple Silicon: working client, LWJGL2 under Rosetta 2
./gradlew runClient -Prosetta

./gradlew clean
```

Decompilation needs headroom; `gradle.properties` sets `-Xmx3G`.

## Java 8 with modern syntax

`enableModernJavaSyntax = true` runs [Jabel](https://github.com/bsideup/jabel), which allows Java 9–17
*syntax* while emitting Java 8 bytecode.

The distinction is syntax, not APIs. `var` and switch expressions desugar and are fine; records,
sealed types, `List.of`, `Optional.orElseThrow()` and anything else needing a runtime class or method
that Java 8 lacks are not.

In practice MCMCP's source stays close to plain Java 8 to match the sibling mods.

## Dev launch ports

`addon.gradle` starts both endpoints in dev launches:

| Task | Client endpoint | Server endpoint |
| --- | --- | --- |
| `runClient` | 25585 | 25586 |
| `runServer` | 25587 | 25588 |

`-Dmcmcp.dev.port` is a **base**: the client endpoint takes it and the server endpoint takes base+1.
A `runClient` that opens a singleplayer world runs both endpoints in one JVM, so a single shared
default would collide.

```bash
./gradlew runClient -PmcpBasePort=26000
```

Both properties are read as *defaults only*. A value in the generated `config/mcmcp.cfg` always wins,
so editing the config is never a fight with the build script.

`separateRunDirectories = true`, so `runClient` uses `run/client/` and `runServer` uses `run/server/`.
Without it the two launches would fight over config, screenshots and logs — which matters more here
than in most mods, because running both at once is the normal development case.

## Versioning

`modVersion` is empty in `buildscript.properties`, so the version comes from the latest git tag via
`com.palantir.git-version`. Release tags are `YYYY.MM.DD`, with `+N` build metadata appended for a
second release on the same day.

A checkout with no tags builds as `NO-GIT-TAG-SET`, which is expected locally and never ships — CI
creates the tag before it builds.

## Why there are no dependencies

`dependencies.gradle` declares nothing, and `usesShadowedDependencies = false`. The two things an MCP
server would normally need a library for are already available:

- **JSON.** Minecraft 1.12.2 bundles `com.google.gson:gson:2.8.0`. Shading Jackson or a newer Gson
  risks a split-package clash with the copy Forge already loaded.
- **HTTP.** `com.sun.net.httpserver` is in Java 8's `rt.jar` and in the `jdk.httpserver` module
  (exported by default) on 9+, so it works under both the vanilla Java 8 launch and the lwjgl3ify
  17/21 launches.

The 2.8.0 pin has real consequences worth knowing before reaching for a Gson API:
`JsonParser.parseString` (2.8.6+), `JsonObject.keySet` (2.8.1+) and `JsonObject.deepCopy` (2.8.2+) do
not exist. `Json` and `JsonSchema` wrap around those gaps.

Keep it dependency-free unless there is a concrete reason not to. Every shaded byte ends up in a jar
people drop into modpacks alongside a few hundred others.

## Buildscript update check

`gradle.properties` sets `systemProp.DISABLE_BUILDSCRIPT_UPDATE_CHECK = true`.

The check fetches `raw.githubusercontent.com` during project evaluation, which has timed out and
hard-failed CI in sibling repos. The auto-update is also unwanted: it overwrites `build.gradle` and
would wipe the `addon.gradle` customisations. Run `./gradlew updateBuildScript` deliberately if a
refresh is ever wanted.

## Continuous integration

| Workflow | Trigger | Does |
| --- | --- | --- |
| `test-mod-build-pr.yml` | Pull request | `./gradlew build`, then boots a dedicated server and drives a real MCP handshake |
| `build-mod-release-pre-release-main.yml` | Push to `main` | Tags, builds, publishes a GitHub pre-release (or a release via `workflow_dispatch`) |
| `cleanup-mod-pre-releases.yml` | After a release build | Prunes old pre-releases |
| `deploy-wiki-pages-main.yml` | `docs/**` or `mkdocs.yml` on `main` | Builds this site with `mkdocs build --strict` and deploys to Pages |

See [Testing](testing.md) for what the smoke test asserts and why.

## Documentation site

```bash
python -m pip install -r docs/requirements.txt
mkdocs serve
```

CI builds with `--strict`, so a broken internal link or a nav entry pointing at a missing file fails
the build rather than shipping a 404.
