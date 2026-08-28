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
TOTAL_USERS=50000
FIRST_REQUEST_BATCH_SIZE=500
FIRST_REQUEST_TURN_INTERVAL_SECONDS=2
```

`LLM_URL` may be a host and port or a complete URL. The launcher normalizes a
bare host and port to `http://...`. The model is discovered automatically from
`MODELS_ENDPOINT`.

The provisioning values use this format:

```dotenv
UNDER_PROVISIONING_TEST=1,1,1
OVER_PROVISIONING_TEST=10000,10000,10000
FINE_TUNED_TEST=100,100,100
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

## Workload timing

Users arrive evenly during `USER_RAMP_MINUTES` and send no requests during
that phase. After all users arrive, first requests are released in batches:
`FIRST_REQUEST_BATCH_SIZE` users every
`FIRST_REQUEST_TURN_INTERVAL_SECONDS` seconds. Subsequent requests follow the
configured low/high profile rate. Only the measured workload lasts
`SIMULATION_MINUTES`; ramp time is not included in quota calculation.

For 50,000 users, a 30-minute ramp introduces approximately 27.8 users per
second. The generator must still have enough memory and file descriptors to
maintain all active users.

## Offline Maven notes

The repository includes a local Maven repository under `local-repo`. Do not use
`dependency-reduced-pom.xml` as the project build file; use `pom.xml`. The
`-o` option prevents Maven from contacting Maven Central.
