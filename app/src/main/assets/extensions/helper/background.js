'use strict';

// Detects video streams/files that pages load and reports them to the app.
//
// Kept deliberately cheap: one listener, only for request types that can carry
// video (no images, scripts, styles or fonts), and no request-header capture.
// The page or player frame that loaded the video is used as the Referer when
// downloading, which is what most video hosts check.

const NATIVE_APP = 'swift';
const TYPES = ['media', 'xmlhttprequest', 'other'];
const FILE_EXT = /\.(mp4|m4v|webm|mkv|mov|avi|flv|3gp)(?:[?#]|$)/i;
const SEGMENT_EXT = /\.(ts|m4s|m4a|aac|mp3|vtt|webvtt|srt|key|jpg|jpeg|png|gif|webp)(?:[?#]|$)/i;
const HLS_EXT = /\.m3u8(?:[?#]|$)/i;
const MIN_FILE_SIZE = 1024 * 1024;

let port = null;
const seen = new Map();   // tabId -> Set(url)
const referers = new Map(); // media url -> referer (small LRU, for download resumes)

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    port = null;
    return;
  }
  port.onMessage.addListener(onNative);
  port.onDisconnect.addListener(() => { port = null; });
}

function post(msg) {
  if (port === null) { connect(); }
  try {
    if (port) { port.postMessage(msg); }
  } catch (e) {
    port = null;
  }
}

function onNative(msg) {
  if (!msg || typeof msg !== 'object') { return; }
  if (msg.type === 'headers') {
    const referer = referers.get(msg.url);
    post({ type: 'headers', id: msg.id, headers: referer ? { referer } : null });
  } else if (msg.type === 'lite') {
    setLite(msg.all === true);
  }
}

// ---------------------------------------------------------------- Lite mode

// With Lite mode on, every page gets perf/lite.css from its first byte.
let liteRegistration = null;
let liteQueue = Promise.resolve();

function setLite(on) {
  liteQueue = liteQueue.then(async () => {
    if (liteRegistration) {
      try { await liteRegistration.unregister(); } catch (e) { }
      liteRegistration = null;
    }
    if (!on) { return; }
    try {
      liteRegistration = await browser.contentScripts.register({
        matches: ['http://*/*', 'https://*/*'],
        css: [{ file: '/perf/lite.css' }],
        allFrames: true,
        runAt: 'document_start',
      });
    } catch (e) {
      liteRegistration = null;
    }
  });
}

// ---------------------------------------------------------------- reader view

// Articles parsed for reader view, by id, until the reader page has fetched them (a few kept).
const articles = new Map();
let nextArticle = 1;

async function startReader(tabId) {
  try {
    await browser.tabs.executeScript(tabId, { file: '/reader/Readability.js' });
    const results = await browser.tabs.executeScript(tabId, { file: '/reader/parse.js' });
    const article = results && results[0];
    if (!article) {
      post({ type: 'reader-failed', tabId });
      return;
    }
    const id = String(nextArticle++);
    articles.set(id, article);
    while (articles.size > 6) { articles.delete(articles.keys().next().value); }
    post({ type: 'reader-ready', tabId, url: browser.runtime.getURL('reader/view.html') + '#' + id });
  } catch (e) {
    post({ type: 'reader-failed', tabId });
  }
}

function contentType(list) {
  if (!list) { return ''; }
  for (let i = 0; i < list.length; i++) {
    const h = list[i];
    if (h.name.length === 12 && h.name.toLowerCase() === 'content-type') { return (h.value || '').toLowerCase(); }
  }
  return '';
}

function headerValue(list, name) {
  for (let i = 0; i < list.length; i++) {
    if (list[i].name.toLowerCase() === name) { return list[i].value || ''; }
  }
  return '';
}

browser.webRequest.onHeadersReceived.addListener(details => {
  if (details.tabId < 0 || details.statusCode >= 400) { return; }
  const url = details.url;
  const ct = contentType(details.responseHeaders);
  let kind = null;
  if (ct.includes('mpegurl') || HLS_EXT.test(url)) {
    kind = 'hls';
  } else if (details.type === 'media' || ct.startsWith('video/') || FILE_EXT.test(url)) {
    // Pieces of adaptive streams (YouTube etc.) are fetched by script and can't be saved as files;
    // reporting each of them only costs work while the video plays.
    if (details.type === 'xmlhttprequest' && !FILE_EXT.test(url)) { return; }
    if (SEGMENT_EXT.test(url) || /mp2t|iso\.segment|mpeg2/.test(ct)) { return; }
    if (ct.startsWith('text/') || ct.includes('json') || ct.includes('html') || ct.startsWith('audio/')) { return; }
    if (!ct.startsWith('video/') && !FILE_EXT.test(url)) { return; }
    kind = 'file';
  }
  if (kind === null) { return; }

  let size = parseInt(headerValue(details.responseHeaders, 'content-length'), 10);
  const range = /\/(\d+)\s*$/.exec(headerValue(details.responseHeaders, 'content-range'));
  if (range) { size = parseInt(range[1], 10); }
  if (!Number.isFinite(size)) { size = -1; }
  // Tiny files are usually ad clips or previews.
  if (kind === 'file' && size > 0 && size < MIN_FILE_SIZE) { return; }

  let set = seen.get(details.tabId);
  if (!set) {
    set = new Set();
    seen.set(details.tabId, set);
  }
  const key = url.split('#')[0];
  if (set.has(key)) { return; }
  set.add(key);

  const referer = details.documentUrl || details.originUrl || '';
  referers.delete(url);
  referers.set(url, referer);
  if (referers.size > 200) { referers.delete(referers.keys().next().value); }

  post({
    type: 'media',
    tabId: details.tabId,
    url,
    kind,
    mime: ct,
    size,
    headers: referer ? { referer } : {},
    frameUrl: details.documentUrl || '',
    originUrl: details.originUrl || '',
  });
}, { urls: ['<all_urls>'], types: TYPES }, ['responseHeaders']);

// A new page in the tab starts a new list of videos.
browser.webNavigation.onCommitted.addListener(details => {
  if (details.frameId === 0) { seen.delete(details.tabId); }
});

browser.tabs.onRemoved.addListener(tabId => { seen.delete(tabId); });

// Content scripts ask which tab they are in, so the app can match media reports to tabs.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === 'whoami') {
    return Promise.resolve({ tabId: sender.tab ? sender.tab.id : -1 });
  }
  if (msg && msg.type === 'reader-start' && sender.tab) {
    startReader(sender.tab.id);
    return undefined;
  }
  if (msg && msg.type === 'reader-get') {
    return Promise.resolve(articles.get(String(msg.id)) || null);
  }
  return undefined;
});

connect();
