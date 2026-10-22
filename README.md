# Swift

A lightweight Android browser with uBlock Origin built in. It is made for watching videos on
ad-heavy sites on an old phone (Moto E4, Android 7.1.2).

## Download

**[Download Swift 1.7 (APK)](https://github.com/ibad20sohail/SwiftBrowser/releases/download/v1.7/Swift-1.7.apk)**
for Android 7 and newer (32-bit ARM, about 150 MB). It installs over an earlier Swift and keeps
tabs and settings. From 1.4 on, Swift can also update itself: Settings → Check for updates.

Older versions: [all releases](https://github.com/ibad20sohail/SwiftBrowser/releases). The APKs
are attached to the releases because GitHub doesn't accept files over 100 MB in the repository.

## Features

- **Firefox engine (GeckoView 143)**: the newest engine that still runs on Android 7.
- **Ad blocking with four levels** (Off / Balanced / High / Extreme), set for all sites or per site.
  - Uses a bundled **uBlock Origin**, with the app switching its filter lists and rules.
  - Has its own **pop-up and redirect blocker**, which stops the "tap anywhere opens an ad" trick.
- **Profiles**: each profile has its own Google login, cookies, history, bookmarks and tabs.
- **Incognito tabs**: no history, and cookies and site data go away when the last one closes.
- **Tabs** in a two-column grid with page previews, like Chrome:
  - Hold a tab and drag it to move it. Hold it over another tab for a moment and let go to
    **group** them. A group is one card; tap it to see its tabs, rename it, pick a colour, add a
    tab, ungroup or close it. Links opened from a grouped tab join its group, and the tab button
    takes the group's colour.
  - **Search** your tabs by title or address.
  - Tabs not opened for 14 days (a setting) wait under **Inactive tabs**, with Close all.
  - A closed tab (or group) can be brought back with Undo, and **Recent tabs** in the menu keeps
    the last 50 closed tabs with their back history.
  - Swipe sideways on the toolbar to go to the next or previous tab, or swipe it toward the page
    to open the tab grid. Hold the tab button for New tab, New incognito tab, Close this tab and
    Recent tabs.
  - How many tabs stay loaded is a setting (1 to 10, 2 by default); the others show their last
    picture straight away and reload their page when opened.
- **Menu** (⋮) that slides up from the bottom: the page's main actions as a row of icons, four
  switches (Lite mode, dark mode, desktop site, full screen) that keep the menu open, and two pages
  of buttons swiped sideways. Swipe it down, tap outside it or press back to close it.
- **Reader view**: a clean, light version of article pages (Mozilla's Readability), with text size
  buttons, in light or dark. **Listen** reads the article aloud with the phone's text-to-speech,
  from the paragraph on screen, marking the paragraph being read.
- **Lite mode**, one switch for all sites (menu or Settings): no animations, shadows or blur, so
  heavy sites scroll smoothly on the phone. It stays on until it is turned off.
- **Show sites as designed** (Settings → Speed & memory): for newer phones, turns off Swift's
  changes for slow phones (no glass blur, fewer animations, lighter YouTube and TheFlixer). It is
  on by itself on phones with 4 GB of memory or more and a fast graphics chip.
- **Full screen** (menu or Settings): browsing without the phone's status and navigation bars; a
  swipe in from the top or bottom edge shows them for a moment. Menus and dialogs keep them hidden.
- **Save offline**: saves a page as a PDF in Downloads, to read without internet in Swift's own
  PDF viewer.
- **Add to Home screen**: a shortcut to the page on the phone's home screen.
- **Swipe from the edges**: from the left edge of a page to go back, from the right to go forward.
- **Home page** for new tabs: a search box (with voice search) and up to 12 shortcuts. Add your
  own with the + tile; hold a shortcut to open it in a new or incognito tab, rename it, change its
  address, move it to the start or remove it. Free places are filled with the most visited sites.
- **Address bar at the top or the bottom** (Settings → Look & screen).
- **Toolbar that hides while scrolling**: it slides away and back with your finger, as in Chrome,
  and finishes with a short slide when you let go (or, with that setting off, it goes and comes
  back in one go after a deliberate scroll). Pull down at the top of a page to reload.
- **Site settings**: the settings button next to the site's name in the menu sets, for that site,
  desktop mode (remembered), sound that starts by itself, location (ask, allow or block), the
  ad-blocking level, and clears its cookies and data. Settings → Privacy & storage lists the sites.
- **Search engine**: Google, DuckDuckGo, Bing or Startpage, with suggestions from the same engine.
- **Data saver**: on mobile data, pages load without images (Settings → Speed & memory).
- **Back up and restore**: settings, profiles, bookmarks, history, open tabs and groups, and site
  settings in one file in the download folder, to restore on this or another phone. Passwords and
  cookies aren't in it.
- **Video**:
  - Full-screen videos use the whole screen and follow the phone's auto-rotate. Turning them to
    landscape automatically, and swipe gestures for brightness (left side) and volume (right
    side), can be switched on in Settings → Video.
  - Sites get H.264 video, which the phone's video hardware plays, instead of AV1.
  - Background play: videos and music keep playing when you leave Swift or turn the screen off,
    with play/pause in the notifications.
  - Playback speed can be set from 0.5x to 3x.
  - In full screen, double-tap the left or right side to skip 10 seconds (keep tapping to skip
    more), and hold a finger down for double speed.
  - **Continue watching**: Swift remembers where long videos were left off and offers to continue
    from there, even after a reload or reopening the tab.
  - Videos a page plays can be downloaded, including HLS (`.m3u8`) streams. These are saved as
    MP4 when possible.
- **Downloads** with pause/resume and notifications, saved to a folder of your choice: Download/Swift,
  Download, Movies, Swift's folder on the SD card, or any folder on the phone.
- **Settings** in the app's own look: the most used switches as tiles, seven topics that show
  what is set, and a search box.
- **Hide cookie pop-ups**: one switch (Settings → Ad blocking) for uBlock Origin's cookie-notice
  lists.
- **Dark / light mode**, with a calm palette in both. Sites that support dark mode follow it.
- **Text size** for all sites, and **zoom** even on sites that turn it off.
- **Tuned for a 2 GB phone**: uBlock Origin runs in its own process, filter lists update only
  while the app is in the background, the web cache is capped, and Firefox's own Safe Browsing
  lists are off (uBlock Origin covers them). Silent videos don't start by themselves (YouTube
  feed previews), and the app's own toolbar is redrawn as little as possible while a page scrolls.
  All tabs share one engine process for pages, decoded images are capped at 48 MB, and when the
  phone runs low on memory only the open tab stays loaded.
- **Problem reports**: engine crashes and Android closing Swift (usually for lack of memory) are
  noted, and Settings → Send a problem report shares them with the app's log (nothing is sent by
  itself).
- **Lighter pages**: frosted-glass effects (CSS `backdrop-filter`) are off, sites that offer a
  version without decorative animations get it, and YouTube's and TheFlixer's large blur effects
  are left out. Each of these makes scrolling several times more work on a slow phone.
- **Start-up screen**: the logo while the app starts, and a loading screen while an open page
  waits for the ad blocker to load its filters.
- **Drawing method**: on Adreno 3xx graphics chips, where Firefox's GPU drawing is slow on
  text-heavy pages, pages are drawn with the CPU by default (videos still go through the GPU).
  Settings → Performance switches it and can show a drawing-speed meter for comparing.

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
