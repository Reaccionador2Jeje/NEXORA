#!/usr/bin/env bash
# Build upstream QEMU for Android arm64-v8a using only Google's Android NDK.
# No Termux package builder, Termux prefix, or host Linux target libraries.
set -euo pipefail

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the official Android NDK}"
ROOT="$(pwd)"
WORK="${QEMU_WORK_DIR:-$ROOT/.qemu-android-build}"
PREFIX="$WORK/prefix"
SRC="$WORK/src"
BUILD="$WORK/build"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
API=26
JOBS="${BUILD_JOBS:-2}"
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"

for tool in "$CC" "$CXX" "$AR" "$RANLIB" "$STRIP" "$TOOLCHAIN/llvm-readelf"; do
  test -x "$tool" || { echo "Missing NDK tool: $tool" >&2; exit 1; }
done

mkdir -p "$SRC" "$BUILD" "$PREFIX"
export CC CXX AR RANLIB STRIP
export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"
export CPPFLAGS="-I$PREFIX/include"
export LDFLAGS="-L$PREFIX/lib -Wl,-z,relro -Wl,-z,now"
# QEMU's configure script derives a target-prefixed pkg-config from --cross-prefix
# unless PKG_CONFIG is explicit. Use the host pkg-config with target-only search paths.
export PKG_CONFIG="/usr/bin/pkg-config"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"

clone_tag() {
  local url="$1" tag="$2" name="$3"
  if [ ! -d "$SRC/$name/.git" ]; then
    git clone --depth 1 --branch "$tag" "$url" "$SRC/$name"
  fi
}

echo "==> Fetching pinned Android dependency sources"
LIBFFI_ARCHIVE="$WORK/libffi-3.4.8.tar.gz"
curl --fail --location --retry 3 \
  https://github.com/libffi/libffi/releases/download/v3.4.8/libffi-3.4.8.tar.gz \
  -o "$LIBFFI_ARCHIVE"
echo "bc9842a18898bfacb0ed1252c4febcc7e78fa139fd27fdc7a3e30d9d9356119b  $LIBFFI_ARCHIVE" | sha256sum --check -
tar -xzf "$LIBFFI_ARCHIVE" -C "$SRC"
mv "$SRC/libffi-3.4.8" "$SRC/libffi"
ICONV_ARCHIVE="$WORK/libiconv-1.18.tar.gz"
curl --fail --location --retry 3 \
  https://ftp.gnu.org/gnu/libiconv/libiconv-1.18.tar.gz \
  -o "$ICONV_ARCHIVE"
echo "3b08f5f4f9b4eb82f151a7040bfd6fe6c6fb922efe4b1659c66ea933276965e8  $ICONV_ARCHIVE" | sha256sum --check -
tar -xzf "$ICONV_ARCHIVE" -C "$SRC"
mv "$SRC/libiconv-1.18" "$SRC/libiconv"
clone_tag https://github.com/PCRE2Project/pcre2.git pcre2-10.45 pcre2
clone_tag https://github.com/GNOME/glib.git 2.84.4 glib
clone_tag https://gitlab.freedesktop.org/pixman/pixman.git pixman-0.46.4 pixman

echo "==> Building libffi for Android"
cd "$SRC/libffi"
mkdir -p "$BUILD/libffi"
cd "$BUILD/libffi"
CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS" \
  "$SRC/libffi/configure" \
  --host=aarch64-linux-android \
  --prefix="$PREFIX" \
  --disable-shared --enable-static --with-pic
make -j"$JOBS"
make install

echo "==> Building GNU libiconv for Android API $API"
mkdir -p "$BUILD/libiconv"
cd "$BUILD/libiconv"
CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS" CPPFLAGS="$CPPFLAGS" LDFLAGS="$LDFLAGS" \
  "$SRC/libiconv/configure" \
  --host=aarch64-linux-android \
  --prefix="$PREFIX" \
  --disable-shared --enable-static
make -j"$JOBS"
make install
test -f "$PREFIX/include/iconv.h"
test -f "$PREFIX/lib/libiconv.a"

echo "==> Building PCRE2 8-bit library for Android"
cmake -S "$SRC/pcre2" -B "$BUILD/pcre2" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-"$API" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF \
  -DPCRE2_BUILD_PCRE2_8=ON -DPCRE2_BUILD_PCRE2_16=OFF \
  -DPCRE2_BUILD_PCRE2_32=OFF -DPCRE2_SUPPORT_JIT=OFF \
  -DPCRE2_BUILD_TESTS=OFF
cmake --build "$BUILD/pcre2" --target install --parallel "$JOBS"

echo "==> Creating Meson cross file for Android ARM64"
cat > "$WORK/android-aarch64.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkgconfig = '/usr/bin/pkg-config'

