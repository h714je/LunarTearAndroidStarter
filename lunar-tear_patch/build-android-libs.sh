#!/usr/bin/env bash
set -euo pipefail

ROOT="$(pwd)"
OUT="$ROOT/../android-app/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$OUT"

export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=0

echo "Building auth-server..."
go build -trimpath -ldflags="-s -w" \
  -o "$OUT/libauth-server.so" \
  ./cmd/auth-server

echo "Building octo-cdn..."
go build -trimpath -ldflags="-s -w" \
  -o "$OUT/libocto-cdn.so" \
  ./cmd/octo-cdn

echo "Building lunar-tear..."
go build -trimpath -ldflags="-s -w" \
  -o "$OUT/liblunar-tear.so" \
  ./cmd/lunar-tear

echo
echo "Done:"
ls -lh "$OUT"
