'use strict';
// Swift's document viewer (2.4). Swift sends the file over its private channel (a native port):
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
  else if (msg.type === 'tts-state') { onTts(msg); }
}

function action(name) { send({ type: 'doc-action', id, action: name }); }

function b64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) { out[i] = bin.charCodeAt(i); }
  return out;
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

function dark() { return matchMedia('(prefers-color-scheme: dark)').matches; }

function loadScript(src) {
  return new Promise((resolve, reject) => {
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

// Where you are, sent to Swift a moment after you stop (Continue on the home page, reopening).
let placeTimer = 0;
function savePlace(place, progress, total) {
  clearTimeout(placeTimer);
  placeTimer = setTimeout(() => {
    send({ type: 'doc-place', id, place: String(place), progress: Math.max(0, Math.min(1, progress || 0)), total: total || 0 });
  }, 700);
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
  status('Opening…');
  try {
    if (info.kind === 'pdf') {
      await openPdf();
    } else {
      const buf = await readAll();
      status('Opening…');
      await (RENDER[info.kind] || RENDER.text)(buf);
    }
    showButtons();
  } catch (e) {
    console.error(e);
    problem("Swift couldn't show this file. It may be damaged, protected by a password, or in a form Swift doesn't read.",
      [{ label: 'Open with another app', action: 'open-with' }]);
  }
}

function showButtons() {
  $('#find').hidden = false;
  $('#smaller').hidden = !viewer.size;
  $('#bigger').hidden = !viewer.size;
  $('#listen').hidden = !viewer.texts;
}

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
  viewer.size = f => { box.style.zoom = f; };
  viewer.texts = () => {
    const els = Array.from(box.querySelectorAll(selector || 'p, li, h1, h2, h3, h4, h5, h6, pre'))
      .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li'));
    return { texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()), els, from: firstVisible(els) };
  };
}

function firstVisible(els) {
  const top = $('#bar').getBoundingClientRect().bottom;
  const i = els.findIndex(e => e.getBoundingClientRect().bottom > top + 4);
  return i < 0 ? 0 : i;
}

function paragraphs(list, headings) {
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
  flowDone(box);
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
      .replace(/[\uF000-\uF0FF]/g, ch => String.fromCharCode(parseInt(map[ch.charCodeAt(0).toString(16)] || '2022', 16)))
      .replace(/font-family:\s*(Symbol|Wingdings)[^;"]*/gi, 'font-family: sans-serif');
    if (fixed !== css) { st.textContent = fixed; }
  }
}

// ------------------------------------------------------------------ spreadsheets

RENDER.sheet = async buf => {
  await loadScript('lib/xlsx.full.min.js');
  const wb = XLSX.read(buf, { type: 'array', dense: true, cellDates: true });
  document.body.classList.add('sheet');
  const pane = document.createElement('div');
  pane.className = 'pane';
  docEl.appendChild(pane);
  const tabs = $('#sheets');
  tabs.hidden = false;
  let current = Math.min(Math.max(0, parseInt(info.place, 10) || 0), wb.SheetNames.length - 1);
  let rows = [];
  let shown = 0;
  let body = null;
  let table = null;
  const BATCH = 300;

  const colName = n => {
    let s = '';
    for (n += 1; n > 0; n = Math.floor((n - 1) / 26)) { s = String.fromCharCode(65 + (n - 1) % 26) + s; }
    return s;
  };

  function more() {
    const end = Math.min(rows.length, shown + BATCH);
    const frag = document.createDocumentFragment();
    for (let r = shown; r < end; r++) {
      const tr = document.createElement('tr');
      const th = document.createElement('th');
      th.className = 'row';
      th.textContent = r + 1;
      tr.appendChild(th);
      for (const v of rows[r]) {
        const td = document.createElement('td');
        const text = v == null ? '' : String(v);
        td.textContent = text;
        if (text !== '' && /^[-+]?[\d.,\s%$€£¥]+$/.test(text)) { td.className = 'num'; }
        tr.appendChild(td);
      }
      frag.appendChild(tr);
    }
    body.appendChild(frag);
    shown = end;
  }

  function show(i) {
    current = i;
    Array.from(tabs.children).forEach((b, j) => b.classList.toggle('on', j === i));
    const ws = wb.Sheets[wb.SheetNames[i]];
    rows = XLSX.utils.sheet_to_json(ws, { header: 1, raw: false, defval: '', blankrows: true });
    const cols = Math.min(200, rows.reduce((m, r) => Math.max(m, r.length), 0));
    rows = rows.map(r => { const c = r.slice(0, cols); while (c.length < cols) { c.push(''); } return c; });
    pane.textContent = '';
    table = document.createElement('table');
    table.className = 'grid';
    table.style.zoom = scale;
    const head = document.createElement('thead');
    const htr = document.createElement('tr');
    const corner = document.createElement('th');
    corner.className = 'row corner';
    htr.appendChild(corner);
    for (let c = 0; c < cols; c++) {
      const th = document.createElement('th');
      th.textContent = colName(c);
      htr.appendChild(th);
    }
    head.appendChild(htr);
    table.appendChild(head);
    body = document.createElement('tbody');
    table.appendChild(body);
    pane.appendChild(table);
    shown = 0;
    more();
    if (rows.length === 0) {
      const p = document.createElement('p');
      p.className = 'flow';
      p.textContent = 'This sheet is empty.';
      pane.appendChild(p);
    }
    pane.scrollTo(0, 0);
    savePlace(i, wb.SheetNames.length > 1 ? i / (wb.SheetNames.length - 1) : 0.5, wb.SheetNames.length);
  }

  wb.SheetNames.forEach((name, i) => {
    const b = document.createElement('button');
    b.textContent = name;
    b.addEventListener('click', () => show(i));
    tabs.appendChild(b);
  });
  pane.addEventListener('scroll', () => {
    if (shown < rows.length && pane.scrollTop + pane.clientHeight > pane.scrollHeight - 800) { more(); }
  }, { passive: true });
  show(current);
  ready();
  viewer.size = f => { if (table) { table.style.zoom = f; } };
  viewer.texts = () => ({ texts: rows.slice(0, 400).map(r => r.filter(v => v !== '').join(', ')).filter(t => t), els: null, from: 0 });
};

// ------------------------------------------------------------------ PowerPoint (.pptx)

RENDER.slides = async buf => {
  await loadScript('lib/pptx-preview.umd.js');
  const box = document.createElement('div');
  box.id = 'slides';
  docEl.appendChild(box);
  const width = Math.min(innerWidth - 12, 1100);
  const previewer = pptxPreview.init(box, { width, height: Math.round(width * 9 / 16), mode: 'list' });
  await previewer.preview(buf);
  flowDone(box, 'p, li');
};

// ------------------------------------------------------------------ e-books (.epub)

RENDER.epub = async buf => {
  await loadScript('lib/jszip.min.js');
  await loadScript('lib/epub.min.js');
  const book = ePub(buf);
  const area = document.createElement('div');
  area.id = 'book';
  area.className = 'pane';
  docEl.appendChild(area);
  const rendition = book.renderTo(area, { manager: 'continuous', flow: 'scrolled', width: '100%', allowScriptedContent: false });
  rendition.themes.default({
    body: { 'line-height': '1.6 !important', padding: '0 16px !important' },
    img: { 'max-width': '100% !important', height: 'auto !important' },
  });
  if (dark()) {
    rendition.themes.override('color', '#e4e4e7', true);
    rendition.themes.override('background', 'transparent', true);
  }
  await rendition.display(info.place && info.place.startsWith('epubcfi') ? info.place : undefined);
  ready();
  rendition.on('relocated', loc => {
    if (!loc || !loc.start) { return; }
    const n = book.spine.length || 1;
    const pct = book.locations.length() ? book.locations.percentageFromCfi(loc.start.cfi) : loc.start.index / Math.max(1, n - 1);
    savePlace(loc.start.cfi, pct, 0);
  });
  // How far through the book, for the home page: worked out when the phone has a moment.
  setTimeout(() => { book.locations.generate(1600).catch(() => {}); }, 4000);
  viewer.size = f => rendition.themes.fontSize(Math.round(f * 100) + '%');
  viewer.texts = () => {
    const texts = [];
    for (const c of rendition.getContents()) {
      c.document.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6').forEach(e => {
        const t = e.textContent.replace(/\s+/g, ' ').trim();
        if (t) { texts.push(t); }
      });
    }
    return { texts, els: null, from: 0 };
  };
  viewer.chapters = () => book.navigation.toc.map(t => ({ label: t.label.trim(), go: () => rendition.display(t.href) }));
};

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
    const texts = s.slice(0, 200000).split(/\n\s*\n/).map(t => t.replace(/\s+/g, ' ').trim()).filter(t => t);
    return { texts, els: null, from: 0 };
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
  let text;
  try { text = wordText(cfb, bytesOf(wd.content)); } catch (e) { text = looseText(bytesOf(wd.content)); }
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
    // Dark mode: the pages turn dark too, their pictures keep their colours.
    pageColors: dark() ? { background: '#1d1e22', foreground: '#dcdce0' } : null,
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
      const req = nextReq++;
      askedAt.set(req, begin);
      send({ type: 'doc-range', id, req, begin, end });
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
  eventBus.on('pagesinit', () => {
    pdfViewer.currentScaleValue = 'page-width';
    const page = parseInt(info.place, 10);
    if (page > 1) { pdfViewer.currentPageNumber = Math.min(page, pdf.numPages); }
    ready();
  });
  eventBus.on('pagechanging', e => {
    savePlace(e.pageNumber, pdf.numPages > 1 ? e.pageNumber / pdf.numPages : 1, pdf.numPages);
  });
  const counted = e => setFindCount(e.matchesCount && e.matchesCount.total);
  eventBus.on('updatefindmatchescount', counted);
  eventBus.on('updatefindcontrolstate', counted);

  viewer.ownFind = true;
  viewer.find = (query, again, previous) => eventBus.dispatch('find', {
    source: null, type: again ? 'again' : '', query, caseSensitive: false, entireWord: false,
    highlightAll: true, findPrevious: !!previous, matchDiacritics: false,
  });
  viewer.closeFind = () => eventBus.dispatch('findbarclose', { source: null });
  viewer.size = f => { pdfViewer.currentScaleValue = 'page-width'; pdfViewer.currentScale = pdfViewer.currentScale * f; };
  viewer.texts = async () => {
    const texts = [];
    const first = pdfViewer.currentPageNumber;
    for (let p = first; p <= Math.min(pdf.numPages, first + 30); p++) {
      const content = await (await pdf.getPage(p)).getTextContent();
      let s = '';
      for (const it of content.items) { s += it.str + (it.hasEOL ? '\n' : ''); }
      for (const para of s.split(/\n\s*\n|(?<=[.!?])\n/)) {
        const t = para.replace(/\s+/g, ' ').trim();
        if (t) { texts.push(t); }
      }
    }
    return { texts, els: null, from: 0 };
  };
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
  document.body.classList.add('finding');
  $('#findtext').focus();
});
$('#findtext').addEventListener('input', e => viewer.find(e.target.value, false, false));
$('#findtext').addEventListener('keydown', e => { if (e.key === 'Enter') { viewer.find(e.target.value, true, e.shiftKey); } });
$('#findnext').addEventListener('click', () => viewer.find($('#findtext').value, true, false));
$('#findprev').addEventListener('click', () => viewer.find($('#findtext').value, true, true));
$('#findclose').addEventListener('click', () => {
  $('#findbar').hidden = true;
  document.body.classList.remove('finding');
  if (viewer.closeFind) { viewer.closeFind(); }
});

