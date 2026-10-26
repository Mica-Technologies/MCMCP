#!/usr/bin/env bash
# Points a GitHub-hosted Ubuntu runner's apt at a list of mirrors instead of azure.archive.ubuntu.com
# alone (#145). That mirror has stalled for minutes on end (widely reported in October 2026), which
# timed out the orchestrator's Linux webview install and left a release without its Linux assets.
#
# apt's mirror+file: method tries the mirrors in order and moves to the next when one fails, and with
# the Acquire timeouts the apt steps pass, a stalled mirror counts as failing. Azure stays last, as a
# fallback. security.ubuntu.com entries are left alone. Run before the first apt-get update.
set -euo pipefail

list=/etc/apt/mirrors.txt
printf '%s\n' \
  http://archive.ubuntu.com/ubuntu \
  http://mirrors.edge.kernel.org/ubuntu \
  http://azure.archive.ubuntu.com/ubuntu \
  | sudo tee "$list" > /dev/null

changed=0
# Ubuntu 24.04 and later: deb822 sources.
if [ -f /etc/apt/sources.list.d/ubuntu.sources ] \
  && grep -qE '^URIs: http://azure\.archive\.ubuntu\.com/ubuntu/?$' /etc/apt/sources.list.d/ubuntu.sources; then
  sudo sed -i -E "s#^URIs: http://azure\.archive\.ubuntu\.com/ubuntu/?\$#URIs: mirror+file:${list}#" \
    /etc/apt/sources.list.d/ubuntu.sources
  changed=1
fi
# Ubuntu 22.04: one-line sources.
if [ -f /etc/apt/sources.list ] && grep -qE '^deb(-src)? http://azure\.archive\.ubuntu\.com/ubuntu/? ' /etc/apt/sources.list; then
  sudo sed -i -E "s#^(deb(-src)?) http://azure\.archive\.ubuntu\.com/ubuntu/? #\1 mirror+file:${list} #" /etc/apt/sources.list
  changed=1
fi

if [ "$changed" = 1 ]; then
  echo "apt now uses the mirror list: $(tr '\n' ' ' < "$list")"
else
  # The runner image changed how it names its mirror. Not fatal: apt still works, only slower.
  echo "::warning::No azure.archive.ubuntu.com source found to replace; apt left as the image set it."
fi
