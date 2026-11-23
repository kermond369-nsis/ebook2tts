#!/usr/bin/env bash
# build-release-arm64.sh —— 兼容入口：委托至 scripts/build-release.sh arm64
set -euo pipefail
exec bash "$(dirname "$0")/build-release.sh" arm64 "$@"
