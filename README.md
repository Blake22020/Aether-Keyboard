# Expressive Keyboard

An Android keyboard in the Material 3 Expressive style, built with Kotlin and Jetpack Compose.
Highly customizable and extensible with plugins and JavaScript mods.

<!-- Add 2–3 screenshots here, for example:
<p>
  <img src="screenshots/keyboard.png" width="250">
  <img src="screenshots/settings.png" width="250">
</p>
-->

## Download

Get the latest APK from [Releases](https://github.com/kotlalipopus/Expressive-Keyboard/releases/latest).

Requires Android 8.0 or newer.

## Features

- **Typing:** word suggestions, autocorrect, glide (swipe) typing
- **Panels:** emoji, GIFs, clipboard, translator, voice input
- **Look:** Material You colors, themes and skins, custom fonts (.ttf/.otf), a photo from your gallery behind the keys
- **Layout:** key height and corner radius, spacing, keyboard width, one-handed mode
- **Feedback:** adjustable vibration and sound
- **Privacy:** incognito mode; suggestions and dictionary learning are turned off in password fields
- **Plugins and mods:** custom buttons, shortcuts, layouts, themes and JavaScript scripts

## Installation

1. Download and install the APK from [Releases](https://github.com/kotlalipopus/Expressive-Keyboard/releases/latest).
2. Open the app and enable the keyboard in system settings.
3. Select Expressive Keyboard as your input method.

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
git clone https://github.com/kotlalipopus/Expressive-Keyboard.git
cd Expressive-Keyboard
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`.

To enable GIF search, put your own Giphy key into `GIPHY_API_KEY` in
`app/src/main/java/com/expressive/keyboard/Common.kt`. Without a key, a built-in GIF set is used.

## License

[MIT](LICENSE)# Expressive-Keyboard
