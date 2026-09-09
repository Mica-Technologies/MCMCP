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

  # A second release on one day is `YYYY.MM.DD+N` in the mod's scheme. SemVer build metadata is
  # ignored in precedence comparisons and a pre-release identifier ranks *below* the release, so
  # neither can carry the N: the only place it can go and still compare greater is the numeric
  # patch field, packed with the day.
  local rerelease=0
  if [[ "$date_part" =~ \+([0-9]+)$ ]]; then
    rerelease="${BASH_REMATCH[1]}"
  fi

  # Ten a day is already far past anything sane, and the cap is what keeps the packing reversible:
  # at N=10 the day would carry into the next day's slot and a re-release would outrank tomorrow.
  # Fail rather than emit a version that silently breaks ordering.
  if [ "$rerelease" -gt 9 ]; then
    echo "orchestrator-version: '$mod_version' is same-day re-release $rerelease, and only 0-9 fit" >&2
    echo "  in the packed patch field. Delete today's release and its tag and cut a clean one." >&2
    return 1
  fi

  # patch = day * 10 + N. The multiplier is what makes it order correctly in every direction:
  #
  #   2026.09.09    -> 26.9.90     the 9th
  #   2026.09.09+1  -> 26.9.91     re-cut the same day, ranks above it
  #   2026.09.10    -> 26.9.100    the next day, ranks above both
  #
  # Putting N anywhere more significant breaks that second comparison — with N in the minor field a
  # re-release of the 9th would outrank the 10th. Ceiling is 31*10+9 = 319, comfortably inside the
  # 65535 an MSI build field accepts, and it never has a leading zero for Cargo to reject.
  local packed=$(( $(strip_zeros "$day") * 10 + rerelease ))

  local app_version
  app_version="$(strip_zeros "${year:2:2}").$(strip_zeros "$month").${packed}"

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

  check "2026.08.26"                              "26.8.260"
  check "2026.08.26-pre.0139.UTC+0000+3cfb1f3"    "26.8.260-139"
  check "2026.08.26-pre.1925.UTC+0000+716318a"    "26.8.260-1925"
  check "2026.12.31"                              "26.12.310"
  check "2026.01.01"                              "26.1.10"
  check "2026.10.09-pre.0005.UTC+0000+abc1234"    "26.10.90-5"
  check "2100.01.01"                              "0.1.10"

  # Same-day re-releases: the case the packing exists for.
  check "2026.09.09"                              "26.9.90"
  check "2026.09.09+1"                            "26.9.91"
  check "2026.09.09+9"                            "26.9.99"

  if ! derive "NO-GIT-TAG-SET" >/dev/null 2>&1; then
    echo "    ok   NO-GIT-TAG-SET is refused rather than guessed at"
  else
    echo "    FAIL NO-GIT-TAG-SET should be refused"
    failures=$((failures + 1))
  fi

  if ! derive "2026.09.09+10" >/dev/null 2>&1; then
    echo "    ok   an 11th same-day release is refused rather than packed into tomorrow"
  else
    echo "    FAIL 2026.09.09+10 should be refused"
    failures=$((failures + 1))
  fi

  # The properties that make the packing worth having, asserted rather than assumed. Compared the
  # way a package manager compares them: numerically, field by field.
  order() {
    local label="$1" lower="$2" higher="$3" a b
    a="$(derive "$lower")"; b="$(derive "$higher")"
    if [ "$(printf '%s\n%s\n' "$a" "$b" | sort -V | head -1)" = "$a" ] && [ "$a" != "$b" ]; then
      echo "    ok   $label ($a < $b)"
    else
      echo "    FAIL $label: expected $a < $b"
      failures=$((failures + 1))
    fi
  }
  order "a re-release outranks the release it replaces" "2026.09.09"   "2026.09.09+1"
  order "the next day outranks a re-release"            "2026.09.09+1" "2026.09.10"
  order "the next day outranks the 9th re-release"      "2026.09.09+9" "2026.09.10"
  order "the next month outranks month end"             "2026.09.30"   "2026.10.01"

  # A pre-release must derive to its own release plus a `-N` identifier, which is what makes SemVer
  # rank it below that release. Asserted structurally rather than through `sort -V`: version sort
  # does not implement SemVer pre-release precedence — it puts `1.0.0` *before* `1.0.0-alpha`, the
  # opposite of the rule installers actually apply — so using it here would test the comparator
  # rather than this script.
  local release pre
  release="$(derive "2026.09.09")"
  pre="$(derive "2026.09.09-pre.0255.UTC+0000+abc")"
  if [ "$pre" = "${release}-255" ]; then
    echo "    ok   a pre-release is its release plus an identifier ($pre ranks below $release)"
  else
    echo "    FAIL pre-release shape: expected ${release}-255, got $pre"
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
