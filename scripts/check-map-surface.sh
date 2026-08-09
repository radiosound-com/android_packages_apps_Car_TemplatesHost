#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
OUTPUT="${1:-/tmp/caramel-host-map-surface.png}"

"$ADB" wait-for-device
"$ADB" shell am force-stop com.android.car.carlauncher
"$ADB" shell am start -W -n com.android.car.carlauncher/.CarLauncher >/dev/null
sleep "${WAIT_SECONDS:-8}"
"$ADB" exec-out screencap -p > "$OUTPUT"

OUTPUT="$OUTPUT" python3 - <<'PY'
from pathlib import Path
import os
import statistics
import struct
import zlib

path = Path(os.environ["OUTPUT"])
data = path.read_bytes()
assert data[:8] == b"\x89PNG\r\n\x1a\n", f"not a PNG: {path}"

offset = 8
width = height = None
raw = bytearray()
while offset < len(data):
    length = struct.unpack(">I", data[offset:offset + 4])[0]
    kind = data[offset + 4:offset + 8]
    chunk = data[offset + 8:offset + 8 + length]
    offset += 12 + length
    if kind == b"IHDR":
        width, height = struct.unpack(">II", chunk[:8])
        color_type = chunk[9]
        assert color_type in (2, 6), f"unsupported PNG color type {color_type}"
        channels = 4 if color_type == 6 else 3
    elif kind == b"IDAT":
        raw.extend(chunk)
    elif kind == b"IEND":
        break

decoded = zlib.decompress(bytes(raw))
bpp = channels
stride = width * bpp
rows = []
previous = bytearray(stride)
cursor = 0
for _ in range(height):
    filter_type = decoded[cursor]
    cursor += 1
    row = bytearray(decoded[cursor:cursor + stride])
    cursor += stride
    for i in range(stride):
        left = row[i - bpp] if i >= bpp else 0
        up = previous[i]
        up_left = previous[i - bpp] if i >= bpp else 0
        if filter_type == 1:
            row[i] = (row[i] + left) & 255
        elif filter_type == 2:
            row[i] = (row[i] + up) & 255
        elif filter_type == 3:
            row[i] = (row[i] + ((left + up) // 2)) & 255
        elif filter_type == 4:
            p = left + up - up_left
            pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
            predictor = left if pa <= pb and pa <= pc else up if pb <= pc else up_left
            row[i] = (row[i] + predictor) & 255
        elif filter_type != 0:
            raise AssertionError(f"unsupported PNG filter {filter_type}")
    rows.append(row)
    previous = row

# The center-right of the car-app map remains uncovered by the route/list
# card and action stack. Avoid the launcher media pane and the vertical map
# controls so the probe measures rendered map pixels rather than chrome.
left = max(0, int(width * 0.62))
top = int(height * 0.20)
right = max(left + 1, int(width * 0.80))
bottom = int(height * 0.78)
pixels = []
for y in range(top, min(height, bottom)):
    row = rows[y]
    for x in range(left, min(width, right)):
        base = x * bpp
        pixels.append(tuple(row[base:base + 3]))

unique = len(set(pixels))
luma = [0.2126 * r + 0.7152 * g + 0.0722 * b for r, g, b in pixels]
spread = statistics.pstdev(luma) if len(luma) > 1 else 0.0
print(f"[HOST-MAP-TEST] image={path} size={width}x{height} probe={left},{top}-{right},{bottom} unique={unique} luma_stdev={spread:.2f}")
assert unique >= 8 and spread >= 2.0, (
    "hosted map surface is blank or uniform in the map probe "
    f"(unique={unique}, luma_stdev={spread:.2f})"
)
PY
