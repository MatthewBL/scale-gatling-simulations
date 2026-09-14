# LLM Workload Simulation

## Purpose

This project measures the behavior of an OpenAI-compatible LLM under a
controlled population of simulated users. It gradually introduces the target
number of users, waits until they are all present, and then measures workload
for `SIMULATION_MINUTES`.

Each user has a subscription tier (`basic`, `standard`, or `pro`) and a usage
profile (`low` or `high`). Requests consume units. Once a user has exhausted
their quota, the next attempted request is recorded as a Gatling failure named
`insufficient-units`, and that user stops. HTTP errors remain separate from
quota failures.

Three provisioning experiments run in sequence: under-provisioning,
over-provisioning, and fine-tuned provisioning. Their tier quotas are read from
`.env` and passed to each run as JVM properties.

## Setup

### 1. Clone the repository

```bash
git clone <repository-url>
cd scale-gatling-simulations
```

### 2. Configure the environment

Copy `.env.example` to `.env` and edit the values:

```bash
cp .env.example .env
```

Important settings include:

```dotenv
LLM_URL=gpu06:9000
ENDPOINT_PATH=/v1/completions
MODELS_ENDPOINT=/v1/models
SIMULATION_MINUTES=60
USER_RAMP_MINUTES=30
FIRST_REQUEST_BATCH_SIZE=500
FIRST_REQUEST_TURN_INTERVAL_SECONDS=2
```

`LLM_URL` may be a host and port or a complete URL. The launcher normalizes a
bare host and port to `http://...`. The model is discovered automatically from
`MODELS_ENDPOINT`.

The provisioning values use this format:

```dotenv
UNDER_PROVISIONING_TEST=2,3,4
OVER_PROVISIONING_TEST=10000,10000,10000
FINE_TUNED_TEST=5,8,10                
```

Each triplet is `basic,standard,pro` units per minute.
Units are not a lifetime quota. In the simulation they refill continuously for
each user at the configured rate. A request consumes `UNITS_PER_REQUEST` units, and
`MAX_ACCUMULATED_REQUESTS` bounds the per-user burst capacity. The example uses
`UNITS_PER_REQUEST=100` so integer unit budgets can represent fractions of a
request per minute. A user that lacks units records `insufficient-units` for
that attempt but remains in the simulation for later requests.

### Oversubscription rate

When an experiment ends, `run-llm-workload.sh` appends one JSON object per run to
`results/oversubscription-rate.jsonl` (the `results/` directory is created when
needed). The oversubscription rate is the per-tier relative difference between
the fine-tuned and under-provisioned unit budgets, in `[basic, standard, pro]`
order, stored as exact decimals:

    oversubscription_rate[i] = FINE_TUNED_TEST[i] / UNDER_PROVISIONING_TEST[i]

With the example values above (`FINE_TUNED_TEST=5,8,10` and
`UNDER_PROVISIONING_TEST=2,3,4`) the rate is `[2.5, 2.67, 2.5]`. A zero
under-provisioned budget is stored as `null` for that tier. The same value is
shown by `estimate_requests.py` (text or `--json` report) without running the
simulation.

### 3. Run the simulation

On Linux, macOS, or WSL:

```bash
chmod u+x run-llm-workload.sh
./run-llm-workload.sh
```

On Windows PowerShell, use Maven directly if the dependencies are available:

```powershell
./mvnw.cmd gatling:test `
	"-Dgatling.simulationClass=simulations.LLMWorkloadSimulation"
```

On an isolated Rocky Linux machine, the launcher uses the bundled offline
repository:

```bash
sh ./mvnw -o \
	-Dmaven.repo.local="$PWD/local-repo" \
	gatling:test \
	-Dgatling.simulationClass=simulations.LLMWorkloadSimulation
