'use strict';
// Swift's document viewer (2.4, 2.5). Swift sends the file over its private channel (a native port):
// PDFs piece by piece as pdf.js asks for them, everything else whole. Nothing leaves the phone.
// Each kind has a renderer below; `viewer` holds what the bar's buttons do for the open one.

const id = decodeURIComponent(location.hash.slice(1));
const $ = s => document.querySelector(s);
const docEl = $('#doc');

const KINDS = {
  pdf: ['PDF', '#d93025'],
  word: ['DOC', '#2b579a'],
  oldword: ['DOC', '#2b579a'],
  odt: ['ODT', '#2b579a'],
  rtf: ['RTF', '#2b579a'],
  sheet: ['XLS', '#217346'],
  slides: ['PPT', '#c43e1c'],
  oldslides: ['PPT', '#c43e1c'],
  odp: ['ODP', '#c43e1c'],
  epub: ['EPUB', '#7b3fa0'],
  text: ['TXT', '#5f6368'],
  zip: ['ZIP', '#8d6e00'],
};

let info = null;
let port = null;
let viewer = {};
let scale = 1;

// ------------------------------------------------------------------ talking to Swift

function send(msg) {
  if (port === null) {
    try {
      port = browser.runtime.connectNative('swift');
    } catch (e) {
      port = null;
      return;
    }
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => { port = null; });
  }
  try { port.postMessage(msg); } catch (e) { port = null; }
}

function onMessage(msg) {
  if (!msg || !msg.type) { return; }
  if (msg.type === 'doc-info') { start(msg); }
  else if (msg.type === 'doc-data') { onData(msg); }
  else if (msg.type === 'doc-range-data') { onRange(msg); }
  else if (msg.type === 'doc-problem') { problem(msg.text, msg.actions || []); }
  else if (msg.type === 'doc-zip-list') { if (zipWait) { zipWait(msg); zipWait = null; } }
  else if (msg.type === 'tts-state') { onTts(msg); }
}

function action(name, extra) { send(Object.assign({ type: 'doc-action', id, action: name }, extra || {})); }

function b64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) { out[i] = bin.charCodeAt(i); }
  return out;
}

function toB64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 32768) { s += String.fromCharCode.apply(null, bytes.subarray(i, i + 32768)); }
  return btoa(s);
}

/** A file made here (a filled-in PDF) goes to Swift in pieces, which saves it to Downloads. */
function sendFile(name, mime, bytes) {
  const PART = 196608;
  for (let at = 0, seq = 0; at < bytes.length || seq === 0; at += PART, seq++) {
    const end = Math.min(bytes.length, at + PART);
    send({ type: 'doc-save', id, name, mime, seq, data: toB64(bytes.subarray(at, end)), last: end >= bytes.length });
    if (end >= bytes.length) { break; }
  }
}

// The whole file, in pieces of a few hundred KB.
let whole = null;
function readAll() {
  return new Promise((resolve, reject) => {
    whole = { resolve, reject, parts: [], got: 0 };
    send({ type: 'doc-read', id });
  });
}

function onData(msg) {
  if (!whole) { return; }
  if (msg.data) {
    const bytes = b64(msg.data);
    whole.parts.push(bytes);
    whole.got += bytes.length;
  }
  if (info && info.size > 0 && !msg.last) {
    status('Opening… ' + Math.min(99, Math.round(whole.got * 100 / info.size)) + '%');
  }
  if (msg.last) {
    const all = new Uint8Array(whole.got);
    let at = 0;
    for (const p of whole.parts) { all.set(p, at); at += p.length; }
    const done = whole.resolve;
    whole = null;
    done(all.buffer);
  }
}

// PDFs: the pieces pdf.js asks for.
let pdfRange = null;
let nextReq = 1;
const askedAt = new Map();
function onRange(msg) {
  const begin = askedAt.get(msg.req);
  askedAt.delete(msg.req);
  if (msg.error) {
    problem("Swift couldn't read this PDF any more. It may have been moved, or the app it came from no longer shares it.",
      [{ label: 'Open with another app', action: 'open-with' }]);
    return;
  }
  if (pdfRange && begin !== undefined && msg.data) { pdfRange.onDataRange(begin, b64(msg.data)); }
}

// ------------------------------------------------------------------ the screen

function status(text) {
  const s = $('#status');
  s.hidden = false;
  s.classList.remove('problem');
  $('#statustext').textContent = text;
}

function ready() { $('#status').hidden = true; }

function problem(text, actions) {
  const s = $('#status');
  s.hidden = false;
  s.classList.add('problem');
  $('#statustext').textContent = text;
  const box = $('#statusactions');
  box.textContent = '';
  for (const a of actions) {
    const b = document.createElement('button');
    b.textContent = a.label;
    b.addEventListener('click', () => action(a.action));
    box.appendChild(b);
  }
}

function note(text, button, onClick) {
  const n = $('#note');
  n.textContent = text;
  if (button) {
    const b = document.createElement('button');
    b.textContent = button;
    b.addEventListener('click', onClick);
    n.appendChild(b);
  }
  n.hidden = false;
}

let toastTimer = 0;
function toast(text, ms) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms || 3000);
}

function dark() { return matchMedia('(prefers-color-scheme: dark)').matches; }

function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

function loadScript(src) {
  return new Promise((resolve, reject) => {
    if (document.querySelector('script[src="' + src + '"]')) { resolve(); return; }
    const s = document.createElement('script');
    s.src = src;
    s.onload = resolve;
    s.onerror = () => reject(new Error('Could not load ' + src));
    document.head.appendChild(s);
  });
}

function addCss(href) {
  const l = document.createElement('link');
  l.rel = 'stylesheet';
  l.href = href;
  document.head.appendChild(l);
}

/** Small things remembered on this phone (text size per document, the e-book style). */
function store(key, value) {
  try {
    if (value === undefined) { return localStorage.getItem(key); }
    localStorage.setItem(key, value);
  } catch (e) { /* private window or storage off: nothing is remembered */ }
  return null;
}

// Where you are, sent to Swift a moment after you stop (Continue on the home page, reopening).
let placeTimer = 0;
function savePlace(place, progress, total) {
  clearTimeout(placeTimer);
  placeTimer = setTimeout(() => {
    send({ type: 'doc-place', id, place: String(place), progress: Math.max(0, Math.min(1, progress || 0)), total: total || 0 });
  }, 700);
}

const PROTECTED = "This file is protected with a password. Swift can't open protected Office files: open it with another app.";

/** Office 2007+ files that are encrypted are stored inside an old-style container instead of a ZIP. */
function locked(buf) {
  if (!['word', 'sheet', 'slides'].includes(info.kind) || buf.byteLength < 8) { return false; }
  const a = new Uint8Array(buf, 0, 8);
  return a[0] === 0xD0 && a[1] === 0xCF && a[2] === 0x11 && a[3] === 0xE0 && !/\.(xls|csv|tsv)$/i.test(info.name);
}

async function start(msg) {
  if (info) { return; }
  info = msg;
  document.title = info.name;
  $('#name').textContent = info.name;
  const k = KINDS[info.kind] || KINDS.text;
  $('#badge').textContent = k[0];
  document.documentElement.style.setProperty('--kind', k[1]);
  const accent = dark() ? info.accentDark : info.accentLight;
  if (accent) { document.documentElement.style.setProperty('--accent', accent); }
  // Swift's own colours (2.6), so the viewer looks like the rest of the app.
  const colors = dark() ? info.colorsDark : info.colorsLight;
  if (colors) {
    const map = { bg: '--bg', surface: '--surface', text: '--text', muted: '--muted', line: '--line', accent: '--accent', onAccent: '--on-accent' };
    for (const k in map) { if (colors[k]) { document.documentElement.style.setProperty(map[k], colors[k]); } }
  }
  status('Opening…');
  try {
    if (info.kind === 'pdf') {
      await openPdf();
    } else if (info.kind === 'zip') {
      await RENDER.zip();
    } else {
      const buf = await readAll();
      status('Opening…');
      if (locked(buf)) {
        problem(PROTECTED, [{ label: 'Open with another app', action: 'open-with' }]);
        return;
      }
      await (RENDER[info.kind] || RENDER.text)(buf);
    }
    restoreSize();
    showButtons();
  } catch (e) {
    console.error(e);
    if (/password|encrypt/i.test(String(e && e.message))) {
      problem(PROTECTED, [{ label: 'Open with another app', action: 'open-with' }]);
      return;
    }
    problem("Swift couldn't show this file. It may be damaged, protected by a password, or in a form Swift doesn't read.",
      [{ label: 'Open with another app', action: 'open-with' }]);
  }
}

function showButtons() {
  $('#find').hidden = false;
  $('#smaller').hidden = !viewer.size;
  $('#bigger').hidden = !viewer.size;
  $('#listen').hidden = !viewer.texts;
  $('#play').hidden = !viewer.slideshow;
}

// Each document keeps its own text size (2.5).
const SIZE_KEY = 'swift-doc-size:' + id;
function restoreSize() {
  const s = parseFloat(store(SIZE_KEY));
  if (!viewer.size || info.kind === 'pdf' || !(s > 0) || s === 1) { return; }
  scale = s;
  viewer.size(scale);
}

// ------------------------------------------------------------------ Back closes what is open (2.6)

// The menu, panels, page previews, the slideshow, find and Fill & sign each take one step of
// Back: while any is open the page has one extra history entry, and Back closes the top one.
const overlays = [];
let pushed = false;
let skipPop = false;
function opened(close) {
  if (!overlays.includes(close)) { overlays.push(close); }
  if (!pushed) {
    history.pushState({ swift: 1 }, '');
    pushed = true;
  }
}
function closed(close) {
  const i = overlays.indexOf(close);
  if (i >= 0) { overlays.splice(i, 1); }
  if (!overlays.length && pushed) {
    pushed = false;
    skipPop = true;
    history.back();
  }
}
addEventListener('popstate', () => {
  if (skipPop) { skipPop = false; return; }
  pushed = false;
  const close = overlays.pop();
  if (close) { close(true); }
  // Something else is still open (a menu over the page previews): the next Back closes it.
  if (overlays.length) {
    history.pushState({ swift: 1 }, '');
    pushed = true;
  }
});

/** A question inside the page, with its own buttons (Save / Discard), unlike the browser's OK/Cancel. */
function ask(text, buttons) {
  const box = document.createElement('div');
  box.id = 'ask';
  const card = document.createElement('div');
  card.className = 'card';
  const p = document.createElement('p');
  p.textContent = text;
  card.appendChild(p);
  const row = document.createElement('div');
  row.className = 'row';
  for (const [label, run, main] of buttons) {
    const b = document.createElement('button');
    b.textContent = label;
    if (main) { b.className = 'main'; }
    b.addEventListener('click', () => { box.remove(); run(); });
    row.appendChild(b);
  }
  card.appendChild(row);
  box.appendChild(card);
  document.body.appendChild(box);
}

// ------------------------------------------------------------------ reading mode (2.5)

