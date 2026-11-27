#!/usr/bin/env python3
"""Download Termux Android QEMU 11.1.3 patches and setjmp-aarch64 shim."""
import json
import os
import urllib.request

ROOT = "/Users/ryomartin/.gemini/antigravity/scratch/android16-emu"
MANIFEST = os.path.join(ROOT, "ref", "termux-qemu-files.json")
OUT_DIR = os.path.join(ROOT, "ref", "termux-patches")
os.makedirs(OUT_DIR, exist_ok=True)

def download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "android16-emu-builder"})
    with urllib.request.urlopen(req) as resp, open(dest, "wb") as f:
        f.write(resp.read())

items = json.load(open(MANIFEST))
for item in items:
    name = item["name"]
    if item["type"] == "file" and name.endswith(".patch"):
        dest = os.path.join(OUT_DIR, name)
        print("Downloading", name)
        download(item["download_url"], dest)
    elif item["type"] == "dir" and name == "setjmp-aarch64":
        sub_dir = os.path.join(OUT_DIR, name)
        os.makedirs(sub_dir, exist_ok=True)
        req = urllib.request.Request(item["url"], headers={"User-Agent": "android16-emu-builder"})
        sub_items = json.loads(urllib.request.urlopen(req).read())
        for sub in sub_items:
            if sub["type"] == "file":
                dest = os.path.join(sub_dir, sub["name"])
                print("Downloading setjmp-aarch64/" + sub["name"])
                download(sub["download_url"], dest)

print("Done! Saved patches to", OUT_DIR)
