#!/usr/bin/env bash
# Moves a GitHub-hosted Ubuntu runner's apt off azure.archive.ubuntu.com as its first choice (#145).
# That mirror has crawled for minutes on end (widely reported in October 2026): it timed out the
# orchestrator's Linux webview install and left a release without its Linux assets.
#
# The runner image already reads a mirror list (sources say mirror+file:/etc/apt/apt-mirrors.txt),
# with Azure first. apt only moves down that list when a mirror fails, and a slow one does not fail,
# so the order is what matters: Canonical's archive and kernel.org first, Azure last. An image that
# names Azure directly instead gets the same list put in its place. Run before the first apt-get update.
set -euo pipefail

mirrors=(
  http://archive.ubuntu.com/ubuntu/
  http://mirrors.edge.kernel.org/ubuntu/
  http://azure.archive.ubuntu.com/ubuntu/
)

sources=()
for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list; do
  [ -f "$file" ] && sources+=("$file")
done

# The mirror lists the sources already read: reorder those.
lists=$(grep -ohE 'mirror\+file:[^ ]+' "${sources[@]}" 2>/dev/null | sed 's#^mirror+file:##' | sort -u || true)
changed=0
for list in $lists; do
  echo "Replacing $list, which was: $(tr '\n\t' '  ' < "$list" 2>/dev/null || echo missing)"
  printf '%s\n' "${mirrors[@]}" | sudo tee "$list" > /dev/null
  changed=1
done

# Sources that name Azure directly: point them at a list of our own.
own=/etc/apt/mcmcp-mirrors.txt
for file in "${sources[@]}"; do
  if grep -qE 'http://azure\.archive\.ubuntu\.com/ubuntu/?( |$)' "$file"; then
    printf '%s\n' "${mirrors[@]}" | sudo tee "$own" > /dev/null
    sudo sed -i -E "s#http://azure\.archive\.ubuntu\.com/ubuntu/?( |\$)#mirror+file:${own}\1#" "$file"
    changed=1
  fi
done

if [ "$changed" = 1 ]; then
  echo "apt now tries, in order: ${mirrors[*]}"
else
  # The runner image changed how it names its mirror. Not fatal: apt still works, only slower.
  echo "::warning::Found no apt mirror list or Azure source to change; apt left as the image set it."
fi