// While reading, Swift's toolbar and the viewer's bar go away; scrolling up or a tap brings them back.
let reading = false;
function setReading(on, auto) {
  if (reading === on) { return; }
  reading = on;
  // A box that grows under the hidden bar (PDF, sheets) scrolls along, so the page stays still.
  const box = viewer.scroller;
  const before = box ? box.getBoundingClientRect().top : 0;
  document.body.classList.toggle('reading', on);
  if (box) {
    const moved = box.getBoundingClientRect().top - before;
    if (moved) { box.scrollTop += moved; lastTop = box.scrollTop; }
  }
  send({ type: 'doc-reading', id, on, auto: !!auto });
  if (!$('#fastscroll').hidden || viewer.pageLabel) { showPill(); }
}

function canHideBars() {
  return $('#findbar').hidden && $('#menu').hidden && $('#panel').hidden && !viewer.editing;
}

/** Taps on the page (not on links, buttons or form fields): one toggles the bars, two quick ones zoom (PDF). */
let tapStart = null;
let lastTap = null;
let tapTimer = 0;
addEventListener('touchstart', e => {
  if (e.touches.length !== 1) { tapStart = null; lastTap = null; return; }
  const t = e.touches[0];
  tapStart = { x: t.clientX, y: t.clientY, time: Date.now(), target: e.target };
}, { passive: true, capture: true });
addEventListener('touchmove', e => {
  if (!tapStart) { return; }
  const t = e.touches[0];
  if (Math.abs(t.clientX - tapStart.x) > 12 || Math.abs(t.clientY - tapStart.y) > 12) { tapStart = null; }
}, { passive: true, capture: true });
addEventListener('touchend', e => {
  const s = tapStart;
  tapStart = null;
  if (!s || e.touches.length || Date.now() - s.time > 350 || interactive(s.target)) { return; }
  onTap(s.x, s.y);
}, { passive: true, capture: true });

function interactive(el) {
  return !!(el && el.closest && el.closest('a, button, input, select, textarea, label, summary, [contenteditable], #bar, #findbar, ' +
    '#menu, #panel, #sheets, #tts, #note, #status, #editbar, #pagepill, #fastscroll, #show, #pages, #pageshead, .annotationLayer section, .annotationEditorLayer'));
}

function onTap(x, y) {
  if (!$('#menu').hidden || !$('#panel').hidden || viewer.editing) { return; }
  const now = Date.now();
  if (lastTap && now - lastTap.time < 320 && Math.hypot(x - lastTap.x, y - lastTap.y) < 40) {
    clearTimeout(tapTimer);
    lastTap = null;
    if (viewer.doubleTap) { viewer.doubleTap(x, y); }
    return;
  }
  lastTap = { x, y, time: now };
  clearTimeout(tapTimer);
  tapTimer = setTimeout(() => {
    lastTap = null;
    if (String(getSelection() || '') !== '') { return; }
    setReading(!reading);
  }, viewer.doubleTap ? 320 : 0);
}

// Scrolling: down hides the bars, up brings them back; the page counter and fast-scroll handle show.
let lastTop = null;
let run = 0;
function watchScroll(el) {
  const target = el || window;
  target.addEventListener('scroll', () => {
    const top = (el || document.scrollingElement).scrollTop;
    if (lastTop !== null && !dragging) {
      const d = top - lastTop;
      if (d !== 0 && (d > 0) !== (run > 0)) { run = 0; }
      run += d;
      // As Swift's own toolbar does on websites: a finger's width down hides, a little less up shows.
      if (run > 64 && !reading && top > 60 && canHideBars()) { setReading(true, true); run = 0; }
      else if (run < -48 && reading && !viewer.holdReading) { setReading(false, true); run = 0; }
    }
    lastTop = top;
    showPill();
  }, { passive: true });
}

let pillTimer = 0;
let pillQueued = false;
function showPill() {
  if (pillQueued) { return; }
  pillQueued = true;
  requestAnimationFrame(() => { pillQueued = false; drawPill(); });
}
function drawPill() {
  const label = viewer.pageLabel ? viewer.pageLabel() : null;
  const pill = $('#pagepill');
  if (label) {
    if (pill.textContent !== label) { pill.textContent = label; }
    pill.hidden = false;
    pill.classList.remove('fade');
  }
  updateThumb();
  clearTimeout(pillTimer);
  pillTimer = setTimeout(() => {
    if (dragging) { return; }
    pill.classList.add('fade');
    $('#fastscroll').classList.add('fade');
  }, 1600);
}
$('#pagepill').addEventListener('click', () => { if (viewer.goTo) { viewer.goTo(); } });

// The fast-scroll handle: long documents, dragged along the right edge.
function scrollerEl() { return viewer.scroller || document.scrollingElement; }
const trackTop = () => (reading ? 12 : 60);
const trackRoom = () => Math.max(40, innerHeight - trackTop() - 48 - (document.body.classList.contains('sheet') && !reading ? 60 : 20));
let dragging = null;

function updateThumb() {
  const s = scrollerEl();
  const fs = $('#fastscroll');
  const room = s.scrollHeight - s.clientHeight;
  if (viewer.noFast || room < s.clientHeight * 3) { fs.hidden = true; return; }
  fs.hidden = false;
  fs.classList.remove('fade');
  if (!dragging) { $('#fastthumb').style.top = (trackTop() + (s.scrollTop / room) * trackRoom()) + 'px'; }
}

const thumbEl = $('#fastthumb');
thumbEl.addEventListener('touchstart', e => {
  e.preventDefault();
  dragging = { y: e.touches[0].clientY, top: parseFloat(thumbEl.style.top) || trackTop() };
}, { passive: false });
thumbEl.addEventListener('touchmove', e => {
  if (!dragging) { return; }
  e.preventDefault();
  const s = scrollerEl();
  const top = Math.min(Math.max(dragging.top + e.touches[0].clientY - dragging.y, trackTop()), trackTop() + trackRoom());
  thumbEl.style.top = top + 'px';
  s.scrollTop = ((top - trackTop()) / trackRoom()) * (s.scrollHeight - s.clientHeight);
}, { passive: false });
thumbEl.addEventListener('touchend', () => { dragging = null; showPill(); });

// ------------------------------------------------------------------ flowing documents

/** The elements read aloud and found in a document that flows top to bottom. */
function flowDone(box, selector) {
  ready();
  const place = Number(info.place);
  if (place > 0 && place < 1) {
    requestAnimationFrame(() => requestAnimationFrame(() => scrollTo(0, place * document.documentElement.scrollHeight)));
  }
  addEventListener('scroll', () => {
    const h = document.documentElement.scrollHeight;
    const room = h - innerHeight;
    savePlace((scrollY / Math.max(1, h)).toFixed(4), room > 0 ? (scrollY + innerHeight) / h : 1);
  }, { passive: true });
  watchScroll(null);
  viewer.pageLabel = () => {
    const room = document.documentElement.scrollHeight - innerHeight;
    return room > innerHeight ? Math.round(Math.min(1, scrollY / room) * 100) + '%' : null;
  };
  viewer.size = f => { box.style.zoom = f; };
  viewer.texts = () => {
    const els = Array.from(box.querySelectorAll(selector || 'p, li, h1, h2, h3, h4, h5, h6, pre'))
      .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li') && !e.closest('.notes'));
    return { texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()), els, from: firstVisible(els) };
  };
}

function firstVisible(els) {
  const top = reading ? 0 : $('#bar').getBoundingClientRect().bottom;
  const i = els.findIndex(e => e.getBoundingClientRect().bottom > top + 4);
  return i < 0 ? 0 : i;
}

function paragraphs(list) {
  const box = document.createElement('div');
  box.className = 'flow';
  for (const item of list) {
    if (item.heading) {
      const h = document.createElement('h2');
      h.className = 'slide-title';
      h.textContent = item.heading;
      box.appendChild(h);
    }
    for (const t of item.lines || [item]) {
      if (typeof t !== 'string' || t.trim() === '') { continue; }
      const p = document.createElement('p');
      p.textContent = t;
      box.appendChild(p);
    }
  }
  if (!box.firstChild) {
    const p = document.createElement('p');
    p.textContent = 'There is no text in this file that Swift can show.';
    box.appendChild(p);
  }
  docEl.appendChild(box);
  flowDone(box, 'p, h2');
}

function decodeText(buf) {
  let s = new TextDecoder('utf-8').decode(buf);
  if ((s.slice(0, 20000).match(/�/g) || []).length > 10) { s = new TextDecoder('windows-1252').decode(buf); }
  return s;
}

const u16 = (a, o) => a[o] | (a[o + 1] << 8);
const u32 = (a, o) => (a[o] | (a[o + 1] << 8) | (a[o + 2] << 16) | (a[o + 3] << 24)) >>> 0;
const bytesOf = c => (c instanceof Uint8Array ? c : Uint8Array.from(c || []));

const RENDER = {};

// ------------------------------------------------------------------ Word (.docx)

let lastBuf = null;
let pageLayout = false;

RENDER.word = async buf => {
  lastBuf = buf;
  await loadScript('lib/jszip.min.js');
  await loadScript('lib/docx-preview.min.js');
  await renderWord();
};

let wordFlowSet = false;
async function renderWord() {
  document.body.classList.toggle('fit', !pageLayout);
  docEl.textContent = '';
  const box = document.createElement('div');
  docEl.appendChild(box);
  await docx.renderAsync(lastBuf, box, null, {
    className: 'docx',
    inWrapper: true,
    ignoreWidth: !pageLayout,
    ignoreHeight: !pageLayout,
    breakPages: true,
    ignoreLastRenderedPageBreak: true,
    experimental: true,
    useBase64URL: true,
    renderHeaders: pageLayout,
    renderFooters: pageLayout,
    renderFootnotes: true,
    renderEndnotes: true,
  });
  fixBullets();
  if (pageLayout) {
    // Real pages, shrunk to the screen's width (pinch to look closer).
    const page = box.querySelector('section.docx');
    if (page && page.offsetWidth > innerWidth) { box.style.zoom = ((innerWidth - 8) / page.offsetWidth).toFixed(3); }
  }
  if (!wordFlowSet) {
    wordFlowSet = true;
    flowDone(box);
  } else {
    // Switching layouts: the listeners stay, the box is new.
    viewer.size = f => { box.style.zoom = f; };
    const texts = viewer.texts;
    viewer.texts = () => {
      const els = Array.from(box.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6, pre'))
        .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li'));
      return els.length ? { texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()), els, from: firstVisible(els) } : texts();
    };
    ready();
  }
}

/**
 * Word's bullets are characters of Windows fonts (Symbol, Wingdings) that phones don't have, so they
 * showed as boxes: the usual ones become ordinary bullet characters.
 */
