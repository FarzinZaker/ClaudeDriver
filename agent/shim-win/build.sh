#!/usr/bin/env bash
# Cross-compile the transparent Windows claude shim (static, no runtime deps).
# Output: dist/claude-<arch>.exe — the CI packages these into the Windows agent
# runtime, and ShimInstaller drops the right one at %USERPROFILE%\.claudedriver\bin\claude.exe.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
for arch in amd64 arm64; do
  GOOS=windows GOARCH="$arch" go build -trimpath -ldflags "-s -w" -o "dist/claude-$arch.exe" .
  echo "built dist/claude-$arch.exe"
done
