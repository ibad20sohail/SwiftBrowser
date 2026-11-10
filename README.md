# Swift

A lightweight Android browser with uBlock Origin built in. It is made for watching videos on
ad-heavy sites on older phones, and runs on Android 7 and newer.

## Download

Swift 3.0.7 for Android 7 and newer comes in two files:

- **[Swift-64bit-3.0.7.apk](https://github.com/ibad20sohail/SwiftBrowser/releases/download/v3.0.7/Swift-64bit-3.0.7.apk)**
  (64-bit ARM, about 202 MB): for newer phones, and for any phone that says Swift "isn't compatible
  with the latest version of Android".
- **[Swift-3.0.7.apk](https://github.com/ibad20sohail/SwiftBrowser/releases/download/v3.0.7/Swift-3.0.7.apk)**
  (32-bit ARM, about 165 MB): for older phones, such as those with 2 GB of memory.

Either installs over an earlier Swift and keeps tabs and settings. Swift can also update itself
(Settings → About Swift → Check for updates); from 3.0.6 on it picks the right file for the phone.

Older versions: [all releases](https://github.com/ibad20sohail/SwiftBrowser/releases). The APKs
are attached to the releases because GitHub doesn't accept files over 100 MB in the repository.

## What Swift does

### Browsing

- **Firefox engine** (GeckoView 143), the newest that still runs on Android 7.
- **Address bar** at the top or the bottom of the screen. While reading it shows the site's name
  and icon (tap it for the site's settings), in the website's own colour when it has one; an open
  red lock warns about sites that aren't secure. While you type it completes addresses and suggests,
  in groups, search suggestions, your open tabs, bookmarks and history, and offers a link you just
  copied. Hold the address for Paste and go, Copy, Share and Edit.
- **More room for the page**: the bar slides away and back with your finger as you scroll, a swipe
  on it can hide it, and full screen hides the phone's own bars too.
- **Address bar buttons**: up to 4 on either side of the address, arranged by dragging: profile,
  home, tabs, menu, new tab, reload, share, bookmark, VPN and Scan QR.
- **Search**: Google, DuckDuckGo, Bing, Startpage or a search engine of your own, with suggestions
  and its logo in the address bar.
- **Home page**: a search box (with voice search and QR codes) that slides into the address bar,
  up to 15 shortcuts you can arrange, what you left half-way, and the news, in the order you choose
  and over a soft gradient or a picture of your own, with a slider for how clear the picture is. The picture runs behind a see-through address bar, and the text and buttons on it take whichever colour reads.
  Colours can be taken from that picture.
- **News** on the home page: headlines from Google News for your country as cards with their
  pictures, topics, more stories as you scroll, and news sites you add yourself. Hold a story to
  open it in a new or incognito tab, share it or hide that site. It's fetched at most once an hour,
  pictures load only as they come on screen, and it can be turned off.
- **Continue**: long videos, articles and documents you left half-way, newest first, with how far
  you got. Hold a card to remove it.
- **Menu (≡)** that slides up from the bottom: the page and its site settings, the main actions,
  then pages of buttons you arrange yourself: hold one to move it or hide it. Among them: Tap to
  hide, Sleep timer, Scan QR, Text size, Translate, Find, Desktop site, Save offline, the page's QR
  code and Quit.
- **Long press** on a link, picture or video: what it is, the main actions as big buttons (new,
  background or incognito tab, share; for pictures open, save, share, copy) and the rest as rows.
- **Selected text**: Search, Share and Listen next to Copy.
- **Reader view**: a clean version of an article, with your choice of font, line spacing and page
  colour (light, dark or sepia). It remembers where you stopped, and **Listen** reads the article
  aloud, marking the paragraph, also with the screen off.
- **Translate**: pages are translated on the phone itself, and nothing is sent anywhere. Each
  language downloads once (about 20 MB).
- **Site settings** for each site: desktop mode, sound that starts by itself, location, ad-blocking
  level, Lite mode, dark page, text size, always open in reader view, and clearing its cookies and
  data.
- **History** by day, with search and Delete day. **Bookmarks** with folders, search and import from
  Chrome or Firefox. Removing things can be undone for a few seconds.
- **Save offline** (a page as a PDF), **Add to Home screen**, **Scan QR codes**, **pull down to
  refresh**, **swipe from the edges** to go back and forward, **zoom** even on sites that turn it
  off, a text size for all sites or each one, and pages that load again by themselves when the
  connection comes back.
- On your home screen: app icon shortcuts (New tab, Incognito tab, Files), a search widget in your
  colours, and quick settings tiles for New tab and Swift's VPN.

### Tabs

- A **grid with page previews**, or a compact list. The open tab shrinks into its card and grows
  back when you pick one.
- **Groups**: drag a tab onto another to group them. Name them, give them a colour, and switch
  between a group's tabs from a strip by the address bar.
- **Pin** a tab (it comes first and stays when you close all), **mute** a tab playing sound, and
  **keep a tab loaded** so it's never unloaded to save memory.
- **Search** your tabs. Tabs not opened for a while wait under **Inactive tabs**.
- **Undo** a closed tab or group, or Close all tabs. **Recent tabs** keeps the last 50 closed tabs
  with their back history.
- Swipe sideways on the address bar to change tab. Double-tap the tab button for the tab you were
  on before; hold it for New tab, New incognito tab, Close this tab and Recent tabs.
- **How many tabs stay ready to open instantly** is your choice (1 to 10). The others show their last
  picture straight away and load again when opened.
- **Profiles**: each has its own Google login, cookies, history, bookmarks and tabs, and shows
  the Google account signed in to it.
- **Incognito tabs**: no history, and cookies, site data and site settings changed in them go away
  when the last one closes. They stay out of screenshots and recent apps unless you allow it.

### Ad blocking

- **uBlock Origin** is built in, with four levels (Off, Balanced, High, Extreme) for all sites or
  each site. From High up it also blocks known fake login (phishing) sites.
- Swift's own **pop-up and redirect blocker** stops the "tap anywhere opens an ad" trick.
- **Tap to hide**: tap a leftover ad or banner to hide it on that site for good.
- **Cookie pop-ups** answered with "Reject" on the sites Swift knows and hidden on most others.
- **Blocked this week**: how many ads and trackers were blocked on each of the last 7 days (counts
  only, never which sites).
- From the first page: after a restart, pages wait for the ad blocker while the start-up screen
  says what's loading, with Load now after 8 seconds.

### Videos and music

**Videos on websites**

- Keep playing in the background: in other tabs, other apps and with the screen off, even on sites
  that pause themselves when you leave them. One tab plays at a time.
- Notification and lock-screen controls with the picture, play and pause, skip, sleep timer and
  close. Headset and Bluetooth buttons work, and playing pauses when headphones are unplugged.
- In full screen: double-tap a side to skip (keep tapping to skip more), hold for double speed,
  swipe for brightness and volume, and turn sideways by itself.
- **Continue watching** where you left off, even after a reload.
- **Sleep timer**: stops after 15 to 90 minutes or at the end of the video, fading the sound out.
- Playback speed from 0.5x to 3x. Sites get H.264 video, which the phone's hardware can play.
- A button on a playing video can open it in **Swift's player**, which is smoother and has gestures
  and subtitles. HLS and DASH streams work there too.

**Swift's player**, for videos and music on the phone and from other apps

- Swipe for brightness, volume and seeking; double-tap to skip (taps in a row add up); hold for 2×;
  pinch to fill the screen; lock; turning; picture size; speed.
- Previous and next, the folder as a playlist, repeat, shuffle, an Up next card, A-B repeat, and
  frame by frame while paused.
- **Sound and subtitles**: audio tracks, subtitle files found next to the video or picked yourself,
  size, colour and timing, for every subtitle and for the sound, kept for each video. Urdu, Arabic
  and other subtitles are read in the right encoding.
- Dolby and DTS sound, a volume boost up to 200%, an equalizer with the phone's presets and bass
  boost, and Night sound (loud parts quieter, quiet voices louder).
- Music keeps playing with the screen off, in the colours of its album cover. It goes on where you
  stopped, and panels open beside the video so it stays in view.

**Pictures**: zoom and swipe through a folder, crop and turn, details (camera, settings, place),
a thumbnail strip, slideshow, moving GIFs, and set as wallpaper or contact photo.

### Downloads and torrents

- **Downloads** with pause and resume, progress on the menu button and in notifications, and a
  folder of your choice. Big ones can wait for Wi-Fi. Downloads cut off by closing Swift go on by
  themselves, and failed ones try again when the connection comes back.
- **Sort downloads into folders** by kind: Videos, Music, Pictures, Documents and Apps.
- **Save videos from pages**, including HLS and DASH streams, choosing the quality with its size,
  as one MP4 when possible.
- **Torrents**: magnet links, .torrent files and "Open with Swift", with a panel of the files (junk
  unticked, a warning for Windows programs and 10-bit videos, a free space check). Speed limits, how
  many at once, Wi-Fi only, only while charging, at night only, extra sources, and protected
  connections. They pause on low battery and while Swift's VPN is on.

### Files

A file manager for the phone's storage and SD card, also with its own icon.

- A start page with how full each storage is and what fills it, categories, Continue, recent files
  and favourite folders. Grid or list, with pictures of photos and videos and how much of a video
  was watched.
- Opens for a website's upload button, or your phone's own picker if you prefer (Settings → Files).
- Copy, move, rename, delete, new folder, share, details, and folder sizes. Hold a file and slide
  to select many. **Two folders side by side** with the phone sideways.
- **Archives** (ZIP, 7z, RAR, TAR, GZ, BZ2, XZ) open like folders. Extract all or some, and make
  ZIP or 7z, with a password for ZIP. RAR5 can't be opened, and RAR can't be made.
- **Recycle bin**: deleted files can be brought back, for as long as you choose.
- **Free up space**: large files, duplicates, app files, empty folders and what Swift and torrents
  leave behind.
- **Private folder** behind your screen lock or a PIN, kept out of other apps, the Gallery and
  backups.
- **Wi-Fi transfer**: the phone's files on a computer's browser, with a code.
- **Network folders**: a computer's or a home storage's shared folders over Wi-Fi (SMB 2 and 3),
  read-only, with videos and music played straight from the share.

### Documents

PDF, Word, Excel, PowerPoint, e-books and text files open in Swift's own viewer, offline; nothing
is uploaded. They look as they were made, in full screen, from Files, Downloads, a site or another
app's "Open with".

- **PDFs**: load only what's on screen, go to a page, contents, page previews, sharp zoom, password
  protection, scanned pages, and **Fill & sign** (fill forms, highlight, draw, add text, save a copy).
- **Word** as pages with their fonts, colours, tables and pictures, or as a simple reading view.
  Old .doc files and .ppt slides too.
- **Excel**: colours, merged cells, frozen rows and columns, and find in the whole sheet.
- **PowerPoint**: a full-screen slideshow with the speaker notes.
- **E-books** page by page or scrolling, with your choice of font and line spacing.
- **Listen** reads the document aloud through all its pages. Reading mode hides the bars, a handle
  scrolls long documents quickly, Save as PDF, Print, and each document remembers where you stopped.

### Privacy and security

- **Secure connections**: each site's protected version when it has one.
- **Private site lookups**: your internet provider can't see which sites you visit (Cloudflare,
  Quad9 or Google).
- **Security status**: what is on, and what Swift has actually checked.
- **Safer browsing**: closes a common way older browsers are attacked, as the engine no longer
  gets security fixes.
- **Saved passwords**, kept on the phone only and locked by Android, each profile with its own,
  never in backups, and shown only after your screen lock.
- **Address bar safety**: it can't be faked by pages posing as a search engine, lookalike names
  show as they really are, and QR codes show where they lead first.
- **Clear browsing data** by time or by dates, seeing how much each thing takes. **Quit** can
  clear what you choose when you leave.
- Logs and problem reports name no addresses or files, and nothing is sent without you seeing it
  first.

### VPN

A free VPN for Swift only: change the country sites see you in, with free setup files from Proton
VPN. Only Swift's traffic goes through it; other apps keep their own connection.

- Add a location by downloading its file in Swift, or from a file or pasted text. Its key is
  locked by Android, and VPN files are never in backups.
- **Pause pages if the VPN drops**: pages wait instead of loading without it, and load by themselves
  when it reconnects.
- **Switch to another location** by itself when one stops working. **Test the locations** shows
  which answer and how fast.
- With no internet, or when none of the locations answers, it turns itself off and says why, so
  pages don't stay paused.
- It comes back by itself after an update or a restart, and can turn on every time Swift starts.
- Reminders before a free file stops working, and a quick settings tile; hold the VPN mark in the
  address bar to switch country.

### Look

- **Google Sans Flex** for all of Swift's own text, and one icon style, Google's Material Symbols
  Rounded: outlined, filled when something is on.
- **Light, dark, or dark at night** (7 pm to 7 am). Sites that support dark mode follow it, and a
  dark page can be turned on for those that don't.
- **Colours**: 12 accent colours or your own, the main colour of each mode, ready-made mixes, and
  colours taken from your home page's picture or your wallpaper.
- **Night light**: a warm layer over Swift, pages and videos too.
- **Start-up screen** in your colours, with the logo and, when something has to load, what's
  loading, step by step.
- Short, light animations and a light vibration on long presses (which can be turned off).

### Speed on older phones

- **Lite mode** in five levels, for all sites and for each site (≡ menu → Lite), each adding to
  the one before: **Off** (sites exactly as designed; where phones with 4 GB of memory or more
  start), **Low** (no frosted glass, less motion, lighter YouTube and TheFlixer; where other phones
  start), **Medium** (no animations, shadows, blur or filters), **High** (GIFs play once, videos
  start only when tapped, fewer graphics layers) and **Extreme** (GIFs stay still, text shows at
  once while a site's font loads).
- **Battery saver**: below a level you choose, or with your phone's battery saver, Swift uses Lite
  mode Medium or higher, keeps only the open tab loaded and loads no pictures on mobile data.
- **Data saver**: on mobile data, pages load without pictures. **Data used** shows each day.
- **Memory**: one engine process for all tabs, decoded pictures capped at 48 MB, and when memory
  runs low, or a minute after Swift goes to the background, only the open or playing tab and those
  you keep loaded stay. **Right now** shows free memory, Swift's own, loaded tabs and recent crashes.
- The cache stays under 100 MB (or 50 or 200, in Settings → Storage & data → Cache size), no
  single file over 10 MB, and Swift empties it by itself when the phone is low on space.
- Pages that stop responding offer Stop, and a crashed page loads again by itself once.

### Settings and backup

- **Settings** in plain words, in twelve topics that show what is set in each, with a search box
  that understands everyday words ("battery", "ads", "music").
- **Back up and restore** settings, profiles, bookmarks, history, open tabs and groups, and site
  settings in one file, also by itself every week. Passwords, cookies, the VPN and the Private
  folder are never in it.
- **Storage**: what Swift keeps on the phone, part by part, the sites that stored data (clear each
  one) and clearing sites you haven't visited for 30 days.
- **Updates**: Swift checks and installs its own updates, signed by Swift, and shows what's new.
- **Problem reports**: crashes and freezes with Swift's log of the last 3 days, shown to you before
  they are sent.

## Requirements

- Android 7 or newer on ARM phones: 32-bit (`phone` build) or 64-bit (`phone64` build), or x86_64
  for the Android emulator (`emu` build).

## Build

To build it you need:

- JDK 17 or newer
- Android SDK with platform 36
- A signing key in `keystore/` (not in git, see below)

```sh
./gradlew :app:assemblePhoneRelease     # 32-bit ARM APK (Swift-<version>.apk)
./gradlew :app:assemblePhone64Release   # 64-bit ARM APK (Swift-64bit-<version>.apk)
./gradlew :app:assembleEmuRelease       # x86_64 APK for the Android emulator
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
| `.../media` | Swift's player, the bridge to the helper extension (video detection, speed), sound and subtitles |
| `.../download` | Download manager, HLS and DASH downloader, TS→MP4 remuxer, notification service |
| `.../torrent` | Torrents (libtorrent) |
| `.../files` | The file manager, archives, recycle bin, Private folder, Wi-Fi transfer, network folders |
| `.../docs` | Opening documents, the viewer's file channel, page previews, old Word and PowerPoint files |
| `.../vpn` | The VPN: WireGuard tunnel for Swift only, locations, encrypted setup files |
| `.../news` | The home page's news |
| `.../core` | Settings storage, the database, backups, logs and shared logic |
| `.../ui` | Activities, home page, tab grid, settings, bottom sheets and dialogs |
| `app/src/main/assets/extensions/ublock` | uBlock Origin 1.75.0, patched by `tools/update_ublock.py` |
| `app/src/main/assets/extensions/helper` | Small built-in extension used by the app |
| `.../helper/docs` | The document viewer, with pdf.js, docx-preview, SheetJS, pptx-preview, epub.js and JSZip (`lib/LICENSES.txt`) |
| `tools/` | uBlock Origin update and patch script |
| `app/src/test` | Tests of the plain logic, run on the PC: `gradle :app:testEmuDebugUnitTest` |

To update uBlock Origin, run `python tools/update_ublock.py --version X.Y.Z`. It stops unless the
download matches the hash addons.mozilla.org publishes (`--skip-amo-check` to override). If
`tools/ublock-patch/swift-bridge.js` changes, also bump `UBO_BRIDGE_REV` in `Engine.kt`.

## Licences

Swift's own code is free software: you can share and change it under the **GNU General Public
License, version 3 or (at your option) any later version** (the `LICENSE` file). It has to be: it
includes GPLv3 parts (uBlock Origin and Jellyfin's FFmpeg decoder).

The app lists every part with its licence and home page under About Swift → Open-source licences
(`ui/LicencesData.kt`). In short:

- GeckoView is MPL 2.0.
- uBlock Origin is GPLv3; the bundled copy is modified by adding `js/swift-bridge.js`, two
  permissions and one web-accessible page (its "page blocked" screen) to its manifest, and the
  storage and picker tweaks listed in `tools/update_ublock.py`.
- Jellyfin's Media3 FFmpeg decoder (Dolby and DTS sound) is GPLv3; desugar_jdk_libs is GPLv2 with
  the Classpath Exception.
- Apache 2.0: AndroidX and Media3, Material Components, Kotlin and kotlinx, the WireGuard tunnel
  library, ZXing and ZXing Android Embedded, Apache POI, Commons Compress/Codec/Collections/Math,
  Zip4j, Guava, SnakeYAML, SparseBitSet, pdf.js, docx-preview, SheetJS, Readability, smbj.
- MIT: libtorrent4j, JSZip, SLF4J. ISC: pptx-preview. BSD 2-Clause: epub.js. XZ for Java is
  public domain; Junrar follows the UnRAR licence.
- Google Sans Flex (Swift's font) is under the SIL Open Font License, and Material Symbols (its
  icons) under Apache 2.0.
- Google Play services (brought in by GeckoView) is under the Android SDK licence.