function fixBullets() {
  const map = { f0b7: '2022', f0a7: '25AA', f0a8: '25A1', f0d8: '27A2', f0fc: '2713', f076: '2756', f0e0: '27A2', f06e: '25A0', f0b2: '25E6' };
  for (const st of document.querySelectorAll('style')) {
    const css = st.textContent;
    const fixed = css.replace(/\\(f0[0-9a-f]{2})/gi, (m, code) => (map[code.toLowerCase()] ? '\\' + map[code.toLowerCase()] : '\\2022'))
      .replace(/[-]/g, ch => String.fromCharCode(parseInt(map[ch.charCodeAt(0).toString(16)] || '2022', 16)))
      .replace(/font-family:\s*(Symbol|Wingdings)[^;"]*/gi, 'font-family: sans-serif');
    if (fixed !== css) { st.textContent = fixed; }
  }
}

// ------------------------------------------------------------------ spreadsheets

/** What SheetJS leaves out of an .xlsx: bold, italic and font colours, and frozen rows and columns. */
async function xlsxBook(buf) {
  await loadScript('lib/jszip.min.js');
  const zip = await JSZip.loadAsync(buf);
  const read = async p => { const f = zip.file(p); return f ? f.async('string') : ''; };
  const attr = (tag, name) => { const m = new RegExp('\\s' + name + '="([^"]*)"').exec(tag); return m ? m[1] : null; };
  const rels = {};
  for (const m of (await read('xl/_rels/workbook.xml.rels')).matchAll(/<Relationship\s[^>]*>/g)) { rels[attr(m[0], 'Id')] = attr(m[0], 'Target'); }
  const paths = [];
  for (const m of (await read('xl/workbook.xml')).matchAll(/<sheet\s[^>]*>/g)) {
    const t = rels[attr(m[0], 'r:id')] || '';
    paths.push(t.startsWith('/') ? t.slice(1) : 'xl/' + t.replace(/^\.\//, ''));
  }
  const styles = await read('xl/styles.xml');
  const section = name => { const m = new RegExp('<' + name + '[\\s>][\\s\\S]*?</' + name + '>').exec(styles); return m ? m[0] : ''; };
  const fonts = [];
  for (const m of section('fonts').matchAll(/<font(?:\s[^>]*)?>([\s\S]*?)<\/font>|<font\s[^>]*\/>/g)) {
    const body = m[1] || '';
    const color = /<color\s[^>]*rgb="([0-9A-Fa-f]{6,8})"/.exec(body);
    fonts.push({
      b: /<b\/>|<b\s+val="(1|true)"\s*\/>/.test(body),
      i: /<i\/>|<i\s+val="(1|true)"\s*\/>/.test(body),
      color: color ? color[1].slice(-6) : null,
    });
  }
  const xfs = [];
  for (const m of section('cellXfs').matchAll(/<xf\s[^>]*>/g)) { xfs.push(parseInt(attr(m[0], 'fontId') || '0', 10)); }
  return {
    fonts, xfs,
    async sheet(i) {
      const xml = paths[i] ? await read(paths[i]) : '';
      const styleOf = new Map();
      for (const m of xml.matchAll(/<c\s([^>]*?)\/?>/g)) {
        const s = /\bs="(\d+)"/.exec(m[1]);
        if (!s || s[1] === '0') { continue; }
        const r = /\br="([A-Z]+\d+)"/.exec(m[1]);
        if (r) { styleOf.set(r[1], parseInt(s[1], 10)); }
      }
      const pane = /<pane\s[^>]*>/.exec(xml);
      const frozen = pane && /state="frozen"/.test(pane[0]);
      return {
        styleOf,
        rows: frozen ? Math.min(20, parseInt(attr(pane[0], 'ySplit') || '0', 10)) : 0,
        cols: frozen ? Math.min(10, parseInt(attr(pane[0], 'xSplit') || '0', 10)) : 0,
      };
    },
  };
}

function lum(hex) {
  const n = parseInt(hex, 16);
  return (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
}

RENDER.sheet = async buf => {
  await loadScript('lib/xlsx.full.min.js');
  const bytes = new Uint8Array(buf, 0, Math.min(4, buf.byteLength));
  // Only the sheet on screen is read (2.5): a big workbook opens as fast as its first sheet.
  const names = XLSX.read(buf, { type: 'array', bookSheets: true }).SheetNames;
  let book = null;
  if (bytes[0] === 0x50 && bytes[1] === 0x4B && /\.(xlsx|xlsm)$/i.test(info.name)) {
    try { book = await xlsxBook(buf); } catch (e) { book = null; }
  }
  document.body.classList.add('sheet');
  document.body.classList.toggle('onesheet', names.length < 2);
  const pane = document.createElement('div');
  pane.className = 'pane';
  docEl.appendChild(pane);
  viewer.scroller = pane;
  const tabs = $('#sheets');
  tabs.hidden = names.length < 2;
  const parsed = new Map();
  let current = Math.min(Math.max(0, parseInt(info.place, 10) || 0), names.length - 1);
  let s = null;
  const BATCH = 300;

  // Found cells (see viewer.find below).
  let hits = [];
  let hitAt = -1;
  let lastQuery = '';
  let hitEl = null;
  function clearHit() {
    if (hitEl) { hitEl.classList.remove('hit'); }
    hitEl = null;
  }

  function sheetAt(i) {
    let ws = parsed.get(i);
    if (!ws) {
      const wb = XLSX.read(buf, { type: 'array', sheets: names[i], dense: true, cellDates: true, cellStyles: true });
      ws = wb.Sheets[names[i]] || {};
      if (parsed.size >= 2) { parsed.delete(parsed.keys().next().value); }
      parsed.set(i, ws);
    }
    return ws;
  }

  const colName = n => {
    let t = '';
    for (n += 1; n > 0; n = Math.floor((n - 1) / 26)) { t = String.fromCharCode(65 + (n - 1) % 26) + t; }
    return t;
  };
  const cellText = cell => (cell == null ? '' : cell.w != null ? String(cell.w) : cell.v != null ? String(cell.v) : '');

  function cellEl(r, c) {
    const key = r * 16384 + c;
    if (s.covered.has(key)) { return null; }
    const cell = (s.data[r] || [])[c];
    const td = document.createElement('td');
    td.dataset.c = c;
    const text = cellText(cell);
    td.textContent = text;
    if (cell && (cell.t === 'n' || cell.t === 'd')) { td.className = 'num'; }
    let fill = cell && cell.s && cell.s.patternType === 'solid' && cell.s.fgColor && cell.s.fgColor.rgb;
    fill = fill ? String(fill).slice(-6) : null;
    if (fill && /^[0-9A-Fa-f]{6}$/.test(fill) && !(dark() && fill.toUpperCase() === 'FFFFFF')) {
      td.style.background = '#' + fill;
      td.style.color = lum(fill) > 0.55 ? '#1c1d21' : '#f4f4f6';
    } else {
      fill = null;
    }
    if (s.extra && text !== '') {
      const xf = s.extra.styleOf.get(colName(c) + (r + 1));
      const font = xf != null ? book.fonts[book.xfs[xf]] : null;
      if (font) {
        if (font.b) { td.style.fontWeight = '700'; }
        if (font.i) { td.style.fontStyle = 'italic'; }
        if (font.color && !fill && /^[0-9A-Fa-f]{6}$/.test(font.color) && (dark() ? lum(font.color) > 0.4 : lum(font.color) < 0.8)) {
          td.style.color = '#' + font.color;
        }
      }
    }
    const m = s.merges.get(key);
    if (m) {
      if (m.rs > 1) { td.rowSpan = m.rs; }
      if (m.cs > 1) { td.colSpan = m.cs; td.style.maxWidth = 'none'; }
    }
    // Pinned when it lies within the frozen columns (a merged cell reaching past them scrolls).
    if (c + (m ? m.cs : 1) - 1 < s.frozenCols) {
      td.classList.add('frozen');
      if (s.lefts.has(c)) { td.style.left = s.lefts.get(c) + 'px'; }
      if (c === s.frozenCols - 1) { td.classList.add('splitc'); }
    }
    return td;
  }

  function rowEl(r) {
    const tr = document.createElement('tr');
    const th = document.createElement('th');
    th.className = 'row';
    th.textContent = r + 1;
    tr.appendChild(th);
    for (const c of s.cols) {
      const td = cellEl(r, c);
      if (td) { tr.appendChild(td); }
    }
    const h = s.rowInfo[r];
    if (h && h.hpx > 30) { tr.style.height = Math.min(200, Math.round(h.hpx)) + 'px'; }
    s.rowEls.set(r, tr);
    return tr;
  }

  function more() {
    const end = Math.min(s.rows.length, s.shown + BATCH);
    const frag = document.createDocumentFragment();
    for (let k = s.shown; k < end; k++) { frag.appendChild(rowEl(s.rows[k])); }
    s.body.appendChild(frag);
    s.shown = end;
  }

  /** Frozen rows and columns stay in view while the rest scrolls under them. */
  function pin() {
    if (!s || !s.table) { return; }
    const heads = Array.from(s.head.rows);
    for (const tr of heads) { for (const cell of tr.cells) { cell.style.top = tr.offsetTop + 'px'; } }
    if (heads.length > 1) { heads[heads.length - 1].classList.add('split'); }
    if (s.frozenCols > 0) {
      s.lefts.clear();
      for (const th of heads[0].cells) { if (th.dataset.c) { s.lefts.set(parseInt(th.dataset.c, 10), th.offsetLeft); } }
      for (const el of s.table.querySelectorAll('td.frozen, th.frozen')) { el.style.left = (s.lefts.get(parseInt(el.dataset.c, 10)) || 0) + 'px'; }
    }
  }

  async function show(i) {
    current = i;
    Array.from(tabs.children).forEach((b, j) => b.classList.toggle('on', j === i));
    pane.textContent = '';
    clearHit();
    hits = [];
    let ws;
    try {
      ws = sheetAt(i);
    } catch (e) {
      const p = document.createElement('p');
      p.className = 'flow';
      p.textContent = /password|encrypt/i.test(String(e && e.message)) ? PROTECTED : "Swift couldn't read this sheet.";
      pane.appendChild(p);
      s = null;
      return;
    }
    const range = ws['!ref'] ? XLSX.utils.decode_range(ws['!ref']) : null;
    const extra = book ? await book.sheet(i).catch(() => null) : null;
    const colsInfo = ws['!cols'] || [];
    const rowInfo = ws['!rows'] || [];
    const cols = [];
    if (range) {
      for (let c = range.s.c; c <= range.e.c && cols.length < 200; c++) { if (!(colsInfo[c] && colsInfo[c].hidden)) { cols.push(c); } }
    }
    const merges = new Map();
    const covered = new Set();
    for (const m of (ws['!merges'] || []).slice(0, 5000)) {
      merges.set(m.s.r * 16384 + m.s.c, { rs: m.e.r - m.s.r + 1, cs: m.e.c - m.s.c + 1 });
      for (let r = m.s.r; r <= m.e.r && covered.size < 300000; r++) {
        for (let c = m.s.c; c <= m.e.c; c++) { if (r !== m.s.r || c !== m.s.c) { covered.add(r * 16384 + c); } }
      }
    }
    const frozenRows = extra ? extra.rows : 0;
    const rows = [];
    const frozen = [];
    if (range) {
      for (let r = range.s.r; r <= range.e.r; r++) {
        if (rowInfo[r] && rowInfo[r].hidden) { continue; }
        (r < frozenRows ? frozen : rows).push(r);
      }
    }
    s = {
      ws, data: ws['!data'] || [], cols, rows, rowInfo, merges, covered, extra,
      frozenCols: extra ? extra.cols : 0, lefts: new Map(), rowEls: new Map(), shown: 0, table: null, head: null, body: null,
    };
    if (!range || (rows.length === 0 && frozen.length === 0)) {
      const p = document.createElement('p');
      p.className = 'flow';
      p.textContent = 'This sheet is empty.';
      pane.appendChild(p);
      savePlace(i, 0, names.length);
      return;
    }
    const table = document.createElement('table');
    table.className = 'grid';
    table.style.zoom = scale;
    const head = document.createElement('thead');
    const htr = document.createElement('tr');
    const corner = document.createElement('th');
    corner.className = 'row corner';
    htr.appendChild(corner);
    for (const c of cols) {
      const th = document.createElement('th');
      th.textContent = colName(c);
      th.dataset.c = c;
      if (extra && c < extra.cols) { th.className = 'frozen'; }
      // The column widths the sheet was made with.
      const w = colsInfo[c] && (colsInfo[c].wpx || (colsInfo[c].wch ? colsInfo[c].wch * 7 + 5 : 0));
      if (w > 0) {
        const px = Math.max(36, Math.min(480, Math.round(w)));
        th.style.minWidth = th.style.maxWidth = th.style.width = px + 'px';
      }
      htr.appendChild(th);
    }
    head.appendChild(htr);
    table.appendChild(head);
    s.table = table;
    s.head = head;
    s.body = document.createElement('tbody');
    table.appendChild(s.body);
    pane.appendChild(table);
    for (const r of frozen) { head.appendChild(rowEl(r)); }
    more();
    requestAnimationFrame(pin);
    pane.scrollTo(0, 0);
    // Which sheet was open, for reopening; a sheet's number isn't how far it was read (2.6).
    savePlace(i, 0, names.length);
  }

  names.forEach((name, i) => {
    const b = document.createElement('button');
    b.textContent = name;
    b.addEventListener('click', () => show(i));
    tabs.appendChild(b);
  });
  pane.addEventListener('scroll', () => {
    if (s && s.shown < s.rows.length && pane.scrollTop + pane.clientHeight > pane.scrollHeight - 800) { more(); }
  }, { passive: true });
  watchScroll(pane);
  await show(current);
  ready();
  viewer.size = f => {
    if (s && s.table) {
      s.table.style.zoom = f;
      requestAnimationFrame(pin);
    }
  };

  // Find in the whole sheet (2.5), not only the rows on screen.
  viewer.ownFind = true;
  viewer.find = (query, again, previous) => {
    const q = query.trim().toLowerCase();
    if (!s) { return; }
    if (!again || q !== lastQuery) {
      lastQuery = q;
      hits = [];
      hitAt = -1;
      if (q) {
        const all = Array.from(s.head ? s.head.rows : []).slice(1).map(tr => parseInt(tr.cells[0].textContent, 10) - 1).concat(s.rows);
        for (const r of all) {
          const row = s.data[r];
          if (!row) { continue; }
          for (const c of s.cols) {
            if (cellText(row[c]).toLowerCase().includes(q)) { hits.push([r, c]); }
          }
          if (hits.length >= 5000) { break; }
        }
      }
    }
    clearHit();
    if (!hits.length) {
      setFindCount(q ? 'None' : '');
      return;
    }
    hitAt = hitAt < 0 ? 0 : (hitAt + (previous ? -1 : 1) + hits.length) % hits.length;
    setFindCount((hitAt + 1) + '/' + hits.length);
    const [r, c] = hits[hitAt];
    const k = s.rows.indexOf(r);
    while (k >= 0 && s.shown <= k) { more(); }
    const tr = s.rowEls.get(r);
    const td = tr && tr.querySelector('td[data-c="' + c + '"]');
    if (td) {
      hitEl = td;
      td.classList.add('hit');
      td.scrollIntoView({ block: 'center', inline: 'center' });
    }
  };
  viewer.closeFind = clearHit;

  viewer.texts = () => {
    const batch = from => {
      const rows = s ? s.rows.slice(from, from + 300) : [];
      const texts = rows.map(r => s.cols.map(c => cellText((s.data[r] || [])[c])).filter(v => v !== '').join(', ')).filter(t => t);
      return { texts, els: null, from: 0, next: s && from + 300 < s.rows.length ? () => batch(from + 300) : null };
    };
    return batch(0);
  };
};

// ------------------------------------------------------------------ PowerPoint (.pptx)

/** The speaker notes of each slide, in order (pptx-preview doesn't show them). */
async function slideNotes(buf) {
  const zip = await JSZip.loadAsync(buf);
  const read = async p => { const f = zip.file(p); return f ? f.async('string') : ''; };
  const parse = s => new DOMParser().parseFromString(s, 'application/xml');
  const RNS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const ANS = 'http://schemas.openxmlformats.org/drawingml/2006/main';
  const relsOf = async path => {
    const dir = path.slice(0, path.lastIndexOf('/') + 1);
    const doc = parse(await read(dir + '_rels/' + path.slice(dir.length) + '.rels'));
    const out = {};
    for (const r of Array.from(doc.getElementsByTagName('Relationship'))) {
      out[r.getAttribute('Id')] = { type: r.getAttribute('Type') || '', target: resolve(dir, r.getAttribute('Target') || '') };
    }
    return out;
  };
  const resolve = (dir, target) => {
    if (target.startsWith('/')) { return target.slice(1); }
    const parts = (dir + target).split('/');
    const out = [];
    for (const p of parts) { if (p === '..') { out.pop(); } else if (p !== '.') { out.push(p); } }
    return out.join('/');
  };
  const pres = parse(await read('ppt/presentation.xml'));
  const presRels = await relsOf('ppt/presentation.xml');
  const notes = [];
  for (const sld of Array.from(pres.getElementsByTagNameNS('*', 'sldId'))) {
    const rel = presRels[sld.getAttributeNS(RNS, 'id')];
    const lines = [];
    if (rel) {
      const rels = await relsOf(rel.target);
      const n = Object.values(rels).find(x => /\/notesSlide$/.test(x.type));
      if (n) {
        const doc = parse(await read(n.target));
        for (const sp of Array.from(doc.getElementsByTagNameNS('*', 'sp'))) {
          const ph = sp.getElementsByTagNameNS('*', 'ph')[0];
          if (!ph || ph.getAttribute('type') !== 'body') { continue; }
          for (const p of Array.from(sp.getElementsByTagNameNS(ANS, 'p'))) {
            const t = Array.from(p.getElementsByTagNameNS(ANS, 't')).map(x => x.textContent).join('').trim();
            if (t) { lines.push(t); }
          }
        }
      }
    }
    notes.push(lines);
  }
  return notes;
}

RENDER.slides = async buf => {
  await loadScript('lib/pptx-preview.umd.js');
  const box = document.createElement('div');
  box.id = 'slides';
  docEl.appendChild(box);
  const width = Math.min(innerWidth - 12, 1100);
  const previewer = pptxPreview.init(box, { width, height: Math.round(width * 9 / 16), mode: 'list' });
  await previewer.preview(buf);
  let notes = [];
  try {
    await loadScript('lib/jszip.min.js');
    notes = await slideNotes(buf);
  } catch (e) {
    notes = [];
  }
  const slides = Array.from(box.querySelectorAll('.pptx-preview-slide-wrapper'));
  slides.forEach((slide, i) => {
    if (!notes[i] || !notes[i].length) { return; }
    const d = document.createElement('details');
    d.className = 'notes';
    const sum = document.createElement('summary');
    sum.textContent = 'Speaker notes';
    d.appendChild(sum);
    for (const line of notes[i]) {
      const p = document.createElement('p');
      p.textContent = line;
      d.appendChild(p);
    }
    slide.after(d);
  });
  flowDone(box, 'p, li');
  const firstSlide = () => {
    const top = reading ? 0 : $('#bar').getBoundingClientRect().bottom;
    const k = slides.findIndex(sl => sl.getBoundingClientRect().bottom > top + 40);
    return k < 0 ? slides.length - 1 : k;
  };
  if (slides.length > 1) { viewer.pageLabel = () => 'Slide ' + (firstSlide() + 1) + ' of ' + slides.length; }
  if (slides.length) { viewer.slideshow = () => slideshow(slides, notes, firstSlide()); }
};

/** The slideshow (2.5): one slide at a time, filling the screen; swipe, or tap the sides. */
function slideshow(slides, notes, at) {
  const show = document.createElement('div');
  show.id = 'show';
  const stage = document.createElement('div');
  stage.className = 'stage';
  show.appendChild(stage);
  const notesBox = document.createElement('div');
  notesBox.className = 'shownotes';
  notesBox.hidden = true;
  show.appendChild(notesBox);
  const bar = document.createElement('div');
  bar.className = 'bar';
  const button = (label, run) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.addEventListener('click', e => { e.stopPropagation(); run(); wake(); });
    bar.appendChild(b);
    return b;
  };
  button('Exit', close);
  button('‹', () => go(at - 1));
  const counter = document.createElement('span');
  bar.appendChild(counter);
  button('›', () => go(at + 1));
  const notesButton = button('Notes', () => { notesOn = !notesOn; render(); });
  show.appendChild(bar);
  let notesOn = false;
  let barTimer = 0;
  function wake() {
    bar.classList.remove('fade');
    clearTimeout(barTimer);
    barTimer = setTimeout(() => bar.classList.add('fade'), 4000);
  }
  function render() {
    stage.textContent = '';
    const src = slides[at];
    const clone = src.cloneNode(true);
    clone.style.margin = '0';
    clone.style.boxShadow = 'none';
    stage.appendChild(clone);
    const w = src.offsetWidth || 1;
    const h = src.offsetHeight || 1;
    const k = Math.min(innerWidth / w, innerHeight / h);
    stage.style.width = w + 'px';
    stage.style.height = h + 'px';
    stage.style.transform = 'scale(' + k + ') translate(-50%, -50%)';
    counter.textContent = (at + 1) + ' / ' + slides.length;
    const lines = notes[at] || [];
    notesButton.hidden = notes.every(n => !n || !n.length);
    notesBox.hidden = !notesOn || !lines.length;
    notesBox.textContent = '';
    for (const l of lines) {
      const p = document.createElement('p');
      p.textContent = l;
      notesBox.appendChild(p);
    }
  }
  function go(i) {
    if (i < 0 || i >= slides.length) { return; }
    at = i;
    render();
  }
  function end() {
    removeEventListener('resize', render);
    show.remove();
    slides[at].scrollIntoView({ block: 'center' });
    setReading(false);
  }
  function close() {
    end();
    closed(end);
  }
  let t0 = null;
  show.addEventListener('touchstart', e => {
    const t = e.touches[0];
    t0 = e.touches.length === 1 ? { x: t.clientX, y: t.clientY, time: Date.now() } : null;
  }, { passive: true });
  show.addEventListener('touchend', e => {
    if (!t0 || e.target.closest('.bar, .shownotes')) { t0 = null; return; }
    const t = e.changedTouches[0];
    const dx = t.clientX - t0.x;
    const dy = t.clientY - t0.y;
    const quick = Date.now() - t0.time < 700;
    t0 = null;
    if (quick && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) { go(at + (dx < 0 ? 1 : -1)); return; }
    if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
      // Where the bar was: it comes back rather than the slide changing.
      if (bar.classList.contains('fade') && t.clientY > innerHeight - 80) { wake(); return; }
      if (t.clientX < innerWidth * 0.3) { go(at - 1); } else if (t.clientX > innerWidth * 0.7) { go(at + 1); } else { wake(); }
    }
  }, { passive: true });
  addEventListener('resize', render);
  document.body.appendChild(show);
  opened(end);
  setReading(true);
  render();
  wake();
}

// ------------------------------------------------------------------ e-books (.epub)

const BOOK_STYLE = 'swift-book-style';
function bookStyle() {
  let st = null;
  try { st = JSON.parse(store(BOOK_STYLE) || 'null'); } catch (e) { st = null; }
  return Object.assign({ font: 'original', spacing: 1.6, layout: 'paged' }, st || {});
}

RENDER.epub = async buf => {
  await loadScript('lib/jszip.min.js');
  await loadScript('lib/epub.min.js');
  const book = ePub(buf);
  await book.ready;
  const area = document.createElement('div');
  area.id = 'book';
  docEl.appendChild(area);
  let style = bookStyle();
  let rendition = null;
  let where = info.place && info.place.startsWith('epubcfi') ? info.place : undefined;
  let section = 0;
  let paged = true;
  const foot = $('#bookfoot');

  function applyStyle() {
    const t = rendition.themes;
    const base = {
      img: { 'max-width': '100% !important', height: 'auto !important' },
      '.speaking': { background: 'rgba(255, 200, 0, 0.35) !important', 'border-radius': '4px' },
    };
    if (!paged) { base.body = { padding: '0 16px !important' }; }
    t.default(base);
    if (style.font !== 'original') { t.override('font-family', style.font === 'serif' ? 'serif' : 'sans-serif', true); }
    t.override('line-height', String(style.spacing), true);
    if (dark()) {
      t.override('color', '#e4e4e7', true);
      t.override('background', 'transparent', true);
    }
    t.fontSize(Math.round(scale * 100) + '%');
  }

  function relocated(loc) {
    if (!loc || !loc.start) { return; }
    where = loc.start.cfi;
    section = loc.start.index || 0;
    const n = book.spine.length || 1;
    const pct = book.locations.length() ? book.locations.percentageFromCfi(loc.start.cfi) : section / n;
    savePlace(loc.start.cfi, pct, 0);
    const d = loc.start.displayed;
    let left = '';
    if (paged && d && d.total > 0) {
      const k = d.total - d.page;
      left = k <= 0 ? 'Last page of this chapter' : k === 1 ? '1 page left in this chapter' : k + ' pages left in this chapter';
    }
    $('#bookleft').textContent = left;
    $('#bookpct').textContent = Math.round(pct * 100) + '%';
    $('#bookdone').style.width = (pct * 100).toFixed(1) + '%';
  }

  // Turned after the touch is over (the page's frame is replaced when a chapter ends).
  function turn(forward) {
    setTimeout(() => { if (forward) { rendition.next(); } else { rendition.prev(); } }, 0);
  }

  function hookInput() {
    let t0 = null;
    const xOf = (e, t) => {
      const frame = e.view && e.view.frameElement;
      return (frame ? frame.getBoundingClientRect().left : 0) + t.clientX;
    };
    rendition.on('touchstart', e => {
      const t = e.touches && e.touches[0];
      t0 = t && e.touches.length === 1 ? { x: xOf(e, t), y: t.clientY, time: Date.now() } : null;
    });
    rendition.on('touchend', e => {
      const t = e.changedTouches && e.changedTouches[0];
      if (!t0 || !t) { t0 = null; return; }
      const x = xOf(e, t);
      const dx = x - t0.x;
      const dy = t.clientY - t0.y;
      const dt = Date.now() - t0.time;
      t0 = null;
      const sel = e.view && e.view.getSelection ? String(e.view.getSelection()) : '';
      if (sel) { return; }
      if (paged && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5 && dt < 800) {
        turn(dx < 0);
        return;
      }
      if (Math.abs(dx) > 12 || Math.abs(dy) > 12 || dt > 400) { return; }
      if (e.target && e.target.closest && e.target.closest('a')) { return; }
      if (!$('#menu').hidden || !$('#panel').hidden) { return; }
      if (paged && x < innerWidth * 0.28) { turn(false); } else if (paged && x > innerWidth * 0.72) { turn(true); } else { setReading(!reading); }
    });
  }

  async function make() {
    if (rendition) {
      try { rendition.destroy(); } catch (e) { /* gone */ }
      area.textContent = '';
    }
    paged = style.layout !== 'scrolled';
    area.className = paged ? 'pane paged' : 'pane scrolled';
    foot.hidden = false;
    rendition = book.renderTo(area, paged
      ? { manager: 'default', flow: 'paginated', spread: 'none', width: '100%', height: '100%', allowScriptedContent: false }
      : { manager: 'continuous', flow: 'scrolled', width: '100%', allowScriptedContent: false });
    applyStyle();
    rendition.on('relocated', relocated);
    hookInput();
    await rendition.display(where);
  }

  await make();
  ready();
  viewer.noFast = true;
  // Pages to turn: the bars wait until a tap in the middle.
  if (paged) {
    setReading(true);
    if (!store('swift-book-hint')) {
      store('swift-book-hint', '1');
      toast('Tap the sides or swipe to turn pages. Tap the middle for the menu.', 5000);
    }
  }
  // How far through the book, for the home page and the bar: worked out when the phone has a moment.
  setTimeout(() => {
    book.locations.generate(1600).then(() => {
      const loc = rendition.currentLocation();
      if (loc && loc.start) { relocated(loc); }
    }).catch(() => {});
  }, 4000);
  viewer.size = f => rendition.themes.fontSize(Math.round(f * 100) + '%');
  viewer.chapters = () => book.navigation.toc.map(t => ({ label: t.label.trim(), go: () => rendition.display(t.href) }));
  viewer.chaptersTitle = 'Chapters';
  viewer.style = () => stylePanel(style, async st => {
    style = st;
    store(BOOK_STYLE, JSON.stringify(st));
    await make();
  });

  // Read aloud: this chapter from the page on screen, then on through the next ones.
  const chapterTexts = (fromHere) => {
    const contents = rendition.getContents()[0];
    if (!contents) { return { texts: [], els: [] }; }
    const els = Array.from(contents.document.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6, blockquote'))
      .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li'));
    let from = 0;
    if (fromHere && where) {
      try {
        const range = rendition.getRange(where);
        const node = range && range.startContainer;
        if (node) {
          const k = els.findIndex(e => e.contains(node) || (e.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING));
          from = k < 0 ? 0 : k;
        }
      } catch (e) { from = 0; }
    }
    const here = section;
    return {
      texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()),
      els,
      from,
      mark: i => {
        const el = els[i];
        if (!el) { return; }
        if (marked) { marked.classList.remove('speaking'); }
        marked = el;
        el.classList.add('speaking');
        try { rendition.display(contents.cfiFromNode(el)); } catch (e) { /* stays */ }
      },
      next: async () => {
        const sec = book.spine.get(here + 1);
        if (!sec) { return null; }
        await rendition.display(sec.href);
        return chapterTexts(false);
      },
    };
  };
  viewer.texts = () => chapterTexts(true);
};

/** The e-book's look: font, line spacing, pages or scrolling. */
function stylePanel(style, apply) {
  const panel = $('#panel');
  panel.textContent = '';
  const st = Object.assign({}, style);
  const group = (title, options, key) => {
    const h = document.createElement('h3');
    h.textContent = title;
    panel.appendChild(h);
    const row = document.createElement('div');
    row.className = 'row';
    for (const [label, value] of options) {
      const b = document.createElement('button');
      b.className = 'chip' + (st[key] === value ? ' on' : '');
      b.textContent = label;
      b.addEventListener('click', () => {
        st[key] = value;
        Array.from(row.children).forEach(x => x.classList.toggle('on', x === b));
      });
      row.appendChild(b);
    }
    panel.appendChild(row);
  };
  group('Font', [['As in the book', 'original'], ['Serif', 'serif'], ['Sans serif', 'sans']], 'font');
  group('Line spacing', [['Close', 1.35], ['Normal', 1.6], ['Wide', 1.9], ['Widest', 2.2]], 'spacing');
  group('Turning', [['Pages', 'paged'], ['Scrolling', 'scrolled']], 'layout');
  const done = document.createElement('button');
  done.className = 'done';
  done.textContent = 'Done';
  const panelOverlay = () => { panel.hidden = true; };
  done.addEventListener('click', () => {
    panel.hidden = true;
    closed(panelOverlay);
    if (JSON.stringify(st) !== JSON.stringify(style)) { apply(st); }
  });
  panel.appendChild(done);
  panel.hidden = false;
  opened(panelOverlay);
}

// ------------------------------------------------------------------ text files

RENDER.text = async buf => {
  let s = decodeText(buf);
  if (/\.json$/i.test(info.name) && s.length < 3000000) {
    try { s = JSON.stringify(JSON.parse(s), null, 2); } catch (e) { /* shown as it is */ }
  }
  const box = document.createElement('div');
  docEl.appendChild(box);
  const LIMIT = 300000;
  let at = 0;
  const add = () => {
    const pre = document.createElement('pre');
    pre.className = 'text';
    pre.textContent = s.slice(at, at + LIMIT);
    box.insertBefore(pre, button);
    at += LIMIT;
    button.hidden = at >= s.length;
  };
  const button = document.createElement('button');
  button.className = 'showmore';
  button.textContent = 'Show more';
  button.addEventListener('click', add);
  box.appendChild(button);
  add();
  flowDone(box);
  viewer.texts = () => {
    const batch = from => {
      const texts = s.slice(from, from + 200000).split(/\n\s*\n/).map(t => t.replace(/\s+/g, ' ').trim()).filter(t => t);
      return { texts, els: null, from: 0, next: from + 200000 < s.length ? () => batch(from + 200000) : null };
    };
    return batch(0);
  };
};

// ------------------------------------------------------------------ old Office files: their text

function oldNote(what) {
  note('This is an old ' + what + ' file (before 2007): Swift shows only its text.', 'Open with another app', () => action('open-with'));
}

RENDER.oldword = async buf => {
  await loadScript('lib/xlsx.full.min.js');
  const cfb = XLSX.CFB.read(new Uint8Array(buf), { type: 'array' });
  const wd = XLSX.CFB.find(cfb, 'WordDocument');
  if (!wd) { throw new Error('Not a Word file'); }
  const content = bytesOf(wd.content);
  // fEncrypted: the text can't be read without the password.
  if (u16(content, 0x0A) & 0x0100) { throw new Error('password'); }
  let text;
  try { text = wordText(cfb, content); } catch (e) { text = looseText(content); }
  oldNote('Word');
  paragraphs(wordParagraphs(text));
};

/** The text of a Word 97–2003 file, from its piece table (MS-DOC). */
function wordText(cfb, wd) {
  const flags = u16(wd, 0x0A);
  const table = XLSX.CFB.find(cfb, (flags & 0x0200) ? '1Table' : '0Table');
  const fcClx = u32(wd, 0x01A2);
  const lcbClx = u32(wd, 0x01A6);
  const ccpText = u32(wd, 0x004C);
  if (!table || !lcbClx) { throw new Error('No piece table'); }
  const clx = bytesOf(table.content).subarray(fcClx, fcClx + lcbClx);
  let i = 0;
  while (clx[i] === 0x01) { i += 3 + u16(clx, i + 1); }
  if (clx[i] !== 0x02) { throw new Error('Bad piece table'); }
  const lcb = u32(clx, i + 1);
  i += 5;
  const n = (lcb - 4) / 12;
  const cps = [];
  for (let k = 0; k <= n; k++) { cps.push(u32(clx, i + 4 * k)); }
  const pcd = i + 4 * (n + 1);
  let out = '';
  for (let k = 0; k < n && out.length < 5000000; k++) {
    const raw = u32(clx, pcd + 8 * k + 2);
    const len = cps[k + 1] - cps[k];
    if (raw & 0x40000000) {
      const fc = (raw & 0x3FFFFFFF) / 2;
      for (let j = 0; j < len; j++) { out += String.fromCharCode(wd[fc + j]); }
    } else {
      const fc = raw & 0x3FFFFFFF;
      for (let j = 0; j < len; j++) { out += String.fromCharCode(u16(wd, fc + 2 * j)); }
    }
  }
  return ccpText > 0 && ccpText <= out.length ? out.slice(0, ccpText) : out;
}

/** When the piece table can't be read: runs of readable text. */
function looseText(a) {
  const runs = [];
  let cur = '';
  for (let i = 0; i + 1 < a.length; i += 2) {
    const c = u16(a, i);
    if ((c >= 32 && c < 0xD800) || c === 13) { cur += String.fromCharCode(c); } else {
      if (cur.replace(/[^A-Za-zÀ-￿]/g, '').length > 20) { runs.push(cur); }
      cur = '';
    }
  }
  if (cur.length > 20) { runs.push(cur); }
  return runs.join('\r');
}

function wordParagraphs(text) {
  // Fields: keep what they show (between \x14 and \x15), drop their codes (\x13 … \x14).
  text = text.replace(/\x13[^\x13\x14\x15]*\x14/g, '').replace(/\x13[^\x13\x14\x15]*\x15/g, '').replace(/[\x14\x15]/g, '');
  return text.split(/[\r\x07\x0C]/).map(t => t.replace(/[\x00-\x08\x0B\x0E-\x1F]/g, ' ').replace(/\s+/g, ' ').trim()).filter(t => t);
}

RENDER.oldslides = async buf => {
  await loadScript('lib/xlsx.full.min.js');
  const cfb = XLSX.CFB.read(new Uint8Array(buf), { type: 'array' });
  const f = XLSX.CFB.find(cfb, 'PowerPoint Document');
  if (!f) { throw new Error('Not a PowerPoint file'); }
  const s = bytesOf(f.content);
  // Slides' text is in the slide list (one group per slide) and in each slide's text boxes; the
  // master slide's placeholders ("Click to edit…") and the notes are left out.
  const listed = [];
  const boxed = [];
  let listCur = null;
  let boxCur = null;
  const stack = [];
  const inside = type => stack.some(c => c.type === type);
  let pos = 0;
  while (pos + 8 <= s.length) {
    while (stack.length && pos >= stack[stack.length - 1].end) { stack.pop(); }
    const verInst = u16(s, pos);
    const type = u16(s, pos + 2);
    const len = u32(s, pos + 4);
    if ((verInst & 0xF) === 0xF) {
      stack.push({ type, inst: verInst >> 4, end: pos + 8 + len });
      if (type === 0x03EE) { boxCur = []; boxed.push(boxCur); }
      pos += 8;
      continue;
    }
    const list = stack.find(c => c.type === 0x0FF0);
    if (type === 0x03F3 && list && list.inst === 0) {
      listCur = [];
      listed.push(listCur);
    } else if ((type === 0x0FA0 || type === 0x0FA8) && !inside(0x03F8) && !inside(0x03F0) && !inside(0x0FC9)) {
      let text = '';
      for (let j = 0; j < len; j += type === 0x0FA0 ? 2 : 1) {
        text += String.fromCharCode(type === 0x0FA0 ? u16(s, pos + 8 + j) : s[pos + 8 + j]);
      }
      const lines = text.split(/[\r\x0B]/).filter(l => l.trim() && l.trim() !== '*');
      if (list) {
        if (list.inst === 0 && listCur) { listCur.push(...lines); }
      } else if (inside(0x03EE) && boxCur) {
        boxCur.push(...lines);
      }
    }
    pos += 8 + len;
  }
  const slides = [];
  for (let i = 0; i < Math.max(listed.length, boxed.length); i++) {
    const lines = [];
    for (const l of (listed[i] || []).concat(boxed[i] || [])) { if (!lines.includes(l)) { lines.push(l); } }
    if (lines.length) { slides.push(lines); }
  }
  oldNote('PowerPoint');
  paragraphs(slides.map((lines, i) => ({ heading: 'Slide ' + (i + 1), lines })));
};

// ------------------------------------------------------------------ OpenDocument and RTF: their text

async function openDocumentXml(buf) {
  await loadScript('lib/jszip.min.js');
  const zip = await JSZip.loadAsync(buf);
  if (!zip.file('content.xml')) { throw new Error('encrypted or not an OpenDocument file'); }
  const xml = await zip.file('content.xml').async('string');
  return new DOMParser().parseFromString(xml, 'application/xml');
}

const TEXT_NS = 'urn:oasis:names:tc:opendocument:xmlns:text:1.0';
const DRAW_NS = 'urn:oasis:names:tc:opendocument:xmlns:drawing:1.0';

RENDER.odt = async buf => {
  const doc = await openDocumentXml(buf);
  const out = [];
  const walk = n => {
    for (let c = n.firstElementChild; c; c = c.nextElementSibling) {
      if (c.namespaceURI === TEXT_NS && (c.localName === 'p' || c.localName === 'h')) { out.push(c.textContent); } else { walk(c); }
    }
  };
  walk(doc.documentElement);
  paragraphs(out);
};

RENDER.odp = async buf => {
  const doc = await openDocumentXml(buf);
  const pages = Array.from(doc.getElementsByTagNameNS(DRAW_NS, 'page'));
  paragraphs(pages.map((pg, i) => ({
    heading: pg.getAttributeNS(DRAW_NS, 'name') || 'Slide ' + (i + 1),
    lines: Array.from(pg.getElementsByTagNameNS(TEXT_NS, 'p')).map(p => p.textContent),
  })));
};

RENDER.rtf = async buf => {
  paragraphs(rtfText(new TextDecoder('windows-1252').decode(buf)).split('\n'));
};

/** The text of an RTF file: groups that aren't text (fonts, colours, pictures) are skipped. */
function rtfText(s) {
  const SKIP = /^(fonttbl|colortbl|stylesheet|info|pict|object|header|footer|themedata|colorschememapping|datastore|latentstyles|listtable|listoverridetable|rsidtbl|generator|xmlnstbl|mmathPr)$/;
  let out = '';
  let depth = 0;
  let skipFrom = -1;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '{') { depth++; continue; }
    if (c === '}') { if (skipFrom === depth) { skipFrom = -1; } depth--; continue; }
    if (c === '\\') {
      const m = /^\\([a-z]+)(-?\d+)? ?|^\\'([0-9a-f]{2})|^\\([^a-z])/i.exec(s.slice(i, i + 40));
      if (!m) { continue; }
      i += m[0].length - 1;
      if (skipFrom >= 0) { continue; }
      if (m[4] === '*') { skipFrom = depth; continue; }
      if (m[3]) { out += String.fromCharCode(parseInt(m[3], 16)); continue; }
      if (m[4]) { if (m[4] === '~') { out += ' '; } else if (m[4] !== '\n' && m[4] !== '\r') { out += m[4]; } continue; }
      const w = m[1];
      if (SKIP.test(w)) { skipFrom = depth; } else if (w === 'par' || w === 'line' || w === 'row') { out += '\n'; } else if (w === 'tab' || w === 'cell') { out += '\t'; }
      continue;
    }
    if (skipFrom >= 0 || c === '\r' || c === '\n') { continue; }
    out += c;
  }
  return out;
}

// ------------------------------------------------------------------ ZIP files (2.5)

// Swift reads the ZIP (it can be large) and lists it; a document inside opens in its own tab.
let zipWait = null;
RENDER.zip = async () => {
  const list = await new Promise((resolve, reject) => {
    zipWait = resolve;
    send({ type: 'doc-zip', id });
    setTimeout(() => reject(new Error('No answer')), 60000);
  });
  if (list.error) {
    problem(list.error, [{ label: 'Open with another app', action: 'open-with' }]);
    return;
  }
  const box = document.createElement('div');
  box.className = 'ziplist';
  const dirOf = n => (n.lastIndexOf('/') < 0 ? '' : n.slice(0, n.lastIndexOf('/')));
  const entries = (list.entries || []).filter(e => !e.name.endsWith('/'))
    .sort((a, b) => dirOf(a.name).localeCompare(dirOf(b.name)) || a.name.localeCompare(b.name));
  let folder = null;
  for (const e of entries) {
    const slash = e.name.lastIndexOf('/');
    const dir = slash < 0 ? '' : e.name.slice(0, slash);
    if (dir !== folder) {
      folder = dir;
      if (dir) {
        const h = document.createElement('div');
        h.className = 'folder';
        h.textContent = dir;
        box.appendChild(h);
      }
    }
    const b = document.createElement('button');
    const k = e.kind ? KINDS[e.kind] : null;
    if (!k) { b.className = 'off'; }
    const badge = document.createElement('span');
    badge.className = 'zbadge';
    badge.textContent = k ? k[0] : (e.name.split('.').pop() || '').slice(0, 4).toUpperCase() || 'FILE';
    badge.style.background = k ? k[1] : '#80868b';
    const name = document.createElement('span');
    name.className = 'zname';
    name.textContent = e.name.slice(slash + 1);
    const size = document.createElement('span');
    size.className = 'zsize';
    size.textContent = e.size >= 0 ? formatSize(e.size) : '';
    b.append(badge, name, size);
    b.addEventListener('click', () => {
      if (!k) { toast("Swift can't show this kind of file."); return; }
      toast('Opening ' + name.textContent + '…');
      action('open-entry', { entry: e.name });
    });
    box.appendChild(b);
  }
  if (!entries.length) {
    const p = document.createElement('p');
    p.className = 'flow';
    p.textContent = 'This ZIP file is empty.';
    box.appendChild(p);
  }
  if (list.truncated) { note('This ZIP file holds more files than Swift lists here; the first ' + entries.length + ' are shown.'); }
  docEl.appendChild(box);
  ready();
  watchScroll(null);
};

// ------------------------------------------------------------------ PDF (pdf.js)

async function openPdf() {
  const lib = await import('./lib/pdf.min.mjs');
  globalThis.pdfjsLib = lib;
  lib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.mjs';
  const ui = await import('./lib/pdf_viewer.mjs');
  addCss('lib/pdf_viewer.css');
  const pane = document.createElement('div');
  pane.id = 'pdfpane';
  pane.className = 'pane';
  const inner = document.createElement('div');
  inner.className = 'pdfViewer';
  pane.appendChild(inner);
  docEl.appendChild(pane);
  const eventBus = new ui.EventBus();
  const linkService = new ui.PDFLinkService({ eventBus });
  const findController = new ui.PDFFindController({ eventBus, linkService });
  const pdfViewer = new ui.PDFViewer({
    container: pane,
    viewer: inner,
    eventBus,
    linkService,
    findController,
    removePageBorders: true,
    // Forms can be filled in, and pages marked (Fill & sign).
    annotationEditorMode: lib.AnnotationEditorType.NONE,
    // Pages drawn sharp up to about 6 megapixels each: enough when zoomed in, light on memory.
    maxCanvasPixels: 6 * 1024 * 1024,
    // Dark mode: the pages turn dark too, their pictures keep their colours.
    pageColors: dark() ? { background: cssVar('--surface') || '#1d1e22', foreground: cssVar('--text') || '#dcdce0' } : null,
  });
  linkService.setViewer(pdfViewer);
  const params = {
    cMapUrl: 'lib/cmaps/',
    cMapPacked: true,
    standardFontDataUrl: 'lib/standard_fonts/',
    isEvalSupported: false,
    disableAutoFetch: true,
    disableStream: true,
    rangeChunkSize: 196608,
  };
  if (info.size > 0 && info.ranges) {
    // Only the pieces needed for the pages on screen: a big PDF opens quickly and stays light.
    pdfRange = new lib.PDFDataRangeTransport(info.size, null);
    pdfRange.requestDataRange = (begin, end) => {
      // pdf.js only accepts pieces that end on its own chunk size (or at the end of the file).
      const piece = Math.floor(4194304 / params.rangeChunkSize) * params.rangeChunkSize;
      for (let at = begin; at < end; at += piece) {
        const req = nextReq++;
        askedAt.set(req, at);
        send({ type: 'doc-range', id, req, begin: at, end: Math.min(end, at + piece) });
      }
    };
    params.range = pdfRange;
    params.length = info.size;
  } else {
    params.data = new Uint8Array(await readAll());
  }
  const task = lib.getDocument(params);
  task.onPassword = (update, reason) => {
    const pw = prompt(reason === lib.PasswordResponses.INCORRECT_PASSWORD ? 'Wrong password. Try again:' : 'This PDF is protected. Its password:');
    if (pw === null) { problem('This PDF needs its password.', []); } else { update(pw); }
  };
  const pdf = await task.promise;
  pdfViewer.setDocument(pdf);
  linkService.setDocument(pdf, null);
  let fit = 1;
  eventBus.on('pagesinit', () => {
    pdfViewer.currentScaleValue = 'page-width';
    fit = pdfViewer.currentScale;
    const page = parseInt(info.place, 10);
    if (page > 1) { pdfViewer.currentPageNumber = Math.min(page, pdf.numPages); }
    ready();
  });
  eventBus.on('pagechanging', e => {
    savePlace(e.pageNumber, pdf.numPages > 1 ? e.pageNumber / pdf.numPages : 1, pdf.numPages);
  });
  const counted = e => {
    const m = e.matchesCount;
    setFindCount(m && m.total ? (m.current ? m.current + '/' : '') + m.total : '');
  };
  eventBus.on('updatefindmatchescount', counted);
  eventBus.on('updatefindcontrolstate', counted);

  viewer.scroller = pane;
  watchScroll(pane);
  viewer.pageLabel = () => 'Page ' + pdfViewer.currentPageNumber + ' of ' + pdf.numPages;
  viewer.goTo = () => {
    const n = parseInt(prompt('Go to page (1–' + pdf.numPages + ')', String(pdfViewer.currentPageNumber)) || '', 10);
    if (n >= 1 && n <= pdf.numPages) { pdfViewer.currentPageNumber = n; }
  };

  // Zoom: two fingers, or a double tap; pdf.js draws the pages again at the new size, so text stays sharp.
  const zoomTo = (target, x, y) => {
    const t = Math.max(fit * 0.5, Math.min(fit * 5, target));
    const factor = t / pdfViewer.currentScale;
    if (Math.abs(factor - 1) > 0.01) { pdfViewer.updateScale({ drawingDelay: 300, scaleFactor: factor, origin: [x, y] }); }
  };
  viewer.doubleTap = (x, y) => zoomTo(pdfViewer.currentScale > fit * 1.25 ? fit : fit * 2.5, x, y);
  let pinch = null;
  const spread = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  pane.addEventListener('touchstart', e => {
    pinch = e.touches.length === 2 && !viewer.drawing ? { d: spread(e.touches), scale: pdfViewer.currentScale } : null;
  }, { passive: true });
  pane.addEventListener('touchmove', e => {
    if (!pinch || e.touches.length !== 2) { return; }
    const t = e.touches;
    zoomTo(pinch.scale * spread(t) / pinch.d, (t[0].clientX + t[1].clientX) / 2, (t[0].clientY + t[1].clientY) / 2);
  }, { passive: true });
  pane.addEventListener('touchend', e => { if (e.touches.length < 2) { pinch = null; } }, { passive: true });

  viewer.ownFind = true;
  viewer.find = (query, again, previous) => eventBus.dispatch('find', {
    source: null, type: again ? 'again' : '', query, caseSensitive: false, entireWord: false,
    highlightAll: true, findPrevious: !!previous, matchDiacritics: false,
  });
  viewer.closeFind = () => eventBus.dispatch('findbarclose', { source: null });
  viewer.size = f => zoomTo(pdfViewer.currentScale * f, innerWidth / 2, innerHeight / 2);

  // The PDF's own table of contents.
  const outline = await pdf.getOutline().catch(() => null);
  if (outline && outline.length) {
    viewer.chaptersTitle = 'Contents';
    viewer.chapters = () => {
      const out = [];
      const walk = (items, depth) => {
        for (const it of items) {
          if (out.length >= 400) { return; }
          if (it.dest) { out.push({ label: (it.title || '').trim() || '…', depth, go: () => linkService.goToDestination(it.dest) }); }
          if (it.items && it.items.length && depth < 3) { walk(it.items, depth + 1); }
        }
      };
      walk(outline, 0);
      return out;
    };
  }
  viewer.pages = () => pagePreviews(pdf, pdfViewer);

  // Fill & sign: forms, highlights, drawing (a signature), text.
  let dirty = false;
  pdf.annotationStorage.onSetModified = () => { dirty = true; };
  const baseName = info.name.replace(/\.pdf$/i, '');
  const saveFilled = async () => {
    toast('Saving a copy…', 8000);
    try {
      const data = await pdf.saveDocument();
      sendFile(baseName + ' (filled in).pdf', 'application/pdf', data);
      dirty = false;
      // Swift says when it is saved.
      $('#toast').hidden = true;
    } catch (e) {
      toast("Swift couldn't save this PDF.");
    }
  };
  viewer.saveCopy = () => { if (dirty) { saveFilled(); } else { action('save-copy'); } };
  viewer.edit = () => {
    const T = lib.AnnotationEditorType;
    const P = lib.AnnotationEditorParamsType;
    const bar = $('#editbar');
    bar.textContent = '';
    viewer.editing = true;
    setReading(false);
    document.body.classList.add('editing');
    const setMode = mode => {
      pdfViewer.annotationEditorMode = { mode };
      viewer.drawing = mode === T.INK;
      Array.from(bar.children).forEach(b => b.classList.toggle('on', b.dataset.mode === String(mode)));
      if (mode === T.INK) {
        // Dark blue ink shows on light and dark pages alike, like a pen.
        eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.INK_COLOR, value: '#1a4fc4' });
        eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.INK_THICKNESS, value: 2 });
      } else if (mode === T.FREETEXT) {
        eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.FREETEXT_COLOR, value: '#1a4fc4' });
      }
    };
    const add = (label, run, mode) => {
      const b = document.createElement('button');
      b.textContent = label;
      if (mode !== undefined) { b.dataset.mode = String(mode); }
      b.addEventListener('click', run);
      bar.appendChild(b);
      return b;
    };
    for (const [label, mode] of [['Highlight', T.HIGHLIGHT], ['Sign', T.INK], ['Text', T.FREETEXT]]) {
      add(label, () => setMode(pdfViewer.annotationEditorMode === mode ? T.NONE : mode), mode);
    }
    add('Undo', () => eventBus.dispatch('editingaction', { source: null, name: 'undo' }));
    add('Save', saveFilled).className = 'main';
    const finish = () => {
      setMode(T.NONE);
      viewer.editing = false;
      viewer.drawing = false;
      bar.hidden = true;
      document.body.classList.remove('editing');
    };
    // Leaving with changes not saved: say so instead of throwing them away (2.6).
    const leave = byBack => {
      if (!dirty) {
        finish();
        if (!byBack) { closed(leave); }
        return;
      }
      if (byBack) { opened(leave); }
      ask('Your changes to this PDF are not saved yet.', [
        ['Keep editing', () => {}],
        ['Discard', () => { dirty = false; finish(); closed(leave); }],
        ['Save a copy', () => { saveFilled(); finish(); closed(leave); }, true],
      ]);
    };
    add('Done', () => leave(false));
    opened(leave);
    bar.hidden = false;
    toast('Tap a form field to fill it in. Sign draws with your finger; Save puts a copy in Downloads.', 5000);
  };

  // Read aloud: a few pages at a time from the page on screen, on to the end; what is read is marked.
  const pageTexts = async first => {
    const texts = [];
    const pageOf = [];
    const last = Math.min(pdf.numPages, first + 9);
    for (let p = first; p <= last; p++) {
      const content = await (await pdf.getPage(p)).getTextContent();
      let s = '';
      for (const it of content.items) { s += it.str + (it.hasEOL ? '\n' : ''); }
      for (const para of s.split(/\n\s*\n|(?<=[.!?])\n/)) {
        const t = para.replace(/\s+/g, ' ').trim();
        if (t) { texts.push(t); pageOf.push(p); }
      }
    }
    return {
      texts,
      els: null,
      from: 0,
      mark: i => {
        const p = pageOf[i];
        if (!p) { return; }
        if (p !== pdfViewer.currentPageNumber) { pdfViewer.currentPageNumber = p; }
        const words = texts[i].split(' ').slice(0, 7).join(' ');
        if (words.length > 3) {
          eventBus.dispatch('find', {
            source: null, type: '', query: words, caseSensitive: false, entireWord: false,
            highlightAll: false, findPrevious: false, matchDiacritics: false,
          });
        }
      },
      unmark: () => eventBus.dispatch('findbarclose', { source: null }),
      next: last < pdf.numPages ? () => pageTexts(last + 1) : null,
    };
  };
  viewer.texts = () => pageTexts(pdfViewer.currentPageNumber);
}

