#!/usr/bin/env python3
"""
Customize an Android 1.6 (Donut) YAFFS2 system.img (2048-byte pages + 64-byte OOB):
  1. Append an APK directly into /system/app/<name>.apk
  2. Optionally append a one-time Launcher shortcut hook to /system/etc/init.goldfish.sh
     so the bundled app appears on the stock Home screen right next to Browser.

Usage:
    python3 scripts/inject-system-apk.py images/system-stock.img images/HoloMarket-v1.5.3.apk images/system.img HoloMarket.apk
"""
import os
import struct
import sys

PAGE_DATA = 2048
PAGE_OOB = 64
PAGE_TOTAL = PAGE_DATA + PAGE_OOB
SEQ_NUMBER = 0x1000

PIN_HOLOMARKET_HOOK = """
# Pin built-in HoloMarket on stock Home screen right next to Browser (screen 1, cellX=3, cellY=3)
if ls /data/misc/.holomarket_pinned >/dev/null 2>&1; then
    :
else
(
    DB=/data/data/com.android.launcher/databases/launcher.db
    for t in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
        if ls "$DB" >/dev/null 2>&1; then
            cnt=`sqlite3 "$DB" "SELECT count(*) FROM favorites;" 2>/dev/null`
            case "$cnt" in
                ""|0) ;;
                *)
                    has=`sqlite3 "$DB" "SELECT count(*) FROM favorites WHERE intent LIKE '%id.neotica.holomarket%';" 2>/dev/null`
                    case "$has" in
                        0)
                            cx=3; cy=3
                            occ=`sqlite3 "$DB" "SELECT count(*) FROM favorites WHERE container=-100 AND screen=1 AND cellX=3 AND cellY=3;" 2>/dev/null`
                            case "$occ" in
                                0) ;;
                                *) cx=1; cy=2 ;;
                            esac
                            sqlite3 "$DB" "INSERT INTO favorites (title,intent,container,screen,cellX,cellY,spanX,spanY,itemType) VALUES ('HoloMarket','#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=id.neotica.holomarket/.feature.home.ui.MainActivity;end',-100,1,$cx,$cy,1,1,0);" 2>/dev/null
                            ps | while read u p rest; do
                                case "$rest" in
                                    *android.process.acore*) kill "$p" 2>/dev/null ;;
                                esac
                            done
                            ;;
                    esac
                    echo 1 > /data/misc/.holomarket_pinned
                    break
                    ;;
            esac
        fi
        sleep 2
    done
) &
fi
"""


def find_app_dir_and_template(img: bytes):
    n_pages = len(img) // PAGE_TOTAL
    app_oid = None
    template_hdr = None
    init_sh_oid = None
    max_oid = 0

    for p in range(n_pages):
        off = p * PAGE_TOTAL
        oob = img[off + PAGE_DATA : off + PAGE_TOTAL]
        seq, oid, cid, nbytes = struct.unpack_from("<IIII", oob, 0)
        if oid < 0x100000 and oid > max_oid:
            max_oid = oid
        if cid == 0 and nbytes == 0xFFFF:
            obj_type, parent_id = struct.unpack_from("<II", img, off)
            name = img[off + 10 : off + 266].split(b"\x00", 1)[0].decode("latin1", "replace")
            if obj_type == 3 and parent_id == 1 and name == "app":
                app_oid = oid
            elif obj_type == 1 and app_oid is not None and parent_id == app_oid and template_hdr is None:
                template_hdr = bytearray(img[off : off + PAGE_DATA])
            elif obj_type == 1 and name == "init.goldfish.sh":
                init_sh_oid = oid

    if app_oid is None or template_hdr is None:
        raise RuntimeError("Could not locate /app directory or template file header in YAFFS2 image")
    return app_oid, template_hdr, init_sh_oid, max_oid


