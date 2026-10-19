'use strict';

// Detects video streams/files that pages load and reports them to the app,
// together with the request headers needed to download them later.

const NATIVE_APP = 'swift';
const TYPES = ['main_frame', 'sub_frame', 'media', 'xmlhttprequest', 'object', 'other'];
const WANTED_HEADERS = new Set(['referer', 'origin', 'cookie', 'user-agent', 'authorization']);
const FILE_EXT = /\.(mp4|m4v|webm|mkv|mov|avi|flv|3gp)(?:[?#]|$)/i;
const SEGMENT_EXT = /\.(ts|m4s|m4a|aac|mp3|vtt|webvtt|srt|key|jpg|png)(?:[?#]|$)/i;
const HLS_EXT = /\.m3u8(?:[?#]|$)/i;

let port = null;
const requestHeaders = new Map(); // requestId -> headers
const seen = new Map();           // tabId -> Set(url)
const recent = new Map();         // url -> headers (small LRU, for download resumes)

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
    post({ type: 'headers', id: msg.id, headers: recent.get(msg.url) || null });
  }
}

function header(list, name) {
  if (!list) { return ''; }
  for (const h of list) {
    if (h.name.toLowerCase() === name) { return h.value || ''; }
  }
  return '';
}

function remember(url, headers) {
  recent.delete(url);
  recent.set(url, headers);
  if (recent.size > 300) {
    recent.delete(recent.keys().next().value);
  }
}

browser.webRequest.onBeforeSendHeaders.addListener(details => {
  if (details.tabId < 0) { return; }
  if (details.type === 'main_frame') { seen.delete(details.tabId); }
  const h = {};
  for (const { name, value } of details.requestHeaders || []) {
    const n = name.toLowerCase();
    if (WANTED_HEADERS.has(n) && value) { h[n] = value; }
  }
  requestHeaders.set(details.requestId, h);
  remember(details.url, h);
}, { urls: ['<all_urls>'], types: TYPES }, ['requestHeaders']);

browser.webRequest.onHeadersReceived.addListener(details => {
  const h = requestHeaders.get(details.requestId) || {};
  requestHeaders.delete(details.requestId);
  if (details.tabId < 0 || details.statusCode >= 400) { return; }
  const url = details.url;
  const ct = header(details.responseHeaders, 'content-type').toLowerCase();
  let kind = null;
  if (ct.includes('mpegurl') || HLS_EXT.test(url)) {
    kind = 'hls';
  } else if (ct.startsWith('video/') && !/mp2t|iso\.segment|mpeg2/.test(ct) && !SEGMENT_EXT.test(url)) {
    kind = 'file';
  } else if (FILE_EXT.test(url) && !ct.startsWith('text/') && !ct.includes('json') && !ct.includes('html')) {
    kind = 'file';
  }
  if (kind === null) { return; }

  let size = parseInt(header(details.responseHeaders, 'content-length'), 10);
  const range = /\/(\d+)\s*$/.exec(header(details.responseHeaders, 'content-range'));
  if (range) { size = parseInt(range[1], 10); }
  if (!Number.isFinite(size)) { size = -1; }
  // Tiny files are usually ad clips or previews.
  if (kind === 'file' && size > 0 && size < 1024 * 1024) { return; }

  let set = seen.get(details.tabId);
  if (!set) {
    set = new Set();
    seen.set(details.tabId, set);
  }
  const key = url.split('#')[0];
  if (set.has(key)) { return; }
  set.add(key);

  post({
    type: 'media',
    tabId: details.tabId,
    url,
    kind,
    mime: ct,
    size,
    headers: h,
    frameUrl: details.documentUrl || '',
    originUrl: details.originUrl || '',
  });
}, { urls: ['<all_urls>'], types: TYPES }, ['responseHeaders']);

browser.webRequest.onErrorOccurred.addListener(details => {
  requestHeaders.delete(details.requestId);
}, { urls: ['<all_urls>'], types: TYPES });

browser.tabs.onRemoved.addListener(tabId => { seen.delete(tabId); });

// Content scripts ask which tab they are in, so the app can match media reports to tabs.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === 'whoami') {
    return Promise.resolve({ tabId: sender.tab ? sender.tab.id : -1 });
  }
  return undefined;
});

connect();
