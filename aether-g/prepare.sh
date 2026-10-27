#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM="$ROOT/worktree/PixelBoard"
if [ ! -d "$UPSTREAM/.git" ]; then
  mkdir -p "$ROOT/worktree"
  git clone --depth 1 https://github.com/Akshayykadam/PixelBoard.git "$UPSTREAM"
fi

mkdir -p "$ROOT/worktree"
cat > "$ROOT/worktree/aether-g-options.json" <<'JSON'
[
  {
    "patches": {
      "Advanced Voice Typing": {"enabled": true},
      "AI Writing Tools": {"enabled": true},
      "Add Gboard Signature Bypass": {"enabled": true},
      "Package Rename": {
        "enabled": true,
        "options": {"app_display_name": "Aether G"}
      }
    }
  }
]
JSON

printf '%s\n' "PixelBoard patch sources: $UPSTREAM"
printf '%s\n' "Aether G prototype options: $ROOT/worktree/aether-g-options.json"
printf '%s\n' "NOTE: Display label is only a prototype. PixelBoard package identifiers are still baked into the upstream patches."
printf '%s\n' "No proprietary APK is downloaded, patched or published by this script."
