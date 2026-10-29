'use strict';

// Detects video streams/files that pages load and reports them to the app.
//
// Kept deliberately cheap: only request types that can carry video (no images,
// scripts, styles or fonts), script requests only when their address looks like a
// video (see XHR_URLS), and no request-header capture.
// The page or player frame that loaded the video is used as the Referer when
// downloading, which is what most video hosts check.

const NATIVE_APP = 'swift';
const FILE_EXT = /\.(mp4|m4v|webm|mkv|mov|avi|flv|3gp)(?:[?#]|$)/i;
const SEGMENT_EXT = /\.(ts|m4s|m4a|aac|mp3|vtt|webvtt|srt|key|jpg|jpeg|png|gif|webp)(?:[?#]|$)/i;
const HLS_EXT = /\.m3u8(?:[?#]|$)/i;
const MIN_FILE_SIZE = 1024 * 1024;

// Script requests (XHR / fetch) are most of what pages load, and each one woke this script with
// its headers only to be ignored. From 2.6 only those whose address names a stream playlist or a
// video file come here: the only script requests ever reported (pieces of streams, YouTube's
// videoplayback, APIs and analytics never were). Addresses are matched as written, so upper case
// is listed too. Movie sites often hand out playlists from addresses like /playlist.php or
// /hls/master: those words count as well.
const XHR_URLS = [];
for (const s of ['m3u8', 'M3U8', 'mpegurl', 'mpegURL', 'playlist', 'Playlist', 'manifest', 'master', 'hls', 'HLS']) {
  XHR_URLS.push('*://*/*' + s + '*');
}
for (const ext of ['mp4', 'm4v', 'webm', 'mkv', 'mov', 'avi', 'flv', '3gp']) {
  XHR_URLS.push('*://*/*.' + ext + '*', '*://*/*.' + ext.toUpperCase() + '*');
}

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
  } else if (msg.type === 'look') {
    lookFromApp = true;
    setLook({
      lite: msg.lite === true,
      fixes: msg.fixes !== false,
      liteExcept: Array.isArray(msg.liteExcept) ? msg.liteExcept : [],
      darkSites: sites(msg.darkSites),
      keepPlaying: msg.keepPlaying === true,
      readerFont: msg.readerFont === 'sans' ? 'sans' : 'serif',
      readerSpacing: Number.isInteger(msg.readerSpacing) ? msg.readerSpacing : 1,
      accentLight: /^#[0-9a-f]{6}$/i.test(msg.accentLight || '') ? msg.accentLight : '#1b66e4',
      accentDark: /^#[0-9a-f]{6}$/i.test(msg.accentDark || '') ? msg.accentDark : '#8ab4ff',
      pageLight: colours(msg.pageLight),
      pageDark: colours(msg.pageDark),
    });
  } else if (msg.type === 'clear-articles') {
    // Clear browsing data (2.6): the articles kept for reader view go too.
    articles.clear();
    browser.storage.local.get(null).then(all => {
      const keys = Object.keys(all || {}).filter(k => k === 'articleIds' || k.startsWith('article:'));
      return keys.length ? browser.storage.local.remove(keys) : null;
    }).catch(() => { });
  }
}

/** The app's page colours for reader view (2.6): only well-formed colours are kept. */
function colours(c) {
  if (!c || typeof c !== 'object') { return null; }
  const out = {};
  for (const k of ['bg', 'surface', 'text', 'muted', 'line', 'accent', 'onAccent']) {
    if (/^#[0-9a-f]{6}$/i.test(c[k] || '')) { out[k] = c[k]; }
  }
  return out;
}

// ---------------------------------------------------------------- how sites look

// Styles pages get from their first byte on:
//  - lite: Lite mode, every page without animations, shadows or blur (but the sites the user
//    left out of it);
//  - youtube, theflixer: lighter versions of these two sites (no big blurs), unless the app
//    shows sites as designed;
//  - dark: the sites the user turned dark (Site settings, 2.6).
// The last choice is kept, so pages loading before the app has said it again look the same.
// It also holds keepPlaying, which content scripts read (see content.js).
const STYLES = {
  lite: { matches: ['http://*/*', 'https://*/*'], css: [{ file: '/perf/lite.css' }], allFrames: true },
  youtube: { matches: ['*://*.youtube.com/*'], css: [{ file: '/perf/youtube.css' }] },
  theflixer: {
    matches: ['http://*/*', 'https://*/*'],
    includeGlobs: ['*://theflixer.*', '*://*.theflixer.*'],
    css: [{ file: '/perf/theflixer.css' }],
  },
  dark: { css: [{ file: '/perf/dark.css' }] },
};

/** Site names as the app sends them (example.com), nothing else. */
function sites(list) {
  return Array.isArray(list) ? list.filter(s => typeof s === 'string' && /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(s)) : [];
}
const registrations = new Map();
let lookQueue = Promise.resolve();
let lookFromApp = false;

const registered = new Map(); // name -> what it was registered with

// [only]: just these sites (and their subdomains); [exclude]: all sites but these.
function setStyle(name, on, exclude, only) {
  const key = on ? JSON.stringify([exclude || [], only || []]) : null;
  lookQueue = lookQueue.then(async () => {
    if ((registered.get(name) || null) === key) { return; }
    const old = registrations.get(name);
    if (old) {
      registrations.delete(name);
      registered.delete(name);
      try { await old.unregister(); } catch (e) { }
    }
    if (!on) { return; }
    const options = Object.assign({ runAt: 'document_start' }, STYLES[name]);
    if (only) {
      options.matches = [];
      for (const site of only) { options.matches.push('*://' + site + '/*', '*://*.' + site + '/*'); }
    }
    if (exclude && exclude.length > 0) {
      options.excludeMatches = [];
      for (const site of exclude) { options.excludeMatches.push('*://' + site + '/*', '*://*.' + site + '/*'); }
    }
    try {
      registrations.set(name, await browser.contentScripts.register(options));
      registered.set(name, key);
    } catch (e) { }
  });
}

function setLook(look) {
  setStyle('lite', look.lite, look.liteExcept);
  setStyle('youtube', look.fixes);
  setStyle('theflixer', look.fixes);
  const dark = look.darkSites || [];
  setStyle('dark', dark.length > 0, null, dark);
  browser.storage.local.set({ look }).catch(() => { });
}

browser.storage.local.get('look').then(r => {
  const look = (r && r.look) || {};
  if (!lookFromApp) {
    setLook({
      lite: look.lite === true,
      fixes: look.fixes !== false,
      liteExcept: Array.isArray(look.liteExcept) ? look.liteExcept : [],
      darkSites: sites(look.darkSites),
      keepPlaying: look.keepPlaying === true,
      readerFont: look.readerFont === 'sans' ? 'sans' : 'serif',
      readerSpacing: Number.isInteger(look.readerSpacing) ? look.readerSpacing : 1,
      accentLight: /^#[0-9a-f]{6}$/i.test(look.accentLight || '') ? look.accentLight : '#1b66e4',
      accentDark: /^#[0-9a-f]{6}$/i.test(look.accentDark || '') ? look.accentDark : '#8ab4ff',
    });
  }
}, () => {
  if (!lookFromApp) { setLook({ lite: false, fixes: true, liteExcept: [], keepPlaying: false }); }
});

// ---------------------------------------------------------------- reader view

// Articles parsed for reader view, by id, until the reader page has fetched them (a few kept).
// They are also kept in storage (the last few), so a reader page still works after Swift restarts.
const articles = new Map();
const KEPT_ARTICLES = 8;

function keepArticle(id, article) {
  browser.storage.local.get('articleIds').then(r => {
    const ids = ((r && r.articleIds) || []).filter(x => x !== id);
    ids.push(id);
    const gone = ids.splice(0, Math.max(0, ids.length - KEPT_ARTICLES));
    const add = { articleIds: ids };
    add['article:' + id] = article;
    return browser.storage.local.set(add).then(() => gone.length ? browser.storage.local.remove(gone.map(x => 'article:' + x)) : null);
  }).catch(() => { });
}

async function startReader(tabId, incognito) {
  try {
    await browser.tabs.executeScript(tabId, { file: '/reader/Readability.js' });
    const results = await browser.tabs.executeScript(tabId, { file: '/reader/parse.js' });
    const article = results && results[0];
    if (!article) {
      post({ type: 'reader-failed', tabId });
      return;
    }
    const id = Date.now().toString(36);
    articles.set(id, article);
    while (articles.size > 6) { articles.delete(articles.keys().next().value); }
    // An incognito tab's article is never written down (2.6): it lives only in memory.
    if (!incognito) { keepArticle(id, article); }
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

function onMediaHeaders(details) {
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
}

// Two functions: the same one can't be added twice with different filters.
browser.webRequest.onHeadersReceived.addListener(
  d => onMediaHeaders(d), { urls: ['<all_urls>'], types: ['media', 'other'] }, ['responseHeaders']);
browser.webRequest.onHeadersReceived.addListener(
  d => onMediaHeaders(d), { urls: XHR_URLS, types: ['xmlhttprequest'] }, ['responseHeaders']);

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
    startReader(sender.tab.id, sender.tab.incognito === true);
    return undefined;
  }
  if (msg && msg.type === 'reader-get') {
    const id = String(msg.id);
    if (articles.has(id)) { return Promise.resolve(articles.get(id)); }
    return browser.storage.local.get('article:' + id).then(r => (r && r['article:' + id]) || null, () => null);
  }
  return undefined;
});

connect();
