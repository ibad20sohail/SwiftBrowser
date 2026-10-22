'use strict';

// Shows the article the background script kept for this page (its id is after the #).

const MIN_SIZE = 14;
const MAX_SIZE = 30;
let size = Number(localStorage.getItem('size')) || 19;

function applySize() {
  document.documentElement.style.setProperty('--size', size + 'px');
}

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
// this page which paragraph it is on, to mark it.
const listen = document.getElementById('listen');
const stopButton = document.getElementById('stop');
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
    mark(st.index);
  } else if (state === 'speaking') {
    // Read to the end.
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
  if (r.top < 0 || r.bottom > window.innerHeight) { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
}

function setState(s) {
  state = s;
  listen.textContent = s === 'speaking' ? 'Pause' : (s === 'paused' ? 'Resume' : 'Listen');
  stopButton.hidden = s === 'idle';
}

// Starts from the first paragraph on screen, so you can scroll to where you want to listen.
function firstVisible() {
  for (let i = 0; i < paragraphs.length; i++) {
    if (paragraphs[i].getBoundingClientRect().bottom > 0) { return i; }
  }
  return 0;
}

listen.addEventListener('click', () => {
  if (state === 'speaking') {
    setState('paused');
    send({ type: 'tts-pause' });
  } else if (state === 'paused') {
    setState('speaking');
    send({ type: 'tts-resume' });
  } else {
    paragraphs = collect();
    if (paragraphs.length === 0) { return; }
    const from = firstVisible();
    setState('speaking');
    mark(from);
    send({
      type: 'tts-start',
      texts: paragraphs.map(e => e.textContent.replace(/\s+/g, ' ').trim()),
      from,
      lang: document.documentElement.lang || '',
    });
  }
});

stopButton.addEventListener('click', () => {
  setState('idle');
  mark(-1);
  send({ type: 'tts-stop' });
});

window.addEventListener('pagehide', () => {
  if (state !== 'idle') { send({ type: 'tts-stop' }); }
});

applySize();
browser.runtime.sendMessage({ type: 'reader-get', id: location.hash.slice(1) }).then(render, () => render(null));