function zoom(f) {
  if (!viewer.size) { return; }
  if (info.kind === 'pdf') { viewer.size(f); return; }
  scale = Math.max(0.6, Math.min(2.5, Math.round(scale * f * 100) / 100));
  viewer.size(scale);
}
$('#smaller').addEventListener('click', () => zoom(1 / 1.15));
$('#bigger').addEventListener('click', () => zoom(1.15));

// ------------------------------------------------------------------ the more menu

function openMenu() {
  const menu = $('#menu');
  menu.textContent = '';
  const item = (label, run) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.addEventListener('click', () => { menu.hidden = true; run(); });
    menu.appendChild(b);
  };
  item('Share', () => action('share'));
  item('Open with another app', () => action('open-with'));
  if (info && info.web && ['word', 'sheet', 'slides', 'oldword', 'oldslides'].includes(info.kind)) {
    item('Exact view (online)', () => action('exact'));
  }
  if (info && info.kind === 'word' && lastBuf) {
    item(pageLayout ? 'Fit to screen' : 'Page layout', () => { pageLayout = !pageLayout; renderWord(); });
  }
  if (viewer.chapters) { item('Chapters', showChapters); }
  if (info) {
    item('Details', () => alert(info.name + '\n' + (info.size > 0 ? formatSize(info.size) + '\n' : '') + (info.where || '')));
  }
  menu.hidden = false;
}

