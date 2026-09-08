#!/usr/bin/env python3
"""
estimate_requests.py - rough estimate of requests sent by scale-gatling-simulations

The Gatling simulation generates its full request schedule before the run starts
(fixed seed, reproducible), so the expected request volume can be computed
analytically from the .env settings. This script is a standalone helper and is
NOT part of the simulation workflow.

Model (mirrors the Java sources in src/main/java/simulations):
  1. TOTAL_USERS are split into tiers (basic/standard/pro) with the share
     settings and the same floor() arithmetic as
     SimulationConfig.userAssignments(). Within each tier,
     round(count * HIGH_USAGE_USER_SHARE) users are "high usage".
  2. Each user's target number of attempts is
         max(1, round(hourlyRequests * SIMULATION_MINUTES / 60))
     and LOOSENESS (a percentage) draws a uniform integer in
         [base - v, base + v]   with   v = max(1, round(base * LOOSENESS / 100))
     (WorkloadScheduleGenerator.calculateBaseTargetRequests / applyLooseness).
  3. Each attempt turns into a real HTTP request only while the user has enough
     refilled units; otherwise it is recorded by Gatling as an
     "insufficient-units" failure. In steady state a user can fulfil at most
         floor((UNITS_PER_REQUEST + tierUnitsPerMinute * SIMULATION_MINUTES)
               / UNITS_PER_REQUEST)
     attempts (initial bank = UNITS_PER_REQUEST, continuous refill afterwards;
     the refill rate is the tier budget passed by run-llm-workload.sh as
     -DBASIC/STANDARD/PRO_UNITS_PER_MINUTE from the *_TEST triplets).

run-llm-workload.sh executes the three provisioning cases in sequence with the
same generated schedule, so this tool reports per-case numbers plus a grand
total across the three runs.

Usage:
    python estimate_requests.py [--env PATH] [--set KEY=VALUE]... [--json]

    --env PATH       .env file to read (default: .env, else .env.example)
    --set KEY=VAL    override a setting; repeatable. Mirrors the -D JVM
                     properties the launcher passes, e.g.
                     --set TOTAL_USERS=20000 --set LOOSENESS=0
    --json           print a machine-readable JSON report instead of the table

Timing-only settings (USER_RAMP_MINUTES, INTERACT_DURING_RAMP,
FIRST_REQUEST_BATCH_SIZE, FIRST_REQUEST_TURN_INTERVAL_SECONDS) affect *when*
requests are sent but not *how many*, and are shown for reference only.

Roughness: request timings are randomized inside uniform slots, so the exact
fulfilled/insufficient split has a short-transient fuzz of about one request per
user at the quota boundary.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys

# Defaults mirror SimulationConfig.load() and run-llm-workload.sh.
DEFAULTS = {
    "SIMULATION_MINUTES": "10",
    "TOTAL_USERS": "10",
    "LOW_USAGE_REQUESTS_PER_HOUR": "360",
    "HIGH_USAGE_REQUESTS_PER_HOUR": "360",
    "HIGH_USAGE_USER_SHARE": "0.5",
    "BASIC_SHARE": "0.7",
    "STANDARD_SHARE": "0.2",
    "PRO_SHARE": "0.1",
    "UNITS_PER_REQUEST": "1",
    "MAX_ACCUMULATED_REQUESTS": "1",
    "USER_RAMP_MINUTES": "30",
    "LOOSENESS": "0",
    "INTERACT_DURING_RAMP": "false",
    "FIRST_REQUEST_BATCH_SIZE": "500",
    "FIRST_REQUEST_TURN_INTERVAL_SECONDS": "2",
    "UNDER_PROVISIONING_TEST": "10,20,30",
    "OVER_PROVISIONING_TEST": "10000,10000,10000",
    "FINE_TUNED_TEST": "25,40,50",
}

CASES = [
    ("under-provisioning", "UNDER_PROVISIONING_TEST"),
    ("over-provisioning", "OVER_PROVISIONING_TEST"),
    ("fine-tuned-provisioning", "FINE_TUNED_TEST"),
]


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
        raise FileNotFoundError(f"environment file not found: {path}")
    try:
        text = open(path, "r", encoding="utf-8-sig").read()
    except UnicodeDecodeError:
        text = open(path, "r", encoding="latin-1").read()
    for line in text.splitlines():
        match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", line)
        if match:
            settings[match.group(1)] = parse_env_value(match.group(2))
    return settings


def load_settings(env_path: str, overrides: list) -> tuple:
    path = env_path
    if path is None:
        path = ".env" if os.path.isfile(".env") else ".env.example"
    settings = dict(DEFAULTS)
    settings.update(load_env_file(path))
    for override in overrides:
        key, sep, value = override.partition("=")
        if not key or not sep:
            sys.exit(f"error: --set must look like KEY=VALUE (got {override!r})")
        settings[key.upper()] = value
    return settings, path


def jround(x: float) -> int:
    """Java Math.round: floor(x + 0.5)."""
    return math.floor(x + 0.5)


def parse_triplet(name: str, value: str) -> list:
    parts = [p.strip() for p in value.split(",")]
    if len(parts) != 3 or any(not p.isdigit() for p in parts):
        sys.exit(
            f"error: {name} must contain exactly three comma-separated unit values: {value!r}"
        )
    return [int(p) for p in parts]


def oversubscription_rate(settings: dict) -> list:
    """Per-tier ratio of fine-tuned to under-provisioned unit budgets,
    in [basic, standard, pro] order. A zero under-provisioned budget yields
    None for that tier."""
    under = parse_triplet("UNDER_PROVISIONING_TEST", settings["UNDER_PROVISIONING_TEST"])
    fine = parse_triplet("FINE_TUNED_TEST", settings["FINE_TUNED_TEST"])
    return [fine[i] / under[i] if under[i] != 0 else None for i in range(len(under))]


def usage_split(count: int, high_share: float, low_hourly: float, high_hourly: float) -> list:
    """Mirror of SimulationConfig.appendAssignments:
    the first round(count * highShare) users of a tier are high usage."""
    high = jround(count * high_share)
    return [
        {"usage": "high", "count": high, "hourly": high_hourly},
        {"usage": "low", "count": count - high, "hourly": low_hourly},
    ]


def target_range(hourly: float, sim_minutes: int, looseness: int):
    """Mirror of WorkloadScheduleGenerator: base target plus looseness jitter.
    Returns (lo, hi): the per-user target is uniform in [lo, hi], mean == base."""
    if hourly <= 0:
        return 0, 0
    base = max(1, jround(hourly * sim_minutes / 60.0))
    if looseness <= 0:
        return base, base
    variation = max(1, jround(base * looseness / 100.0))
    return max(0, base - variation), base + variation


def fulfill_cap(units_per_minute: int, sim_minutes: int, units_per_request: int) -> int:
    """Steady-state number of attempts a user can actually pay for:
    the initial bank is UNITS_PER_REQUEST and the tier budget refills per minute."""
    spendable = units_per_request + units_per_minute * sim_minutes
    return max(0, math.floor(spendable / units_per_request))


def expected_min_target(lo: int, hi: int, cap: int) -> float:
    """E[min(T, cap)] for T uniform integer in [lo, hi] inclusive."""
    if cap <= lo:
        return float(cap)
    if cap >= hi:
        return (lo + hi) / 2.0
    below = sum(range(lo, cap))          # t = lo .. cap-1
    bounded = (hi - cap + 1) * cap       # t = cap .. hi, all capped
    return (below + bounded) / (hi - lo + 1)


def build_users(settings: dict) -> dict:
    sim_minutes = max(0, int(settings["SIMULATION_MINUTES"]))
    total = max(0, int(settings["TOTAL_USERS"]))
    basic = min(total, math.floor(total * float(settings["BASIC_SHARE"])))
    standard = min(total - basic, math.floor(total * float(settings["STANDARD_SHARE"])))
    pro = max(0, total - basic - standard)
    high_share = min(1.0, max(0.0, float(settings["HIGH_USAGE_USER_SHARE"])))
    low_hourly = max(0.0, float(settings["LOW_USAGE_REQUESTS_PER_HOUR"]))
    high_hourly = max(0.0, float(settings["HIGH_USAGE_REQUESTS_PER_HOUR"]))
    # The Java generator clamps LOOSENESS to [0, 100].
    looseness = min(100, max(0, int(settings["LOOSENESS"])))

    groups = []
    for tier, count in (("basic", basic), ("standard", standard), ("pro", pro)):
        for group in usage_split(count, high_share, low_hourly, high_hourly):
            lo, hi = target_range(group["hourly"], sim_minutes, looseness)
            groups.append(
                {
                    "tier": tier,
                    "usage": group["usage"],
                    "count": group["count"],
                    "hourly": group["hourly"],
                    "target_lo": lo,
                    "target_hi": hi,
                }
            )
    return {
        "sim_minutes": sim_minutes,
        "total": total,
        "tiers": {"basic": basic, "standard": standard, "pro": pro},
        "groups": groups,
    }


def case_stats(user_info: dict, upm: tuple, units_per_request: int) -> dict:
    sim_minutes = user_info["sim_minutes"]
    tier_index = {"basic": 0, "standard": 1, "pro": 2}
    attempts = {"mean": 0.0, "min": 0, "max": 0}
    http = {"mean": 0.0, "min": 0, "max": 0}
    for g in user_info["groups"]:
        cap = fulfill_cap(upm[tier_index[g["tier"]]], sim_minutes, units_per_request)
        count = g["count"]
        attempts["mean"] += count * (g["target_lo"] + g["target_hi"]) / 2.0
        attempts["min"] += count * g["target_lo"]
        attempts["max"] += count * g["target_hi"]
        http["mean"] += count * expected_min_target(g["target_lo"], g["target_hi"], cap)
        http["min"] += count * min(g["target_lo"], cap)
        http["max"] += count * min(g["target_hi"], cap)
    counts = (user_info["tiers"]["basic"], user_info["tiers"]["standard"], user_info["tiers"]["pro"])
    entitlement = sum(c * u for c, u in zip(counts, upm)) / units_per_request
    return {"attempts": attempts, "http": http, "entitlement_per_minute": entitlement}


def fmt(x: float) -> str:
    return f"{round(x):,}"


def build_report(settings: dict, env_path: str, user_info: dict, units_per_request: int) -> dict:
    cases = []
    for name, key in CASES:
        upm = tuple(parse_triplet(key, settings[key]))
        stats = case_stats(user_info, upm, units_per_request)
        stats["name"] = name
        stats["units_per_minute"] = list(upm)
        stats["demand_per_minute"] = (
            stats["attempts"]["mean"] / user_info["sim_minutes"] if user_info["sim_minutes"] > 0 else 0.0
        )
        stats["success_rate"] = (
            stats["http"]["mean"] / stats["attempts"]["mean"] if stats["attempts"]["mean"] > 0 else 0.0
        )
        cases.append(stats)

    total_attempts = {"mean": 0.0, "min": 0, "max": 0}
    total_http = {"mean": 0.0, "min": 0, "max": 0}
    for c in cases:
        for key in ("mean", "min", "max"):
            total_attempts[key] += c["attempts"][key]
            total_http[key] += c["http"][key]
    grand = {
        "attempts": total_attempts,
        "http": total_http,
        "insufficient_units": total_attempts["mean"] - total_http["mean"],
    }
    return {"cases": cases, "grand_total": grand}


def print_config(settings: dict, env_path: str, user_info: dict) -> None:
    tiers = user_info["tiers"]
    low = sum(g["count"] for g in user_info["groups"] if g["usage"] == "low")
    high = sum(g["count"] for g in user_info["groups"] if g["usage"] == "high")
    print(f"Configuration (loaded from: {env_path}, plus Java defaults)")
    print(f"  Simulation minutes             : {settings['SIMULATION_MINUTES']}")
    print(
        f"  Total users                    : {user_info['total']:,}   "
        f"(basic {tiers['basic']:,} / standard {tiers['standard']:,} / pro {tiers['pro']:,})"
    )
    print(
        f"  Usage split                    : low {low:,} / high {high:,}  "
        f"(high share {float(settings['HIGH_USAGE_USER_SHARE']):.0%})"
    )
    print(
        "  Hourly request rates           : "
        f"{settings['LOW_USAGE_REQUESTS_PER_HOUR']} / {settings['HIGH_USAGE_REQUESTS_PER_HOUR']} req/hour (low/high)"
    )
    print(f"  Looseness                      : {settings['LOOSENESS']} %  (per-user target uniform in base +/- variation)")
    print(f"  Units per request              : {settings['UNITS_PER_REQUEST']}")
    print(
        f"  Max accumulated requests       : {settings['MAX_ACCUMULATED_REQUESTS']}  "
        "(burst cap; total volume unchanged)"
    )
    print(
        f"  Ramp / interact during ramp    : {settings['USER_RAMP_MINUTES']} min / "
        f"{settings['INTERACT_DURING_RAMP']}  (timing only, count unchanged)"
    )
    print(
        f"  First request batch / interval : {settings['FIRST_REQUEST_BATCH_SIZE']} / "
        f"{settings['FIRST_REQUEST_TURN_INTERVAL_SECONDS']} s  (timing only)"
    )


def print_schedule(user_info: dict) -> None:
    print("Scheduled attempts (per user, before unit gating)")
    for g in user_info["groups"]:
        if g["count"] == 0:
            continue
        base = (g["target_lo"] + g["target_hi"]) / 2.0
        rng = f"  (range {g['target_lo']} .. {g['target_hi']})" if g["target_hi"] > g["target_lo"] else ""
        print(f"  {g['usage']:>4} {g['tier']:<8} x {g['count']:>6,} users : {base:g} attempts on average{rng}")
    total_mean = sum(g["count"] * (g["target_lo"] + g["target_hi"]) / 2.0 for g in user_info["groups"])
    total_min = sum(g["count"] * g["target_lo"] for g in user_info["groups"])
    total_max = sum(g["count"] * g["target_hi"] for g in user_info["groups"])
    demand = total_mean / user_info["sim_minutes"] if user_info["sim_minutes"] > 0 else 0.0
    print(f"  Total attempts: {fmt(total_mean)}  (min {fmt(total_min)} .. max {fmt(total_max)})")
    print(
        f"  Expected demand: ~{demand:,.0f} requests/minute over the "
        f"{user_info['sim_minutes']}-minute measurement window"
    )


def print_cases(report: dict) -> None:
    print("Per provisioning case (each is one full sequential run)")
    for c in report["cases"]:
        upm = c["units_per_minute"]
        a, h = c["attempts"], c["http"]
        print(f"{c['name']}")
        print(f"  units/min (basic/standard/pro) : {upm[0]:,} / {upm[1]:,} / {upm[2]:,}")
        print(f"  aggregate entitlement          : {c['entitlement_per_minute']:,.0f} requests/minute")
        print(f"  attempts                       : {fmt(a['mean'])}  (range {fmt(a['min'])} .. {fmt(a['max'])})")
        print(
            f"  HTTP requests                  : {fmt(h['mean'])}  ({c['success_rate']:.1%} of attempts; "
            f"range {fmt(h['min'])} .. {fmt(h['max'])})"
        )
        print(f"  insufficient-units             : {fmt(a['mean'] - h['mean'])}")
        ent, dem = c["entitlement_per_minute"], c["demand_per_minute"]
        if ent < dem:
            verdict = f"entitlement ({ent:,.0f}/min) < demand ({dem:,.0f}/min) -> quota-bound, insufficient-units expected"
        else:
            verdict = f"entitlement ({ent:,.0f}/min) >= demand ({dem:,.0f}/min) -> every attempt can be fulfilled"
        print(f"  verdict                        : {verdict}")


def print_oversubscription(settings: dict) -> None:
    print("Oversubscription rate (fine-tuned / under-provisioned, basic/standard/pro)")
    print(f"  {oversubscription_rate(settings)}")


def print_grand(report: dict) -> None:
    g = report["grand_total"]
    print("Grand total across the 3 sequential runs")
    print(
        f"  Attempts           : {fmt(g['attempts']['mean'])}  "
        f"(range {fmt(g['attempts']['min'])} .. {fmt(g['attempts']['max'])}; includes looseness jitter)"
    )
    print(f"  HTTP requests      : {fmt(g['http']['mean'])}  (expected value)")
    print(f"  Insufficient-units : {fmt(g['insufficient_units'])}")
    print()
    print("Roughness: request timings are random inside uniform slots, so real counts can")
    print("differ by a few requests per user at the quota boundary; means are expected totals.")


def print_report(settings: dict, env_path: str, user_info: dict, units_per_request: int, report: dict) -> None:
    print_config(settings, env_path, user_info)
    print()
    print_schedule(user_info)
    print()
    print_cases(report)
    print()
    print_oversubscription(settings)
    print()
    print_grand(report)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Estimate the number of requests LLMWorkloadSimulation will send.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="example: python estimate_requests.py --set TOTAL_USERS=20000 --set LOOSENESS=0",
    )
    parser.add_argument(
        "--env", metavar="PATH", default=None,
        help=".env file to read (default: .env, else .env.example)",
    )
    parser.add_argument(
        "--set", action="append", default=[], metavar="KEY=VALUE",
        help="override a setting (repeatable, e.g. --set TOTAL_USERS=20000)",
    )
    parser.add_argument("--json", action="store_true", help="emit JSON instead of the text report")
    args = parser.parse_args(argv)

    settings, env_path = load_settings(args.env, args.set)
    user_info = build_users(settings)
    units_per_request = int(settings["UNITS_PER_REQUEST"])
    if units_per_request <= 0:
        sys.exit("error: UNITS_PER_REQUEST must be positive")
    report = build_report(settings, env_path, user_info, units_per_request)

    if args.json:
        payload = {
            "env_file": env_path,
            "config": settings,
            "users": user_info,
            "oversubscription_rate": oversubscription_rate(settings),
            "cases": [
                {
                    "name": c["name"],
                    "units_per_minute": c["units_per_minute"],
                    "entitlement_per_minute": c["entitlement_per_minute"],
                    "demand_per_minute": c["demand_per_minute"],
                    "success_rate": c["success_rate"],
                    "attempts": c["attempts"],
                    "http": c["http"],
                }
                for c in report["cases"]
            ],
            "grand_total": report["grand_total"],
        }
        print(json.dumps(payload, indent=2, sort_keys=True))
    else:
        print_report(settings, env_path, user_info, units_per_request, report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())