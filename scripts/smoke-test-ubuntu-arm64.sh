#!/usr/bin/env bash
# Host-side acceptance test for the exact Ubuntu ARM64 UEFI + NoCloud boot path.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/nexora-ubuntu-arm64-smoke"
BASE_URL="https://cloud-images.ubuntu.com/releases/noble/release"
IMAGE_NAME="ubuntu-24.04-server-cloudimg-arm64.img"
mkdir -p "$WORK"
cd "$WORK"

echo "==> Fetching Canonical SHA256 manifest"
curl --fail --location --retry 3 "$BASE_URL/SHA256SUMS" -o SHA256SUMS
EXPECTED="$(awk -v name="$IMAGE_NAME" '$2 == name || $2 == "*" name { print $1; exit }' SHA256SUMS)"
test "${#EXPECTED}" -eq 64

echo "==> Downloading Ubuntu 24.04 ARM64 cloud image"
curl --fail --location --retry 3 "$BASE_URL/$IMAGE_NAME" -o "$IMAGE_NAME"
echo "$EXPECTED  $IMAGE_NAME" | sha256sum --check -

echo "==> Creating FAT12 NoCloud seed disk"
cat > user-data <<'USERDATA'
#cloud-config
hostname: nexora
network: {config: disabled}
users:
  - default
  - name: nexora
    gecos: NEXORA Boot Test
    groups: [adm, sudo]
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    lock_passwd: true
runcmd:
  - [sh, -c, "echo NEXORA_VM_READY > /dev/ttyAMA0"]
USERDATA
cat > meta-data <<'METADATA'
instance-id: iid-nexora-arm64-smoke
local-hostname: nexora
METADATA
truncate -s 1440K seed.img
mkfs.vfat -F 12 -n CIDATA seed.img
mcopy -i seed.img user-data ::/user-data
mcopy -i seed.img meta-data ::/meta-data

echo "==> Preparing matching AAVMF variable store"
cp /usr/share/AAVMF/AAVMF_VARS.fd vars.fd
SERIAL_LOG="$WORK/serial.log"
: > "$SERIAL_LOG"

echo "==> Booting Ubuntu ARM64 under host QEMU TCG"
qemu-system-aarch64 \
  -machine virt \
  -cpu cortex-a72 \
  -smp 2 \
  -m 1024 \
  -drive if=pflash,format=raw,readonly=on,file=/usr/share/AAVMF/AAVMF_CODE.fd \
  -drive if=pflash,format=raw,file="$WORK/vars.fd" \
  -drive if=none,id=system,format=qcow2,file="$WORK/$IMAGE_NAME" \
  -device virtio-blk-device,drive=system \
  -drive if=none,id=seed,format=raw,file="$WORK/seed.img" \
  -device virtio-blk-device,drive=seed \
  -nic none \
  -display none \
  -serial "file:$SERIAL_LOG" \
  -monitor none \
  -no-reboot \
  >"$WORK/qemu-host.log" 2>&1 &
QEMU_PID=$!
cleanup() {
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    kill -TERM "$QEMU_PID" 2>/dev/null || true
    wait "$QEMU_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

READY=0
for _ in $(seq 1 300); do
  if grep -q "NEXORA_VM_READY" "$SERIAL_LOG"; then
    READY=1
    break
  fi
  if ! kill -0 "$QEMU_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  echo "ERROR: Ubuntu did not reach the cloud-init serial marker." >&2
  echo "--- QEMU host log ---" >&2
  tail -n 100 "$WORK/qemu-host.log" >&2 || true
  echo "--- Guest serial log ---" >&2
  tail -n 160 "$SERIAL_LOG" >&2 || true
  exit 1
fi

echo "Ubuntu ARM64 reached NEXORA_VM_READY through UEFI and NoCloud."
tail -n 30 "$SERIAL_LOG"
