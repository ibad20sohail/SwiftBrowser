# Swift

A lightweight Android browser with uBlock Origin built in. It is made for watching videos on
ad-heavy sites on an old phone (Moto E4, Android 7.1.2).

## Download

**[Download Swift 2.3 (APK)](https://github.com/ibad20sohail/SwiftBrowser/releases/download/v2.3/Swift-2.3.apk)**
for Android 7 and newer (32-bit ARM, about 150 MB). It installs over an earlier Swift and keeps
tabs and settings. From 1.4 on, Swift can also update itself: Settings → Check for updates.

Older versions: [all releases](https://github.com/ibad20sohail/SwiftBrowser/releases). The APKs
are attached to the releases because GitHub doesn't accept files over 100 MB in the repository.

## Features

Swift 2.0 redid every screen in one consistent look: the same rounded sheets, rows, buttons and
short animations everywhere, a theme that can turn dark at night, plain words instead of technical
ones, and a short welcome on the first start.

- **Colours** (2.3, Settings → Look & screen → Colours, with a live preview of light and dark):
  12 accent colours (blue, indigo, purple, pink, red, orange, amber, green, teal, cyan, brown,
  graphite) or **your own** (a palette or a colour code), and the main colour of each mode: white,
  warm paper, cool grey or your own for light mode; grey, pure black, navy, warm dark or your own
  for dark mode. **Ready-made** mixes (Classic, Paper & teal, AMOLED, Midnight, Sunset, Forest,
  Rose) set all three at once. Bars, cards, sheets, dialogs and Swift's logo follow it; a colour
  too dark (or too light) for easy reading is adjusted. Colours are part of backups.
- **Logo** (2.3): a bold S leaning forward with speed lines, on a rounded square; in the accent
  colour inside the app, blue on the home screen.
- **Night light** (2.3, Settings → Look & screen): a warm layer over Swift, pages and videos too,
  from 7 pm to 7 am or always, in three strengths. Taps go through it.

- **Firefox engine (GeckoView 143)**: the newest engine that still runs on Android 7.
- **Free VPN for Swift only** (2.1, Settings → VPN, or VPN in the menu): change the country sites
  see you in, with free setup files from Proton VPN (WireGuard, one file per server; the how-to is
  in Settings). Only Swift's own traffic goes through it; other apps keep their connection.
  - Add a location by downloading its `.conf` file in Swift ("Add to VPN"), by tapping it in
    Downloads, or from a file or pasted text. Its key is kept encrypted with a key in Android's
    keystore, the downloaded copy is deleted, and VPN files are never in backups.
  - Tap a location to connect or switch to it; Swift waits for the server to answer before it says
    "Connected" and shows the address and city sites see.
  - **Pause pages if the VPN drops** (on by default): while the VPN should be on but isn't, pages
    wait ("Waiting for the VPN") instead of loading over your own connection, and load by
    themselves when it reconnects. Reconnect or Turn off from the menu, the "VPN off" mark in the
    address bar or Settings.
  - **Connect when Swift starts**, if it was on when you left.
  - **Renew reminders** (2.2): each location shows when Proton's free file stops working (a year after
    it was made); two weeks before, Swift reminds you to tap Extend on Proton's page. Hold a location
    for "I extended it".
  - **Switch to another location** (on by default): when a server doesn't answer, or connects but
    passes nothing (an expired or revoked file), Swift moves to the next location by itself (the
    same country first, the fastest from the last test) and says where it went. **Test the
    locations** tries them all and shows which answer and how fast. The VPN sheet groups locations
    by country.
- **Ad blocking with four levels** (Off / Balanced / High / Extreme), set for all sites or per site.
  From High up (2.2) it also blocks known fake login (phishing) sites, showing uBlock Origin's
  "page blocked" screen with the list that caught it and a way to go on anyway.
  - Uses a bundled **uBlock Origin**, with the app switching its filter lists and rules.
  - Has its own **pop-up and redirect blocker**, which stops the "tap anywhere opens an ad" trick.
  - **Blocked this week** (2.3, Settings → Ad blocking): how many ads and trackers were blocked on
    each of the last 7 days (counts only, never which sites).
- **Profiles**: each profile has its own Google login, cookies, history, bookmarks and tabs.
- **Incognito tabs**: no history, and cookies and site data go away when the last one closes.
- **Tabs** in a grid with page previews, like Chrome (two columns upright, three on a turned
  phone). The open tab shrinks into its card when the grid opens and grows back when you pick one:
  - Hold a tab and drag it to move it. Hold it over another tab for a moment and let go to
    **group** them. A group is one card; tap it to see its tabs, rename it, pick a colour, add a
    tab, ungroup or close it. Links opened from a grouped tab join its group, and the tab button
    takes the group's colour.
  - **Search** your tabs by title or address.
  - Tabs not opened for 14 days (a setting) wait under **Inactive tabs**, with Close all.
  - A closed tab (or group) can be brought back with Undo, and **Recent tabs** in the menu keeps
    the last 50 closed tabs with their back history. **Close all tabs** in the menu can be undone
    (2.3); closing all incognito tabs still asks first, as their data goes for good.
  - Swipe sideways on the toolbar to go to the next or previous tab. Hold the tab button for New
    tab, New incognito tab, Close this tab and Recent tabs.
  - How many tabs stay loaded is a setting (1 to 10, 2 by default); the others show their last
    picture straight away and reload their page when opened.
- **Menu** (≡) that slides up from the bottom: the page with its site settings, copy and
  Settings buttons, the page's main actions as a row of icons, then pages of buttons (2 rows of 4,
  swipe sideways or tap the dots). **Arrange it yourself** (2.3): hold any button a moment and it
  lifts; drag it to another place, or to the edge to go to the next page, and let go: it stays
  there. Drop it on the strip at the top to hide it (with Undo). Hidden buttons wait under
  **Hidden** on the last page: tap one to put it back. Settings → Look & screen → Reset the menu
  starts over (with Undo). The switches (Lite mode, dark mode, desktop site, full screen) are
  buttons there too and keep the menu open. Among the others: **Tap to hide** (tap a leftover ad or
  banner and hide it on that site for good, with uBlock Origin's element picker), the **Sleep
  timer**, **Scan QR** and **Text size**. Swipe it down, tap outside it or press back to close it.
- **Continue** (2.3) on the home page: long videos and reader view articles you left half-way,
  newest first, with a picture (the video's, YouTube's own, or the article's first) and how far you
  got. An article opens in reader view where you stopped. Hold a card to remove it. Only pages in
  the profile's history show; Settings → Home & news turns it off.
- **App icon shortcuts** (2.3): hold Swift's icon on the home screen for New tab, Incognito tab or
  Scan QR code.
- **Swift VPN tile** (2.3): add it to Android's quick settings to turn Swift's VPN on or off from
  the notification shade. **Hold the VPN mark** in the address bar to switch country in one tap.
- **Reader view**: a clean, light version of article pages (Mozilla's Readability), with text size
  buttons, in light or dark. It remembers where you stopped reading each article. **Listen** reads
  the article aloud with the phone's text-to-speech, from the paragraph on screen, marking the
  paragraph being read, with pause, previous and next paragraph and speed (0.75x to 2x). It goes on
  with Swift in the background or the screen off, with pause and stop in a notification. Serif or
  sans-serif text and the line spacing are set in Settings → Browsing.
- **Battery saver** (2.3, on by default, Settings → Speed & memory): below 20% battery (or 15 or
  30, as chosen), or while Android's battery saver is on, Swift uses Lite mode, keeps only the open
  tab loaded and loads pages without pictures on mobile data. Everything is back when you charge;
  if it changes while Swift is in the background, Swift says so when you come back.
- **Lite mode**, one switch for all sites (menu or Settings): no animations, shadows or blur, so
  heavy sites scroll smoothly on the phone. It stays on until it is turned off; a site that looks
  broken with it can be left out (Site settings).
- **Show sites as designed** (Settings → Speed & memory): for newer phones, turns off Swift's
  changes for slow phones (no glass blur, fewer animations, lighter YouTube and TheFlixer). It is
  on by itself on phones with 4 GB of memory or more and a fast graphics chip.
- **Full screen** (menu or Settings): browsing without the phone's status and navigation bars; a
  swipe in from the top or bottom edge shows them for a moment. Menus and dialogs keep them hidden.
- **Save offline**: saves a page as a PDF in Downloads, to read without internet in Swift's own
  PDF viewer.
- **Add to Home screen**: a shortcut to the page on the phone's home screen.
- **Swipe from the edges**: from the left edge of a page to go back, from the right to go forward.
- **Home page** for new tabs: a search box (with voice search) that slides into the address bar
  when tapped, up to 12 shortcuts, and the **news**: English headlines from Google News for your
  country (from the phone, or chosen), with topic chips, pull down to refresh, more stories while
  scrolling, and news sites you add yourself (their pictures show on the right). **Stories with
  big pictures** (2.2) turns them into cards with the picture across the top, as in Chrome (Google
  News' headlines often have no picture; sites you add usually do). Hold a story to
  open it in a new or incognito tab, share it or hide that site. Light on the phone: fetched at most
  once an hour, pictures only as they come on screen and not on mobile data with Data saver; it can
  be turned off (Settings → Home & news). shortcuts: add your own with the + tile (it gets the site's icon); hold
  a shortcut and drag it to another place, or hold it and let go to open it in a new or incognito
  tab, rename it, change its address, move it to the start or remove it. Free places are filled
  with the most visited sites.
- **Address bar buttons** (2.2, Settings → Look & screen): up to 4 buttons on either side of the
  address, arranged by dragging: profile, home (the home page over the open tab; Back returns to
  the page), tabs, menu (always there), new tab, reload, share, bookmark, VPN and Scan QR.
- **Scan QR codes** (2.2): next to the microphone while typing an address, or as a button. Web
  addresses open; other text can be searched, copied or shared; Wi-Fi codes show the password.
- **Address bar at the top or the bottom** (Settings → Look & screen). While reading it shows just
  the site's name and icon (tap it for the site's settings); an open red lock warns about sites that
  aren't secure. While you type, it takes the whole toolbar and suggests, in groups, what you typed,
  search suggestions, your **open tabs** (switch to them), bookmarks and history, with ↖ to keep
  typing from a suggestion; it also offers a link you just copied. Hold the address for Paste and
  go, Copy, Share and Edit.
- **Address bar swipes** (2.1, Settings → Look & screen): swipe up on the address bar to hide it,
  and down from the top edge of the screen to bring it back (mirrored for a bar at the bottom).
  Scrolling also brings it back. Swipe up, swipe down, both or off.
- **Long press** on a link, picture or video: a compact sheet with what it is (the site's name;
  tap for the whole address; a small preview of a picture), the main actions as big buttons (new,
  background or incognito tab, share; for pictures open, save, share, copy) and the rest as rows.
- **Toolbar that hides while scrolling**: it slides away and back with your finger, as in Chrome,
  and finishes with a short slide when you let go (or, with that setting off, it goes and comes
  back in one go after a deliberate scroll). Pull down at the top of a page to reload.
- **Site settings**: the settings button next to the site's name in the menu sets, for that site,
  desktop mode (remembered), sound that starts by itself, location (ask, allow or block), the
  ad-blocking level, Lite mode, its own **text size** (2.3, also Text size in the menu), **open
  articles in reader view** (2.3; Swift also offers "Always" after you use reader view twice on a
  site), and clears its cookies and data. Settings → Privacy lists the sites.
- **Secure DNS** (2.2, on with Cloudflare; or Quad9, Google, off): the names of the sites you visit
  are looked up encrypted, so the network can't read them. Wi-Fi sign-in pages still work.
- **Secure connections first**: every site's secure (https) version when it has one; a site
  without one still opens and Swift says it isn't secure. Firefox also answers known cookie
  banners with "Reject".
- **Back online**: a page that couldn't load for lack of connection loads again by itself when the
  connection comes back.
- **Search engine**: Google, DuckDuckGo, Bing or Startpage, with suggestions from the same engine
  and its logo in the address bar.
- **Data saver**: on mobile data, pages load without images (Settings → Speed & memory); "Show"
  brings a page's pictures back for that site.
- **History** grouped by day (Today, Yesterday, then dates), with Delete day, and the search word
  marked in the results. **Bookmarks** can have their name and address changed. In History,
  Bookmarks and Downloads, swipe a row away to remove it; every removal (and deleting a downloaded
  file) can be undone for a few seconds, with a line showing the time left.
- **Back up and restore**: settings, profiles, bookmarks, history, open tabs and groups, and site
  settings in one file in the download folder, to restore on this or another phone. Passwords and
  cookies aren't in it. A backup is also made by itself once a week (the last two are kept).
- **Video**:
  - Full-screen videos use the whole screen and follow the phone's auto-rotate. Turning them to
    landscape automatically, and swipe gestures for brightness (left side) and volume (right
    side), can be switched on in Settings → Video.
  - Sites get H.264 video, which the phone's video hardware plays, instead of AV1.
  - **Keep playing in the background** (a switch): a song or video goes on when you switch tabs,
    open another app or turn the screen off, even on sites that pause themselves when you leave
    them (YouTube). One tab plays at a time.
  - **Media notification** with the picture, previous/next (or skip 10 seconds), play/pause, the
    sleep timer and close, also on the lock screen. Headset and Bluetooth buttons work, and
    playing pauses when headphones are unplugged.
  - **Sleep timer**: stops playing after 15 to 90 minutes or at the end of the video, fading the
    sound out over a few seconds, and stops the next one a site starts by itself. The time left
    shows in the notification. The screen stays on only while a video is really playing.
  - Playback speed can be set from 0.5x to 3x.
  - In full screen, double-tap the left or right side to skip 10 seconds (keep tapping to skip
    more), and hold a finger down for double speed.
  - **Continue watching**: Swift remembers where long videos were left off and offers to continue
    from there (a "Continue from" button), even after a reload or reopening the tab.
  - Videos a page plays can be downloaded, including HLS (`.m3u8`) streams, choosing the quality
    with its approximate size. These are saved as MP4 when possible.
- **Downloads** with pause/resume and notifications, saved to a folder of your choice: Download/Swift,
  Download, Movies, Swift's folder on the SD card, or any folder on the phone. Big downloads can
  wait for Wi-Fi. Downloads cut off by closing Swift go on by themselves, failed ones are tried
  again when the connection comes back, and a tab that only led to a download closes.
- **Storage** (Settings): what Swift keeps on the phone, part by part, counted the way Android
  counts it (the app with its compiled code, its data, and its own folder on the phone storage, where
  downloads go without storage permission), the sites that stored data (clear each one), and
  clearing sites you haven't visited for 30 days. Update files are deleted by themselves once
  installed. The page cache is capped at
  20 MB and uBlock Origin keeps its compiled lists once instead of twice. When the phone is low on
  space, Swift empties its caches by itself (at most once a day).
- **Settings** in the app's own look: the most used switches as tiles, nine topics that show
  what is set, settings for experts folded under **Advanced**, and a search box that also
  understands everyday words ("battery", "ads", "music").
- **Updates** show their download progress, and "What's new" after an update.
- **Light animations** for the app's own parts (never the page), short enough to keep a slow phone
  smooth, and a light vibration on long presses and tab dragging (can be turned off).
- **Hide cookie pop-ups**: one switch (Settings → Ad blocking) for uBlock Origin's cookie-notice
  lists.
- **Light, dark, or dark at night** (7 pm to 7 am), with a calm palette in both, and an **accent
  colour** of your choice. Sites that support dark mode follow it; so do error pages and reader
  view.
- **Text size** for all sites, and **zoom** even on sites that turn it off.
- **Tuned for a 2 GB phone**: uBlock Origin runs in its own process, filter lists update only
  while the app is in the background, the web cache is capped, and Firefox's own Safe Browsing
  lists are off (uBlock Origin covers them). Silent videos don't start by themselves (YouTube
  feed previews), and the app's own toolbar is redrawn as little as possible while a page scrolls.
  All tabs share one engine process for pages, decoded images are capped at 48 MB, and when the
  phone runs low on memory, or a minute after Swift goes to the background, only the open (or
  playing) tab stays loaded.
- **Problem reports**: engine crashes and Android closing Swift (usually for lack of memory) are
  noted (not when the phone was restarted or Swift updated), and Settings → Send a problem report
  shares them with the app's log (nothing is sent by itself).
- **Lighter pages**: frosted-glass effects (CSS `backdrop-filter`) are off, sites that offer a
  version without decorative animations get it, and YouTube's and TheFlixer's large blur effects
  are left out. Each of these makes scrolling several times more work on a slow phone.
- **Start-up screen**: the logo while the app starts, then the open page's last picture while it
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
| `.../vpn` | The VPN: WireGuard tunnel for Swift only, locations, encrypted setup files |
| `.../ui` | Activities, home page, tab grid, settings, bottom sheets and dialogs |
| `app/src/main/assets/extensions/ublock` | uBlock Origin 1.75.0, patched by `tools/update_ublock.py` |
| `app/src/main/assets/extensions/helper` | Small built-in extension used by the app |
| `tools/` | uBlock Origin update and patch script |

To update uBlock Origin, run `python tools/update_ublock.py --version X.Y.Z`. If
`tools/ublock-patch/swift-bridge.js` changes, also bump `UBO_BRIDGE_REV` in `Engine.kt`.

## Licenses

- GeckoView is MPL 2.0.
- The WireGuard tunnel library (`com.wireguard.android:tunnel`) is Apache 2.0.
- The QR scanner (`com.journeyapps:zxing-android-embedded`, with ZXing) is Apache 2.0.
- uBlock Origin is GPLv3; the bundled copy is modified by adding `js/swift-bridge.js`, two
  permissions and one web-accessible page (its "page blocked" screen) to its manifest, and the
  storage and picker tweaks listed in `tools/update_ublock.py`.
