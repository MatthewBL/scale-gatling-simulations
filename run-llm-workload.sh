#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# Load .env without xargs so quoted prompts and values containing spaces survive.
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

# Protect the generator before increasing request rate.
export JAVA_OPTS="${JAVA_OPTS:--Xmx4g}"
requested_nofile="${GATLING_NOFILE_LIMIT:-65535}"
current_nofile="$(ulimit -n)"
if [[ "$current_nofile" != "unlimited" && "$current_nofile" -lt "$requested_nofile" ]]; then
  if ! ulimit -n "$requested_nofile" 2>/dev/null; then
    echo "WARNING: could not raise open-file limit to $requested_nofile (current: $current_nofile)." >&2
  fi
fi

export LLM_URL="${LLM_URL:-http://localhost:11434}"
export ENDPOINT_PATH="${ENDPOINT_PATH:-/v1/completions}"
export MODELS_ENDPOINT="${MODELS_ENDPOINT:-/v1/models}"

if [[ "$LLM_URL" != http://* && "$LLM_URL" != https://* ]]; then
  export LLM_URL="http://$LLM_URL"
fi

echo "Target: $LLM_URL$ENDPOINT_PATH (model discovered from $LLM_URL$MODELS_ENDPOINT)"
echo "Generator: JAVA_OPTS=$JAVA_OPTS, open files=$(ulimit -n)"

export MAVEN_OPTS="${MAVEN_OPTS:-} $JAVA_OPTS"

parse_case() {
  local case_name="$1"
  local values="$2"
  local basic_units standard_units pro_units extra units
  IFS="," read -r basic_units standard_units pro_units extra <<< "$values"
  if [[ -n "${extra:-}" || -z "${basic_units:-}" || -z "${standard_units:-}" || -z "${pro_units:-}" ]]; then
    echo "ERROR: $case_name must contain exactly three comma-separated unit values: '$values'" >&2
    exit 1
  fi
  for units in "$basic_units" "$standard_units" "$pro_units"; do
    if [[ ! "$units" =~ ^[0-9]+$ ]]; then
      echo "ERROR: $case_name contains an invalid unit value: '$units'" >&2
      exit 1
    fi
  done
  printf '%s:%s:%s' "$basic_units" "$standard_units" "$pro_units"
}

cases=(
  "under-provisioning:$(parse_case UNDER_PROVISIONING_TEST "${UNDER_PROVISIONING_TEST:-10,20,30}")"
  "over-provisioning:$(parse_case OVER_PROVISIONING_TEST "${OVER_PROVISIONING_TEST:-10000,10000,10000}")"
  "fine-tuned-provisioning:$(parse_case FINE_TUNED_TEST "${FINE_TUNED_TEST:-25,40,50}")"
)

for case in "${cases[@]}"; do
  IFS=":" read -r caseName basicUnits standardUnits proUnits <<< "$case"
  runId="${caseName}-$(date +%Y%m%d%H%M%S)"
  echo "Starting $caseName: basic=$basicUnits, standard=$standardUnits, pro=$proUnits units/min"

  if [[ -f target/gatling-llm-simulations-0.1.0-SNAPSHOT.jar && "${USE_JAR:-false}" == "true" ]]; then
    # shellcheck disable=SC2086
    java $JAVA_OPTS \
      -Dgatling.core.checkVersion=false \
      -Dgatling.runId="$runId" \
      -DBASIC_UNITS_PER_MINUTE="$basicUnits" \
      -DSTANDARD_UNITS_PER_MINUTE="$standardUnits" \
      -DPRO_UNITS_PER_MINUTE="$proUnits" \
      -jar target/gatling-llm-simulations-0.1.0-SNAPSHOT.jar \
      -s simulations.LLMWorkloadSimulation -rf target/gatling
  else
    sh ./mvnw -o \
      -Dmaven.repo.local="$ROOT_DIR/local-repo" \
      -Dgatling.runId="$runId" \
      -DBASIC_UNITS_PER_MINUTE="$basicUnits" \
      -DSTANDARD_UNITS_PER_MINUTE="$standardUnits" \
      -DPRO_UNITS_PER_MINUTE="$proUnits" \
      gatling:test \
      -Dgatling.simulationClass=simulations.LLMWorkloadSimulation
  fi
done
