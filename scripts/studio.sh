#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
exec open -a "$GHOST_ROOT/.local/apps/Android Studio.app" "$GHOST_ROOT"

