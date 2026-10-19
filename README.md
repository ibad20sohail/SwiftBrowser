# Swift

A lightweight Android browser with uBlock Origin built in. It is made for watching videos on
ad-heavy sites on an old phone (Moto E4, Android 7.1.2).

## Features

- **Firefox engine (GeckoView 143)**: the newest engine that still runs on Android 7.
- **Ad blocking with four levels** (Off / Balanced / High / Extreme), set for all sites or per site.
  - Uses a bundled **uBlock Origin**, with the app switching its filter lists and rules.
  - Has its own **pop-up and redirect blocker**, which stops the "tap anywhere opens an ad" trick.
- **Profiles**: each profile has its own Google login, cookies, history, bookmarks and tabs.
- **Incognito tabs**: no history, and cookies and site data go away when the last one closes.
- **Tabs** in a two-column grid with page previews, like Chrome. Only a few stay loaded to save
  memory; the rest restore when opened.
- **Home page** for new tabs: a Google search box (with voice search) and shortcuts to the most
  visited sites.
- **Toolbar that hides while scrolling down**, and pull down at the top of a page to reload.
- **Video**:
  - Full-screen videos turn to landscape automatically.
  - Swipe gestures control brightness (left side) and volume (right side).
  - Playback speed can be set from 0.5x to 3x.
  - Videos a page plays can be downloaded, including HLS (`.m3u8`) streams. These are saved as
    MP4 when possible.
- **Downloads** with pause/resume and notifications.
- **Dark / light mode**. Sites that support dark mode follow it.
- **Google search** with suggestions in the address bar.
- **Tuned for a 2 GB phone**: uBlock Origin runs in its own process, filter lists update only
  while the app is in the background, the web cache is capped, and Firefox's own Safe Browsing
  lists are off (uBlock Origin covers them). Pages can be drawn with the CPU instead of the GPU
  (Settings → Performance) for phones whose graphics chip is slow.

## Build

Requirements:

- JDK 17+
- Android SDK with platform 36
- A signing key in `keystore/` (not in git, see below)

```sh
./gradlew :app:assemblePhoneRelease   # 32-bit ARM APK for the phone
./gradlew :app:assembleEmuDebug       # x86_64 APK for the Android emulator
```

Outputs are in `app/build/outputs/apk/`.

The signing key isn't in git. Create `keystore/keystore.properties` like this:

```
storeFile=keystore/swift-release.jks
storePassword=...
keyAlias=swift
keyPassword=...
```

Keep the key safe. An update only installs over the existing app (keeping its data) if it's
signed with the same key.

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/com/swiftbrowser/engine` | Gecko runtime and extension setup |
| `.../tabs` | Tabs, sessions and the GeckoView delegates (navigation, pop-up policy, crashes, permissions) |
| `.../adblock` | Ad-block levels, the uBlock Origin bridge and the pop-up/redirect policy |
| `.../media` | Bridge to the helper extension: video detection, playback speed, tap tracking |
| `.../download` | Download manager, HLS downloader, TS→MP4 remuxer, notification service |
| `.../ui` | Activities, home page, tab grid, settings, bottom sheets and dialogs |
| `app/src/main/assets/extensions/ublock` | uBlock Origin 1.75.0, patched by `tools/update_ublock.py` |
| `app/src/main/assets/extensions/helper` | Small built-in extension used by the app |
| `tools/` | uBlock Origin update and patch script |

To update uBlock Origin, run `python tools/update_ublock.py --version X.Y.Z`. If
`tools/ublock-patch/swift-bridge.js` changes, also bump `UBO_BRIDGE_REV` in `Engine.kt`.

## Licenses

- GeckoView is MPL 2.0.
- uBlock Origin is GPLv3; the bundled copy is modified only by adding `js/swift-bridge.js` and two
  permissions to its manifest.
