#!/bin/sh
set -eu

# Visual smoke test for the 1080x600 AAOS landscape emulator profile. It is
# intentionally structural rather than pixel-for-pixel: the map changes as
# the app moves, while the Automotive host chrome must keep these anchors.
ADB=${ADB:-adb}
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-vanilla-host.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT

remote=/sdcard/caramel-vanilla-host-layout.png
"$ADB" shell screencap -p "$remote" >/dev/null
"$ADB" pull -q "$remote" "$tmp_dir/screen.png"

python3 - "$tmp_dir/screen.png" <<'PY'
import struct
import sys
import zlib


def decode_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("FAIL: screenshot is not a PNG")
    pos = 8
    compressed = bytearray()
    width = height = color_type = None
    while pos < len(data):
        size = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + size]
        pos += size + 12
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, _ = struct.unpack(
                ">IIBBBBB", chunk
            )
            if depth != 8 or color_type != 6:
                raise SystemExit("FAIL: expected 8-bit RGBA screenshot")
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break

    raw = zlib.decompress(compressed)
    bpp = 4
    stride = width * bpp
    rows = []
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        current = bytearray(raw[pos:pos + stride])
        pos += stride
        for i in range(stride):
            left = current[i - bpp] if i >= bpp else 0
            above = previous[i]
            upper_left = previous[i - bpp] if i >= bpp else 0
            if filter_type == 1:
                current[i] = (current[i] + left) & 255
            elif filter_type == 2:
                current[i] = (current[i] + above) & 255
            elif filter_type == 3:
                current[i] = (current[i] + (left + above) // 2) & 255
            elif filter_type == 4:
                estimate = left + above - upper_left
                pa = abs(estimate - left)
                pb = abs(estimate - above)
                pc = abs(estimate - upper_left)
                predictor = left if pa <= pb and pa <= pc else above if pb <= pc else upper_left
                current[i] = (current[i] + predictor) & 255
            elif filter_type != 0:
                raise SystemExit("FAIL: unsupported PNG filter")
        rows.append(current)
        previous = current
    return width, height, rows


width, height, rows = decode_png(sys.argv[1])
if (width, height) != (1080, 600):
    raise SystemExit(f"FAIL: expected 1080x600, got {width}x{height}")


def pixel(x, y):
    row = rows[y]
    return tuple(row[x * 4:x * 4 + 4])


def is_neutral_dark(value):
    r, g, b, _ = value
    return max(r, g, b) < 105 and max(value[:3]) - min(value[:3]) < 18


checks = {
    "left panel begins at the stock 9px margin": is_neutral_dark(pixel(10, 80)),
    "content clears the status bar": not is_neutral_dark(pixel(30, 60)),
    "settings control exists": is_neutral_dark(pixel(970, 75)),
    "search control exists": is_neutral_dark(pixel(1040, 75)),
}
failed = [name for name, passed in checks.items() if not passed]
if failed:
    for name in failed:
        print("FAIL:", name)
    raise SystemExit(1)
print("PASS: Automotive host layout anchors match the stock landscape profile")
PY
