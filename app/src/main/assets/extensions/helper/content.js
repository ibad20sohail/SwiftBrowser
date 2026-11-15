'use strict';

// Runs in every frame. Reports real link taps (so the app can tell a link the
// user tapped from a script hijacking the tap), applies the playback speed and
// (3.0.4) mutes a muted tab, does the full-screen video gestures (skip, hold for
// double speed), reports where long videos are so the app can offer to continue
// them, and, in the top frame, tells the app the page's icon, (3.0.4) the colour
// it asks for its bar and, on Google's pages, which account is signed in. 3.0.11: it also tells the
// app when a form with an address is sent, so Swift can offer to keep it.
(function () {
  if (window.__swiftHelper) { return; }
  window.__swiftHelper = true;

  const NATIVE_APP = 'swift';
  const isTop = window === window.top;
  let port = null;
  let tabId = -1;
  let rate = 1;
  // 3.0.4: the tab is muted. Only what Swift muted is unmuted again (a video the page muted
  // itself stays so), and a page that unmutes its player is muted again, a few times at most.
  let muted = false;
  const silenced = new WeakSet();
  const unmutedByPage = new WeakMap();

  function hello() {
    try {
      port.postMessage({ type: 'hello', top: isTop, tabId, url: location.href });
    } catch (e) {
      port = null;
    }
  }

  function connect() {
    if (port !== null) { return port; }
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
      port = null;
      return null;
    }
    port.onMessage.addListener(onNative);
    port.onDisconnect.addListener(() => { port = null; });
    hello();
    return port;
  }

  function send(msg) {
    const p = connect();
    if (p) {
      try { p.postMessage(msg); } catch (e) { port = null; }
    }
  }

  function videos() {
    return Array.from(document.querySelectorAll('video'));
  }

  function applyRate() {
    for (const v of videos()) {
      try { v.playbackRate = rate; } catch (e) { }
    }
  }

  // A video or song as the tab's mute wants it.
  function applyMuteTo(m) {
    if (muted) {
      if (!m.muted) {
        try { m.muted = true; silenced.add(m); } catch (e) { }
      }
    } else if (silenced.has(m)) {
      silenced.delete(m);
      try { m.muted = false; } catch (e) { }
    }
  }

  function applyMute() {
    for (const m of document.querySelectorAll('video, audio')) { applyMuteTo(m); }
  }

  function onNative(msg) {
    if (!msg || typeof msg !== 'object') { return; }
    if (msg.type === 'speed') {
      rate = Number(msg.rate) || 1;
      applyRate();
    } else if (msg.type === 'mute') {
      muted = msg.on === true;
      applyMute();
    } else if (msg.type === 'reader') {
      // Only the page itself, not its frames; the background script does the work.
      if (isTop) { browser.runtime.sendMessage({ type: 'reader-start' }); }
    } else if (msg.type === 'seek') {
      const v = fullScreenVideo();
      if (v && Number.isFinite(v.duration)) {
        v.currentTime = Math.max(0, Math.min(v.duration - 0.5, v.currentTime + Number(msg.delta || 0)));
      }
    } else if (msg.type === 'rate-hold') {
      // Held down on a full-screen video: double speed until let go.
      const v = fullScreenVideo();
      if (!v) { return; }
      if (msg.on) {
        if (v.__swiftHeldRate === undefined) { v.__swiftHeldRate = v.playbackRate; }
        try { v.playbackRate = 2; } catch (e) { }
      } else if (v.__swiftHeldRate !== undefined) {
        try { v.playbackRate = v.__swiftHeldRate; } catch (e) { }
        delete v.__swiftHeldRate;
      }
    } else if (msg.type === 'seek-playing') {
      // A headset or notification button: what plays in this frame skips.
      for (const m of document.querySelectorAll('video, audio')) {
        if (!m.paused && Number.isFinite(m.duration)) {
          m.currentTime = Math.max(0, Math.min(m.duration - 0.5, m.currentTime + Number(msg.delta || 0)));
        }
      }
    } else if (msg.type === 'fade') {
      // The sleep timer: quieter and quieter, then paused, with the volume as it was.
      const ms = Math.max(500, Number(msg.ms) || 6000);
      for (const m of document.querySelectorAll('video, audio')) {
        if (m.paused) { continue; }
        const start = m.volume;
        const t0 = performance.now();
        const step = () => {
          const f = Math.min(1, (performance.now() - t0) / ms);
          try { m.volume = start * (1 - f); } catch (e) { }
          if (f < 1 && !m.paused) {
            setTimeout(step, 100);
          } else {
            m.pause();
            try { m.volume = start; } catch (e) { }
          }
        };
        step();
      }
    } else if (msg.type === 'resume') {
      // Continue watching: the video of that length goes back to where it was left off.
      const d = Number(msg.d);
      for (const v of videos()) {
        if (Number.isFinite(v.duration) && Math.abs(v.duration - d) < 5) {
          v.currentTime = Number(msg.t) || 0;
          if (v.paused) { v.play().catch(() => { }); }
          break;
        }
      }
    }
  }

  // The video shown full screen, if it is in this frame (only one frame has it).
  function fullScreenVideo() {
    const fs = document.fullscreenElement || document.mozFullScreenElement;
    if (!fs) { return null; }
    if (fs.tagName === 'VIDEO') { return fs; }
    let best = null;
    let bestArea = 0;
    for (const v of fs.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      const area = r.width * r.height;
      if (area > bestArea) {
        bestArea = area;
        best = v;
      }
    }
    return best;
  }

  // ---------------------------------------------------------------- continue watching

  // Films and episodes, not clips or ads.
  const MIN_LENGTH = 120;
  // How often a playing video's place is reported (2.6: was 5 seconds); each report is written to
  // the phone's storage. Pausing and finishing still report at once.
  const REPORT_EVERY = 20000;
  const started = new WeakSet();
  let lastReport = 0;

  function longVideo(el) {
    return el && el.tagName === 'VIDEO' && Number.isFinite(el.duration) && el.duration >= MIN_LENGTH;
  }

  // A picture for the home page's "Continue" (2.3): the video's poster, else the page's preview picture.
  function videoPicture(v) {
    let src = v.poster || '';
    if (!src) {
      const meta = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
      src = meta ? (meta.content || '') : '';
    }
    try { src = src ? new URL(src, location.href).href : ''; } catch (e) { src = ''; }
    return /^https?:/.test(src) && src.length < 2000 ? src : '';
  }

  function reportPosition(v, ended) {
    send({ type: 'video-pos', t: ended ? v.duration : v.currentTime, d: v.duration, ended: ended === true, img: videoPicture(v) });
  }

  // Every 20 seconds while playing, and when paused or finished.
  document.addEventListener('timeupdate', ev => {
    const v = ev.target;
    if (!longVideo(v) || v.paused) { return; }
    const now = Date.now();
    if (now - lastReport < REPORT_EVERY) { return; }
    lastReport = now;
    reportPosition(v, false);
  }, true);

  // 3.0: a pause reports at once, but not more than once in a few seconds (a page pausing and playing
  // over and over made Swift write it down each time).
  const PAUSE_EVERY = 5000;
  let lastPause = 0;
  document.addEventListener('pause', ev => {
    const v = ev.target;
    if (!longVideo(v) || v.ended || v.currentTime <= 0) { return; }
    const now = Date.now();
    if (now - lastPause < PAUSE_EVERY) { return; }
    lastPause = now;
    lastReport = now;
    reportPosition(v, false);
  }, true);

  document.addEventListener('ended', ev => {
    if (longVideo(ev.target)) { reportPosition(ev.target, true); }
  }, true);

  // When a long video first plays, the app may offer to continue from where it was left off.
  document.addEventListener('playing', ev => {
    const v = ev.target;
    if (!longVideo(v) || started.has(v)) { return; }
    started.add(v);
    send({ type: 'video-start', t: v.currentTime, d: v.duration });
  }, true);

  // The page's best icon for tab cards and home page shortcuts: a PNG/ICO of
  // roughly 48-192 px if declared, otherwise the app falls back to /favicon.ico.
  function reportIcon() {
    let best = '';
    let bestScore = -1;
    for (const link of document.querySelectorAll('link[rel][href]')) {
      const rel = (link.getAttribute('rel') || '').toLowerCase();
      if (!/(^|\s)(icon|apple-touch-icon|apple-touch-icon-precomposed)(\s|$)/.test(rel)) { continue; }
      const href = link.href || '';
      if (href === '' || href.length > 100000) { continue; }
      const type = (link.getAttribute('type') || '').toLowerCase();
      if (type.includes('svg') || /\.svg(?:[?#]|$)/i.test(href) || href.startsWith('data:image/svg')) { continue; }
      const m = /(\d+)\s*x\s*(\d+)/i.exec(link.getAttribute('sizes') || '');
      const size = m ? parseInt(m[1], 10) : (rel.includes('apple-touch') ? 180 : 32);
      const score = size >= 48 ? 1000 - Math.abs(size - 96) : size;
      if (score > bestScore) {
        bestScore = score;
        best = href;
      }
    }
    send({ type: 'icon', href: best, page: location.href });
  }

  // ---------------------------------------------------------------- the page's colour (3.0.4)

  // The colour the page asks browsers to give their bar (<meta name="theme-color">), for Swift's
  // address bar: the first one the browser can read whose media (if any) matches, so a page's dark
  // colour goes with Swift's dark look (pages see that as prefers-color-scheme). Sent as written ('':
  // none), and only when it changes. Pages change it from script, so <head> is watched; a change is
  // looked at a moment later, however many came at once.
  let themeSent = null;
  let themeTimer = 0;

  function themeColor() {
    for (const meta of document.querySelectorAll('meta[name="theme-color" i][content]')) {
      const media = (meta.getAttribute('media') || '').trim();
      if (media && !window.matchMedia(media).matches) { continue; }
      const c = (meta.getAttribute('content') || '').trim();
      if (c && c.length <= 100 && CSS.supports('color', c)) { return c; }
    }
    return '';
  }

  function sendThemeColor() {
    themeTimer = 0;
    const c = themeColor();
    if (c === themeSent) { return; }
    themeSent = c;
    send({ type: 'theme-color', top: true, color: c });
  }

  function themeColorSoon() {
    if (!themeTimer) { themeTimer = setTimeout(sendThemeColor, 150); }
  }

  function watchThemeColor() {
    sendThemeColor();
    if (document.head) {
      new MutationObserver(themeColorSoon).observe(document.head, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['name', 'content', 'media'],
      });
    }
    // Swift turned light or dark: the other look's colour.
    try { window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', themeColorSoon); } catch (e) { }
    // Back from the back-forward cache: Swift forgot this page's colour when another page loaded.
    window.addEventListener('pageshow', ev => {
      if (ev.persisted) {
        themeSent = null;
        sendThemeColor();
      }
    });
  }

  // ---------------------------------------------------------------- the Google account

  // Which Google account is signed in (3.0), for Swift's profiles: its name, address and picture,
  // as Google's own bar shows them ("Google Account: Name (address)" on the account button, a link
  // to its sign-out options). A sign-in button near the top and no account: signed out.
  const GOOGLE_HOST = /(^|\.)google\.(com|co\.[a-z]{2}|com\.[a-z]{2}|[a-z]{2})$/;
  const ACCOUNT_LABEL = /^(?:[^:(]*:\s*)?(.*?)\s*\(\s*([^\s@()<>]+@[^\s@()<>]+\.[^\s@()<>]+)\s*\)$/;

  function accountIn(el) {
    const label = (el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
    const m = ACCOUNT_LABEL.exec(label);
    if (!m) { return null; }
    const img = el.tagName === 'IMG' ? el : el.querySelector('img');
    const src = img ? (img.currentSrc || img.src || '') : '';
    const photo = /^https:\/\/[a-z0-9-]+\.googleusercontent\.com\//.test(src) ? src : '';
    return { name: m[1].trim(), email: m[2], photo };
  }

  function googleAccount() {
    for (const el of document.querySelectorAll('a[href*="accounts.google.com/SignOutOptions"][aria-label*="@"]')) {
      const found = accountIn(el);
      if (found) { return found; }
    }
    // Pages whose bar has the button without that link: only with the account's picture in it.
    for (const el of document.querySelectorAll('#gb [aria-label*="@"], header [aria-label*="@"], [role="banner"] [aria-label*="@"]')) {
      const found = accountIn(el);
      if (found && found.photo) { return found; }
    }
    return null;
  }

  function signInOffered() {
    for (const a of document.querySelectorAll('a[href*="accounts.google.com/ServiceLogin"], a[href*="accounts.google.com/v3/signin"]')) {
      const r = a.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.top < 200) { return true; }
    }
    return false;
  }

  // The bar may come after the page (Gmail, Google Account): looked for again twice.
  function reportGoogleAccount(tries) {
    const found = googleAccount();
    if (found) {
      send(Object.assign({ type: 'google-account', page: location.href }, found));
    } else if (tries > 1) {
      setTimeout(() => reportGoogleAccount(tries - 1), tries === 3 ? 1500 : 3000);
    } else if (signInOffered()) {
      send({ type: 'google-account', page: location.href, out: true });
    }
  }

  // ---------------------------------------------------------------- keep playing in the background

  // With "Keep playing in the background" on, a page that is playing a video or music is not told
  // that it went out of view (another tab, another app, the screen off): many players, YouTube's
  // among them, pause themselves when that happens. Other pages are told as usual.
  // The setting is read and the document's visibility wrapped once something plays in this frame
  // (2.6): most frames (ads, trackers, widgets) never play anything, so they do no work at start.
  let keepPlaying = false;
  let pretending = false;
  let watching = false;

  function mediaPlaying() {
    for (const m of document.querySelectorAll('video, audio')) {
      if (!m.paused && !m.ended && m.readyState > 2) { return true; }
    }
    return false;
  }

  function lookChanged(look) {
    keepPlaying = !!(look && look.keepPlaying === true);
    if (!keepPlaying) { pretending = false; }
  }

  function watchVisibility() {
    watching = true;
    window.removeEventListener('play', onFirstMedia, true);
    window.removeEventListener('timeupdate', onFirstMedia, true);
    try {
      browser.storage.local.get('look').then(r => lookChanged(r && r.look), () => { });
      browser.storage.onChanged.addListener((changes, area) => {
        if (area === 'local' && changes.look) { lookChanged(changes.look.newValue); }
      });
    } catch (e) { }

    try {
      const page = document.wrappedJSObject;
      Object.defineProperty(page, 'hidden', {
        configurable: true,
        enumerable: true,
        get: exportFunction(function () { return pretending ? false : document.hidden; }, window),
      });
      Object.defineProperty(page, 'visibilityState', {
        configurable: true,
        enumerable: true,
        get: exportFunction(function () { return pretending ? 'visible' : document.visibilityState; }, window),
      });
    } catch (e) { }
  }

  // The first video or song that plays here sets it up; 'timeupdate' also catches one that was
  // already playing when it was put in the page. On the window, before the page's own listeners.
  function onFirstMedia(ev) {
    const m = ev.target;
    if (!watching && m && (m.tagName === 'VIDEO' || m.tagName === 'AUDIO')) { watchVisibility(); }
  }
  window.addEventListener('play', onFirstMedia, true);
  window.addEventListener('timeupdate', onFirstMedia, true);

  // Capture phase on the window: before any of the page's own listeners. So it is added in every
  // frame from the start (it does nothing until the page is hidden while something plays).
  window.addEventListener('visibilitychange', ev => {
    if (ev.target !== document) { return; }
    if (document.hidden) {
      if (!keepPlaying || !mediaPlaying()) { return; }
      pretending = true;
    } else if (pretending) {
      // Back on screen: the page never knew it was gone.
      pretending = false;
    } else {
      return;
    }
    ev.stopImmediatePropagation();
  }, true);

  // The sleep timer can stop playback "at the end of this video"; any media that finishes says so.
  document.addEventListener('ended', ev => {
    if (!ev.isTrusted) { return; }
    const m = ev.target;
    if (m && (m.tagName === 'VIDEO' || m.tagName === 'AUDIO') && Number.isFinite(m.duration) && m.duration > 20) {
      send({ type: 'media-ended' });
    }
  }, true);

  // For Swift's log (2.7): a video or song that fails, and one that stops to load more (once
  // every 30 s at most, with how often it happened), which is how a lagging player looks.
  let waits = 0;
  let waitSent = 0;
  // 3.0: failures too at most once every 10 s, and 10 in all, per frame (each is a line in the log).
  let errorsSent = 0;
  let errorSent = 0;
  // Swift 3.0.11: a page being left cuts its videos off, which isn't a failure worth a line (seen as
  // codes 1 and 4 within 0.1 s of each navigation in a problem report).
  let leaving = false;
  window.addEventListener('beforeunload', () => { leaving = true; }, true);
  window.addEventListener('pagehide', () => { leaving = true; }, true);
  window.addEventListener('pageshow', () => { leaving = false; }, true);
  window.addEventListener('error', ev => {
    const m = ev.target;
    if (!ev.isTrusted || !m || (m.tagName !== 'VIDEO' && m.tagName !== 'AUDIO')) { return; }
    const e = m.error;
    // MEDIA_ERR_ABORTED (1): the fetch was stopped on purpose, not a failure.
    if (leaving || (e && e.code === 1)) { return; }
    const now = Date.now();
    if (errorsSent >= 10 || now - errorSent < 10000) { return; }
    errorsSent++;
    errorSent = now;
    send({ type: 'media-error', code: e ? e.code : 0, text: e && e.message ? String(e.message).slice(0, 200) : '' });
  }, true);
  // 3.0.11: how much is loaded ahead of where it plays: the buffered range holding the playhead (the
  // last range misled when the buffer has gaps, even below zero).
  const loadedAhead = m => {
    const b = m.buffered;
    const t = m.currentTime;
    for (let i = 0; i < b.length; i++) {
      if (b.start(i) <= t + 0.5 && t < b.end(i)) { return Math.round(b.end(i) - t); }
    }
    return 0;
  };
  window.addEventListener('waiting', ev => {
    const m = ev.target;
    if (!ev.isTrusted || !m || m.tagName !== 'VIDEO' || m.currentTime < 1) { return; }
    waits++;
    const now = Date.now();
    if (now - waitSent < 30000) { return; }
    waitSent = now;
    send({ type: 'media-waiting', count: waits, t: Math.round(m.currentTime), buffered: loadedAhead(m) });
    waits = 0;
  }, true);

  // ---------------------------------------------------------------- addresses sent (3.0.11)

  // A form with an address that the page sends: its fields go to Swift, which may offer to keep the
  // address (the engine fills saved ones in, but GeckoView 143 can't ask to save one). A field is
  // known by the name the page gives it for browsers (autocomplete="postal-code"), else guessed from
  // its name, label or placeholder. Never in incognito tabs, and only forms with a street.
  const AC_FIELDS = {
    'name': 'name', 'given-name': 'given', 'additional-name': 'additional', 'family-name': 'family',
    'organization': 'org', 'street-address': 'street', 'address-line1': 'line1', 'address-line2': 'line2',
    'address-line3': 'line3', 'address-level1': 'region', 'address-level2': 'city', 'postal-code': 'postcode',
    'country': 'country', 'country-name': 'country', 'tel': 'tel', 'tel-national': 'tel', 'email': 'email',
  };
  // Autocomplete names of something else (a card, a sign-in, a part of a phone number).
  const AC_OTHER = /^(cc-|username|current-password|new-password|one-time-code|webauthn|bday|sex|url|photo|impp|transaction-|language|nickname|organization-title|honorific-|tel-|address-level[34])/;
  // Guesses, in this order: the first that fits.
  const GUESSES = [
    ['email', /e.?mail/],
    ['tel', /phone|mobile|\btel\b|cell|whatsapp/],
    ['postcode', /post.?code|postal|\bzip|pin.?code|\bplz\b|\bcep\b/],
    ['country', /country|\bnation/],
    ['region', /\bstate\b|province|region|county|prefecture/],
    ['city', /\bcity\b|\btown\b|locality|suburb/],
    ['org', /company|organi[sz]ation|business.?name/],
    ['line2', /(address|addr|line).?2|apartment|\bapt\b|suite|\bflat\b|building|landmark/],
    ['street', /street|address|\baddr|line.?1|\broad\b/],
    ['given', /first.?name|given.?name|\bfname\b|forename/],
    ['family', /last.?name|family.?name|surname|\blname\b/],
    ['name', /full.?name|your.?name|\bname\b/],
  ];
  const NOT_ADDRESS = /pass|captcha|search|coupon|promo|voucher|card|cvv|cvc|iban|otp|user.?name|user.?id|login|confirm|ip.?address|web.?address|url/;
  const SKIP_TYPES = /^(hidden|password|checkbox|radio|file|submit|button|image|reset|search|date|datetime-local|month|week|time|range|color)$/;
  // A button that sends the form ("Continue", "Place order"), for pages whose script sends it.
  const SENDS = /continue|next|save|submit|order|pay|checkout|check out|confirm|deliver|ship|proceed|done|update|sign up|register|create|add address/i;

  function hintOf(el) {
    let t = [el.name, el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label')].join(' ');
    if (el.labels && el.labels.length) { t += ' ' + (el.labels[0].textContent || '').slice(0, 80); }
    return t.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_\-[\].:]+/g, ' ').toLowerCase();
  }

  function kindOf(el) {
    if (el.tagName === 'INPUT' && SKIP_TYPES.test((el.type || '').toLowerCase())) { return null; }
    const tokens = (el.getAttribute('autocomplete') || '').trim().toLowerCase().split(/\s+/);
    const last = tokens[tokens.length - 1];
    if (AC_FIELDS[last]) { return AC_FIELDS[last]; }
    if (last && AC_OTHER.test(last)) { return null; }
    if (el.type === 'email') { return 'email'; }
    if (el.type === 'tel') { return 'tel'; }
    const hint = hintOf(el);
    if (NOT_ADDRESS.test(hint)) { return null; }
    for (const [kind, re] of GUESSES) {
      if (re.test(hint)) { return kind; }
    }
    return null;
  }

  // What the form holds: one value for each kind of field (the first one seen), or null when it
  // isn't an address (no street, or fewer than three fields filled).
  function addressIn(root) {
    const fields = root.querySelectorAll('input, select, textarea');
    if (fields.length < 3 || fields.length > 200) { return null; }
    const out = {};
    let n = 0;
    for (const el of fields) {
      // Fields that aren't shown (a trap for robots, or a step not reached) aren't the user's.
      if (el.disabled || el.getClientRects().length === 0) { continue; }
      const kind = kindOf(el);
      if (!kind || out[kind]) { continue; }
      let v = '';
      if (el.tagName === 'SELECT') {
        const o = el.options[el.selectedIndex];
        const value = o ? (o.value || '').trim() : '';
        const text = o ? (o.textContent || '').trim() : '';
        // "Select a country", "--": left as it was.
        if (!value || /^(-+|select|choose)/i.test(text)) { continue; }
        v = kind === 'country' && /^[A-Za-z]{2,3}$/.test(value) ? value : text;
      } else {
        v = (el.value || '').trim();
      }
      if (!v || v.length > 300) { continue; }
      out[kind] = v;
      n++;
    }
    return n >= 3 && (out.street || out.line1) ? out : null;
  }

  let addressSent = '';
  let addressSentAt = 0;

  function sendAddress(root) {
    const found = root ? addressIn(root) : null;
    if (!found) { return; }
    // The form's button and the form itself (or a page sending twice) say it once.
    const key = JSON.stringify(found);
    const now = Date.now();
    if (key === addressSent && now - addressSentAt < 10000) { return; }
    addressSent = key;
    addressSentAt = now;
    send({ type: 'address-form', fields: found });
  }

  if (!(browser.extension && browser.extension.inIncognitoContext)) {
    window.addEventListener('submit', ev => {
      if (ev.isTrusted && ev.target && ev.target.tagName === 'FORM') { sendAddress(ev.target); }
    }, true);
    // A page whose script sends the form: the fields around the button tapped, when it says it sends.
    window.addEventListener('click', ev => {
      if (!ev.isTrusted) { return; }
      let el = ev.target;
      if (el && el.nodeType !== 1) { el = el.parentElement; }
      const button = el && el.closest ? el.closest('button, input[type=submit], input[type=button], [role=button]') : null;
      if (!button) { return; }
      const label = (button.textContent || button.value || button.getAttribute('aria-label') || '').slice(0, 60);
      if (button.type !== 'submit' && !SENDS.test(label)) { return; }
      // Its form, or the nearest box around it with a few fields (six levels up at most).
      let box = button.closest('form');
      if (!box) {
        box = button.parentElement;
        let up = 0;
        while (box && up < 6 && box.querySelectorAll('input, select, textarea').length < 4) {
          box = box.parentElement;
          up++;
        }
        if (up >= 6) { box = null; }
      }
      if (box) { sendAddress(box); }
    }, true);
  }

  // Capture phase on window: runs before the page's own click handlers.
  window.addEventListener('click', ev => {
    if (!ev.isTrusted) { return; }
    let el = ev.target;
    if (el && el.nodeType !== 1) { el = el.parentElement; }
    const a = el && el.closest ? el.closest('a[href]') : null;
    send({ type: 'click', href: a ? a.href : '', target: a ? (a.target || '') : '', top: isTop });
  }, true);

  // When any video starts, apply the chosen speed and make sure the app can reach this frame.
  // 3.0.4: a muted tab's video or song is muted as it starts (one put in the page later too), and a
  // frame that only plays sound connects as well, so that muting the tab reaches it.
  document.addEventListener('play', ev => {
    const v = ev.target;
    if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO')) { return; }
    if (muted) { applyMuteTo(v); }
    if (v.tagName !== 'VIDEO') {
      connect();
      return;
    }
    if (rate !== 1) {
      try { v.playbackRate = rate; } catch (e) { }
    }
    send({ type: 'playing', top: isTop });
  }, true);

  // 3.0.4: a page that unmutes its player while the tab is muted has it muted again (20 times at
  // most for each video or song, so a page that keeps unmuting can't make a loop of it).
  document.addEventListener('volumechange', ev => {
    const m = ev.target;
    if (!muted || !m || m.muted || (m.tagName !== 'VIDEO' && m.tagName !== 'AUDIO')) { return; }
    const times = (unmutedByPage.get(m) || 0) + 1;
    unmutedByPage.set(m, times);
    if (times <= 20) { applyMuteTo(m); }
  }, true);

  if (isTop) {
    browser.runtime.sendMessage({ type: 'whoami' }).then(r => {
      if (r && typeof r.tabId === 'number') { tabId = r.tabId; }
      // A tap may have connected before the answer came; introduce this frame again with its tab.
      if (port !== null) { hello(); } else { connect(); }
    }, () => { connect(); });

    if (location.protocol === 'http:' || location.protocol === 'https:') {
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', reportIcon, { once: true });
      } else {
        reportIcon();
      }
      // 3.0.4: the page's colour for the address bar (incognito tabs keep their own colours).
      if (!(browser.extension && browser.extension.inIncognitoContext)) {
        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', watchThemeColor, { once: true });
        } else {
          watchThemeColor();
        }
      }
      // 3.0.8: the page's height is no longer sent (the fast scroll handle it was for is gone).
    }

    // Pages of the first account only: "/u/1/" and "authuser=1" are another one signed in beside it.
    if (location.protocol === 'https:' && GOOGLE_HOST.test(location.hostname) &&
        !/\/u\/[1-9]|[?&]authuser=[1-9]/.test(location.pathname + location.search)) {
      const start = () => reportGoogleAccount(3);
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start, { once: true });
      } else {
        start();
      }
    }
  }
})();
