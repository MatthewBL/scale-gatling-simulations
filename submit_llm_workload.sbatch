#!/usr/bin/env bash
#SBATCH --job-name=gatling-llm-workload
#SBATCH --output=gatling-llm-workload-%j.out
#SBATCH --error=gatling-llm-workload-%j.err
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=4
#SBATCH --mem=8G
#SBATCH --time=04:00:00
#SBATCH --nodelist=c06

set -euo pipefail

ROOT_DIR="${SLURM_SUBMIT_DIR}"
cd "$ROOT_DIR"

# Adjust this module name if the cluster exposes a different Java 11 module.
if command -v module >/dev/null 2>&1; then
  module load openjdk/11
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java is not available in the batch environment." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  echo "ERROR: .env was not found in $ROOT_DIR." >&2
  exit 1
fi

echo "SLURM job: ${SLURM_JOB_ID:-unknown}"
echo "Node: ${SLURM_JOB_NODELIST:-unknown}"
echo "Working directory: $ROOT_DIR"

exec bash "$ROOT_DIR/run-llm-workload.sh"