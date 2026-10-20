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

applySize();
browser.runtime.sendMessage({ type: 'reader-get', id: location.hash.slice(1) }).then(render, () => render(null));
