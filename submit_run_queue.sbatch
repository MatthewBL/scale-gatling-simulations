#!/usr/bin/env bash
#SBATCH --job-name=gatling-run-queue
#SBATCH --output=run-queue-%j.out
#SBATCH --error=run-queue-%j.err
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=4
#SBATCH --mem=8G
#SBATCH --time=24:00:00
#SBATCH --nodelist=c06

# Drain or watch the run queue on a dedicated compute node. Items from
# queue/pending/ are executed strictly one at a time (FIFO) by run_queue.py, and
# each item first runs its three provisioning cases (under-provisioning,
# over-provisioning, fine-tuned-provisioning) in sequence, so the next item only
# starts once all three cases of the current one have finished.
#
# Usage:
#   sbatch submit_run_queue.sbatch            # once mode (default): drain queue, then exit
#   sbatch submit_run_queue.sbatch watch      # watch mode: keep polling for new runs
#   sbatch --nodelist=c07 submit_run_queue.sbatch
#   sbatch --time=12:00:00 submit_run_queue.sbatch   # override the wall-clock limit
#
# Wall-clock budget: one item costs about 3 x (USER_RAMP_MINUTES +
# SIMULATION_MINUTES) plus 10-15 minutes of overhead per case, so with the default
# .env (30 + 60 minutes) that is roughly 5 hours per queued run.
#
# Enqueue runs before (or during, in watch mode) from the submit directory:
#   python3 run_queue.py add <name> [--set KEY=VALUE ...] [--single]

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

# run_queue.py only needs the Python 3 standard library.
if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "ERROR: python3 is not available in the batch environment." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  echo "ERROR: .env was not found in $ROOT_DIR." >&2
  exit 1
fi

mode="${1:-once}"
case "$mode" in
  once|watch) ;;
  *) echo "ERROR: unknown mode '$mode' (expected 'once' or 'watch')." >&2; exit 1 ;;
esac

echo "SLURM job: ${SLURM_JOB_ID:-unknown}"
echo "Node: ${SLURM_JOB_NODELIST:-unknown}"
echo "Working directory: $ROOT_DIR"
if [[ "$mode" == once ]]; then
  echo "Queue worker: $PYTHON run_queue.py start --once"
else
  echo "Queue worker: $PYTHON run_queue.py start (watch mode)"
fi

# 'once' drains the queue and exits; 'watch' keeps polling for newly added runs.
# The exit code is propagated so SLURM records the job as FAILED when a run failed.
if [[ "$mode" == once ]]; then
  exec "$PYTHON" run_queue.py start --once
else
  exec "$PYTHON" run_queue.py start
fi