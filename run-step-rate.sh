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
export INITIAL_RATE="${INITIAL_RATE:-1}"
export RATE_INCREMENT="${RATE_INCREMENT:-1}"
export RATE_LEVELS="${RATE_LEVELS:-5}"
export LEVEL_DURATION_SECONDS="${LEVEL_DURATION_SECONDS:-60}"

echo "Target: $LLM_URL$ENDPOINT_PATH (model discovered from $LLM_URL$MODELS_ENDPOINT)"
echo "Model: $MODEL_NAME"
echo "Steps: $RATE_LEVELS levels, start=$INITIAL_RATE req/s, increment=$RATE_INCREMENT req/s, duration=$LEVEL_DURATION_SECONDS s"
echo "Generator: JAVA_OPTS=$JAVA_OPTS, open files=$(ulimit -n)"

if [[ -f target/gatling-llm-simulations-0.1.0-SNAPSHOT.jar && "${USE_JAR:-false}" == "true" ]]; then
  # shellcheck disable=SC2086
  exec java $JAVA_OPTS -Dgatling.core.checkVersion=false \
    -jar target/gatling-llm-simulations-0.1.0-SNAPSHOT.jar \
    -s simulations.StepRateLLMSimulation -rf target/gatling
fi

export MAVEN_OPTS="${MAVEN_OPTS:-} $JAVA_OPTS"
exec ./mvnw gatling:test -Dgatling.simulationClass=simulations.StepRateLLMSimulation
