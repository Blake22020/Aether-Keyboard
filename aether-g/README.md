# Aether G — experimental Gboard patch project

Aether G is a **separate experiment** from Aether Keyboard, designed around a user-supplied, legitimately obtained Gboard APK.

## Status

Scaffold only — no Aether G APK has been built or verified yet. The original Aether Keyboard and its `main` branch are unchanged.

## Upstream

- [PixelBoard](https://github.com/Akshayykadam/PixelBoard), GPL-3.0, maintained by Akshay Kadam.
- [Morphe](https://github.com/Akshayykadam/PixelBoard) patch-bundle toolchain is used upstream.
- Gboard is proprietary software owned by Google. Aether G does **not** contain or redistribute Gboard APK files.

## Goals

1. Maintain the full Gboard typing engine by applying independently maintained patches to an APK supplied locally.
2. Explore PixelBoard-supported capabilities such as natural voice typing, writing tools, OCR, screenshot suggestions and Material 3 rounded keys. Actual availability depends on device, Google services and selected APK version.
3. Separate name and install identity from PixelBoard and normal Aether (requires further modification to upstream patch source; changing the display label alone is insufficient).
4. Preserve attribution and comply with GPL-3.0 for any copied or modified PixelBoard code.
5. Private device testing first; no automated publication of a modified proprietary APK.

## Current prototype

The script `prepare.sh` clones the PixelBoard sources to a local ignored worktree, and creates an Aether G patch-options file. It does not automatically publish a Gboard-derived binary or assert that the package identity has been changed.

```bash
cd aether-g
bash prepare.sh
```

Build dependencies and patching instructions are in upstream [PixelBoard](https://github.com/Akshayykadam/PixelBoard) documentation. **Do not use the unmodified PixelBoard package rename patch for Aether G branding**: it still has PixelBoard-specific IDs, icons, provider authorities, and resources. These need a full code audit and refactor before building Aether G.

## Credit

Thank you to Akshay Kadam and PixelBoard contributors for the GPL-3.0 patch framework. This repository's standalone Aether Keyboard remains separately MIT-licensed. Gboard remains Google's proprietary software.
