#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <standard|ai>" >&2
  exit 64
}

profile="${1:-}"
case "$profile" in
  standard|ai) ;;
  *) usage ;;
esac

cli_user="vscode"
cli_group="$(id -gn "$cli_user" 2>/dev/null || printf '%s' "$cli_user")"
home_dir="/home/vscode"
state_dir="$home_dir/.local/state"

run_as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  else
    sudo "$@"
  fi
}

# Docker creates named-volume mount points as root. Create and reclaim only
# this profile's directories; host data is never bind-mounted any more.
state_dirs=(
  "$home_dir/.local"
  "$home_dir/.local/share"
  "$state_dir"
  "$state_dir/gh"
)
volume_dirs=()

if [[ "$profile" == "ai" ]]; then
  state_dirs+=(
    "$state_dir/codex-home"
    "$state_dir/codex"
    "$state_dir/opencode"
  )
  volume_dirs=(
    "$home_dir/.config"
    "$home_dir/.config/opencode"
    "$home_dir/.local/share/opencode"
  )
fi

for dir in "${state_dirs[@]}" "${volume_dirs[@]}"; do
  run_as_root install -d -m 0755 -o "$cli_user" -g "$cli_group" "$dir"
done

# Existing volume contents may come from containers created by another UID.
run_as_root chown -R "$cli_user:$cli_group" "$state_dir"
for dir in "${volume_dirs[@]}"; do
  run_as_root chown -R "$cli_user:$cli_group" "$dir"
done

# GitHub CLI stores credentials in this directory.
chmod 700 "$state_dir/gh"

# Fail early if a profile cannot write to its persistent state.
test -w "$state_dir/gh"
if [[ "$profile" == "ai" ]]; then
  test -w "$state_dir/codex-home"
  test -w "$state_dir/codex"
  test -w "$state_dir/opencode"
  test -w "$home_dir/.config/opencode"
  test -w "$home_dir/.local/share/opencode"
fi

# Version output stays profile-specific so the standard image never needs the
# AI CLIs and the AI image verifies its extra tools.
tools=(node npm java mvn gradle gh)
if [[ "$profile" == "ai" ]]; then
  tools+=(codex opencode)
fi

echo "FinResolve dev container profile: $profile"
for tool in "${tools[@]}"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required tool for profile '$profile': $tool" >&2
    exit 1
  fi
  "$tool" --version
done
