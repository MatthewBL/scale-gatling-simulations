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
Users arrive evenly during `USER_RAMP_MINUTES` and send no requests during that phase. After all users arrive, they begin sending requests according to their generated schedules.

For example, with 50,000 users and a 30-minute ramp:
- ~27.8 users per second join the simulation
- All requests are paused until the ramp completes
- After ramp, requests begin according to each user's schedule

**Extensible Design**
The workload generation system is modular, supporting different distribution strategies through the `WorkloadTrendStrategy` interface. Currently, the uniform distribution strategy ensures consistent workload throughout the experiment. Future strategies could implement peak hours, circadian patterns, or other realistic trends.

## Workload Timing (Legacy)

## Offline Maven notes

The repository includes a local Maven repository under `local-repo`. Do not use
`dependency-reduced-pom.xml` as the project build file; use `pom.xml`. The
`-o` option prevents Maven from contacting Maven Central.
