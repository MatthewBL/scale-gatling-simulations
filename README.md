# Gatling LLM Simulations

This project contains the original quota/user simulation and a step-rate capacity
simulation for OpenAI-compatible LLM servers (local llama/Ollama and vLLM).

## Quick start

1. Ensure your SSH tunnel is up and the target is reachable locally.
2. Run the step-rate experiment on Linux/macOS/WSL:

```bash
LLM_URL=http://localhost:11434 ./run-step-rate.sh
```

`run-step-rate.sh` sets a 4 GiB Java heap and attempts to raise `ulimit -n` to
65535 before starting Gatling. Make it executable once with `chmod +x run-step-rate.sh`.

To submit the same experiment as a background SLURM job on Rocky Linux:

```bash
chmod u+x run-step-rate.sh submit_step_rate.sbatch submit_step_rate.sh
sbatch submit_step_rate.sbatch
```

The batch file defaults to node `c06`. Override it for a single submission
with `sbatch --nodelist=c07 submit_step_rate.sbatch`, or use the wrapper when
you want to select the node through `TARGET_NODE` or an argument.

The node can also be provided as an argument:

```bash
./submit_step_rate.sh gpu07
```

The argument takes precedence over `TARGET_NODE`. The wrapper passes the value
to SLURM as `--nodelist`; a variable inside the `.sbatch` file cannot be used
for this because SLURM parses `#SBATCH` directives before running the script.

SLURM writes output to `gatling-step-rate-<job-id>.out` and errors to
`gatling-step-rate-<job-id>.err`. The batch script loads Java 11 when the
cluster provides an `openjdk/11` module, then uses the bundled offline Maven
repository through `run-step-rate.sh`.

## Configuration

You can override the defaults with system properties or environment variables:

- LLM_URL (default: http://localhost:11434)
- ENDPOINT_PATH (default: /v1/completions)
- MODELS_ENDPOINT (default: /v1/models; the first model `id` returned by this endpoint is used)
- USER_RAMP_MINUTES (default: 30; users send no requests during the ramp)
- FIRST_REQUEST_BATCH_SIZE (default: 500)
- FIRST_REQUEST_TURN_INTERVAL_SECONDS (default: 2)
- SSH_TUNNELS (optional comma-separated list of base URLs; if set, users are evenly distributed across them)

Example:

```
./mvnw gatling:test -Dgatling.simulationClass=simulations.BasicLLMUsersSimulation
```

## Step-rate capacity experiment

`StepRateLLMSimulation` first ramps users in over `USER_RAMP_MINUTES`, waits
until all users arrive, and then starts the measured workload. Each user's
first request is staggered by batch and turn interval. The measured workload
lasts `SIMULATION_MINUTES`; each successful request consumes `UNITS_PER_REQUEST`.
The first request after quota exhaustion is recorded as a Gatling failed
request with the message `insufficient-units`, and that user stops.

`run-step-rate.sh` runs three provisioning cases in succession. Configure
their basic/standard/pro units per minute in `.env` as comma-separated values:

```dotenv
UNDER_PROVISIONING_TEST=1,1,1
OVER_PROVISIONING_TEST=10000,10000,10000
FINE_TUNED_TEST=100,100,100
```

The launcher validates these values and passes them as JVM properties for each
run, so the provisioning values are not read by the simulation as environment
configuration.

With the current 10 low-usage and 50 high-usage requests per hour over 60
minutes, fine-tuned provisioning gives basic users 18 requests' worth of
units, while standard and pro users receive at least 50 requests' worth.

Other workload settings can be configured with:

```bash
SIMULATION_MINUTES=60 TOTAL_USERS=50000 UNITS_PER_REQUEST=10 ./run-step-rate.sh
```

To point the identical experiment back at vLLM on the HPC (directly or through
an SSH tunnel), only change the target URL:

```bash
LLM_URL=http://localhost:8000 ENDPOINT_PATH=/v1/completions \
MODELS_ENDPOINT=/v1/models ./run-step-rate.sh
```

Both backends must expose an OpenAI-compatible completions endpoint. The request
uses the portable fields `model`, `prompt`, `max_tokens`, and `stream=false`.

## Notes

The step-rate simulation sends OpenAI-compatible POST completion requests.
