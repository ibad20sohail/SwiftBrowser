"""Fetch uBlock Origin and install a patched copy as Swift's built-in extension.

Usage:
    python tools/update_ublock.py [--version 1.75.0] [--xpi path/to/uBlock0.firefox.signed.xpi]

The patch:
  * copies tools/ublock-patch/swift-bridge.js into js/,
  * loads it from background.html (after uBO's own start.js),
  * adds the "nativeMessaging" and "geckoViewAddons" permissions it needs,
  * lists document-blocked.html as web-accessible: GeckoView refuses uBO's redirect to its
    "page blocked" screen otherwise, and a blocked site (phishing, malware) shows a blank page,
  * makes js/cachestorage.js keep compiled lists and the filter snapshot only in
    uBO's fast IndexedDB cache, not a second time in extension storage (~25 MB),
  * sizes the element picker for a phone (css/epicker-ui.css).

uBO's version string is left untouched (a 4-part version would switch uBO into
dev-build mode). When the bridge changes, bump UBO_BRIDGE_REV in Engine.kt so the
app reinstalls the extension.

3.0: the XPI's SHA-256 is printed and compared with the one addons.mozilla.org
publishes for that version (GitHub's signed XPI is the same file); a different one,
or none to compare with, stops the update unless --skip-amo-check is given. Files in
the XPI that would land outside the ublock folder are refused.
"""

import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, "app", "src", "main", "assets", "extensions", "ublock")
BRIDGE = os.path.join(ROOT, "tools", "ublock-patch", "swift-bridge.js")
BRIDGE_TAG = '<script src="js/swift-bridge.js" type="module"></script>'

PICKER_CSS = r"""/* Swift: the picker on a phone. */
/* The panel spans the bottom of the screen, with text and buttons sized to the screen's width:
   pages laid out wider than the phone (no mobile version) get them scaled up to match. */
html#ublock0-epicker {
    --font-size: clamp(15px, 4.2vw, 46px);
    --font-size-smaller: calc(var(--font-size) * 0.9);
    --font-size-xsmall: calc(var(--font-size) * 0.8);
    --font-size-larger: calc(var(--font-size) * 1.1);
    --monospace-size: calc(var(--font-size) * 0.85);
    --button-font-size: var(--font-size);
    font-size: var(--font-size);
}
#ublock0-epicker aside,
#ublock0-epicker:not(.paused) aside,
#ublock0-epicker.minimized aside {
    bottom: 1vw !important;
    left: 1vw !important;
    max-width: 98vw !important;
    min-width: 0 !important;
    right: auto !important;
    top: auto !important;
    width: 98vw !important;
}
#ublock0-epicker #toolbar button {
    flex: 1;
    font-size: 1em;
    margin: 0.2em;
    min-height: 2.6em;
}
#ublock0-epicker #windowbar {
    min-height: 2.2em;
}
#ublock0-epicker #candidateFilters .changeFilter li {
    font-size: var(--monospace-size);
    padding: 0.3em 0;
}
"""

CACHE_OLD = r"""                await Promise.all([
                    cacheAPIs[fastCache].set(rawbin, serializedbin),
                    extensionStorage.set(serializedbin),
                ]);"""
CACHE_NEW = r"""                // Swift: what the fast cache keeps (compiled lists, selfie) isn't
                // stored a second time in extension storage, which saves about
                // 25 MB on a phone. Without the fast cache, uBO rebuilds it.
                const mirrored = fastCache === 'indexedDB'
                    ? keys.filter(k => /^cache\/(compiled|selfie)\//.test(k))
                    : [];
                const rest = {};
                for ( const key of keys ) {
                    if ( mirrored.includes(key) ) { continue; }
                    rest[key] = serializedbin[key];
                }
                await Promise.all([
                    cacheAPIs[fastCache].set(rawbin, serializedbin),
                    extensionStorage.set(rest),
                    mirrored.length !== 0
                        ? extensionStorage.remove(mirrored)
                        : Promise.resolve(),
                ]);"""


def fetch(version):
    url = (
        "https://github.com/gorhill/uBlock/releases/download/"
        f"{version}/uBlock0_{version}.firefox.signed.xpi"
    )
    print("Downloading", url)
    with urllib.request.urlopen(url, timeout=120) as resp:
        return resp.read()


AMO_VERSION = "https://addons.mozilla.org/api/v5/addons/addon/uBlock0@raymondhill.net/versions/{version}/"