/** Small pictures of every page (2.5): tap one to go there. Drawn only as they come into view. */
function pagePreviews(pdf, pdfViewer) {
  const wrap = document.createElement('div');
  wrap.id = 'pages';
  const head = document.createElement('div');
  head.id = 'pageshead';
  const title = document.createElement('span');
  title.textContent = 'Pages';
  const close = document.createElement('button');
  close.className = 'icon';
  close.setAttribute('aria-label', 'Close');
  close.innerHTML = '<svg viewBox="0 0 24 24"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';
  head.append(title, close);
  const remove = () => { io.disconnect(); wrap.remove(); head.remove(); };
  const done = () => { remove(); closed(remove); };
  close.addEventListener('click', done);
  const queue = [];
  let busy = false;
  const drawNext = async () => {
    if (busy || !queue.length || !wrap.isConnected) { return; }
    busy = true;
    const { n, blank } = queue.shift();
    try {
      const page = await pdf.getPage(n);
      const vp1 = page.getViewport({ scale: 1 });
      const vp = page.getViewport({ scale: 220 / vp1.width });
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(vp.width);
      canvas.height = Math.round(vp.height);
      await page.render({ canvas, viewport: vp }).promise;
      if (blank.isConnected) { blank.replaceWith(canvas); }
    } catch (e) { /* stays blank */ }
    busy = false;
    drawNext();
  };
  const io = new IntersectionObserver(entries => {
    for (const en of entries) {
      if (!en.isIntersecting) { continue; }
      io.unobserve(en.target);
      queue.push({ n: parseInt(en.target.dataset.n, 10), blank: en.target.querySelector('.blank') });
    }
    drawNext();
  }, { root: wrap, rootMargin: '400px' });
  const current = pdfViewer.currentPageNumber;
  let currentEl = null;
  for (let n = 1; n <= pdf.numPages; n++) {
    const t = document.createElement('button');
    t.className = 'thumb' + (n === current ? ' on' : '');
    t.dataset.n = n;
    const blank = document.createElement('div');
    blank.className = 'blank';
    blank.style.aspectRatio = '0.707';
    const label = document.createElement('span');
    label.textContent = n;
    t.append(blank, label);
    t.addEventListener('click', () => { pdfViewer.currentPageNumber = n; done(); });
    wrap.appendChild(t);
    io.observe(t);
    if (n === current) { currentEl = t; }
  }
  document.body.append(wrap, head);
  opened(remove);
  if (currentEl) { currentEl.scrollIntoView({ block: 'center' }); }
}

