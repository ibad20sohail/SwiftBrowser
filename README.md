# Swift

A lightweight Android browser with uBlock Origin built in. It is made for watching videos on
ad-heavy sites on an old phone (Moto E4, Android 7.1.2).

## Download

**[Download Swift 3.0.4 (APK)](https://github.com/ibad20sohail/SwiftBrowser/releases/download/v3.0.4/Swift-3.0.4.apk)**
for Android 7 and newer (32-bit ARM, about 172 MB). It installs over an earlier Swift and keeps
tabs and settings. From 1.4 on, Swift can also update itself: Settings → About Swift → Check for
updates.

Older versions: [all releases](https://github.com/ibad20sohail/SwiftBrowser/releases). The APKs
are attached to the releases because GitHub doesn't accept files over 100 MB in the repository.

## Features

Swift 2.0 redid every screen in one consistent look: the same rounded sheets, rows, buttons and
short animations everywhere, a theme that can turn dark at night, plain words instead of technical
ones, and a short welcome on the first start.

- **Colours** (2.3, Settings → Look & screen → Colours, with a live preview of light and dark):
  12 accent colours (blue, indigo, purple, pink, red, orange, amber, green, teal, cyan, brown,
  graphite) or **your own** (made with a colour maker, 2.5, and kept exactly as made), and the main colour of each mode: white,
  warm paper, cool grey or your own for light mode; grey, pure black, navy, warm dark or your own
  for dark mode. **Ready-made** mixes (Classic, Paper & teal, AMOLED, Midnight, Sunset, Forest,
  Rose) set all three at once. Bars, cards, sheets, dialogs and Swift's logo follow it; a colour
  too dark (or too light) for easy reading is adjusted. Colours are part of backups.
- **Logo** (2.6): a white comet (its tail curving in to a point, with two streaks), no letters; in
  the accent colour inside the app, and on a dark circle as the app icon (2.7).
- **Night light** (2.3, Settings → Look & screen): a warm layer over Swift, pages and videos too,
  from 7 pm to 7 am or always, in three strengths. Taps go through it.

- **New in 3.0.4**:
  - **Tabs**: pin a tab (it comes first, can't be swiped away, and stays open when you close all
    tabs); mute a tab playing sound from its card, its row or the ≡ menu (the whole tab, frames and
    Web Audio too); show the tabs as a compact list instead of picture cards; and, on a tab in a
    group, a strip of the group's tabs by the address bar to switch between them.
  - **Player**: repeat a part between two points (A-B, with marks on the seek bar); frame by frame
    for a paused video; Night sound, a compressor for watching at low volume (loud parts quieter,
    quiet voices louder), also in Settings → Video & downloads.
  - **Files**: folder sizes in the lists (and sorting by size sorts folders too); two folders side
    by side with the phone sideways, with Copy and Move going straight into the other side's folder
    ("Copy to the right"); and **network folders** (Tools): a computer's or a home storage's shared
    folders over Wi-Fi with SMB 2 and 3, read-only, with videos and music played straight from the
    share and files copied to the phone (smbj and smbj-rpc; a remembered password is
    keystore-encrypted). The storage bar shows how much pictures, videos, music and documents
    take; Camera, Download, Pictures, Movies, Music and Documents have folder icons of their own.
    In a folder the title bar stays put and the search box scrolls away, with the path pinned
    under the title bar.
  - **Look**: Swift's own font, Google Sans Flex (three fixed weights; a switch in Look & screen
    goes back to the phone's font), every text on one type scale, and one icon style: Google's
    Material Symbols Rounded, outlined, filled when something is on. Colours can come from your
    home page's picture or the phone's wallpaper (Colours → From your picture), as Material You
    does on newer Androids. Bolder headings, buttons that change shape a little when pressed,
    spring motion, a background for the home page (a gradient, your accent colour or your own
    picture), grouped rounded rows and big titles that shrink as you scroll in Settings, History,
    Downloads and Bookmarks, one set of four corner sizes, a tick in switches, a new loading
    spinner, and rows that fade at their ends when there's more.
  - **Address bar**: a website's own colour (its theme-color) colours the bar (Look & screen →
    Website colours in the address bar), and a floating style (Address bar style → Floating):
    a rounded bar with the page showing around it at the bottom.
  - **Player and reader**: thicker seek bars with a slim handle (wavy while music plays), music in
    the colours of its album cover, a play button that turns into pause, and a sepia page in reader
    view (Aa → Page colour). The home-screen widget follows Swift's light or dark look and accent.
- **New in 3.0.3**:
  - **Player**: previous and next beside play (the 10-second jumps stay on a double tap, and taps in a
    row add up: +10, +20, +30 s). Sound and subtitles, the ⋮ menu, speed, sleep, turning and the
    playlist open in a panel beside the video (under it when upright), without dimming it, so the
    video stays in view. Subtitle timing for every subtitle, the video's own too, and sound timing,
    in steps of 0.1 s; each video keeps its timing and the tracks chosen. An equalizer with the
    phone's presets and bass boost, pinch to fill the screen, an Up next card before the next video,
    and subtitles you can drag up or down.
  - **Continue in Files**: videos and music from the phone left half-way show in Files (after
    Favourites); the home page's Continue keeps web videos, articles and documents read from a link.
    The home page shows the shortcuts first again.
  - **Files**: video pictures show how much was watched, with a tick on finished ones; hold a file
    and slide to select many; the search box stays at the top while the title bar and the path
    scroll away.
  - **Tabs**: double-tap the tab button for the tab you were on before; tab pictures stay inside
    their rounded cards; a tab whose picture would be blank (a playing video) shows its site's icon.
    Long pages get a fast scroll handle at the right edge.
  - **Look and feel**: messages in the chosen colours (they were charcoal in both modes), switches
    that slide, buttons that spring and tick, names on hold for icon buttons, picture editor icons
    only, and no flash of the phone's bars as Settings or Files open in full screen.
  - **Downloads**: a progress ring round the menu button, pictures of finished videos, and time left
    for website videos.
- **New in 3.0.2**:
  - **A new player**, after VLC's: the times over a seek bar as wide as the screen; back and on
    10 seconds beside play (with a playlist on a narrow screen, previous and next instead); over a
    video a light ring for play, and shades top and bottom so the controls read over any picture.
    A tap in the middle shows or hides the controls at once; a double tap on a side jumps, and says
    so on that side. A speed, sleep timer or subtitle timing that is on shows as a chip under the
    title (tap to change it, hold to turn it off). Sound and subtitles are one sheet and one button.
  - **Tabs stay loaded while you use Swift**: opening Settings, Files or the player no longer
    unloads the other tabs. They are unloaded a minute after you leave Swift, and Android's memory
    warnings count while Swift is on screen only when memory is really low. Right now says when
    tabs were last unloaded, and why.
  - **DASH videos** (.mpd, used by many video sites): found on pages, played in Swift's player, and
    downloaded as one MP4 (the best H.264 up to 1080p with its AAC sound, in the phone's language
    when there is a choice). Live, copy-protected and multi-part streams say why they can't be
    saved. A stream's progress weighs its sound by its size, so it no longer jumps to half way.
  - **Home page order** (Settings → Home & news): Continue now comes first, then the shortcuts and
    the news; any order can be chosen, and nothing is left out.
  - **Licence**: Swift's own code is under the GNU GPL, version 3 or later (see [LICENSE](LICENSE)).
- **New in 3.0.1**:
  - **Incognito leaves nothing behind**: site settings changed in an incognito tab last until the
    incognito tabs close; they are no longer saved or put in backups.
  - **Keep loaded**: hold a tab in the tab grid and choose Keep loaded; Swift never unloads it to
    save memory (Android itself still can, if the phone runs out). A lower number of tabs kept
    loaded applies at once, and the idle check sleeps while Swift is out of sight.
  - **Right now** (Settings → Speed & memory): free memory, Swift's own, tabs loaded and recent
    page engine crashes, measured when you look. **Security status** (Privacy & security): what is
    on, and what Swift has checked (uBlock Origin loaded, the VPN's server answering).
  - **Videos**: website videos play in Swift's player again, and downloads Swift starts no longer
    close it (both stopped the app since 3.0); Videos on this page has a play button; a desktop
    tab's videos and downloads go as desktop; downloading the same address again shows the one you
    have first; detected video addresses are checked.
  - **Player**: a subtitle language to show first (Settings → Files & media), the subtitle timing
    kept for each video, and a note when a video is more than the phone's chip is made for.
  - **Backups** carry a format number (a newer Swift's backup is refused, not half read) and a
    restore says exactly what it added.
  - **Truthful site settings**: clearing a site's data says it happens in every profile, and a
    site's own Balanced or High level says it only changes pop-ups and redirects there.
- **New in 3.0** (a full check of the app: bugs, safety and privacy fixes across every screen):
  - **Pop-ups and tabs**: links that open a new tab load once in the engine's own window (before,
    a hidden second copy kept running and using memory, and sign-in pop-ups couldn't report back);
    a tab used for a long time stays loaded when you switch away; links from other apps reuse a
    tab already showing them, and Back returns to that app.
  - **Ad blocking from the first page**: after a restart, restored pages wait for uBlock Origin
    (with Load now after 8 s); a page that loaded before it was ready offers a reload, and the menu
    says "Reload to block" instead of "! blocked".
  - **Safety**: the address bar can't be faked by pages posing as a search engine and shows the
    real end of long addresses; lookalike Unicode names show as punycode; QR codes show where they
    lead first; "Open in app" links can't hand Swift's files to other apps; a page can't trap you
    in endless messages (Block dialogs from this page); sites ask before using cross-site cookies;
    Safer browsing (Settings → Privacy & security: no JavaScript JIT or WebAssembly) and "Tell
    sites a newer Firefox version" for the engine that no longer gets updates.
  - **Privacy**: incognito pages, the Private folder and saved passwords stay out of screenshots
    and recent apps (incognito can allow them); no search suggestions in incognito; incognito
    downloads, saved pages and their notifications stay private; logs and problem reports name no
    addresses or files (a preview, sites left out unless you choose); backups never carry the
    Private folder's PIN or file list.
  - **Files**: Compress can't empty the file it packs; moves delete only what was copied; a copy
    cut short never looks complete; the Cleaner always keeps one copy of duplicates; a shorter
    recycle bin time asks first; moved photos stay in the Gallery; booby-trapped archives can't
    hang Files; Wi-Fi transfer goes on in the background and locks out code guessing.
  - **Private folder**: a PIN stays in charge (adding a screen lock no longer opens or replaces
    it); Forgot PIN starts a 24-hour wait; wrong PINs wait longer each time.
  - **Documents**: password-protected PDFs open again (the password box, Go to page and Details
    work on the document screen), scanned PDFs (JPEG 2000, JBIG2) show their pages, Word files
    that are really RTF or HTML open, big sheets show their first rows, and documents can't load
    anything from the web.
  - **Downloads and torrents**: "Remove from list" keeps the files; HLS videos with separate sound
    download with sound; converting to MP4 never loses the only copy; downloads open in Swift's
    player; torrents don't announce themselves on the local network, can use encrypted
    connections only, and look up a magnet's files only after a tap.
  - **Player**: website videos load through the engine (newer certificates, the page's cookies);
    Urdu, Arabic and other subtitles are read in the right encoding (or choose one); the volume
    boost shows a badge, resets for each file and stays gentler with headphones.
  - **VPN**: if it was on, it comes back by itself after an update or restart, with pages waiting.
  - **Google account on each profile**: the profiles list, the profile button and the home page
    show the Google account signed in to each profile, with its name and photo (read from
    Google's own pages, never in incognito).
  - **Settings**: search matches whole words, history shows everything (more as you scroll),
    Clear browsing data remembers its time range and asks before clearing all time, update
    downloads are checked to be signed by Swift, and About lists every open-source part.
- **New in 2.9.2**:
  - **Torrents** (libtorrent): magnet links on pages, .torrent files and "Open with Swift" open a panel
    with the torrent's files (junk such as samples, .txt and .exe unticked, a warning for Windows
    programs and 10-bit videos, a free space check). They download in Downloads with progress,
    speed, peers and time left, go on after a restart, and stop sharing once complete. They pause
    while Swift's VPN is on (Proton's free servers refuse torrents), wait for Wi-Fi (at first),
    pause on low battery and when storage runs out. Settings → Video & downloads → Torrents: how
    many at once, speed limits, Wi-Fi only, only while charging, at night only, extra trackers.
  - **Downloads**: filters (All, Videos, Torrents, Files), a card for a copied magnet link, Pause all
    and Play in the notifications, and the phone stays awake while something downloads.
  - **Player**: Dolby (AC-3, E-AC-3, TrueHD) and DTS sound through FFmpeg; a clear message for 10-bit
    HEVC videos; volume boost up to 200%; subtitle colour and background; screenshots; subtitles
    from a Subs folder next to the video.
  - **Files**: Free up space also lists what torrents leave behind.
- **New in 2.9.1**:
  - **Files**: the search box and the folder path scroll away with the list (more room for files);
    they come back at the top of the list, and stay put in an empty or short folder.
  - **Picture viewer**: the bottom buttons are icons only; holding one shows its name.
- **New in 2.9**:
  - **Media player** (Files, "Open with Swift"): Android's Media3 player with swipe gestures
    (brightness, volume, seeking), double-tap skips, hold for 2×, lock, speed, picture size, turning,
    subtitles (a same-name .srt/.vtt/.ass, or pick one; size and timing), sound and subtitle tracks,
    the folder as a playlist, repeat, shuffle, sleep timer, going on where you stopped (also in the
    home page's Continue), and music in the background with notification and lock-screen controls.
  - **Picture viewer**: crop and turn (save a copy or replace), details (camera, settings, place),
    thumbnail strip, slideshow, moving GIFs, sharp zoom, set as wallpaper or contact photo; Swift can
    open pictures from other apps.
  - **Files**: a recycle bin (30 days, Undo, restore), Free up space (large files, duplicates, app
    files, empty folders, Swift's leftovers), a Private folder behind the screen lock (or a PIN),
    Wi-Fi transfer (a page on your computer, with a code), Recent documents on the start page, and
    animations. Settings → Files & media (also from Files' ⋮).
  - **Documents** are found in Files now: the Documents screen, its icon and menu button are gone
    (documents still open in the full-screen viewer).
  - **Swift player for website videos** (Settings → Video & downloads, off at first): a button on a
    playing video opens it in Swift's player.
  - **Sort downloads into folders** (off at first): Videos, Music, Pictures, Documents, Apps.
  - **Fixes**: panels no longer close when you scroll back up; deleted files leave Continue,
    Downloads and the recent lists.
- **New in 2.8**:
  - **Documents on a screen of their own**, not in a tab: full screen over whatever opened them
    (Documents, Files, Downloads, a site, another app's "Open with"), with a Back arrow; Back closes
    the document's panels first, then returns where you were.
  - **Documents look as they were made**: Word as pages (white paper, its fonts, colours, tables and
    pictures; Reading view is a switch), PDFs and Excel in their own colours, in dark mode too; only
    the viewer's bars follow your colours. Old Word files (.doc, before 2007) now show as their pages
    (fonts, colours, tables, pictures) and old PowerPoint files (.ppt) as their slides, laid out, with
    speaker notes, read on the phone (Apache POI 5.0 for Word, Swift's own reader for PowerPoint).
  - **Pictures, videos and music from Files** open in Swift's own viewer: zoom and swipe through a
    folder's pictures, play videos and songs (the next song follows).
  - **Documents screen**: Recently opened as page previews, list or grid, hold to select several
    (share, star, delete), Details, smaller filter chips, blank previews replaced by the file's mark.
  - **Files**: a start page with storage bars, categories, recent files and favourite folders; grid
    view with thumbnails; checkboxes when selecting; Back goes up one folder at a time.
  - **Smaller headers** with room above the search box (Documents, Files, History, Bookmarks,
    Downloads), and Swift's logo is round inside the app too, like its icon.
  - **Clear browsing data**: choose dates (from a day to a day), see how much each item takes for
    that time (MB, or how many), the total on the Clear button, and delete the downloaded files too.
  - **Settings rearranged**: Clear browsing data, When you quit and Backup are in **Storage & data**;
    **Privacy & security** keeps secure connections, secure DNS, cookie banners, passwords and site
    settings. "Downloads kept inside Swift" is gone.
- **New in 2.7**:
  - **Files**, a file manager (≡ menu, its own icon, Swift's icon and the widget): phone storage, the
    SD card, Downloads; copy, move, rename, delete, new folder, share, properties; archives (ZIP, 7z,
    RAR, TAR, GZ, BZ2, XZ) open like folders, extract all or some, and ZIP or 7z can be made, with a
    password for ZIP. RAR5 can't be opened, and RAR can't be made (closed format).
  - **Round, dark app icon** (and the Documents and Files icons, and Swift's shortcuts).
  - **Start screen in your colours**: Swift draws it itself (Android's own could only be white),
    builds the browser behind it, and the logo glides into the home page's.
  - **Home page**: smaller shortcuts, five to a row (up to 15); hold one and the others make room as
    you move it. The profile in use shows under the logo; tap it to change profile.
  - **Documents and Files as their own apps** in the app list (Settings → Look & screen can hide
    them), and on Swift's icon: New tab, Incognito, Files, Documents and the last document read.
  - **Pages always drawn with the graphics chip** (the CPU drawing option is gone: on the Moto E4 the
    chip turned out faster on heavy sites).
  - **Background tabs held back**: their scripts get much less of the shared engine process (timers
    at most every 10 s), and tabs not opened for 30 minutes let go of their page, whatever the
    number of tabs kept loaded.
  - **Stuck and crashed pages**: a page whose script stops responding offers Stop; a crashed page
    loads again by itself once.
  - **Search shortcuts removed** ("yt cats" no longer searches YouTube; typing searches with your
    search engine, as before 2.6).
  - **Logs**: a file a day, the last 3 days kept, written as things happen; engine crashes with
    their notes and where they crashed, freezes of Swift's screen, memory every minute, Android's
    memory warnings, page loads, videos that fail or stall, and the engine's own warnings and
    errors. Site names only, never addresses; nothing of the engine's while incognito is open.
    Settings → About → Send a problem report attaches them.
- **New in 2.6**:
  - **Clear browsing data by time** (Settings → Storage & data since 2.8, or Clear data in the ≡ menu): the
    last hour, 24 hours, 7 days, 4 weeks or all time, and what: history, cookies and site data,
    saved pictures and files, the downloads list, the documents list, where you left off, site
    settings, saved passwords. Cookies for a time are cleared for the sites visited then (the
    engine can't clear them by time). **Quit** in the ≡ menu closes Swift and clears what you chose
    in Settings → When you quit (open tabs too, if you like).
  - **Saved passwords**: after you sign in, Swift offers to keep the password (Save, Not now, Never
    for this site) and fills it in next time. They are encrypted with a key that stays in Android's
    keystore, each profile has its own, they aren't in backups, and incognito tabs never save one.
    Settings → Saved passwords shows them after your screen lock: copy, show or delete (with Undo).
  - **Translate** (≡ → Translate): Firefox's translation engine on the phone itself; each language
    downloads once (about 20 MB). Pages in another language can offer it; downloaded languages can
    be deleted in Settings → Browsing & tabs.
  - **Selected text**: Search, Share and Listen next to Copy. The bar no longer stays on screen
    over other pages.
  - **Address bar**: completes the address as you type ("you" → youtube.com, from your history and
    bookmarks), and your own search engine. (Search shortcuts, "yt cats", were removed in 2.7.)
  - **Bookmarks**: folders, search, and **Import** from Chrome or Firefox (their bookmarks file).
  - **Dark page** per site (Site settings): turns a site without a dark mode dark; pictures and
    videos stay as they are. Off by default.
  - **Home screen widget** (search, incognito, scan a QR code) and a **New tab** quick settings tile.
  - **Data used** per day on mobile data and Wi-Fi (Settings → Speed & memory).
  - **QR code** of the page in the ≡ menu, to open it on another phone or share as a picture.
  - Incognito keeps its documents and downloads in memory only; dialogs from a tab that isn't on
    screen wait for it and say which site asks; Undo for more removals; faster typing in the
    address bar, start-up, tab switching and scrolling the news; lighter on the battery.
- **Firefox engine (GeckoView 143)**: the newest engine that still runs on Android 7.
- **Documents** (2.4): PDF, Word (.docx), Excel (.xlsx, .xls, .csv, .ods), PowerPoint (.pptx),
  e-books (.epub) and text files open in Swift's own viewer, offline; nothing is uploaded. Old .doc
  and .ppt files (and .odt, .odp, .rtf) show their text.
  - **Documents** in the ≡ menu and on the home page: the documents opened in Swift, then every
    document on the phone, newest first, with page previews, filters (PDF, Word, Excel,
    PowerPoint, E-books, Text) and search. Without storage access, **Browse files** uses Android's
    own file picker.
  - **Open with Swift** from other apps (WhatsApp, Gmail, file managers), and downloaded documents
    open in the viewer. A link to a document on a website offers Open, Download, and for Office
    files **Exact view** (Microsoft's free online viewer, which gets the link).
  - The viewer: find in the document, text size or zoom, **Listen** (read aloud), dark pages in
    dark mode, Share and Open with another app. PDFs load only the parts on screen, so big ones
    open quickly. Where you stopped is remembered; unfinished documents are in **Continue**.
- **Documents, better** (2.5):
  - **Reading mode**: Swift's toolbar and the viewer's bar go away while reading (scroll down, or
    tap the page); scrolling up or a tap brings them back. A page counter ("Page 3 of 20") and a
    handle on the right edge to scroll through long documents quickly.
  - **PDFs**: go to a page (tap the counter), the PDF's own contents, page previews, double tap or
    two fingers to zoom, with the text drawn again sharp at every size. **Fill & sign**: fill in
    forms, highlight, draw (a signature) or add text, and save a copy to Downloads.
  - **E-books** turn page by page (tap the sides or swipe), with a choice of font, line spacing,
    or scrolling instead, and how many pages are left in the chapter.
  - **PowerPoint**: a full-screen slideshow (swipe or tap the sides) and the speaker notes.
  - **Excel**: cell colours, bold and italic, merged cells, frozen rows and columns, the sheet's
    own column widths, and find in the whole sheet. Big workbooks open faster: only the sheet on
    screen is read.
  - **Save as PDF** and **Print** for Word, Excel, PowerPoint and text files (PDFs print as
    they are). **ZIP files** list what they hold; documents in them open in their own tab.
  - **Listen** carries on through all the pages and chapters; in PDFs the part being read is marked.
  - Documents on websites open **without a copy** in Downloads; **Save a copy** keeps one.
  - **Documents screen**: star documents (they stay at the top), sort by date, name or size, look
    through one folder, files on the memory card are marked "SD card", rename and delete (with
    Undo), and **Add to home screen**. The last two documents are on Swift's icon (hold it).
  - Each document keeps its own text size; password-protected Office files say so.
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
- **Incognito tabs**: no history, and cookies and site data go away when the last one closes,
  and so do site settings changed in them (3.0.1: they used to be saved like any other).
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
  site), and clears its cookies and data. Settings → Privacy lists the sites. Changed from an
  incognito tab, they last until incognito closes; Lite mode, Dark page and ad blocking then
  count for the site's normal tabs too, as one engine part serves every tab.
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
| `.../download` | Download manager, HLS and DASH downloader, TS→MP4 remuxer, notification service |
| `.../vpn` | The VPN: WireGuard tunnel for Swift only, locations, encrypted setup files |
| `.../ui` | Activities, home page, tab grid, settings, bottom sheets and dialogs |
| `app/src/main/assets/extensions/ublock` | uBlock Origin 1.75.0, patched by `tools/update_ublock.py` |
| `app/src/main/assets/extensions/helper` | Small built-in extension used by the app |
| `.../helper/docs` | The document viewer, with pdf.js, docx-preview, SheetJS, pptx-preview, epub.js and JSZip (`lib/LICENSES.txt`) |
| `.../docs` (Kotlin) | Opening documents, the viewer's file channel, page previews |
| `tools/` | uBlock Origin update and patch script |
| `app/src/test` | Tests of the plain logic (3.0), run on the PC: `gradle :app:testEmuDebugUnitTest` |

To update uBlock Origin, run `python tools/update_ublock.py --version X.Y.Z`. Since 3.0 it stops
unless the download matches the hash addons.mozilla.org publishes (`--skip-amo-check` to override).
If `tools/ublock-patch/swift-bridge.js` changes, also bump `UBO_BRIDGE_REV` in `Engine.kt`.

## Licenses

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
  Zip4j, Guava, SnakeYAML, SparseBitSet, pdf.js, docx-preview, SheetJS, Readability.
- MIT: libtorrent4j, JSZip, SLF4J. ISC: pptx-preview. BSD 2-Clause: epub.js. XZ for Java is
  public domain; Junrar follows the UnRAR licence.
- Google Play services (brought in by GeckoView) is under the Android SDK licence.
