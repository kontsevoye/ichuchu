#!/usr/bin/env bash
# Load a retained Nix development profile without nix develop's registry Bash lookup.
set -euo pipefail
profile_name="${1:-}"
[[ "$profile_name" == ci || "$profile_name" == signing ]] || exit 2
shift
(($# > 0)) || exit 2
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
profile="$repo_root/.cache/nix-$profile_name"
[[ -L "$profile" ]] || { echo 'Pinned development profile is unavailable' >&2; exit 1; }
# This official command reads the recorded environment directly, without a flake
# evaluation or a separate global nixpkgs#bashInteractive dependency.
environment="$(nix print-dev-env "$profile")"
eval "$environment"
exec "$@"
