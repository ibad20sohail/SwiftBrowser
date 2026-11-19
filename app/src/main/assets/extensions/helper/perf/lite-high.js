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
  // Swift 3.0.11: a (not looping) video started under the finger: it plays whenever the site starts it
  // again (after a stall, a quality change, an ad). Pausing those made players stop and start again
  // over and over (hundreds of times in one problem report), the video stalling all along.
  const started = new WeakSet();
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
    if (started.has(v)) { return; }
    if (once.has(v)) {
      // Tapped: once, then it stops again.
      v.loop = false;
      return;
    }
    if (touched(v)) {
      if (!v.loop) { started.add(v); }
      return;
    }
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

  // Swift 3.0.13: what a page's scripts animate frame by frame (requestAnimationFrame: carousels,
  // sliders, effects that follow the scrolling, canvas ads, animated drawings) runs at most 30 times a
  // second at High and 15 at Extreme. Medium already takes away what is costly to draw; on a slow
  // phone the pages' own scripts are what is left. Scrolling and videos don't go through it, so they
  // stay smooth. The page's own function is replaced in its own world (as content.js does for
  // document.hidden): every callback still runs, just on fewer frames.
  (function fewerFrames() {
    const page = window.wrappedJSObject;
    if (!page || typeof window.requestAnimationFrame !== 'function') { return; }
    const nativeRequest = window.requestAnimationFrame.bind(window);
    let queue = new Map();
    let nextId = 1;
    let waiting = false;
    let last = -Infinity;
    // Frames come every 16.7 ms: one a little early counts as on time.
    const gap = () => (extreme() ? 1000 / 15 : 1000 / 30) - 4;
    function frame(ts) {
      waiting = false;
      if (queue.size === 0) { return; }
      if (ts - last < gap()) {
        waiting = true;
        nativeRequest(frame);
        return;
      }
      last = ts;
      const due = queue;
      queue = new Map();
      for (const cb of due.values()) {
        try { cb(ts); } catch (e) { }
      }
    }
    function request(cb) {
      if (typeof cb !== 'function') { return 0; }
      const id = nextId++;
      queue.set(id, cb);
      if (!waiting) {
        waiting = true;
        nativeRequest(frame);
      }
      return id;
    }
    function cancel(id) {
      queue.delete(id);
    }
    try {
      page.requestAnimationFrame = exportFunction(request, window);
      page.cancelAnimationFrame = exportFunction(cancel, window);
    } catch (e) { }
  })();
})();