[properties]
# NDK Clang already embeds its Android sysroot. Omitting Meson's sys_root
# avoids incorrectly prefixing absolute dependency include paths with it.
pkg_config_libdir = ['$PREFIX/lib/pkgconfig', '$PREFIX/share/pkgconfig']

[built-in options]
c_args = ['-I$PREFIX/include']
cpp_args = ['-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib']
cpp_link_args = ['-L$PREFIX/lib']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF

echo "==> Building GLib for Android"
meson setup "$BUILD/glib" "$SRC/glib" \
  --cross-file "$WORK/android-aarch64.ini" \
  --prefix "$PREFIX" --buildtype release --default-library static \
  -Dtests=false -Dinstalled_tests=false -Dintrospection=disabled \
  -Ddocumentation=false -Dman-pages=disabled \
  -Dlibmount=disabled -Dselinux=disabled -Dlibelf=disabled \
  -Dsysprof=disabled
meson compile -C "$BUILD/glib" --jobs "$JOBS"
meson install -C "$BUILD/glib"

echo "==> Building Pixman for Android"
meson setup "$BUILD/pixman" "$SRC/pixman" \
  --cross-file "$WORK/android-aarch64.ini" \
  --prefix "$PREFIX" --buildtype release --default-library static \
  -Dtests=disabled -Ddemos=disabled -Dgtk=disabled \
  -Dlibpng=disabled -Dopenmp=disabled
meson compile -C "$BUILD/pixman" --jobs "$JOBS"
meson install -C "$BUILD/pixman"

echo "==> Verifying target dependencies"
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion glib-2.0
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion pixman-1
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion libffi
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion libpcre2-8