def patch_init_goldfish_sh(out: bytearray, init_sh_oid: int, hook_text: str):
    n_pages = len(out) // PAGE_TOTAL
    hdr_page = None
    chunks = {}
    orig_size = 0

    for p in range(n_pages):
        off = p * PAGE_TOTAL
        seq, oid, cid, nbytes = struct.unpack_from("<IIII", out, off + PAGE_DATA)
        if oid == init_sh_oid:
            if cid == 0 and nbytes == 0xFFFF:
                hdr_page = p
                orig_size = struct.unpack_from("<I", out, off + 0x124)[0]
            elif cid > 0:
                chunks[cid] = (p, bytes(out[off : off + nbytes]))

    if hdr_page is None:
        raise RuntimeError("Could not locate init.goldfish.sh header page")

    orig_script = b"".join(chunks[k][1] for k in sorted(chunks))[:orig_size]
    if b".holomarket_pinned" in orig_script:
        return

    new_script = orig_script.rstrip(b"\n") + b"\n" + hook_text.encode("ascii")
    # Pad to the next 64-page YAFFS2 erase block boundary so every erase block has a uniform seqNumber
    pages_now = len(out) // PAGE_TOTAL
    rem = pages_now % 64
    if rem != 0:
        out.extend(b"\xff" * ((64 - rem) * PAGE_TOTAL))

    # Update header + data using next YAFFS2 sequence number (cleanly supports >1 page)
    new_seq = SEQ_NUMBER + 1
    hdr = bytearray(out[hdr_page * PAGE_TOTAL : hdr_page * PAGE_TOTAL + PAGE_DATA])
    struct.pack_into("<I", hdr, 0x124, len(new_script))
    hdr_oob = bytearray(PAGE_OOB)
    struct.pack_into("<IIII", hdr_oob, 0, new_seq, init_sh_oid, 0, 0xFFFF)
    out.extend(hdr)
    out.extend(hdr_oob)

    cid = 1
    for off in range(0, len(new_script), PAGE_DATA):
        piece = new_script[off : off + PAGE_DATA]
        page = bytearray(b"\xff" * PAGE_DATA)
        page[: len(piece)] = piece
        oob = bytearray(PAGE_OOB)
        struct.pack_into("<IIII", oob, 0, new_seq, init_sh_oid, cid, len(piece))
        out.extend(page)
        out.extend(oob)
        cid += 1

    print(f"Patched /system/etc/init.goldfish.sh ({orig_size} -> {len(new_script)} bytes, oid={init_sh_oid})")


def inject_apk(src_img_path: str, apk_path: str, out_img_path: str, target_name: str, pin_holomarket: bool = True):
    img = open(src_img_path, "rb").read()
    if len(img) % PAGE_TOTAL != 0:
        raise RuntimeError(f"Unexpected YAFFS2 image size (not multiple of {PAGE_TOTAL}): {len(img)}")

    apk = open(apk_path, "rb").read()
    app_oid, hdr, init_sh_oid, max_oid = find_app_dir_and_template(img)
    new_oid = max_oid + 1

    # Update object header: name (offset 10..265), mode=0100644 (0x10c), uid/gid=0, fileSize (0x124)
    name_bytes = target_name.encode("ascii")
    if len(name_bytes) > 254:
        raise ValueError("Target filename too long")
    hdr[10:266] = b"\x00" * 256
    hdr[10 : 10 + len(name_bytes)] = name_bytes
    struct.pack_into("<I", hdr, 0x04, app_oid)     # parentObjectId = /app
    struct.pack_into("<I", hdr, 0x10C, 0o100644)   # yst_mode = regular file 0644
    struct.pack_into("<I", hdr, 0x110, 0)          # yst_uid = root
    struct.pack_into("<I", hdr, 0x114, 0)          # yst_gid = root
    struct.pack_into("<I", hdr, 0x124, len(apk))   # fileSize

    out = bytearray(img)
    # Append header page (chunkId = 0, byteCount = 0xffff)
    hdr_oob = bytearray(PAGE_OOB)
    struct.pack_into("<IIII", hdr_oob, 0, SEQ_NUMBER, new_oid, 0, 0xFFFF)
    out.extend(hdr)
    out.extend(hdr_oob)

    # Append data pages (chunkId = 1..N)
    chunk_id = 1
    for off in range(0, len(apk), PAGE_DATA):
        piece = apk[off : off + PAGE_DATA]
        page = bytearray(b"\xff" * PAGE_DATA)
        page[: len(piece)] = piece
        oob = bytearray(PAGE_OOB)
        struct.pack_into("<IIII", oob, 0, SEQ_NUMBER, new_oid, chunk_id, len(piece))
        out.extend(page)
        out.extend(oob)
        chunk_id += 1

    if pin_holomarket and init_sh_oid is not None:
        patch_init_goldfish_sh(out, init_sh_oid, PIN_HOLOMARKET_HOOK)

    with open(out_img_path, "wb") as f:
        f.write(out)

    print(
        f"Injected /system/app/{target_name} ({len(apk)} bytes, oid={new_oid}, "
        f"{chunk_id} pages) -> {out_img_path} ({len(out)} bytes)"
    )


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print(f"Usage: {sys.argv[0]} <stock_system.img> <apk_path> <out_system.img> [TargetName.apk]")
        sys.exit(1)
    src, apk, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    name = sys.argv[4] if len(sys.argv) >= 5 else os.path.basename(apk)
    inject_apk(src, apk, dst, name)
