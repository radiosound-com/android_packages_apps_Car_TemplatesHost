#!/bin/sh
set -eu

# End-to-end checks for the two interactive surfaces on the 1080x600 AAOS
# landscape emulator profile: settings ListTemplate chrome and map gestures.
ADB=${ADB:-adb}
PACKAGE=${PACKAGE:-net.osmand.dev}
COMPONENT="$PACKAGE/androidx.car.app.activity.CarAppActivity"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-vanilla-host-interaction.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT

capture() {
    "$ADB" shell screencap -p "$1" >/dev/null
    "$ADB" pull -q "$1" "$2"
}

"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -W -n "$COMPONENT" >/dev/null
sleep 5
capture /sdcard/caramel-vanilla-map-before.png "$tmp_dir/map-before.png"
"$ADB" shell input swipe 700 300 850 300 500
sleep 1
capture /sdcard/caramel-vanilla-map-after-right.png "$tmp_dir/map-after-right.png"

"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -W -n "$COMPONENT" >/dev/null
sleep 5
capture /sdcard/caramel-vanilla-map-before-down.png "$tmp_dir/map-before-down.png"
"$ADB" shell input swipe 850 300 850 450 500
sleep 1
capture /sdcard/caramel-vanilla-map-after-down.png "$tmp_dir/map-after-down.png"

"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -W -n "$COMPONENT" >/dev/null
sleep 2
"$ADB" shell input tap 970 98
sleep 2
capture /sdcard/caramel-vanilla-settings.png "$tmp_dir/settings.png"

python3 - "$tmp_dir/map-before.png" "$tmp_dir/map-after-right.png" \
    "$tmp_dir/map-before-down.png" "$tmp_dir/map-after-down.png" \
    "$tmp_dir/settings.png" <<'PY'
import struct
import sys
import zlib


def decode_png(path):
    data = open(path, "rb").read()
    pos = 8
    compressed = bytearray()
    width = height = None
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
    stride = width * 4
    rows = []
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        current = bytearray(raw[pos:pos + stride])
        pos += stride
        for i in range(stride):
            left = current[i - 4] if i >= 4 else 0
            above = previous[i]
            upper_left = previous[i - 4] if i >= 4 else 0
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


def pixel(rows, x, y):
    return tuple(rows[y][x * 4:x * 4 + 4])


def near(value, expected, tolerance):
    return all(abs(value[i] - expected[i]) <= tolerance for i in range(3))


def shift_error(reference, moved, shift_x, shift_y):
    """Compare map content after a candidate translation.

    A positive shift means the rendered map content moved right/down relative
    to the previous screenshot, matching a paper map dragged under a finger.
    """
    _, _, reference_rows = reference
    _, _, moved_rows = moved
    total = count = 0
    for y in range(70, 530, 4):
        reference_y = y - shift_y
        if not 70 <= reference_y < 530:
            continue
        for x in range(405, 900, 4):
            reference_x = x - shift_x
            if not 405 <= reference_x < 900:
                continue
            moved_pixel = moved_rows[y]
            reference_pixel = reference_rows[reference_y]
            moved_offset = x * 4
            reference_offset = reference_x * 4
            total += sum(abs(moved_pixel[moved_offset + channel]
                             - reference_pixel[reference_offset + channel])
                         for channel in range(3))
            count += 1
    return total / count if count else float("inf")


before_right = decode_png(sys.argv[1])
after_right = decode_png(sys.argv[2])
before_down = decode_png(sys.argv[3])
after_down = decode_png(sys.argv[4])
settings = decode_png(sys.argv[5])
if any(image[:2] != (1080, 600) for image in
       (before_right, after_right, before_down, after_down, settings)):
    raise SystemExit("FAIL: expected 1080x600 screenshots")

_, _, before_rows = before_right
_, _, after_rows = after_right
_, _, settings_rows = settings

map_active = 0
map_diffs = 0
for y in range(100, 500):
    for x in range(600, 900):
        old = pixel(before_rows, x, y)[:3]
        new = pixel(after_rows, x, y)[:3]
        if max(old) > 40:
            map_active += 1
        if old != new:
            map_diffs += 1

right_scores = [(shift_error(before_right, after_right, shift, 0), shift)
                for shift in range(-220, 221, 10)]
down_scores = [(shift_error(before_down, after_down, 0, shift), shift)
               for shift in range(-220, 221, 10)]
right_shift = min(right_scores)[1]
down_shift = min(down_scores)[1]

checks = {
    "map is rendered before gesture": map_active > 10000,
    "map responds to a swipe": map_diffs > 1000,
    "rightward finger drag moves map right": right_shift > 20,
    "downward finger drag moves map down": down_shift > 20,
    "settings uses stock dark background": near(pixel(settings_rows, 50, 100), (19, 19, 19, 255), 12),
    "settings toolbar back affordance exists": max(pixel(settings_rows, 40, 87)[:3]) > 100,
    "settings switch is rendered": max(pixel(settings_rows, 994, 167)[:3]) > 50
        and max(pixel(settings_rows, 994, 167)[:3]) - min(pixel(settings_rows, 994, 167)[:3]) < 12,
}
failed = [name for name, passed in checks.items() if not passed]
if failed:
    for name in failed:
        print("FAIL:", name)
    print("map_active=", map_active, "map_diffs=", map_diffs,
          "right_shift=", right_shift, "down_shift=", down_shift)
    raise SystemExit(1)
print("PASS: settings layout and map gesture behavior match the stock host profile")
PY
