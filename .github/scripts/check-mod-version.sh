#!/usr/bin/env bash
#
# Fails if the version Gradle resolved does not look like a MCMCP mod version.
#
# `build.gradle:1555` derives the mod version with `git describe --abbrev=0 --tags` and **no
# `--match` filter**, so it takes the most recent tag of any name in this repository. That is fine
# while the only tags are the mod's own — and this repository also contains the orchestrator, whose
# releases would very reasonably be tagged `app-0.1.0` by anybody who had not read the comment
# saying not to.
#
# The failure that would cause is silent. The build succeeds; the jar ships; its manifest,
# `mcmod.info` and `McmcpConstants.MOD_VERSION` all read `app-0.1.0`, and the first sign of trouble
# is a player reporting a version that does not exist. `build.gradle` is the GregTechCEu buildscript
# verbatim and must not be edited (see CLAUDE.md), so the convention carries the constraint — and
# this is what checks the convention held.
#
# Accepted:
#   2026.08.24                                          a release
#   2026.08.24+1                                        a second release the same day
#   2026.08.13-pre.1925.UTC+0000+716318a-2-gb5d53ae     a pre-release
#
# Rejected, loudly:
#   app-0.1.0            somebody tagged the orchestrator
#   NO-GIT-TAG-SET       no tags are reachable, so nothing identifies this build

set -uo pipefail

VERSION="${1:-}"

if [ -z "$VERSION" ]; then
  echo "==> Resolving the mod version with Gradle"
  # `| tail -n 1`: Gradle prints banners during project evaluation even under -q, and a stray line
  # here would be compared against the pattern instead of the version. The release workflow learned
  # this the hard way in a sibling repo.
  VERSION="$(./gradlew -q printModVersion 2>/dev/null | tail -n 1 | xargs)"
fi

echo "    resolved version: ${VERSION}"

if [ "$VERSION" = "NO-GIT-TAG-SET" ]; then
  echo "==> FAIL: no git tag is reachable from HEAD, so this build has no identity."
  echo "    Expected locally on a fresh checkout with no tags; never in CI, which fetches them."
  echo "    Check that the checkout used fetch-depth: 0."
  exit 1
fi

# YYYY.MM.DD, optionally followed by +N or the -pre. form.
if ! printf '%s' "$VERSION" | grep -qE '^[0-9]{4}\.[0-9]{2}\.[0-9]{2}([-+].*)?$'; then
  echo "==> FAIL: '${VERSION}' is not a MCMCP mod version."
  echo
  echo "    build.gradle resolves the mod version from the most recent git tag of ANY name, so a"
  echo "    tag that was not meant for the mod becomes the mod's version — silently, in the jar"
  echo "    manifest, mcmod.info and McmcpConstants.MOD_VERSION."
  echo
  echo "    The orchestrator's version lives in orchestrator/Cargo.toml and must never be a tag."
  echo "    Its binaries and installers attach to the mod's release instead."
  echo
  echo "    Tags currently reachable from HEAD, newest first:"
  git tag --sort=-creatordate --merged HEAD 2>/dev/null | head -5 | sed 's/^/      /'
  exit 1
fi

echo "==> OK: the mod version looks like a mod version"
exit 0