// ------------------------------------------------------------------ the bar's buttons

function setFindCount(n) { $('#findcount').textContent = n ? String(n) : ''; }

$('#find').addEventListener('click', () => {
  if (!viewer.ownFind) {
    // Swift's own find bar finds in Word, text and the rest.
    action('find');
    return;
  }
  const bar = $('#findbar');
  bar.hidden = false;
  setReading(false);
  document.body.classList.add('finding');
  opened(endFind);
  $('#findtext').focus();
});
function endFind() {
  $('#findbar').hidden = true;
  document.body.classList.remove('finding');
  setFindCount('');
  if (viewer.closeFind) { viewer.closeFind(); }
}
let findTimer = 0;
$('#findtext').addEventListener('input', e => {
  clearTimeout(findTimer);
  const q = e.target.value;
  findTimer = setTimeout(() => viewer.find(q, false, false), info && info.kind === 'sheet' ? 300 : 0);
});
$('#findtext').addEventListener('keydown', e => { if (e.key === 'Enter') { viewer.find(e.target.value, true, e.shiftKey); } });
$('#findnext').addEventListener('click', () => viewer.find($('#findtext').value, true, false));
$('#findprev').addEventListener('click', () => viewer.find($('#findtext').value, true, true));
$('#findclose').addEventListener('click', () => {
  endFind();
  closed(endFind);
});

