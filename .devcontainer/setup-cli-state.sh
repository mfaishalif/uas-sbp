#!/usr/bin/env bash
set -euo pipefail

# Docker may create mount parents and new volumes as root. Only change Linux
# directories here; the Windows bind mounts contain shared host data.
cli_user="$(id -un)"
cli_group="$(id -gn)"
sudo install -d -o "$cli_user" -g "$cli_group" \
  /home/vscode/.local \
  /home/vscode/.local/share \
  /home/vscode/.local/state \
  /home/vscode/.local/state/gh \
  /home/vscode/.local/state/codex-home \
  /home/vscode/.local/state/codex \
  /home/vscode/.local/state/opencode
sudo chown -R "$cli_user:$cli_group" /home/vscode/.local/state
chmod 700 /home/vscode/.local/state/gh

# Keep Linux sockets, locks, temporary files and runtime metadata off the
# Windows mount. Share only portable settings and seed credentials once.
codex_home=/home/vscode/.local/state/codex-home
host_codex=/home/vscode/.codex
for entry in config.toml AGENTS.md AGENTS.override.md skills; do
  if [[ -e "$host_codex/$entry" && ! -e "$codex_home/$entry" && ! -L "$codex_home/$entry" ]]; then
    ln -s "$host_codex/$entry" "$codex_home/$entry"
  fi
done
if [[ -f "$host_codex/auth.json" && ! -e "$codex_home/auth.json" ]]; then
  install -m 600 "$host_codex/auth.json" "$codex_home/auth.json"
fi

test -w /home/vscode/.local/state/codex
test -w /home/vscode/.local/state/opencode
