# Aether Keyboard

Aether Keyboard is an independent fork of [Expressive Keyboard](https://github.com/kotlalipopus/Expressive-Keyboard), an Android keyboard in the Material 3 Expressive style, built with Kotlin and Jetpack Compose.
Highly customizable and extensible with plugins and JavaScript mods.

<!-- Add 2–3 screenshots here, for example:
<p>
  <img src="screenshots/keyboard.png" width="250">
  <img src="screenshots/settings.png" width="250">
</p>
-->

## Download

Get the latest APK from [Releases](https://github.com/Blake22020/Aether-Keyboard/releases/latest).

Requires Android 8.0 or newer.

## Features

- **Typing:** word suggestions, autocorrect, glide (swipe) typing
- **Languages:** 90+ layouts; Chinese (Pinyin, simplified and traditional) and Japanese (Romaji → Kana) input; Indic scripts (InScript)
- **Emoji:** all Unicode 16 emoji, search in English and Russian, skin tones, built-in styles (Twemoji, Noto, Blob, OpenMoji)
- **Designs:** Material You Expressive, Material 3 (Google guidelines), Liquid Glass, Classic, Neon, Neumorphism — one tap
- **App language:** Russian or English
- **Panels:** emoji, GIFs, clipboard, translator, voice input
- **Look:** Material You colors, themes and skins, custom fonts (.ttf/.otf), a photo from your gallery behind the keys
- **Layout:** key height and corner radius, spacing, keyboard width, one-handed mode
- **Feedback:** adjustable vibration and sound
- **Privacy:** incognito mode; suggestions and dictionary learning are turned off in password fields
- **Plugins and mods:** custom buttons, shortcuts, layouts, themes and JavaScript scripts that can draw and animate over the keyboard, press keys and change layouts on the fly

## Installation

1. Download and install a verified Aether APK from [Aether Releases](https://github.com/Blake22020/Aether-Keyboard/releases) or the successful [Build APK workflow](https://github.com/Blake22020/Aether-Keyboard/actions/workflows/build.yml) artifacts. Upstream Expressive Keyboard APKs have a different application ID.
2. Open the app and enable the keyboard in system settings.
3. Select Aether Keyboard as your input method.

## Permissions

| Permission | Why |
|---|---|
| Internet | GIFs (Giphy), translator, downloading suggestion dictionaries, mods with the `network` permission |
| Microphone | Voice input |
| Vibration | Haptic feedback on key press |

Your typed text is not sent anywhere unless you use the translator yourself or install a mod
that has both network and text-reading permissions.

## Plugins and mods

Plugins are imported in **Settings → Plugins and mods**. The format and JavaScript API are described
in [PLUGINS.md](PLUGINS.md) (in Russian); examples live in [`app/src/main/assets/plugins`](app/src/main/assets/plugins).

## Building from source

You need Android Studio (or JDK 17 and Android SDK 35).

```bash
git clone https://github.com/Blake22020/Aether-Keyboard.git
cd Aether-Keyboard
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`.

To enable GIF search, put your own Giphy key into `GIPHY_API_KEY` in
`app/src/main/java/com/expressive/keyboard/Common.kt`. Without a key, a built-in GIF set is used.

## Aether changes

- Key height slider now goes down to 24 dp instead of 44 dp.
- Suggestion text shrinks to fit rather than immediately truncating.
- Separate application ID allows installation alongside the upstream release.
- Existing upstream plugin and JavaScript mod formats remain unchanged.

**Screenshot clipboard:** enable Settings → Clipboard → Automatically add screenshots, then grant photo access. Aether observes new screenshots visible to the app while its IME service is active, saves them to local clipboard history and offers them in the suggestion bar; manual Share → Aether Keyboard is an alternative. Android limits background media access. This is not screen recording.

**Typing:** context-ranked word completions, compact keys (down to 24dp), conservatively biased next-key touch regions, and long suggestions that shrink to fit.

**Upstream v11.1.2 integration (Oct 25, 2026):** merges new source files for AI text improvements (provider availability varies by device/API setup), inline autofill support, layout editor, microphone fallback, revised skins and keyboard blur. APK compilation and on-device validation are required before this branch is promoted.


## Thanks and attribution

Thank you to [kotlalipopus](https://github.com/kotlalipopus/Expressive-Keyboard) for the original Expressive Keyboard project. Aether Keyboard is an independent MIT-licensed fork, with the original copyright notice preserved in [LICENSE](LICENSE).

Thank you to the Android Open Source Project contributors for open Android keyboard research and [LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/). LatinIME's Apache-2.0 code is **not currently incorporated into this fork**, and Aether does not contain Google's proprietary Gboard code or claim affiliation with Google.

## Acknowledgments

Thank you to [kotlalipopus](https://github.com/kotlalipopus/Expressive-Keyboard), creator of the upstream MIT-licensed Expressive Keyboard. Aether is an independent fork, not affiliated with Google or PixelBoard. No proprietary Gboard code is included.

## License

[MIT](LICENSE). Copyright (c) 2026 kotlalipopus is retained in the original LICENSE file. Changes to this fork are maintained independently by Blake22020.


Build validation: GitHub Actions runs on feature branches and pull requests; merge only after a successful Android build.