function zoom(f) {
  if (!viewer.size) { return; }
  if (info.kind === 'pdf') { viewer.size(f); return; }
  scale = Math.max(0.6, Math.min(2.5, Math.round(scale * f * 100) / 100));
  viewer.size(scale);
  store(SIZE_KEY, String(scale));
}
$('#smaller').addEventListener('click', () => zoom(1 / 1.15));
$('#bigger').addEventListener('click', () => zoom(1.15));
$('#play').addEventListener('click', () => { if (viewer.slideshow) { viewer.slideshow(); } });

// ------------------------------------------------------------------ the more menu

const menuOverlay = () => { $('#menu').hidden = true; };
function hideMenu() {
  if ($('#menu').hidden) { return; }
  $('#menu').hidden = true;
  closed(menuOverlay);
}

function openMenu() {
  const menu = $('#menu');
  menu.textContent = '';
  const item = (label, run) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.addEventListener('click', () => { run(); hideMenu(); });
    menu.appendChild(b);
  };
  const shown = info && $('#status').hidden;
  if (shown && viewer.goTo) { item('Go to page…', viewer.goTo); }
  if (shown && viewer.chapters) { item(viewer.chaptersTitle || 'Chapters', showChapters); }
  if (shown && viewer.pages) { item('Pages', viewer.pages); }
  if (shown && viewer.slideshow) { item('Slideshow', viewer.slideshow); }
  if (shown && viewer.style) { item('Text style', viewer.style); }
  if (shown && viewer.edit) { item('Fill & sign', viewer.edit); }
  if (info && info.kind === 'word' && lastBuf) {
    item(pageLayout ? 'Fit to screen' : 'Page layout', () => { pageLayout = !pageLayout; renderWord(); });
  }
  item('Share', () => action('share'));
  item('Open with another app', () => action('open-with'));
  if (info && info.copy) { item('Save a copy', () => (viewer.saveCopy ? viewer.saveCopy() : action('save-copy'))); }
  if (shown && info.kind !== 'pdf' && info.kind !== 'epub' && info.kind !== 'zip') { item('Save as PDF', () => printOut('save-pdf')); }
  if (shown && info.kind !== 'epub' && info.kind !== 'zip') { item('Print', () => printOut('print')); }
  if (info && info.web && ['word', 'sheet', 'slides', 'oldword', 'oldslides'].includes(info.kind)) {
    item('Exact view (online)', () => action('exact'));
  }
  if (info) { item('Add to home screen', () => action('shortcut')); }
  if (info) {
    item('Details', () => alert(info.name + '\n' + (info.size > 0 ? formatSize(info.size) + '\n' : '') + (info.where || '')));
  }
  menu.style.maxHeight = '';
  menu.hidden = false;
  opened(menuOverlay);
}

