'use strict';

// Runs in every frame. Reports real link taps (so the app can tell a link the
// user tapped from a script hijacking the tap), applies the playback speed and,
// in the top frame, tells the app the page's icon.
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
      for (const v of videos()) {
        if (!v.paused && Number.isFinite(v.duration)) {
          v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + Number(msg.delta || 0)));
        }
      }
    }
  }

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