echo "==> Configuring upstream QEMU for Android"
QEMU_SOURCE="$WORK/src/qemu"
# Bionic has no shm_open()/shm_unlink() declarations. Keep QEMU's fd-backed
# shared-memory behavior on Android by creating a private temporary file and
# unlinking it immediately after opening it. TMPDIR is set to app cache at runtime.
python3 - "$QEMU_SOURCE/util/oslib-posix.c" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1])
text = source.read_text()
pattern = r"int qemu_shm_alloc\(size_t size, Error \*\*errp\)\n\{[\s\S]*\}\s*\Z"
replacement = r"""int qemu_shm_alloc(size_t size, Error **errp)
{
    int fd;

#ifdef __ANDROID__
    g_autofree char *shm_path = g_strdup_printf("%s/qemu-" FMT_pid
                                                 "-shm-XXXXXX",
                                                 g_get_tmp_dir(), getpid());
    fd = mkstemp(shm_path);
    if (fd < 0) {
        error_setg_errno(errp, errno,
                         "failed to create Android temporary shared memory");
        return -1;
    }
    if (fcntl(fd, F_SETFD, FD_CLOEXEC) == -1 || unlink(shm_path) == -1) {
        int saved_errno = errno;
        close(fd);
        unlink(shm_path);
        error_setg_errno(errp, saved_errno,
                         "failed to detach Android temporary shared memory");
        return -1;
    }
#else
    g_autoptr(GString) shm_name = g_string_new(NULL);
    int oflag, cur_sequence;
    static int sequence;
    mode_t mode;

    cur_sequence = qatomic_fetch_inc(&sequence);
    mode = 0;
    oflag = O_RDWR | O_CREAT | O_EXCL;
    g_string_printf(shm_name, "/qemu-" FMT_pid "-shm-%d", getpid(),
                    cur_sequence);

    fd = shm_open(shm_name->str, oflag, mode);
    if (fd < 0) {
        error_setg_errno(errp, errno,
                         "failed to create POSIX shared memory");
        return -1;
    }
    shm_unlink(shm_name->str);
#endif

    if (ftruncate(fd, size) == -1) {
        error_setg_errno(errp, errno,
                         "failed to resize POSIX shared memory to %zu", size);
        close(fd);
        return -1;
    }

    return fd;
}"""
text, count = re.subn(pattern, lambda _: replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("Could not locate exactly one qemu_shm_alloc() in QEMU 10.1.2")

# Validate the transformed in-memory source before writing it to disk.
function_start = text.index("int qemu_shm_alloc(size_t size, Error **errp)")
function_text = text[function_start:]
android_branch = re.search(r"#ifdef __ANDROID__(.*?)#else", function_text, re.S)
posix_branch = re.search(r"#else(.*?)#endif", function_text, re.S)
if not android_branch or "mkstemp(" not in android_branch.group(1):
    raise SystemExit("Android shared-memory implementation was not applied")
if not posix_branch or "shm_open(" not in posix_branch.group(1):
    raise SystemExit("Non-Android POSIX shared-memory fallback was not preserved")
if "shm_open(" in android_branch.group(1) or "shm_unlink(" in android_branch.group(1):
    raise SystemExit("Android branch still references unavailable POSIX shm APIs")
source.write_text(text)
PY
QEMU_BUILD="$BUILD/qemu"
# QEMU's Meson cross file expects a target-prefixed nm executable. The
# Android NDK ships llvm-nm instead, so provide the conventional alias.
CROSS_TOOLS="$WORK/cross-tools"
mkdir -p "$QEMU_BUILD" "$CROSS_TOOLS"
ln -sf "$TOOLCHAIN/llvm-nm" "$CROSS_TOOLS/aarch64-linux-android-nm"
cd "$QEMU_BUILD"
"$QEMU_SOURCE/configure" \
  --target-list=aarch64-softmmu \
  --cross-prefix="$CROSS_TOOLS/aarch64-linux-android-" \
  --cc="$CC" --cxx="$CXX" --host-cc=cc \
  --extra-cflags="-O2 -fPIC -I$PREFIX/include" \
  --extra-ldflags="-L$PREFIX/lib -Wl,-rpath-link,$PREFIX/lib" \
  --disable-docs -Dwerror=false \
  -Dtools=disabled -Dguest_agent=disabled -Dxen=disabled \
  -Dgtk=disabled -Dsdl=disabled -Dvnc=disabled -Dslirp=disabled \
  -Dcapstone=disabled -Dcurl=disabled -Dlibnfs=disabled \
  -Dspice=disabled -Dusb_redir=disabled -Dlibusb=disabled \
  -Dopengl=disabled -Dvirglrenderer=disabled -Dkvm=disabled \
  -Dvhost_kernel=disabled -Dvhost_net=disabled -Dvhost_user=disabled \
  -Dvhost_crypto=disabled -Dvhost_vdpa=disabled \
  -Dvhost_user_blk_server=disabled -Dlibvduse=disabled -Dvduse_blk_export=disabled \
  -Dtcg=enabled -Dpixman=enabled -Dfdt=internal \
  -Daudio_drv_list=[] -Dpkgversion=NEXORA-Android-ARM64

echo "==> Compiling QEMU"
ninja -C "$QEMU_BUILD" -j"$JOBS" qemu-system-aarch64
test -s "$QEMU_BUILD/qemu-system-aarch64"
"$TOOLCHAIN/llvm-readelf" -h "$QEMU_BUILD/qemu-system-aarch64" | grep -q 'AArch64'
"$TOOLCHAIN/llvm-readelf" -l "$QEMU_BUILD/qemu-system-aarch64" | grep -q '/system/bin/linker64'

RUNTIME_LIBS="$WORK/runtime-libs"
mkdir -p "$RUNTIME_LIBS"
while IFS= read -r library; do
  ln -s "$library" "$RUNTIME_LIBS/$(basename "$library")" 2>/dev/null || true
done < <(find "$PREFIX/lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -print)
NDK_RUNTIME_LIBS="$SYSROOT/usr/lib/aarch64-linux-android/$API"
if [ -d "$NDK_RUNTIME_LIBS" ]; then
  while IFS= read -r library; do
    ln -s "$library" "$RUNTIME_LIBS/$(basename "$library")" 2>/dev/null || true
  done < <(find "$NDK_RUNTIME_LIBS" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -print)
fi

mkdir -p "$ROOT/artifacts/qemu-android-arm64"
python3 "$ROOT/scripts/package-qemu-android.py" \
  --binary "$QEMU_BUILD/qemu-system-aarch64" \
  --library-dir "$RUNTIME_LIBS" \
  --output "$ROOT/artifacts/qemu-android-arm64" \
  --readelf "$TOOLCHAIN/llvm-readelf"

cp "$WORK/android-aarch64.ini" "$ROOT/artifacts/qemu-android-arm64/"
mkdir -p "$ROOT/artifacts/qemu-android-arm64/licenses"
for project in qemu libffi libiconv pcre2 glib pixman; do
  mkdir -p "$ROOT/artifacts/qemu-android-arm64/licenses/$project"
  find "$SRC/$project" -maxdepth 1 -type f \
    \( -iname 'COPYING*' -o -iname 'LICENSE*' -o -iname 'LICENCE*' \) \
    -exec cp -n {} "$ROOT/artifacts/qemu-android-arm64/licenses/$project/" \;
done
git -C "$WORK/src/qemu" rev-parse HEAD > "$ROOT/artifacts/qemu-android-arm64/QEMU_COMMIT.txt"
printf 'NDK=r30\nAPI=%s\nABI=arm64-v8a\n' "$API" > "$ROOT/artifacts/qemu-android-arm64/BUILD_INFO.txt"
(
  cd "$ROOT/artifacts/qemu-android-arm64"
  find . -maxdepth 1 -type f \( -name 'qemu-system-aarch64' -o -name '*.so*' \) -print0 |
    sort -z | xargs -0 sha256sum
) > "$ROOT/artifacts/qemu-android-arm64/SHA256SUMS"

echo "QEMU Android ARM64 candidate built and staged."
