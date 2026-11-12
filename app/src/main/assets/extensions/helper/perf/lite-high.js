/*
 * Swift Lite mode, High and Extreme (3.0.9): Swift decides which silent videos play (the engine lets
 * them all start at these levels; see SessionFactory's PERMISSION_AUTOPLAY_INAUDIBLE).
 *  - A video you touch plays as the site wants: a preview you hold or swipe on, the one under your
 *    finger and for a moment after you let go. Before, the engine refused those previews and showed
 *    its own play button, which (inside the site's link) opened the video instead.
 *  - A site's "GIFs" that are really short silent looping videos (Reddit, X, Giphy, Tenor, Imgur…)
 *    play once at High, like GIF pictures, and stay still at Extreme (lite-extreme.js says so). A tap
 *    on one plays it once more.
 *  - Any other silent video nobody touched doesn't start (feed previews while scrolling, autoplaying
 *    ads). Videos with sound are left to the engine: they start when you tap play.
 */
(function () {
  'use strict';
  if (window.swiftLiteHigh) { return; }
  window.swiftLiteHigh = true;

  const extreme = () => window.swiftLiteExtreme === true;
  // A looping silent video that has had its one play.
  const done = new WeakSet();
  // Tapped: it plays once more.
  const once = new WeakSet();
  // Where the finger is (or was let go), and until when a touch there counts.
  let finger = null;
  let fingerUntil = 0;
  const AFTER_MS = 1500;

  // No sound to hear: muted, silent, or (known once it has loaded that far) no sound track at all.
  function silent(v) {
    return v instanceof HTMLVideoElement && (v.muted || v.volume === 0 || (v.readyState >= 1 && v.mozHasAudio === false));
  }

  // The part of the page the video takes, or while it has no size yet, the box it sits in.
  function boxOf(el) {
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      const r = n.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) { return r; }
    }
    return null;
  }

  function touched(v) {
    if (!finger || performance.now() > fingerUntil) { return false; }
    const r = boxOf(v);
    return !!r && finger.x >= r.left && finger.x <= r.right && finger.y >= r.top && finger.y <= r.bottom;
  }

  function follow(e) {
    const t = e.touches && e.touches[0];
    if (!t) { return; }
    finger = { x: t.clientX, y: t.clientY };
    fingerUntil = Infinity;
  }
  const passive = { capture: true, passive: true };
  document.addEventListener('touchstart', follow, passive);
  document.addEventListener('touchmove', follow, passive);
  const letGo = e => {
    if (e.touches && e.touches.length > 0) { return; }
    fingerUntil = performance.now() + AFTER_MS;
  };
  document.addEventListener('touchend', letGo, passive);
  document.addEventListener('touchcancel', letGo, passive);

  document.addEventListener('play', e => {
    const v = e.target;
    if (!silent(v)) { return; }
    if (once.has(v)) {
      // Tapped: once, then it stops again.
      v.loop = false;
      return;
    }
    if (touched(v)) { return; }
    if (v.loop && !extreme() && !done.has(v)) {
      // High: a GIF video's one play, to the end.
      v.loop = false;
      return;
    }
    v.pause();
  }, true);

  document.addEventListener('ended', e => {
    const v = e.target;
    if (!silent(v)) { return; }
    done.add(v);
    once.delete(v);
  }, true);

  // A tap on a video (or on what a site lays over it) plays it once. The site's own handler goes
  // first; if the video is still paused after it, it plays.
  function videosAt(x, y) {
    const out = [];
    for (const v of document.getElementsByTagName('video')) {
      const r = v.getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom && silent(v)) { out.push(v); }
    }
    return out;
  }
  let pausedAtDown = [];
  document.addEventListener('pointerdown', e => {
    pausedAtDown = e.isPrimary ? videosAt(e.clientX, e.clientY).filter(v => v.paused) : [];
  }, true);
  document.addEventListener('click', e => {
    if (!e.isTrusted) { return; }
    for (const v of videosAt(e.clientX, e.clientY)) {
      once.add(v);
      done.delete(v);
      if (pausedAtDown.includes(v)) {
        setTimeout(() => {
          if (v.paused) {
            if (v.ended) { v.currentTime = 0; }
            v.play().catch(() => { });
          }
        }, 0);
      }
    }
    pausedAtDown = [];
  }, true);
})();
