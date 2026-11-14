#!/usr/bin/env python3
"""Stage a Termux-built Android ARM64 QEMU binary and its non-platform DSOs."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

SYSTEM_LIBRARIES = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libz.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libOpenSLES.so", "libvulkan.so", "libjnigraphics.so",
    "libnativewindow.so", "libmediandk.so", "libcamera2ndk.so",
    "libaaudio.so", "libbinder_ndk.so",
}

def run_readelf(readelf: str, option: str, path: Path) -> str:
    result = subprocess.run(
        [readelf, option, str(path)],
        check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    return result.stdout

def needed_libraries(readelf: str, path: Path) -> list[str]:
    output = run_readelf(readelf, "-d", path)
    return re.findall(r"Shared library: \\[(.+?)\\]", output)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--prefix", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--readelf", default=shutil.which("llvm-readelf") or shutil.which("readelf"))
    args = parser.parse_args()

    if not args.readelf:
        raise SystemExit("ERROR: llvm-readelf/readelf is required")
    binary = args.binary.resolve()
    prefix_lib = args.prefix / "lib"
    output = args.output
    output.mkdir(parents=True, exist_ok=True)

    header = run_readelf(args.readelf, "-h", binary)
    if not re.search(r"Machine:\\s+AArch64", header):
        raise SystemExit("ERROR: QEMU binary is not an AArch64 ELF executable")
    program_headers = run_readelf(args.readelf, "-l", binary)
    if "Requesting program interpreter: /system/bin/linker64" not in program_headers:
        raise SystemExit("ERROR: QEMU binary does not request Android's 64-bit Bionic linker")

    library_index: dict[str, Path] = {}
    for root, _, files in os.walk(prefix_lib):
        for filename in files:
            if filename not in library_index:
                candidate = Path(root) / filename
                if candidate.is_file():
                    library_index[filename] = candidate

    pending = list(needed_libraries(args.readelf, binary))
    visited: set[str] = set()
    copied: list[str] = []
    missing_platform: list[str] = []

    while pending:
        name = pending.pop()
        if name in visited:
            continue
        visited.add(name)
        source = library_index.get(name)
        if source is None:
            if name in SYSTEM_LIBRARIES:
                missing_platform.append(name)
                continue
            raise SystemExit(f"ERROR: unresolved non-platform dependency: {name}")
        destination = output / name
        shutil.copy2(source, destination)
        copied.append(name)
        pending.extend(needed_libraries(args.readelf, source))

    shutil.copy2(binary, output / "qemu-system-aarch64")
    print("QEMU ELF: AArch64 / Android Bionic linker64")
    print("Bundled shared libraries:")
    for name in sorted(copied):
        print(f"  {name}")
    print("Android platform libraries:")
    for name in sorted(missing_platform):
        print(f"  {name}")
    print(f"Staged executable: {output / 'qemu-system-aarch64'}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
