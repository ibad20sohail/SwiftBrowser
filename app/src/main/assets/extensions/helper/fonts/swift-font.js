'use strict';

// 3.0.5: Swift's own pages use its font, or the phone's if Swift's couldn't be read. The app's answer reaches the page only after it has started (with
// the look, or the document's details), so the last answer is kept and put on the page before its
// first paint, as reader view's colours are. Nothing kept yet: Swift's font, which is the default.

function applySwiftFont(on) {
  document.documentElement.classList.toggle('phone-font', !on);
  try { localStorage.setItem('swiftFont', on ? 'on' : 'off'); } catch (e) { }
}

try {
  if (localStorage.getItem('swiftFont') === 'off') { document.documentElement.classList.add('phone-font'); }
} catch (e) { }

// The document viewer's colours: the same idea, so its first paint is in the colours last seen (Swift's
// own arrive only after the page has started) and a page opening in the dark look isn't blue on grey.

const DOC_COLORS = {
  bg: '--bg', surface: '--surface', text: '--text', muted: '--muted', line: '--line', accent: '--accent', onAccent: '--on-accent',
  backdrop: '--backdrop', surfaceAlt: '--surface-alt', accentSoft: '--accent-soft', onAccentSoft: '--on-accent-soft',
};

function applyDocColors(colors) {
  if (!colors) { return; }
  for (const k in DOC_COLORS) { if (colors[k]) { document.documentElement.style.setProperty(DOC_COLORS[k], colors[k]); } }
}

function keepDocColors(light, dark) {
  try { localStorage.setItem('swiftDocColors', JSON.stringify({ light, dark })); } catch (e) { }
}

try {
  if (/\/docs\/view\.html$/.test(location.pathname)) {
    const kept = JSON.parse(localStorage.getItem('swiftDocColors') || 'null');
    if (kept) { applyDocColors(matchMedia('(prefers-color-scheme: dark)').matches ? kept.dark : kept.light); }
  }
} catch (e) { }