function showChapters() {
  const menu = $('#menu');
  menu.textContent = '';
  for (const ch of viewer.chapters().slice(0, 80)) {
    const b = document.createElement('button');
    b.textContent = ch.label;
    b.addEventListener('click', () => { menu.hidden = true; ch.go(); });
    menu.appendChild(b);
  }
  menu.style.maxHeight = '70vh';
  menu.style.overflowY = 'auto';
  menu.hidden = false;
}

function formatSize(n) {
  if (n < 1024) { return n + ' B'; }
  if (n < 1048576) { return Math.round(n / 1024) + ' KB'; }
  return (n / 1048576).toFixed(1) + ' MB';
}

$('#more').addEventListener('click', e => {
  e.stopPropagation();
  if ($('#menu').hidden) { openMenu(); } else { $('#menu').hidden = true; }
});
document.addEventListener('click', e => {
  if (!$('#menu').hidden && !e.target.closest('#menu')) { $('#menu').hidden = true; }
});

// ------------------------------------------------------------------ read aloud (Swift's Listen)

let ttsEls = null;
let ttsState = 'idle';
let marked = null;

$('#listen').addEventListener('click', async () => {
  if (ttsState !== 'idle') { send({ type: 'tts-stop' }); return; }
  if (!viewer.texts) { return; }
  const t = await viewer.texts();
  if (!t || !t.texts.length) { note('There is no text here to read aloud.'); return; }
  ttsEls = t.els;
  send({ type: 'tts-start', texts: t.texts, from: t.from || 0, rate: 1, lang: document.documentElement.lang || '', title: info.name });
  setTts('speaking');
});

function setTts(state) {
  ttsState = state;
  $('#listen').classList.toggle('on', state !== 'idle');
  $('#tts').hidden = state === 'idle';
  $('#ttstoggle').textContent = state === 'paused' ? 'Resume' : 'Pause';
  if (state === 'idle' && marked) { marked.classList.remove('speaking'); marked = null; }
}

function onTts(st) {
  if (!st.speaking) { setTts('idle'); return; }
  setTts(st.paused ? 'paused' : 'speaking');
  if (ttsEls && typeof st.index === 'number' && ttsEls[st.index]) {
    if (marked) { marked.classList.remove('speaking'); }
    marked = ttsEls[st.index];
    marked.classList.add('speaking');
    const r = marked.getBoundingClientRect();
    if (r.top < 60 || r.bottom > innerHeight - 90) { marked.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  }
}

$('#ttstoggle').addEventListener('click', () => {
  if (ttsState === 'speaking') { send({ type: 'tts-pause' }); setTts('paused'); } else { send({ type: 'tts-resume' }); setTts('speaking'); }
});
$('#ttsstop').addEventListener('click', () => { send({ type: 'tts-stop' }); setTts('idle'); });
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
