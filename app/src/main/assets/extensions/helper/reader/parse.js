'use strict';

// Injected into a page (after Readability.js) when the user asks for reader view. The value of the
// last expression is the article handed back to the background script, or null.
(function () {
  try {
    const article = new Readability(document.cloneNode(true), { charThreshold: 400 }).parse();
    if (!article || !article.content || (article.textContent || '').trim().length < 300) { return null; }
    return {
      title: article.title || document.title || '',
      byline: article.byline || '',
      siteName: article.siteName || location.hostname.replace(/^www\./, ''),
      content: article.content,
      lang: article.lang || document.documentElement.lang || '',
      dir: article.dir || '',
      length: article.length || 0,
      url: location.href,
    };
  } catch (e) {
    return null;
  }
})();