def amo_sha256(version):
    """The SHA-256 addons.mozilla.org publishes for uBO's signed XPI of [version], or None."""
    url = AMO_VERSION.format(version=urllib.parse.quote(version, safe=""))
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            info = json.load(resp)
    except Exception as e:  # offline, AMO down, no such version
        print("Couldn't get the hash from addons.mozilla.org:", e)
        return None
    published = ((info.get("file") or {}).get("hash") or "").lower()
    return published[len("sha256:"):] if published.startswith("sha256:") else None


def check_xpi(data, skip):
    """Prints the XPI's SHA-256 and stops unless it is the file AMO published for its version."""
    digest = hashlib.sha256(data).hexdigest()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        version = json.loads(z.read("manifest.json").decode("utf-8")).get("version", "")
    print("uBlock Origin", version, "XPI SHA-256:", digest)
    if skip:
        print("Not compared with addons.mozilla.org (--skip-amo-check)")
        return
    published = amo_sha256(version)
    if published is None:
        raise SystemExit("No hash to compare with: run again online, or with --skip-amo-check after checking it yourself")
    if published != digest:
        raise SystemExit(f"This XPI isn't the one addons.mozilla.org published for {version} (its SHA-256 is {published}): stopped")
    print("Matches the file addons.mozilla.org published for", version)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.75.0")
    ap.add_argument("--xpi")
    ap.add_argument(
        "--locales", default="en",
        help="comma-separated uBO UI languages to keep (default: en); 'all' keeps every language",
    )
    ap.add_argument(
        "--skip-amo-check", action="store_true",
        help="don't compare the XPI's SHA-256 with the one addons.mozilla.org publishes",
    )
    args = ap.parse_args()

    data = open(args.xpi, "rb").read() if args.xpi else fetch(args.version)
    check_xpi(data, args.skip_amo_check)

    if os.path.isdir(DEST):
        shutil.rmtree(DEST)
    os.makedirs(DEST)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            if info.filename.startswith("META-INF/"):
                continue  # Mozilla signature; meaningless once patched.
            target = os.path.normpath(os.path.join(DEST, info.filename))
            # 3.0: inside the folder itself (DEST + separator): "../ublock-x/…" starts with DEST too.
            if target == DEST and info.is_dir():
                continue
            if not target.startswith(DEST + os.sep):
                raise SystemExit("Refusing unsafe path in archive: " + info.filename)
            if info.is_dir():
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(info) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)

    if args.locales != "all":
        keep = {code.strip() for code in args.locales.split(",") if code.strip()}
        keep.add("en")  # default_locale must exist
        locales_dir = os.path.join(DEST, "_locales")
        for code in os.listdir(locales_dir):
            if code not in keep:
                shutil.rmtree(os.path.join(locales_dir, code))

    manifest_path = os.path.join(DEST, "manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    perms = manifest.setdefault("permissions", [])
    for p in ("nativeMessaging", "geckoViewAddons"):
        if p not in perms:
            perms.append(p)
    war = manifest.setdefault("web_accessible_resources", [])
    if "/document-blocked.html" not in war:
        war.append("/document-blocked.html")
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")

    shutil.copyfile(BRIDGE, os.path.join(DEST, "js", "swift-bridge.js"))

    bg_path = os.path.join(DEST, "background.html")
    with open(bg_path, encoding="utf-8") as f:
        html = f.read()
    if BRIDGE_TAG not in html:
        anchor = '<script src="js/start.js" type="module"></script>'
        if anchor not in html:
            raise SystemExit("background.html changed upstream; update the patch anchor")
        html = html.replace(anchor, anchor + "\n" + BRIDGE_TAG)
    with open(bg_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(html)

    cache_path = os.path.join(DEST, "js", "cachestorage.js")
    with open(cache_path, encoding="utf-8") as f:
        js = f.read()
    if CACHE_NEW not in js:
        if CACHE_OLD not in js:
            raise SystemExit("js/cachestorage.js changed upstream; update the storage patch")
        js = js.replace(CACHE_OLD, CACHE_NEW)
    with open(cache_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(js)

    picker_path = os.path.join(DEST, "css", "epicker-ui.css")
    with open(picker_path, encoding="utf-8") as f:
        css = f.read()
    if "/* Swift: the picker on a phone. */" not in css:
        with open(picker_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(css.rstrip("\n") + "\n\n" + PICKER_CSS)

    print("uBlock Origin", manifest.get("version"), "installed to", DEST)


if __name__ == "__main__":
    sys.exit(main())
