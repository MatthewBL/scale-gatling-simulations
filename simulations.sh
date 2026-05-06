#!/usr/bin/env bash
set -euo pipefail

cases=(
  "baseline 17 26 35"
  "moderate 21 31 42"
  "excessive 35 52 70"
)

for c in "${cases[@]}"; do
  read -r name basic standard pro <<< "$c"
  runId="${name}-$(date +%Y%m%d%H%M%S)"
  mvn gatling:test \
    "-Dgatling.runId=$runId" \
    "-DBASIC_UNITS_PER_MINUTE=$basic" \
    "-DSTANDARD_UNITS_PER_MINUTE=$standard" \
    "-DPRO_UNITS_PER_MINUTE=$pro"
done
