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
