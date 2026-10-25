"""Fetch uBlock Origin and install a patched copy as Swift's built-in extension.

Usage:
    python tools/update_ublock.py [--version 1.75.0] [--xpi path/to/uBlock0.firefox.signed.xpi]

The patch:
  * copies tools/ublock-patch/swift-bridge.js into js/,
  * loads it from background.html (after uBO's own start.js),
  * adds the "nativeMessaging" and "geckoViewAddons" permissions it needs,
  * makes js/cachestorage.js keep compiled lists and the filter snapshot only in
    uBO's fast IndexedDB cache, not a second time in extension storage (~25 MB).

uBO's version string is left untouched (a 4-part version would switch uBO into
dev-build mode). When the bridge changes, bump UBO_BRIDGE_REV in Engine.kt so the
app reinstalls the extension.
"""

import argparse
import io
import json
import os
import shutil
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, "app", "src", "main", "assets", "extensions", "ublock")
BRIDGE = os.path.join(ROOT, "tools", "ublock-patch", "swift-bridge.js")
BRIDGE_TAG = '<script src="js/swift-bridge.js" type="module"></script>'

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
    with urllib.request.urlopen(url) as resp:
        return resp.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.75.0")
    ap.add_argument("--xpi")
    ap.add_argument(
        "--locales", default="en",
        help="comma-separated uBO UI languages to keep (default: en); 'all' keeps every language",
    )
    args = ap.parse_args()

    data = open(args.xpi, "rb").read() if args.xpi else fetch(args.version)

    if os.path.isdir(DEST):
        shutil.rmtree(DEST)
    os.makedirs(DEST)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            if info.filename.startswith("META-INF/"):
                continue  # Mozilla signature; meaningless once patched.
            target = os.path.normpath(os.path.join(DEST, info.filename))
            if not target.startswith(DEST):
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

    print("uBlock Origin", manifest.get("version"), "installed to", DEST)


if __name__ == "__main__":
    sys.exit(main())
