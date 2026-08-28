#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_NODE="${1:-${TARGET_NODE:-c06}}"

if [[ -z "$TARGET_NODE" ]]; then
  echo "ERROR: target node cannot be empty." >&2
  exit 1
fi

exec sbatch --nodelist="$TARGET_NODE" "$ROOT_DIR/submit_llm_workload.sbatch"