#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM_SHA="0a3d0c68a3a083b58bff3bdb7fc86e8e604308a8"
SOURCE="$ROOT/worktree/PixelBoard"
mkdir -p "$ROOT/worktree"
if [[ ! -d "$SOURCE/.git" ]]; then
  git clone https://github.com/Akshayykadam/PixelBoard.git "$SOURCE"
fi
git -C "$SOURCE" fetch --depth 1 origin "$UPSTREAM_SHA"
git -C "$SOURCE" checkout --detach "$UPSTREAM_SHA"

# Isolate our installation package. This is source-level patching only;
# the final package, permissions, authorities and branded assets require auditing.
CONSTANTS="$SOURCE/pixelboard-patches/patches/src/main/kotlin/com/akshaykadam/pixelboard/patches/shared/Constants.kt"
python3 - "$CONSTANTS" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = 'const val GBOARD_PATCHED_PACKAGE_NAME = "com.akshaykadam.pixelboard"'
new = 'const val GBOARD_PATCHED_PACKAGE_NAME = "dev.blake.aetherg"'
assert old in s or new in s, 'Package rename constant not found'
s = s.replace(old, new)
p.write_text(s)
PY

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

cd "$SOURCE/pixelboard-patches"
chmod +x gradlew
./gradlew test :patches:buildAndroid --no-daemon

printf 'Compiled patch bundles:\n'
find patches/build/libs -maxdepth 1 -name '*.mpp' -print
printf 'IMPORTANT: Patch source still includes PixelBoard-specific branding and resources. Do not release without full identity and behavioral audit.\n'
