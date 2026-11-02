'use strict';
// 3.0: the slow part of opening spreadsheets and OpenDocument files, on a thread of its own (a Worker).
// The viewer's page shares its thread with uBlock Origin, which checks every request of every tab:
// SheetJS reads a whole workbook in one go (seconds for a big one), and all that time no page could
// load. Here it reads the file and hands back only what the page draws: a sheet's cells as text,
// with their fills, bold, italic and colours, a few thousand rows at a time.
// view.js loads this same file as a plain script when a Worker can't start, and calls it directly.

(function (scope) {
  const W = {};
  let buf = null;
  let names = [];
  let book = null;

  /** What SheetJS leaves out of an .xlsx: bold, italic and font colours, and frozen rows and columns. */
  async function xlsxBook(data) {
    const zip = await JSZip.loadAsync(data);
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
        const f = paths[i] ? zip.file(paths[i]) : null;
        // 3.0: a big sheet's styles aren't searched cell by cell (its text alone is many megabytes);
        // its frozen panes still are, from the start of the file.
        const size = f && f._data && f._data.uncompressedSize;
        const big = size > STYLED_XML;
        const xml = f ? await f.async('string') : '';
        const styleOf = new Map();
        if (!big) {
          for (const m of xml.matchAll(/<c\s([^>]*?)\/?>/g)) {
            const s = /\bs="(\d+)"/.exec(m[1]);
            if (!s || s[1] === '0') { continue; }
            const r = /\br="([A-Z]+\d+)"/.exec(m[1]);
            if (r) { styleOf.set(r[1], parseInt(s[1], 10)); }
          }
        }
        const pane = /<pane\s[^>]*>/.exec(xml.slice(0, 20000));
        const frozen = pane && /state="frozen"/.test(pane[0]);
        return {
          styleOf,
          rows: frozen ? Math.min(20, parseInt(attr(pane[0], 'ySplit') || '0', 10)) : 0,
          cols: frozen ? Math.min(10, parseInt(attr(pane[0], 'xSplit') || '0', 10)) : 0,
        };
      },
    };
  }

  // A sheet's text past this many characters isn't searched for each cell's style.
  const STYLED_XML = 8 * 1024 * 1024;

  const colName = n => {
    let t = '';
    for (n += 1; n > 0; n = Math.floor((n - 1) / 26)) { t = String.fromCharCode(65 + (n - 1) % 26) + t; }
    return t;
  };

  /** The workbook [data] (an ArrayBuffer), named [name]: its sheets' names. */
  W.sheetOpen = async (data, name) => {
    buf = data;
    book = null;
    names = XLSX.read(buf, { type: 'array', bookSheets: true }).SheetNames || [];
    const head = new Uint8Array(buf, 0, Math.min(4, buf.byteLength));
    if (head[0] === 0x50 && head[1] === 0x4B && /\.(xlsx|xlsm)$/i.test(name || '')) {
      try { book = await xlsxBook(buf); } catch (e) { book = null; }
    }
    return { names };
  };

  /**
   * Sheet [i], its first [maxRows] rows: what the page draws. Each cell is { x: its text, n: a
   * number or date, f: fill colour, b, i: bold, italic, c: text colour }; [more] when the sheet
   * goes on past them.
   */
  W.sheetRead = async (i, maxRows) => {
    const wb = XLSX.read(buf, { type: 'array', sheets: names[i], dense: true, cellDates: true, cellStyles: true, sheetRows: maxRows });
    const ws = wb.Sheets[names[i]] || {};
    const extra = book ? await book.sheet(i).catch(() => null) : null;
    const ref = ws['!ref'] || null;
    const range = ref ? XLSX.utils.decode_range(ref) : null;
    const full = ws['!fullref'] ? XLSX.utils.decode_range(ws['!fullref']) : null;
    const colsInfo = ws['!cols'] || [];
    const rowsInfo = ws['!rows'] || [];
    const cols = [];
    const widths = {};
    if (range) {
      for (let c = range.s.c; c <= range.e.c && cols.length < 200; c++) {
        const ci = colsInfo[c];
        if (ci && ci.hidden) { continue; }
        cols.push(c);
        const w = ci && (ci.wpx || (ci.wch ? ci.wch * 7 + 5 : 0));
        if (w > 0) { widths[c] = w; }
      }
    }
    const rowInfo = [];
    const data = [];
    const dense = ws['!data'] || [];
    if (range) {
      for (let r = range.s.r; r <= range.e.r; r++) {
        const ri = rowsInfo[r];
        if (ri) { rowInfo[r] = { hidden: !!ri.hidden, hpx: ri.hpx || 0 }; }
        const src = dense[r];
        if (!src) { continue; }
        const row = [];
        for (const c of cols) {
          const cell = src[c];
          if (!cell) { continue; }
          const out = { x: cell.w != null ? String(cell.w) : cell.v != null ? String(cell.v) : '' };
          if (cell.t === 'n' || cell.t === 'd') { out.n = 1; }
          const fill = cell.s && cell.s.patternType === 'solid' && cell.s.fgColor && cell.s.fgColor.rgb;
          if (fill && /^[0-9A-Fa-f]{6}$/.test(String(fill).slice(-6))) { out.f = String(fill).slice(-6); }
          if (extra && out.x !== '') {
            const xf = extra.styleOf.get(colName(c) + (r + 1));
            const font = xf != null ? book.fonts[book.xfs[xf]] : null;
            if (font) {
              if (font.b) { out.b = 1; }
              if (font.i) { out.i = 1; }
              if (font.color && /^[0-9A-Fa-f]{6}$/.test(font.color)) { out.c = font.color; }
            }
          }
          row[c] = out;
        }
        data[r] = row;
      }
    }
    return {
      ref,
      more: !!(full && range && full.e.r > range.e.r),
      cols, widths, rowInfo, data,
      merges: (ws['!merges'] || []).slice(0, 5000),
      frozenRows: extra ? extra.rows : 0,
      frozenCols: extra ? extra.cols : 0,
    };
  };

  /** The text of [path] in the ZIP [data] (an OpenDocument file's content.xml), or null. */
  W.zipText = async (data, path) => {
    const zip = await JSZip.loadAsync(data);
    const f = zip.file(path);
    return f ? f.async('string') : null;
  };

  const inWorker = typeof document === 'undefined' && typeof importScripts === 'function';
  if (!inWorker) {
    scope.SwiftWork = W;
    return;
  }
  // The page asks { n, op, args }; the answer is { n, ok } or { n, error }.
  let loaded = false;
  try {
    importScripts('lib/xlsx.full.min.js', 'lib/jszip.min.js');
    loaded = true;
  } catch (e) {
    postMessage({ failed: String(e && e.message || e) });
  }
  if (!loaded) { return; }
  scope.onmessage = async e => {
    const m = e.data || {};
    try {
      if (typeof W[m.op] !== 'function') { throw new Error('Unknown work'); }
      postMessage({ n: m.n, ok: await W[m.op](...(m.args || [])) });
    } catch (err) {
      postMessage({ n: m.n, error: String(err && err.message || err) });
    }
  };
  postMessage({ ready: true });
})(self);
