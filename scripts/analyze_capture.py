#!/usr/bin/env python3
from __future__ import annotations

import collections
import struct
import sys
from pathlib import Path

MAGIC = 0x55AA55AA

TYPE_NAMES = {
    0x01: "Open",
    0x02: "Plugged",
    0x03: "Phase",
    0x04: "Unplugged",
    0x06: "Video",
    0x07: "Audio",
    0x08: "Command",
    0x0D: "BTDevName",
    0x0E: "WiFiDevName",
    0x12: "BTPairedList",
    0x19: "BoxSettings",
    0xCC: "SWVersion",
}


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: analyze_capture.py /path/to/test-capture.bin")
        return 1

    path = Path(sys.argv[1])
    data = path.read_bytes()

    pos = 0
    type_counter: collections.Counter[int] = collections.Counter()
    resolutions: collections.Counter[tuple[int, int]] = collections.Counter()
    command_counter: collections.Counter[int] = collections.Counter()
    phase_counter: collections.Counter[int] = collections.Counter()
    plugged = []
    firmware = None
    phone_info = []

    while pos + 16 <= len(data):
        magic, length, msg_type, type_check = struct.unpack_from("<IIII", data, pos)
        if magic != MAGIC:
            raise RuntimeError(f"invalid magic at 0x{pos:x}: 0x{magic:x}")
        if type_check != ((~msg_type) & 0xFFFFFFFF):
            raise RuntimeError(f"invalid typeCheck at 0x{pos:x}")

        payload = data[pos + 16 : pos + 16 + length]
        if len(payload) != length:
            raise RuntimeError(f"truncated payload at 0x{pos:x}")

        type_counter[msg_type] += 1

        if msg_type == 0x06 and length >= 20:
            width, height, *_ = struct.unpack_from("<IIIII", payload, 0)
            resolutions[(width, height)] += 1
        elif msg_type == 0x08 and length >= 4:
            command_counter[struct.unpack_from("<I", payload, 0)[0]] += 1
        elif msg_type == 0x03 and length >= 4:
            phase_counter[struct.unpack_from("<I", payload, 0)[0]] += 1
        elif msg_type == 0x02 and length >= 4:
            phone_type = struct.unpack_from("<I", payload, 0)[0]
            wifi = struct.unpack_from("<I", payload, 4)[0] if length >= 8 else None
            plugged.append((phone_type, wifi))
        elif msg_type == 0xCC:
            firmware = payload.rstrip(b"\0").decode("utf-8", "replace")
        elif msg_type == 0x19:
            text = payload.decode("utf-8", "replace")
            if "MDLinkType" in text:
                phone_info.append(text[:180])

        pos += 16 + length

    print(f"file: {path}")
    print(f"bytes: {len(data)}")
    print(f"messages: {sum(type_counter.values())}")
    print()
    print("message types:")
    for msg_type, count in type_counter.most_common():
        print(f"  {TYPE_NAMES.get(msg_type, hex(msg_type))}: {count}")

    print()
    if firmware:
        print(f"firmware: {firmware}")
    if plugged:
        print(f"plugged: {plugged}")
    if phase_counter:
        print(f"phases: {dict(phase_counter)}")
    if resolutions:
        print(f"video resolutions: {dict(resolutions)}")
    if command_counter:
        print(f"commands: {dict(command_counter)}")
    if phone_info:
        print("phone-related BoxSettings:")
        for item in phone_info[:3]:
            print(f"  {item}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
