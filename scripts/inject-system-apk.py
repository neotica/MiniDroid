#!/usr/bin/env python3
"""
Append an APK directly into /system/app/<name>.apk inside an Android 1.6 (Donut)
YAFFS2 system.img (2048-byte pages + 64-byte OOB).

Usage:
    python3 scripts/inject-system-apk.py images/system-stock.img images/HoloMarket-v1.5.2.apk images/system.img HoloMarket.apk
"""
import os
import struct
import sys

PAGE_DATA = 2048
PAGE_OOB = 64
PAGE_TOTAL = PAGE_DATA + PAGE_OOB
SEQ_NUMBER = 0x1000


def find_app_dir_and_template(img: bytes):
    n_pages = len(img) // PAGE_TOTAL
    app_oid = None
    template_hdr = None
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

    if app_oid is None or template_hdr is None:
        raise RuntimeError("Could not locate /app directory or template file header in YAFFS2 image")
    return app_oid, template_hdr, max_oid


def inject_apk(src_img_path: str, apk_path: str, out_img_path: str, target_name: str):
    img = open(src_img_path, "rb").read()
    if len(img) % PAGE_TOTAL != 0:
        raise RuntimeError(f"Unexpected YAFFS2 image size (not multiple of {PAGE_TOTAL}): {len(img)}")

    apk = open(apk_path, "rb").read()
    app_oid, hdr, max_oid = find_app_dir_and_template(img)
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
