#!/usr/bin/env python3
"""Validate and stage an Android/Bionic AArch64 QEMU build and its shared libraries.

This utility is intentionally independent of Termux. It accepts an arbitrary
standalone Android NDK/QEMU build output and a directory containing the
libraries produced by that same build.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

def run_readelf(readelf: str, option: str, path: Path) -> str:
    return subprocess.run(
        [readelf, option, str(path)], check=True, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    ).stdout

def needed_libraries(readelf: str, path: Path) -> list[str]:
    return re.findall(r"Shared library: \[(.+?)\]", run_readelf(readelf, "-d", path))

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--library-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--readelf", default=shutil.which("llvm-readelf") or shutil.which("readelf"))
    args = parser.parse_args()
    if not args.readelf:
        raise SystemExit("ERROR: llvm-readelf/readelf is required")
    binary = args.binary.resolve()
    lib_dir = args.library_dir.resolve()
    output = args.output
    if not binary.is_file() or not lib_dir.is_dir():
        raise SystemExit("ERROR: QEMU binary or standalone library directory is missing")
    header = run_readelf(args.readelf, "-h", binary)
    if not re.search(r"Machine:\s+AArch64", header):
        raise SystemExit("ERROR: QEMU is not an AArch64 ELF executable")
    phdr = run_readelf(args.readelf, "-l", binary)
    if "Requesting program interpreter: /system/bin/linker64" not in phdr:
        raise SystemExit("ERROR: QEMU must use Android Bionic linker64")
    index = {p.name: p for p in lib_dir.rglob("*.so*") if p.is_file()}
    platform = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
                "libz.so", "libEGL.so", "libGLESv2.so", "libvulkan.so"}
    output.mkdir(parents=True, exist_ok=True)
    pending = needed_libraries(args.readelf, binary)
    copied: set[str] = set()
    while pending:
        name = pending.pop()
        if name in copied or name in platform:
            continue
        source = index.get(name)
        if source is None:
            raise SystemExit(f"ERROR: unresolved non-platform dependency: {name}")
        shutil.copy2(source, output / name)
        copied.add(name)
        pending.extend(needed_libraries(args.readelf, source))
    shutil.copy2(binary, output / "qemu-system-aarch64")
    print(f"Validated Android AArch64 QEMU; bundled {len(copied)} shared libraries")
    return 0

if __name__ == "__main__":
    sys.exit(main())