```

For a background SLURM job, submit from the repository directory:

```bash
chmod u+x run-llm-workload.sh submit_llm_workload.sbatch submit_llm_workload.sh
sbatch submit_llm_workload.sbatch
```

The default SLURM node is configured in `submit_llm_workload.sbatch`. Override
it for one submission with:

```bash
sbatch --nodelist=c07 submit_llm_workload.sbatch
```

or use the helper:

```bash
./submit_llm_workload.sh c07
```

SLURM writes logs to `gatling-llm-workload-<job-id>.out` and
`gatling-llm-workload-<job-id>.err`.

### 4. Run a queue of executions (`run_queue.py`)

If you want to run several simulations back-to-back without watching for each one
to finish — each with different parameters — use the queue runner. It executes
runs strictly one at a time (FIFO): the next run starts only when the previous
one ends, so you can enqueue several runs and walk away.

A queued "run" is a small `.env`-style file holding only the parameters that
differ from the base `.env`. The worker merges each run's overrides over `.env`
and passes them as `-D` JVM properties (which take precedence), so no Java code
changes are needed and any parameter can differ per run.

```bash
# Enqueue a run (any number, with different parameters)
python run_queue.py add ramp-test --set TOTAL_USERS=500 --set SIMULATION_MINUTES=30
python run_queue.py add full-load   --set TOTAL_USERS=20000 --set SIMULATION_MINUTES=60
python run_queue.py add tuned       --set BASIC_UNITS_PER_MINUTE=50 --set STANDARD_UNITS_PER_MINUTE=100

# Drain whatever is pending now, then exit:
python run_queue.py start --once

# Or keep watching for newly added runs until Ctrl-C (fire-and-forget):
python run_queue.py start
```

Other commands: `python run_queue.py run <name> --dry-run` (preview the exact
Maven command for one run), `python run_queue.py list`, and
`python run_queue.py clear`.

Directory layout (created on demand, git-ignored):

```
queue/pending/<name>.env    enqueued, waiting
queue/running/<name>.env    currently executing
queue/done/<name>.env       finished OK
queue/failed/<name>.env     finished with an error
results/runs.jsonl          append-only per-run audit log
logs/<runId>.log            per-run Gatling console output
```

Each run gets a unique `runId=<name>-<timestamp>`, so its Gatling report lands in
its own `target/gatling/<runId>/` directory. `run_queue.py` works on Windows
(`mvnw.cmd`) and Linux/SLURM (`./mvnw`); `run-llm-workload.sh` remains available
for the three-case provisioning experiment (or its cases can be enqueued as three
separate runs).

## Workload Timing & Generation

Before the simulation starts, a modular workload schedule generator creates a precise request schedule for each user. This approach offers several advantages:

**Request Timing Distribution**
Users receive randomized request schedules distributed uniformly throughout the simulation duration. This prevents large gaps while maintaining consistent overall workload.

**Looseness Parameter**
Each user's request count can vary around their assigned profile using the `LOOSENESS` environment variable:
- Defined as a percentage (0-100)
- Example: If a user's low profile is 10 requests/hour and `LOOSENESS=20`, they might send 8, 9, 10, 11, or 12 requests
- Default: 0 (no variation)

**First-Request Behavior**
After the ramp-up phase completes, users begin sending requests at random intervals instead of in rigid batches. This creates a more realistic initial load pattern.

**User Ramp-up Phase**
By default, users arrive evenly during `USER_RAMP_MINUTES` and send no requests during that phase. After all users arrive, they begin sending requests according to their generated schedules.

For example, with 50,000 users and a 30-minute ramp:
- ~27.8 users per second join the simulation
- All requests are paused until the ramp completes
- After ramp, requests begin according to each user's schedule

**Interacting During the Ramp**
Set `INTERACT_DURING_RAMP=true` to let users start sending requests as soon as they
join the simulation instead of waiting for the full ramp to complete:

```dotenv
INTERACT_DURING_RAMP=true
```

With this option:
- The rendezvous barrier is skipped, so each user's schedule starts when that user is
  injected rather than when the last user arrives
- Load grows linearly during the ramp (each arriving user immediately begins their
  pause → request loop)
- Unit limits, refills, and `insufficient-units` accounting are active from the very
  first arrivals; no other setting changes
- The total run envelope stays `USER_RAMP_MINUTES + SIMULATION_MINUTES`

Leave it unset or set to `false` to keep the default behavior of waiting until all
users have arrived.

**Extensible Design**
The workload generation system is modular, supporting different distribution strategies through the `WorkloadTrendStrategy` interface. Currently, the uniform distribution strategy ensures consistent workload throughout the experiment. Future strategies could implement peak hours, circadian patterns, or other realistic trends.

The same reproducible stochastic demand schedule is used for all three
experiments. Only the per-subscription unit budgets change. This models
statistical multiplexing: the sum of subscription entitlements may be slightly
above service capacity while users are independently active only part of the
time.

## Workload Timing (Legacy)

## Offline Maven notes

The repository includes a local Maven repository under `local-repo`. Do not use
`dependency-reduced-pom.xml` as the project build file; use `pom.xml`. The
`-o` option prevents Maven from contacting Maven Central.
