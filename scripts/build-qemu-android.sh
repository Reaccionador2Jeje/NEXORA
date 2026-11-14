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
API=24
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
export LDFLAGS="-Wl,-z,relro -Wl,-z,now"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"

clone_tag() {
  local url="$1" tag="$2" name="$3"
  if [ ! -d "$SRC/$name/.git" ]; then
    git clone --depth 1 --branch "$tag" "$url" "$SRC/$name"
  fi
}

echo "==> Fetching pinned Android dependency sources"
clone_tag https://github.com/libffi/libffi.git v3.4.8 libffi
clone_tag https://github.com/PCRE2Project/pcre2.git pcre2-10.45 pcre2
clone_tag https://github.com/GNOME/glib.git 2.84.4 glib
clone_tag https://gitlab.freedesktop.org/pixman/pixman.git pixman-0.46.4 pixman

echo "==> Building libffi for Android"
cd "$SRC/libffi"
autoreconf -fi
mkdir -p "$BUILD/libffi"
cd "$BUILD/libffi"
"$SRC/libffi/configure" \
  --host=aarch64-linux-android \
  --disable-shared --enable-static --with-pic \
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
  CFLAGS="$CFLAGS"
make -j"$JOBS"
make install

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
sys_root = '$SYSROOT'
pkg_config_libdir = ['$PREFIX/lib/pkgconfig', '$PREFIX/share/pkgconfig']

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
  -Dlibffi=enabled -Dpcre2=enabled -Dsysprof=disabled
meson compile -C "$BUILD/glib" --jobs "$JOBS"
meson install -C "$BUILD/glib"

echo "==> Building Pixman for Android"
meson setup "$BUILD/pixman" "$SRC/pixman" \
  --cross-file "$WORK/android-aarch64.ini" \
  --prefix "$PREFIX" --buildtype release --default-library static \
  -Dtests=false -Ddemos=false -Dgtk=false \
  -Dlibpng=false -Dopenmp=false
meson compile -C "$BUILD/pixman" --jobs "$JOBS"
meson install -C "$BUILD/pixman"

echo "==> Verifying target dependencies"
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion glib-2.0
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion pixman-1
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion libffi
PKG_CONFIG_PATH="$PKG_CONFIG_PATH" pkg-config --modversion libpcre2-8

echo "==> Configuring upstream QEMU for Android"
cd "$WORK/src/qemu"
QEMU_BUILD="$BUILD/qemu"
mkdir -p "$QEMU_BUILD"
./configure \
  --prefix="$PREFIX" \
  --target-list=aarch64-softmmu \
  --cross-prefix="$TOOLCHAIN/aarch64-linux-android-" \
  --cc="$CC" --cxx="$CXX" --host-cc=cc \
  --extra-cflags="-O2 -fPIC -I$PREFIX/include" \
  --extra-ldflags="-L$PREFIX/lib -Wl,-rpath-link,$PREFIX/lib" \
  --disable-docs -Dwerror=false \
  -Dtools=disabled -Dguest_agent=disabled \
  -Dgtk=disabled -Dsdl=disabled -Dvnc=disabled -Dslirp=disabled \
  -Dcapstone=disabled -Dcurl=disabled -Dlibnfs=disabled \
  -Dspice=disabled -Dusb_redir=disabled -Dlibusb=disabled \
  -Dopengl=disabled -Dvirglrenderer=disabled -Dkvm=disabled \
  -Dtcg=enabled -Dpixman=enabled -Dfdt=internal \
  -Daudio_drv_list=[] -Dpkgversion=NEXORA-Android-ARM64

echo "==> Compiling QEMU"
ninja -C "$QEMU_BUILD" -j"$JOBS" qemu-system-aarch64
test -s "$QEMU_BUILD/qemu-system-aarch64"
"$TOOLCHAIN/llvm-readelf" -h "$QEMU_BUILD/qemu-system-aarch64" | grep -q 'AArch64'
"$TOOLCHAIN/llvm-readelf" -l "$QEMU_BUILD/qemu-system-aarch64" | grep -q '/system/bin/linker64'

mkdir -p "$ROOT/artifacts/qemu-android-arm64"
python3 "$ROOT/scripts/package-qemu-android.py" \
  --binary "$QEMU_BUILD/qemu-system-aarch64" \
  --library-dir "$PREFIX/lib" \
  --output "$ROOT/artifacts/qemu-android-arm64" \
  --readelf "$TOOLCHAIN/llvm-readelf"

cp "$WORK/android-aarch64.ini" "$ROOT/artifacts/qemu-android-arm64/"
mkdir -p "$ROOT/artifacts/qemu-android-arm64/licenses"
for project in qemu libffi pcre2 glib pixman; do
  mkdir -p "$ROOT/artifacts/qemu-android-arm64/licenses/$project"
  find "$SRC/$project" -maxdepth 1 -type f \
    \( -iname 'COPYING*' -o -iname 'LICENSE*' -o -iname 'LICENCE*' \) \
    -exec cp -n {} "$ROOT/artifacts/qemu-android-arm64/licenses/$project/" \;
done
git -C "$WORK/src/qemu" rev-parse HEAD > "$ROOT/artifacts/qemu-android-arm64/QEMU_COMMIT.txt"
printf 'NDK=r30\nAPI=%s\nABI=arm64-v8a\n' "$API" > "$ROOT/artifacts/qemu-android-arm64/BUILD_INFO.txt"
echo "QEMU Android ARM64 candidate built and staged."
