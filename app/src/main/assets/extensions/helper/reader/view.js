'use strict';

// Shows the article the background script kept for this page (its id is after the #).

const MIN_SIZE = 14;
const MAX_SIZE = 30;
let size = Number(localStorage.getItem('size')) || 19;

function applySize() {
  document.documentElement.style.setProperty('--size', size + 'px');
}

// Font and line spacing, chosen in the app (Settings → Browsing → Reader view text).
const FONTS = { serif: 'Georgia, "Noto Serif", serif', sans: 'Roboto, "Noto Sans", sans-serif' };
const LINES = ['1.45', '1.65', '1.9'];

function applyStyle(look) {
  const root = document.documentElement.style;
  root.setProperty('--font', FONTS[look && look.readerFont] || FONTS.serif);
  root.setProperty('--line', LINES[look && Number.isInteger(look.readerSpacing) ? look.readerSpacing : 1] || LINES[1]);
  // Links and buttons in the app's accent colour (light and dark).
  if (look && look.accentLight) { root.setProperty('--accent-light', look.accentLight); }
  if (look && look.accentDark) { root.setProperty('--accent-dark', look.accentDark); }
}

browser.storage.local.get('look').then(r => applyStyle(r && r.look), () => { });
browser.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.look) { applyStyle(changes.look.newValue); }
});

// The article comes from a web page: keep its text, pictures and links, nothing that runs.
function clean(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('script, style, iframe, object, embed, form, input, button, link, meta, base').forEach(e => e.remove());
  doc.querySelectorAll('*').forEach(el => {
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase();
      const value = attr.value.trim().toLowerCase();
      if (name.startsWith('on') || name === 'style' ||
          ((name === 'href' || name === 'src' || name === 'srcset') && value.startsWith('javascript:'))) {
        el.removeAttribute(attr.name);
      }
    }
  });
  const fragment = document.createDocumentFragment();
  Array.from(doc.body.childNodes).forEach(n => fragment.appendChild(document.importNode(n, true)));
  return fragment;
}

function render(article) {
  if (!article) {
    document.getElementById('missing').hidden = false;
    // The article is gone (Swift restarted): "Original page" goes back to the page it came from.
    document.getElementById('original').addEventListener('click', ev => {
      ev.preventDefault();
      history.back();
    });
    listen.hidden = true;
    return;
  }
  document.title = article.title || 'Reader view';
  if (article.lang) { document.documentElement.lang = article.lang; }
  if (article.dir) { document.documentElement.dir = article.dir; }
  const minutes = Math.max(1, Math.round((article.length || 0) / 1200));
  document.getElementById('site').textContent = (article.siteName || '') + ' · ' + minutes + ' min read';
  document.getElementById('title').textContent = article.title || '';
  document.getElementById('byline').textContent = article.byline || '';
  document.getElementById('content').appendChild(clean(article.content || ''));
  document.getElementById('original').href = article.url;
  restorePlace(article.url, article.title);
}

document.getElementById('smaller').addEventListener('click', () => {
  size = Math.max(MIN_SIZE, size - 2);
  localStorage.setItem('size', String(size));
  applySize();
});
document.getElementById('bigger').addEventListener('click', () => {
  size = Math.min(MAX_SIZE, size + 2);
  localStorage.setItem('size', String(size));
  applySize();
});

// ---------------------------------------------------------------- read aloud

// The phone's own text-to-speech (in the app) reads the article paragraph by paragraph and tells
// this page which paragraph it is on, to mark it. It goes on with Swift in the background, with
// pause and stop in the notification.
const listen = document.getElementById('listen');
const player = document.getElementById('player');
const toggle = document.getElementById('toggle');
const rateButton = document.getElementById('rate');
const RATES = [1, 1.25, 1.5, 2, 0.75];
let rate = Number(localStorage.getItem('rate')) || 1;
let paragraphs = [];
let state = 'idle'; // idle, speaking, paused
let marked = null;
let port = null;

function send(msg) {
  if (port === null) {
    try {
      port = browser.runtime.connectNative('swift');
    } catch (e) {
      port = null;
      return;
    }
    port.onMessage.addListener(onState);
    port.onDisconnect.addListener(() => { port = null; });
  }
  try { port.postMessage(msg); } catch (e) { port = null; }
}

function onState(st) {
  if (!st || st.type !== 'tts-state') { return; }
  if (st.error) {
    setState('idle');
    mark(-1);
    alert(st.error);
    return;
  }
  if (st.speaking) {
    if (state !== 'speaking') { setState('speaking'); }
    mark(st.index);
  } else if (st.paused) {
    // Paused from the notification.
    if (state !== 'paused') { setState('paused'); }
  } else if (state !== 'idle') {
    // Read to the end, or stopped from the notification.
    setState('idle');
    mark(-1);
  }
}