function showChapters() {
  const menu = $('#menu');
  menu.textContent = '';
  for (const ch of viewer.chapters().slice(0, 400)) {
    const b = document.createElement('button');
    b.textContent = ch.label;
    if (ch.depth) {
      b.className = 'sub';
      b.style.paddingLeft = (18 + ch.depth * 16) + 'px';
    }
    b.addEventListener('click', () => { ch.go(); hideMenu(); });
    menu.appendChild(b);
  }
  menu.style.maxHeight = '70vh';
  menu.hidden = false;
  opened(menuOverlay);
}

function formatSize(n) {
  if (n < 1024) { return n + ' B'; }
  if (n < 1048576) { return Math.round(n / 1024) + ' KB'; }
  return (n / 1048576).toFixed(1) + ' MB';
}

$('#more').addEventListener('click', e => {
  e.stopPropagation();
  if ($('#menu').hidden) { openMenu(); } else { hideMenu(); }
});
document.addEventListener('click', e => {
  if (!$('#menu').hidden && !e.target.closest('#menu')) { hideMenu(); }
});

/** Save as PDF or print (2.5): everything is drawn first (a long sheet's rows), then Swift takes over. */
function printOut(what) {
  if (info.kind === 'sheet') {
    // The rows not drawn yet (up to a few thousand) go on paper too.
    const pane = document.querySelector('.pane');
    for (let k = 0; k < 20 && pane && pane.scrollHeight > 0; k++) { pane.dispatchEvent(new Event('scroll')); pane.scrollTop = pane.scrollHeight; }
  }
  for (const d of document.querySelectorAll('details.notes')) { d.open = true; }
  toast(what === 'print' ? 'Preparing to print…' : 'Saving as PDF…');
  setTimeout(() => action(what), 300);
}

