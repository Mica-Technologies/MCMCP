#!/usr/bin/env bash
#
# Turns the mod's version into the orchestrator app's.
#
# The two ship together in one release, so they should not carry unrelated version numbers — but the
# app is an installer, and Windows constrains what an installer version may be:
#
#   * MSI ProductVersion is major.minor.build, with major and minor capped at 255 and build at 65535.
#     A literal 2026 in the major is refused outright: "app version major number cannot be greater
#     than 255".
#   * Cargo wants semver, which forbids leading zeros in a numeric identifier. `2026.08.26` is not a
#     version at all as far as Cargo is concerned — `08` sinks it.
#   * An MSI pre-release identifier is allowed, but must be numeric-only and no greater than 65535.
#
# So the date is carried in the shape those rules leave open:
#
#   2026.08.26                        -> 26.8.26
#   2026.08.26-pre.0139.UTC+0000+abc  -> 26.8.26-139
#
# Two-digit year in the major, which is good until 2255. The pre-release build's hhmm becomes the
# numeric pre-release identifier, which also gets the ordering right for free: semver ranks a
# pre-release below the release it precedes, so 26.8.26-139 < 26.8.26 < 26.8.27, and an installer
# upgrading between them does the right thing.
#
# Usage:
#   orchestrator-version.sh <mod-version>            print the app version
#   orchestrator-version.sh <mod-version> --write    also write it into Cargo.toml and tauri.conf.json
#   orchestrator-version.sh --self-test              check the mapping against known inputs

set -euo pipefail

CARGO_TOML="orchestrator/Cargo.toml"
TAURI_CONF="orchestrator/app/tauri.conf.json"

# Strips leading zeros without turning "00" into the empty string, and without bash reading a
# zero-padded number as octal — which is how "08" becomes an error rather than an eight.
strip_zeros() {
  local value="${1#"${1%%[!0]*}"}"
  printf '%s' "${value:-0}"
}

derive() {
  local mod_version="$1"

  local date_part="${mod_version%%-*}"
  if [[ ! "$date_part" =~ ^([0-9]{4})\.([0-9]{2})\.([0-9]{2}) ]]; then
    echo "orchestrator-version: '$mod_version' does not start with YYYY.MM.DD" >&2
    return 1
  fi
  local year="${BASH_REMATCH[1]}" month="${BASH_REMATCH[2]}" day="${BASH_REMATCH[3]}"

  # A second release on one day is `YYYY.MM.DD+N` in the mod's scheme, and YY.M.D has nowhere to put
  # the N. Emitting the same version twice would leave the installer unable to tell the builds apart
  # and quietly refusing to upgrade — which is the exact failure this whole change exists to fix, so
  # it gets said out loud rather than discovered later.
  if [[ "$date_part" == *"+"* ]]; then
    echo "orchestrator-version: WARNING: '$mod_version' is a same-day re-release; the app version" >&2
    echo "  collides with the earlier build of the same day and installers will not treat it as an" >&2
    echo "  upgrade. Ship it as a pre-release, or move the scheme to a packed build field." >&2
  fi

  local app_version
  app_version="$(strip_zeros "${year:2:2}").$(strip_zeros "$month").$(strip_zeros "$day")"

  # Pre-release builds are `-pre.hhmm.<tz>+<sha>`. Only the hhmm survives, because it is the only
  # part that is numeric, ordered, and inside the 65535 an MSI will accept.
  if [[ "$mod_version" =~ -pre\.([0-9]{1,4}) ]]; then
    app_version="${app_version}-$(strip_zeros "${BASH_REMATCH[1]}")"
  fi

  printf '%s' "$app_version"
}

write_version() {
  local app_version="$1"

  if [ ! -f "$CARGO_TOML" ] || [ ! -f "$TAURI_CONF" ]; then
    echo "orchestrator-version: run this from the repository root" >&2
    return 1
  fi

  # The workspace version line only. `version = ` also appears on every dependency below it, but
  # those are indented inside a table, so anchoring to the start of the line picks out the one.
  #
  # No `$` anchor: these files are checked out with CRLF on Windows, and `"..."$` will not match a
  # line that ends `"`. That failed silently in exactly the direction that matters — tauri.conf.json
  # updated, Cargo.toml did not, so the installer carried the right version while the app still
  # reported the placeholder from CARGO_PKG_VERSION.
  perl -0pi -e 's/^version = "[^"]*"/version = "'"$app_version"'"/m' "$CARGO_TOML"
  perl -0pi -e 's/"version": "[^"]*"/"version": "'"$app_version"'"/' "$TAURI_CONF"

  local in_cargo in_tauri
  in_cargo="$(grep -cE "^version = \"${app_version}\"" "$CARGO_TOML" || true)"
  in_tauri="$(grep -cE "\"version\": \"${app_version}\"" "$TAURI_CONF" || true)"
  if [ "$in_cargo" != "1" ] || [ "$in_tauri" != "1" ]; then
    echo "orchestrator-version: failed to write $app_version (cargo=$in_cargo tauri=$in_tauri)" >&2
    return 1
  fi
  echo "==> orchestrator app version set to $app_version"
}

self_test() {
  local failures=0
  check() {
    local input="$1" expected="$2" actual
    actual="$(derive "$input" 2>/dev/null)"
    if [ "$actual" = "$expected" ]; then
      echo "    ok   $input -> $actual"
    else
      echo "    FAIL $input -> $actual (expected $expected)"
      failures=$((failures + 1))
    fi
  }

  check "2026.08.26"                              "26.8.26"
  check "2026.08.26-pre.0139.UTC+0000+3cfb1f3"    "26.8.26-139"
  check "2026.08.26-pre.1925.UTC+0000+716318a"    "26.8.26-1925"
  check "2026.12.31"                              "26.12.31"
  check "2026.01.01"                              "26.1.1"
  check "2026.10.09-pre.0005.UTC+0000+abc1234"    "26.10.9-5"
  check "2100.01.01"                              "0.1.1"

  if ! derive "NO-GIT-TAG-SET" >/dev/null 2>&1; then
    echo "    ok   NO-GIT-TAG-SET is refused rather than guessed at"
  else
    echo "    FAIL NO-GIT-TAG-SET should be refused"
    failures=$((failures + 1))
  fi

  if [ "$failures" -ne 0 ]; then
    echo "==> FAIL: $failures case(s) wrong"
    return 1
  fi
  echo "==> PASS: the version mapping holds"
}

main() {
  if [ "${1:-}" = "--self-test" ]; then
    self_test
    return
  fi
  if [ -z "${1:-}" ]; then
    echo "usage: orchestrator-version.sh <mod-version> [--write] | --self-test" >&2
    return 1
  fi

  local app_version
  app_version="$(derive "$1")"

  if [ "${2:-}" = "--write" ]; then
    write_version "$app_version"
  else
    printf '%s\n' "$app_version"
  fi
}

main "$@"
