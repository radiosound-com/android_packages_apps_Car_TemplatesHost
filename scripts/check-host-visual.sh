#!/bin/sh
# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.

set -eu

ADB=${ADB:-adb}
PACKAGE=${PACKAGE:-com.android.car.libraries.templates.conformance}
COMPONENT="$PACKAGE/androidx.car.app.activity.CarAppActivity"
HOST=com.android.car.libraries.templates.host
DISPLAY_ID=${DISPLAY_ID:-4619827259835644672}
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/caramel-host-visual.XXXXXX")
trap 'rm -f "$TMP_DIR/grid.png"' EXIT

# Regression check for the two renderer seams that are easy to break: a
# normal template must have an opaque neutral backdrop, and its header must
# be visible below the top system bar.
"$ADB" shell pm disable-user --user 10 com.google.android.apps.automotive.templates.host >/dev/null 2>&1 || true
"$ADB" shell pm enable --user 10 "$HOST" >/dev/null 2>&1
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -W -n "$COMPONENT" --es mode grid >/dev/null
sleep 2
"$ADB" exec-out screencap -p -d "$DISPLAY_ID" > "$TMP_DIR/grid.png"

python3 - "$TMP_DIR/grid.png" <<'PY'
import struct
import sys
import zlib

data = open(sys.argv[1], "rb").read()
assert data[:8] == b"\x89PNG\r\n\x1a\n"
pos = 8
raw = b""
width = height = bit_depth = color_type = None
while pos < len(data):
    size = struct.unpack(">I", data[pos:pos + 4])[0]
    kind = data[pos + 4:pos + 8]
    chunk = data[pos + 8:pos + 8 + size]
    pos += size + 12
    if kind == b"IHDR":
        width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", chunk)
    elif kind == b"IDAT":
        raw += chunk
    elif kind == b"IEND":
        break
assert (width, height, bit_depth, color_type) == (1080, 600, 8, 6)
decoded = zlib.decompress(raw)
stride = width * 4
rows = []
previous = bytearray(stride)
offset = 0
for _ in range(height):
    filter_type = decoded[offset]
    offset += 1
    row = bytearray(decoded[offset:offset + stride])
    offset += stride
    for i in range(stride):
        left = row[i - 4] if i >= 4 else 0
        up = previous[i]
        upper_left = previous[i - 4] if i >= 4 else 0
        if filter_type == 1:
            row[i] = (row[i] + left) & 255
        elif filter_type == 2:
            row[i] = (row[i] + up) & 255
        elif filter_type == 3:
            row[i] = (row[i] + (left + up) // 2) & 255
        elif filter_type == 4:
            estimate = left + up - upper_left
            distances = (abs(estimate - left), abs(estimate - up), abs(estimate - upper_left))
            predictor = left if distances[0] <= distances[1] and distances[0] <= distances[2] else up if distances[1] <= distances[2] else upper_left
            row[i] = (row[i] + predictor) & 255
        elif filter_type != 0:
            raise SystemExit("unsupported PNG filter")
    rows.append(row)
    previous = row

pixel = tuple(rows[100][800 * 4:800 * 4 + 4])
expected = (19, 19, 19, 255)
if pixel != expected:
    raise SystemExit(f"FAIL: normal template backdrop is not opaque/neutral: {pixel}")

bright_title_pixels = 0
for y in range(70, 106):
    for x in range(20, 210):
        r, g, b, _ = rows[y][x * 4:x * 4 + 4]
        if r > 170 and g > 170 and b > 170:
            bright_title_pixels += 1
if bright_title_pixels < 40:
    raise SystemExit(f"FAIL: grid header is clipped or missing ({bright_title_pixels} bright pixels)")
print("PASS: normal template backdrop and header are visible")
PY