// ------------------------------------------------------------------ read aloud (Swift's Listen)

let ttsBatch = null;
let ttsState = 'idle';
let marked = null;

$('#listen').addEventListener('click', async () => {
  if (ttsState !== 'idle') { stopTts(); return; }
  if (!viewer.texts) { return; }
  startTts(await viewer.texts());
});

function startTts(t) {
  if (!t || !t.texts.length) {
    if (!ttsBatch) { note('There is no text here to read aloud.'); }
    setTts('idle');
    return;
  }
  ttsBatch = t;
  send({ type: 'tts-start', texts: t.texts, from: t.from || 0, rate: 1, lang: document.documentElement.lang || '', title: info.name });
  setTts('speaking');
}

function stopTts() {
  const b = ttsBatch;
  ttsBatch = null;
  send({ type: 'tts-stop' });
  setTts('idle');
  if (b && b.unmark) { b.unmark(); }
}

function setTts(state) {
  ttsState = state;
  $('#listen').classList.toggle('on', state !== 'idle');
  $('#tts').hidden = state === 'idle';
  document.body.classList.toggle('listening', state !== 'idle');
  $('#ttstoggle').textContent = state === 'paused' ? 'Resume' : 'Pause';
  if (state === 'idle' && marked) { marked.classList.remove('speaking'); marked = null; }
}

function onTts(st) {
  if (!st.speaking && !st.paused) {
    // The end of what was handed over: the next pages or chapter follow (2.5).
    const b = ttsBatch;
    if (st.finished && b && b.next) {
      ttsBatch = null;
      Promise.resolve(b.next()).then(t => {
        if (t && t.texts.length) { startTts(t); } else { setTts('idle'); }
      }).catch(() => setTts('idle'));
      return;
    }
    if (b && b.unmark) { b.unmark(); }
    ttsBatch = null;
    setTts('idle');
    return;
  }
  setTts(st.paused ? 'paused' : 'speaking');
  if (!ttsBatch || typeof st.index !== 'number') { return; }
  if (ttsBatch.mark) { ttsBatch.mark(st.index); return; }
  const els = ttsBatch.els;
  if (els && els[st.index]) {
    if (marked) { marked.classList.remove('speaking'); }
    marked = els[st.index];
    marked.classList.add('speaking');
    const r = marked.getBoundingClientRect();
    if (r.top < 60 || r.bottom > innerHeight - 90) { marked.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  }
}

$('#ttstoggle').addEventListener('click', () => {
  if (ttsState === 'speaking') { send({ type: 'tts-pause' }); setTts('paused'); } else { send({ type: 'tts-resume' }); setTts('speaking'); }
});
$('#ttsstop').addEventListener('click', stopTts);
addEventListener('pagehide', () => { if (ttsState !== 'idle') { send({ type: 'tts-stop' }); } });

// ------------------------------------------------------------------ go

// Swift may not be listening yet (a tab restored as Swift starts): say hello again until it answers.
send({ type: 'doc-hello', id });
let helloTries = 0;
const helloTimer = setInterval(() => {
  if (info || ++helloTries > 30) { clearInterval(helloTimer); return; }
  if (port) { try { port.disconnect(); } catch (e) { /* gone already */ } }
  port = null;
  send({ type: 'doc-hello', id });
}, 1500);
