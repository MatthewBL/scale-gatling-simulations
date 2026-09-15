#!/usr/bin/env python3
"""
run_queue.py - queue and sequentially execute Gatling LLM simulations.

The Gatling simulation (simulations.LLMWorkloadSimulation) reads every parameter
from .env via SimulationConfig.load() with priority: -D JVM properties, then
.env, then environment variables. run_queue.py exploits that priority to run an
arbitrary list of executions back-to-back, each with its own parameters, without
touching any Java code.

A queued "run" is a small .env-style file (queue/pending/<name>.env) holding only
the parameters that differ from the base .env, plus the item's QUEUE_MODE. By
default one queued item is a provisioning SWEEP: it expands into the three
experiments of run-llm-workload.sh, executed in sequence - under-provisioning,
over-provisioning and fine-tuned-provisioning - each as its own Gatling execution
with its own runId, log file and report directory. `add --single` enqueues an
isolated execution instead (one run, no case expansion).

For every case the worker merges the item's overrides over the base .env, adds
the case's tier quotas taken from the matching *_TEST triplet
(BASIC/STANDARD/PRO_UNITS_PER_MINUTE) and passes every merged key as a -D JVM
property to `mvnw gatling:test`, so the -D values win (mirroring
run-llm-workload.sh).

Runs are executed strictly one at a time (FIFO): the next item only starts after
the last case of the current item has finished, so you can enqueue several sweeps
and walk away. The cases of a sweep also run strictly sequentially, and a sweep
stops at the first case that exits non-zero (mirroring the `set -e` behavior of
run-llm-workload.sh): the remaining cases are skipped and the item is moved to
queue/failed/. The oversubscription-rate record is appended only when all three
cases of a sweep completed.

Directory layout (all created on demand):
    queue/pending/<name>.env    enqueued, waiting
    queue/running/<name>.env    claimed and currently executing
    queue/done/<name>.env       finished successfully
    queue/failed/<name>.env     finished with an error
    results/runs.jsonl          append-only per-run audit log
    results/oversubscription-rate.jsonl  one record per completed sweep
    logs/<runId>.log            per-case Gatling console output

Usage:
    python run_queue.py add <name> [--set KEY=VALUE ...] [--env-file PATH] [--single]
        Enqueue a new run (a three-experiment sweep by default). --set is
        repeatable; values override the base .env. --env-file imports overrides
        from an existing .env-style file. --single enqueues one isolated
        execution instead of the sweep.
    python run_queue.py start [--once] [--interval SECONDS]
        Process the queue sequentially. With --once, drain what is pending now
        and exit; by default keep polling for newly added runs until Ctrl-C.
    python run_queue.py run <name> [--dry-run]
        Run a single named pending item now. --dry-run prints the exact commands
        (one per case) that would be used without executing them or touching the
        queue.
    python run_queue.py list [--json]
        Show pending / running / done / failed items and recent activity.
    python run_queue.py clear [--done] [--failed] [--all]
        Remove processed run files (done and failed by default).

Environment / command building mirrors run-llm-workload.sh:
    * Windows uses mvnw.cmd (via cmd.exe /c), POSIX uses `sh ./mvnw`.
    * Offline mode (-o -Dmaven.repo.local=...) is used when local-repo exists.
    * MAVEN_OPTS defaults to -Xmx4g unless one is already set.

Test hook: set the environment variable RUN_QUEUE_CMD to an alternate launcher
(e.g. "python -c ...") to substitute the Maven invocation; it is called once per
case, which makes it easy to verify queue state transitions without a live LLM.
The value is split with shlex, so on Windows use forward slashes in file paths
(shlex treats backslashes as escape characters).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))

QUEUE_DIR = os.path.join(ROOT_DIR, "queue")
PENDING_DIR = os.path.join(QUEUE_DIR, "pending")
RUNNING_DIR = os.path.join(QUEUE_DIR, "running")
DONE_DIR = os.path.join(QUEUE_DIR, "done")
FAILED_DIR = os.path.join(QUEUE_DIR, "failed")

RESULTS_DIR = os.path.join(ROOT_DIR, "results")
RUNS_LOG = os.path.join(RESULTS_DIR, "runs.jsonl")
OVERSUBSCRIPTION_LOG = os.path.join(RESULTS_DIR, "oversubscription-rate.jsonl")
LOGS_DIR = os.path.join(ROOT_DIR, "logs")

LOCAL_REPO = os.path.join(ROOT_DIR, "local-repo")

BASE_ENV = (
    os.path.join(ROOT_DIR, ".env")
    if os.path.isfile(os.path.join(ROOT_DIR, ".env"))
    else os.path.join(ROOT_DIR, ".env.example")
)

# A run name becomes part of a filesystem path and the Gatling runId, so keep it
# to a safe, portable token.
SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

# Unit budgets are non-negative integers, exactly three per case (basic,standard,pro).
UNIT_VALUE_RE = re.compile(r"^[0-9]+$")

# A queued item is a provisioning sweep by default: the same three cases, in the
# same order, as run-llm-workload.sh. Each tuple is
# (case name, .env key holding the "basic,standard,pro" units/min triplet, fallback
# triplet mirroring the ${VAR:-default} values of run-llm-workload.sh).
CASES = (
    ("under-provisioning", "UNDER_PROVISIONING_TEST", "10,20,30"),
    ("over-provisioning", "OVER_PROVISIONING_TEST", "10000,10000,10000"),
    ("fine-tuned-provisioning", "FINE_TUNED_TEST", "25,40,50"),
)
CASE_ENV_KEYS = {env_key for _, env_key, _ in CASES}
CASE_SOURCES = {case_name: (env_key, fallback) for case_name, env_key, fallback in CASES}
QUOTA_KEYS = ("BASIC_UNITS_PER_MINUTE", "STANDARD_UNITS_PER_MINUTE", "PRO_UNITS_PER_MINUTE")

# The mode lives inside the item file, so the queue state transitions stay simple.
MODE_KEY = "QUEUE_MODE"
MODE_SWEEP = "SWEEP"
MODE_SINGLE = "SINGLE"

# Pre-rendered JSON fragment marker used when writing the oversubscription record.
_RAW_RATE_FIELD = "@@OVERSUBSCRIPTION_RATE@@"


def ensure_dirs() -> None:
    for d in (QUEUE_DIR, PENDING_DIR, RUNNING_DIR, DONE_DIR, FAILED_DIR, RESULTS_DIR, LOGS_DIR):
        os.makedirs(d, exist_ok=True)


# --------------------------------------------------------------------------- #
# .env parsing (mirrors estimate_requests.py)
# --------------------------------------------------------------------------- #
def parse_env_value(raw: str) -> str:
    """Strip a trailing comment (outside quotes) and surrounding quotes."""
    out = []
    in_single = in_double = False
    for i, ch in enumerate(raw):
        if ch == "'" and not in_double:
            in_single = not in_single
            continue
        if ch == '"' and not in_single:
            in_double = not in_double
            continue
        if ch == "#" and not in_single and not in_double:
            if i == 0 or raw[i - 1].isspace():
                break
        out.append(ch)
    value = "".join(out).strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        value = value[1:-1].strip()
    return value


def load_env_file(path: str) -> dict:
    settings = {}
    if not os.path.isfile(path):
        raise FileNotFoundError("environment file not found: %s" % path)
    try:
        text = open(path, "r", encoding="utf-8-sig").read()
    except UnicodeDecodeError:
        text = open(path, "r", encoding="latin-1").read()
    for line in text.splitlines():
        match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", line)
        if match:
            settings[match.group(1)] = parse_env_value(match.group(2))
    return settings


def format_env_value(value: str) -> str:
    """Quote a value for the .env-style item file when it needs it."""
    if re.search(r"[\s#]", value):
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return '"' + escaped + '"'
    return value


def write_item_file(path: str, overrides: dict) -> None:
    lines = ["%s=%s" % (k, format_env_value(overrides[k])) for k in sorted(overrides)]
    text = "\n".join(lines) + ("\n" if lines else "")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def shell_join(args) -> str:
    return " ".join(shlex.quote(a) for a in args)


# --------------------------------------------------------------------------- #
# Audit log
# --------------------------------------------------------------------------- #
def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime())


def run_id_for(name: str, case: str = "") -> str:
    stamp = time.strftime("%Y%m%d%H%M%S") + "%03d" % (int(time.time() * 1000) % 1000)
    return "%s-%s-%s" % (name, case, stamp) if case else "%s-%s" % (name, stamp)


def parse_case(case_name: str, raw: str):
    """Parse a 'basic,standard,pro' units/min triplet (mirrors run-llm-workload.sh)."""
    values = [value.strip() for value in str(raw).split(",")]
    if len(values) != 3 or any(value == "" for value in values):
        sys.exit("error: %s must contain exactly three comma-separated unit values: %r"
                 % (case_name, raw))
    for value in values:
        if not UNIT_VALUE_RE.match(value):
            sys.exit("error: %s contains an invalid unit value: %r" % (case_name, value))
    return [int(value) for value in values]


def resolve_case_env_key(merged: dict, env_key: str, fallback: str) -> str:
    value = merged.get(env_key, "")
    return value.strip() or fallback


def item_mode(path: str) -> str:
    """Read QUEUE_MODE from an item file (missing/unknown means SWEEP)."""
    try:
        mode = load_env_file(path).get(MODE_KEY, "").strip().upper()
    except Exception:
        return MODE_SWEEP
    return mode if mode in (MODE_SWEEP, MODE_SINGLE) else MODE_SWEEP


def log_event(record: dict) -> None:
    ensure_dirs()
    with open(RUNS_LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(record) + "\n")


# --------------------------------------------------------------------------- #
# Command construction & invocation
# --------------------------------------------------------------------------- #
def build_command(run_id: str, merged: dict):
    if os.path.isdir(LOCAL_REPO):
        offline = ["-o", "-Dmaven.repo.local=" + LOCAL_REPO]
    else:
        offline = []

    launcher = os.environ.get("RUN_QUEUE_CMD", "")
    if launcher:
        base = shlex.split(launcher)
    elif os.name == "nt":
        # .cmd files cannot be launched directly by CreateProcess; route via cmd.
        base = ["cmd.exe", "/c", "mvnw.cmd"]
    else:
        base = ["sh", "./mvnw"]

    args = list(base) + [
        "gatling:test",
        "-Dgatling.simulationClass=simulations.LLMWorkloadSimulation",
        "-Dgatling.runId=" + run_id,
        "-Dgatling.core.checkVersion=false",
    ] + offline
    for k in sorted(command_params(merged)):
        args.append("-D%s=%s" % (k, merged[k]))
    return args


def command_params(merged: dict) -> dict:
    """Parameters actually passed to the simulation.

    QUEUE_MODE and the *_TEST triplets are queue/case selectors, not simulation
    parameters (run-llm-workload.sh does not pass them either): the worker turns
    a triplet into the three BASIC/STANDARD/PRO_UNITS_PER_MINUTE quotas.
    """
    return {k: v for k, v in merged.items() if k != MODE_KEY and k not in CASE_ENV_KEYS}


def invoke(args, log_path: str) -> int:
    env = dict(os.environ)
    maven_opts = env.get("MAVEN_OPTS", "")
    if "-Xmx" not in maven_opts:
        env["MAVEN_OPTS"] = ("-Xmx4g " + maven_opts).strip()
    ensure_dirs()
    with open(log_path, "w", encoding="utf-8") as logf:
        proc = subprocess.run(args, cwd=ROOT_DIR, env=env, stdout=logf, stderr=subprocess.STDOUT)
    return proc.returncode


# --------------------------------------------------------------------------- #
# Run processing
# --------------------------------------------------------------------------- #
def sweep_plan(name: str, merged: dict, mode: str):
    """Return [(case name, run_id, case parameters)] for one queued item.

    A sweep expands into the three provisioning cases of run-llm-workload.sh,
    each with its own runId and its tier quotas taken from the matching *_TEST
    triplet. A single item stays one execution with the merged parameters.
    """
    if mode == MODE_SINGLE:
        return [(None, run_id_for(name), dict(merged))]

    plan = []
    for case_name, env_key, fallback in CASES:
        triplet = resolve_case_env_key(merged, env_key, fallback)
        units = parse_case(env_key, triplet)
        params = dict(merged)
        params.update(zip(QUOTA_KEYS, units))
        plan.append((case_name, run_id_for(name, case_name), params))
    return plan


def format_decimal(value: float) -> str:
    """Format a ratio like awk's OFMT/CONVFMT %.17g, so the queue appends exactly
    the same numbers to results/oversubscription-rate.jsonl as run-llm-workload.sh."""
    return "%.17g" % value


def write_oversubscription_summary(name: str, run_ids, merged: dict) -> None:
    """Append the record run-llm-workload.sh writes once a sweep completes."""
    under = parse_case("UNDER_PROVISIONING_TEST",
                       resolve_case_env_key(merged, "UNDER_PROVISIONING_TEST", "10,20,30"))
    fine = parse_case("FINE_TUNED_TEST",
                      resolve_case_env_key(merged, "FINE_TUNED_TEST", "25,40,50"))
    rate = ["null" if under[i] == 0 else format_decimal(fine[i] / under[i])
            for i in range(len(under))]
    record = {
        "run_timestamp": time.strftime("%Y%m%d%H%M%S"),
        "run": name,
        "run_ids": list(run_ids),
        "under_provisioning": under,
        "fine_tuned": fine,
        "oversubscription_rate": _RAW_RATE_FIELD,
    }
    line = json.dumps(record).replace('"%s"' % _RAW_RATE_FIELD, "[%s]" % ", ".join(rate))
    ensure_dirs()
    with open(OVERSUBSCRIPTION_LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print("Oversubscription rate (fine-tuned / under-provisioned, basic/standard/pro): [%s]"
          % ", ".join(rate))
    print("Oversubscription rate stored in %s" % OVERSUBSCRIPTION_LOG)


def _run_one(pending_path: str, dry_run: bool = False) -> bool:
    name = os.path.splitext(os.path.basename(pending_path))[0]
    overrides = load_env_file(pending_path)
    merged = dict(load_env_file(BASE_ENV))
    merged.update(overrides)
    mode = merged.pop(MODE_KEY, "").strip().upper() or MODE_SWEEP
    if mode not in (MODE_SWEEP, MODE_SINGLE):
        sys.exit("error: %s=%r in %s (expected %s or %s)"
                 % (MODE_KEY, mode, pending_path, MODE_SWEEP, MODE_SINGLE))
    plan = sweep_plan(name, merged, mode)
    case_names = [case_name or name for case_name, _, _ in plan]

    if dry_run:
        print("Run        : %s (%s, %d case(s))" % (name, mode.lower(), len(plan)))
        for case_name, run_id, params in plan:
            print("")
            print("Case       : %s" % (case_name or name))
            if case_name:
                env_key, fallback = CASE_SOURCES[case_name]
                print("Quotas     : basic=%s standard=%s pro=%s (from %s=%s)"
                      % (params.get("BASIC_UNITS_PER_MINUTE"),
                         params.get("STANDARD_UNITS_PER_MINUTE"),
                         params.get("PRO_UNITS_PER_MINUTE"),
                         env_key, params.get(env_key, fallback)))
            print("run_id     : %s" % run_id)
            passed = command_params(params)
            print("Parameters (%d, passed as -D):" % len(passed))
            for k in sorted(passed):
                print("  %s=%s" % (k, passed[k]))
            print("Command:")
            print("  " + shell_join(build_command(run_id, params)))
            print("Log file (would be): %s" % os.path.join(LOGS_DIR, run_id + ".log"))
        return True

    running_path = os.path.join(RUNNING_DIR, os.path.basename(pending_path))
    ensure_dirs()
    os.replace(pending_path, running_path)  # atomic claim on the same filesystem
    log_event({"event": "started", "run": name, "mode": mode, "cases": case_names,
               "status": "running", "started_at": now_iso()})
    print("[%s] START  %s (%s, %d case(s))"
          % (now_iso(), name, mode.lower(), len(plan)))

    completed = []
    failed_case = None
    exit_code = 0
    for case_name, run_id, params in plan:
        log_path = os.path.join(LOGS_DIR, run_id + ".log")
        log_event({"event": "case_started", "run": name, "case": case_name or name,
                   "run_id": run_id, "mode": mode, "status": "running",
                   "started_at": now_iso()})
        print("[%s] CASE   %s %s (run_id=%s)"
              % (now_iso(), name, case_name or name, run_id))

        exit_code = invoke(build_command(run_id, params), log_path)
        case_status = "done" if exit_code == 0 else "failed"
        record = {"event": "case_finished", "run": name, "case": case_name or name,
                  "run_id": run_id, "mode": mode, "status": case_status,
                  "exit_code": exit_code, "finished_at": now_iso(),
                  "log_file": os.path.join("logs", run_id + ".log")}
        if exit_code == 0:
            record["report_dir"] = os.path.join("target", "gatling", run_id)
            completed.append(case_name or name)
        else:
            failed_case = case_name or name
        log_event(record)
        print("[%s] CASE   %s %s -> %s (exit=%d, log=%s)"
              % (now_iso(), name, case_name or name, case_status, exit_code, log_path))

        if exit_code != 0:
            # Mirror run-llm-workload.sh's `set -e`: stop this sweep here.
            if mode == MODE_SWEEP:
                print("[%s] SWEEP  %s stopped at %s; skipping the remaining case(s)"
                      % (now_iso(), name, failed_case))
            break

    status = "done" if failed_case is None else "failed"
    dest = os.path.join(DONE_DIR if failed_case is None else FAILED_DIR,
                        os.path.basename(pending_path))
    os.replace(running_path, dest)
    log_event({"event": "finished", "run": name, "mode": mode, "status": status,
               "exit_code": exit_code, "run_ids": [r for _, r, _ in plan],
               "cases": case_names, "cases_completed": completed,
               "failed_case": failed_case, "finished_at": now_iso()})
    print("[%s] END    %s -> %s (exit=%d)" % (now_iso(), name, status, exit_code))

    if status == "done" and mode == MODE_SWEEP:
        write_oversubscription_summary(name, [r for _, r, _ in plan], merged)
    return failed_case is None


def requeue_stale() -> None:
    """If a previous worker was interrupted mid-run, put its item back in queue."""
    stale = [f for f in os.listdir(RUNNING_DIR) if f.endswith(".env")]
    for f in stale:
        os.replace(os.path.join(RUNNING_DIR, f), os.path.join(PENDING_DIR, f))
        print("WARNING: interrupted run %r moved back to pending" % f)


# --------------------------------------------------------------------------- #
# Subcommands
# --------------------------------------------------------------------------- #
def cmd_add(args) -> None:
    ensure_dirs()
    name = args.name
    if not SAFE_NAME_RE.match(name):
        sys.exit("error: name must match %s (got %r)" % (SAFE_NAME_RE.pattern, name))

    overrides = {}
    for kv in args.set or []:
        key, sep, value = kv.partition("=")
        if not key or not sep:
            sys.exit("error: --set must look like KEY=VALUE (got %r)" % kv)
        overrides[key.strip().upper()] = value
    if args.env_file:
        for k, v in load_env_file(args.env_file).items():
            overrides.setdefault(k.upper(), v)  # explicit --set wins

    mode = MODE_SINGLE if args.single else MODE_SWEEP
    if mode == MODE_SWEEP:
        # Each case overwrites these from its *_TEST triplet, so setting them
        # directly on a sweep item would be silently ignored.
        conflicting = sorted(k for k in overrides if k in QUOTA_KEYS)
        if conflicting:
            sys.exit("error: %s cannot be set on a sweep item (each case derives it from "
                     "the *_TEST triplet): use --set UNDER_PROVISIONING_TEST=... or --single"
                     % ", ".join(conflicting))
        # Fail fast on malformed triplets instead of dying mid-sweep.
        merged = dict(load_env_file(BASE_ENV))
        merged.update(overrides)
        for _, env_key, fallback in CASES:
            parse_case(env_key, resolve_case_env_key(merged, env_key, fallback))

    dest = os.path.join(PENDING_DIR, name + ".env")
    if os.path.exists(dest):
        sys.exit("error: a pending run named %r already exists (%s)" % (name, dest))

    if not overrides:
        print("note: no overrides given; run will use the base .env as-is")
    item = dict(overrides)
    item[MODE_KEY] = mode
    write_item_file(dest, item)
    log_event({"event": "enqueued", "run": name, "status": "queued", "mode": mode,
               "cases": [c[0] for c in CASES] if mode == MODE_SWEEP else [name],
               "params": overrides, "enqueued_at": now_iso()})
    print("Enqueued %s -> %s" % (name, dest))
    if mode == MODE_SWEEP:
        print("Mode: %s (under-provisioning, over-provisioning and fine-tuned-provisioning,"
              " in sequence)" % mode)
    else:
        print("Mode: %s (one isolated execution)" % mode)
    print("Start the worker with: python run_queue.py start [--once]")


def cmd_start(args) -> None:
    ensure_dirs()
    requeue_stale()
    while True:
        pending = sorted(f for f in os.listdir(PENDING_DIR) if f.endswith(".env"))
        if not pending:
            if args.once:
                print("Queue is empty.")
                break
            time.sleep(max(1.0, args.interval))
            continue
        all_ok = True
        for f in pending:
            all_ok = _run_one(os.path.join(PENDING_DIR, f)) and all_ok
        if args.once:
            print("Queue drained (all ok=%s)." % all_ok)
            if not all_ok:
                # Propagate failures so SLURM marks the job FAILED.
                sys.exit(1)
            break


def cmd_run(args) -> None:
    ensure_dirs()
    name = args.name
    if not SAFE_NAME_RE.match(name):
        sys.exit("error: name must match %s (got %r)" % (SAFE_NAME_RE.pattern, name))
    path = os.path.join(PENDING_DIR, name + ".env")
    if not os.path.exists(path):
        sys.exit("error: no pending run named %r (looked for %s)" % (name, path))
    _run_one(path, dry_run=args.dry_run)


def cmd_list(args) -> None:
    ensure_dirs()
    groups = {}
    dirs = {}
    for label, d in (("pending", PENDING_DIR), ("running", RUNNING_DIR),
                     ("done", DONE_DIR), ("failed", FAILED_DIR)):
        dirs[label] = d
        groups[label] = sorted(f for f in os.listdir(d) if f.endswith(".env"))
    if args.json:
        print(json.dumps({label: [{"file": f, "mode": item_mode(os.path.join(dirs[label], f))}
                                  for f in files]
                          for label, files in groups.items()}))
        return
    for label in ("pending", "running", "done", "failed"):
        files = groups[label]
        print("%-8s %d" % (label + ":", len(files)))
        for f in files:
            print("          %-34s [%s]" % (f, item_mode(os.path.join(dirs[label], f))))
    if os.path.exists(RUNS_LOG):
        with open(RUNS_LOG, encoding="utf-8") as fh:
            lines = [l for l in fh if l.strip()]
        print("\nRecent activity (%d record(s) in %s):" % (len(lines), RUNS_LOG))
        for line in lines[-5:]:
            rec = json.loads(line)
            stamp = rec.get("started_at") or rec.get("enqueued_at") or rec.get("finished_at") or ""
            event = rec.get("event", "")
            if rec.get("case"):
                event = "%s:%s" % (event, rec["case"])
            run_id = rec.get("run_id") or ",".join(rec.get("run_ids", []))
            print("  %s %-24s %-12s %s" % (stamp, event, rec.get("run", ""), run_id))
            if rec.get("failed_case"):
                print("  %s %-24s %-12s failed case: %s"
                      % (stamp, "", "", rec["failed_case"]))


def cmd_clear(args) -> None:
    ensure_dirs()
    targets = []
    if args.all:
        targets = [PENDING_DIR, RUNNING_DIR, DONE_DIR, FAILED_DIR]
    else:
        if args.done:
            targets.append(DONE_DIR)
        if args.failed:
            targets.append(FAILED_DIR)
        if not targets:
            targets = [DONE_DIR, FAILED_DIR]
    removed = 0
    for d in targets:
        for f in os.listdir(d):
            if f.endswith(".env"):
                os.remove(os.path.join(d, f))
                removed += 1
    print("Removed %d run file(s)." % removed)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def main(argv=None) -> None:
    parser = argparse.ArgumentParser(
        prog="run_queue.py",
        description="Queue and sequentially run Gatling LLM simulations.")
    sub = parser.add_subparsers(dest="command", required=True)

    p_add = sub.add_parser("add", help="Enqueue a new run (a 3-experiment sweep by default)")
    p_add.add_argument("name", help="Run name (letters, digits, '.', '_', '-')")
    p_add.add_argument("--set", action="append", metavar="KEY=VALUE",
                       help="Override a parameter; repeatable")
    p_add.add_argument("--env-file", metavar="PATH",
                       help="Import overrides from an .env-style file")
    p_add.add_argument("--single", action="store_true",
                       help="Enqueue one isolated execution instead of the 3-case sweep")
    p_add.set_defaults(func=cmd_add)

    p_start = sub.add_parser("start", help="Process the queue sequentially")
    p_start.add_argument("--once", action="store_true",
                         help="Drain what is pending now, then exit")
    p_start.add_argument("--interval", type=float, default=2.0,
                         help="Poll interval seconds when watching (default 2)")
    p_start.set_defaults(func=cmd_start)

    p_run = sub.add_parser("run", help="Run a single named pending item")
    p_run.add_argument("name")
    p_run.add_argument("--dry-run", action="store_true",
                       help="Print the command without executing or touching the queue")
    p_run.set_defaults(func=cmd_run)

    p_list = sub.add_parser("list", help="Show queue state")
    p_list.add_argument("--json", action="store_true")
    p_list.set_defaults(func=cmd_list)

    p_clear = sub.add_parser("clear", help="Remove processed run files")
    p_clear.add_argument("--done", action="store_true")
    p_clear.add_argument("--failed", action="store_true")
    p_clear.add_argument("--all", action="store_true",
                         help="Also remove pending and running items")
    p_clear.set_defaults(func=cmd_clear)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
