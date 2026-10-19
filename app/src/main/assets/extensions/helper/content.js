'use strict';

// Runs in every frame. Reports real link taps (so the app can tell a link the
// user tapped from a script hijacking the tap) and applies the playback speed.
(function () {
  if (window.__swiftHelper) { return; }
  window.__swiftHelper = true;

  const NATIVE_APP = 'swift';
  const isTop = window === window.top;
  let port = null;
  let tabId = -1;
  let rate = 1;

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
    port.postMessage({ type: 'hello', top: isTop, tabId, url: location.href });
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
    } else if (msg.type === 'seek') {
      for (const v of videos()) {
        if (!v.paused && Number.isFinite(v.duration)) {
          v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + Number(msg.delta || 0)));
        }
      }
    }
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
      connect();
    }, () => { connect(); });
  }
})();
