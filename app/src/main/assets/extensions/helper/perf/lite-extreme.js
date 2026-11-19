/*
 * Swift Lite mode, Extreme (3.0.9): the site's silent "GIF" videos stay still until tapped
 * (lite-high.js reads this), and moving drawings (SVG animations) stand still, like GIF pictures.
 * 3.0.13: lite-high.js also lets the page's script animations run at 15 frames a second (30 at High).
 */
(function () {
  'use strict';
  if (window.swiftLiteExtreme) { return; }
  window.swiftLiteExtreme = true;

  // An SVG drawing's own animations (SMIL; CSS ones are off from Medium).
  function still(svg) {
    try { if (typeof svg.pauseAnimations === 'function' && !svg.animationsPaused()) { svg.pauseAnimations(); } } catch (e) { }
  }
  function stillAll(root) {
    if (root.tagName && root.tagName.toLowerCase() === 'svg') { still(root); }
    if (root.getElementsByTagName) { for (const s of root.getElementsByTagName('svg')) { still(s); } }
  }
  new MutationObserver(records => {
    for (const r of records) {
      for (const n of r.addedNodes) { if (n.nodeType === 1) { stillAll(n); } }
    }
  }).observe(document, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', () => stillAll(document), { once: true });
})();
