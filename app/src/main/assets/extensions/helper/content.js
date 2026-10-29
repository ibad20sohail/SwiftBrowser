'use strict';

// Runs in every frame. Reports real link taps (so the app can tell a link the
// user tapped from a script hijacking the tap), applies the playback speed, does
// the full-screen video gestures (skip, hold for double speed), reports where long
// videos are so the app can offer to continue them, and, in the top frame, tells
// the app the page's icon.
(function () {
  if (window.__swiftHelper) { return; }
  window.__swiftHelper = true;

  const NATIVE_APP = 'swift';
  const isTop = window === window.top;
  let port = null;
  let tabId = -1;
  let rate = 1;

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

  function onNative(msg) {
    if (!msg || typeof msg !== 'object') { return; }
    if (msg.type === 'speed') {
      rate = Number(msg.rate) || 1;
      applyRate();
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

  document.addEventListener('pause', ev => {
    const v = ev.target;
    if (longVideo(v) && !v.ended && v.currentTime > 0) { reportPosition(v, false); }
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
  window.addEventListener('error', ev => {
    const m = ev.target;
    if (!ev.isTrusted || !m || (m.tagName !== 'VIDEO' && m.tagName !== 'AUDIO')) { return; }
    const e = m.error;
    send({ type: 'media-error', code: e ? e.code : 0, text: e && e.message ? String(e.message).slice(0, 200) : '' });
  }, true);
  window.addEventListener('waiting', ev => {
    const m = ev.target;
    if (!ev.isTrusted || !m || m.tagName !== 'VIDEO' || m.currentTime < 1) { return; }
    waits++;
    const now = Date.now();
    if (now - waitSent < 30000) { return; }
    waitSent = now;
    send({ type: 'media-waiting', count: waits, t: Math.round(m.currentTime), buffered: m.buffered.length ? Math.round(m.buffered.end(m.buffered.length - 1) - m.currentTime) : 0 });
    waits = 0;
  }, true);

  // Capture phase on window: runs before the page's own click handlers.
  window.addEventListener('click', ev => {
    if (!ev.isTrusted) { return; }
    let el = ev.target;
    if (el && el.nodeType !== 1) { el = el.parentElement; }
    const a = el && el.closest ? el.closest('a[href]') : null;
    send({ type: 'click', href: a ? a.href : '', target: a ? (a.target || '') : '', top: isTop });
  }, true);

  // When any video starts, apply the chosen speed and make sure the app can reach this frame.
  document.addEventListener('play', ev => {
    const v = ev.target;
    if (!v || v.tagName !== 'VIDEO') { return; }
    if (rate !== 1) {
      try { v.playbackRate = rate; } catch (e) { }
    }
    send({ type: 'playing', top: isTop });
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
    }
  }
})();