function collect() {
  const all = Array.from(document.querySelectorAll(
    '#title, #content p, #content h1, #content h2, #content h3, #content h4, #content li, #content blockquote, #content figcaption'));
  // Containers holding other picked elements would be read twice.
  return all.filter(e => e.textContent.trim().length > 1 && !all.some(o => o !== e && e.contains(o)));
}

function mark(index) {
  const el = paragraphs[index] || null;
  if (el === marked) { return; }
  if (marked) { marked.classList.remove('reading'); }
  marked = el;
  if (!el) { return; }
  el.classList.add('reading');
  const r = el.getBoundingClientRect();
  if (r.top < 0 || r.bottom > window.innerHeight - 90) { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
}

function showRate() {
  rateButton.textContent = String(rate).replace(/^0\./, '.') + '×';
}

function setState(s) {
  state = s;
  const reading = s !== 'idle';
  listen.hidden = reading;
  player.hidden = !reading;
  document.body.classList.toggle('playing', reading);
  toggle.textContent = s === 'paused' ? 'Resume' : 'Pause';
  showRate();
}

// Starts from the first paragraph on screen, so you can scroll to where you want to listen.
function firstVisible() {
  for (let i = 0; i < paragraphs.length; i++) {
    if (paragraphs[i].getBoundingClientRect().bottom > 0) { return i; }
  }
  return 0;
}

listen.addEventListener('click', () => {
  paragraphs = collect();
  if (paragraphs.length === 0) { return; }
  const from = firstVisible();
  setState('speaking');
  mark(from);
  send({
    type: 'tts-start',
    texts: paragraphs.map(e => e.textContent.replace(/\s+/g, ' ').trim()),
    from,
    rate,
    lang: document.documentElement.lang || '',
    title: document.title,
  });
});

toggle.addEventListener('click', () => {
  if (state === 'speaking') {
    setState('paused');
    send({ type: 'tts-pause' });
  } else if (state === 'paused') {
    setState('speaking');
    send({ type: 'tts-resume' });
  }
});

document.getElementById('prev').addEventListener('click', () => send({ type: 'tts-skip', delta: -1 }));
document.getElementById('next').addEventListener('click', () => send({ type: 'tts-skip', delta: 1 }));

rateButton.addEventListener('click', () => {
  rate = RATES[(RATES.indexOf(rate) + 1) % RATES.length] || 1;
  localStorage.setItem('rate', String(rate));
  showRate();
  send({ type: 'tts-rate', rate });
});

document.getElementById('stop').addEventListener('click', () => {
  setState('idle');
  mark(-1);
  send({ type: 'tts-stop' });
});

window.addEventListener('pagehide', () => {
  if (state !== 'idle') { send({ type: 'tts-stop' }); }
});

// ---------------------------------------------------------------- where you stopped reading

// Each article opens where it was left, as a share of its length (the text size may have changed).
let articleKey = null;
let saveTimer = 0;

function restorePlace(url, title) {
  articleKey = url ? 'place:' + url.split('#')[0] : null;
  if (!articleKey) { return; }
  const share = Number(localStorage.getItem(articleKey));
  if (share > 0.02) {
    requestAnimationFrame(() => window.scrollTo(0, share * document.documentElement.scrollHeight));
  }
  window.addEventListener('scroll', () => {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      const h = document.documentElement.scrollHeight;
      const share = h > 0 ? window.scrollY / h : 0;
      try {
        // Near the end counts as read: next time it opens at the top.
        if (window.scrollY + window.innerHeight >= h - 40) { localStorage.removeItem(articleKey); } else { localStorage.setItem(articleKey, share.toFixed(4)); }
      } catch (e) { }
      // Swift lists articles left half-way on its home page (2.3).
      const done = window.scrollY + window.innerHeight >= h - 40;
      const picture = document.querySelector('#content img');
      const img = picture && /^https?:/.test(picture.src) && picture.src.length < 2000 ? picture.src : '';
      send({ type: 'reader-place', url: url.split('#')[0], title: title || '', share: done ? 1 : (h > 0 ? (window.scrollY + window.innerHeight) / h : 0), done, img });
    }, 400);
  }, { passive: true });
}

applySize();
showRate();
browser.runtime.sendMessage({ type: 'reader-get', id: location.hash.slice(1) }).then(render, () => render(null));
