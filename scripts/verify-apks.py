#!/usr/bin/env python3
"""Check actual APK flags, packaged ABIs, alignment and signing, not just filenames."""

import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ABIS = {"arm64-v8a", "x86_64"}
BUILD_TOOLS = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.1.0"


def verify_elf(name: str, data: bytes) -> None:
    if data[:6] != b"\x7fELF\x02\x01":
        raise RuntimeError(f"{name}: expected a little-endian 64-bit ELF library")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != {"arm64-v8a": 183, "x86_64": 62}[name.split("/")[1]]:
        raise RuntimeError(f"{name}: ELF architecture does not match its ABI directory")
    table = struct.unpack_from("<Q", data, 32)[0]
    stride, count = struct.unpack_from("<HH", data, 54)
    loads = 0
    for index in range(count):
        kind, _, offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, table + index * stride
        )
        if kind == 1:  # PT_LOAD, ELF64 program header
            loads += 1
            if alignment < 16384 or (offset - address) % 16384:
                raise RuntimeError(f"{name}: load segment is not compatible with 16 KiB pages")
    if not loads:
        raise RuntimeError(f"{name}: missing ELF load segments")


def verify(apk: Path, *, debug: bool, signed: bool) -> None:
    with zipfile.ZipFile(apk) as archive:
        native = {
            name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")
        }
        abis = {name.split("/")[1] for name in native}
        if abis != ABIS or any(f"lib/{abi}/libnfssaf.so" not in native for abi in ABIS):
            raise RuntimeError(f"{apk}: incomplete universal native libraries: {sorted(native)}")
        if any("libfsx" in name for name in native):
            raise RuntimeError(f"{apk}: test executable must not ship in the app")
        for name in native:
            verify_elf(name, archive.read(name))
    badging = subprocess.run(
        [str(BUILD_TOOLS / "aapt2"), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if bool(re.search(r"^application-debuggable$", badging, re.MULTILINE)) != debug:
        raise RuntimeError(f"{apk}: incorrect debuggable flag")
    if not re.search(r"^package: name='dev.nfssaf' ", badging, re.MULTILINE):
        raise RuntimeError(f"{apk}: unexpected application ID")
    subprocess.run(
        [str(BUILD_TOOLS / "zipalign"), "-c", "-P", "16", "4", str(apk)],
        check=True,
        capture_output=True,
    )
    signature = subprocess.run(
        [str(BUILD_TOOLS / "apksigner"), "verify", str(apk)], capture_output=True
    )
    if (signature.returncode == 0) != signed:
        raise RuntimeError(
            f"{apk}: expected signed={signed}, signature exit={signature.returncode}"
        )
    print(
        f"PASS {apk.name}: universal ARM64+x86-64, debug={debug}, signed={signed}, ZIP/16 KiB ELF aligned"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debug", type=Path)
    parser.add_argument("--release", type=Path)
    parser.add_argument("--signed-release", action="store_true")
    args = parser.parse_args()
    if not args.debug and not args.release:
        parser.error("Supply --debug and/or --release")
    if args.debug:
        verify(args.debug, debug=True, signed=True)
    if args.release:
        verify(args.release, debug=False, signed=args.signed_release)
