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

## Configuration

You can override the defaults with system properties or environment variables:

- LLM_URL (default: http://localhost:11434)
- ENDPOINT_PATH (default: /v1/completions)
- MODELS_ENDPOINT (default: /v1/models; the first model `id` returned by this endpoint is used)
- SSH_TUNNELS (optional comma-separated list of base URLs; if set, users are evenly distributed across them)

Example:

```
./mvnw gatling:test -Dgatling.simulationClass=simulations.BasicLLMUsersSimulation
```

## Step-rate capacity experiment

`StepRateLLMSimulation` sends one request per virtual user and checks explicitly
for HTTP 200. Its conservative defaults are 1 req/s initially, increments of
1 req/s, five levels and 60 seconds per level. Tune with:

```bash
INITIAL_RATE=1 RATE_INCREMENT=2 RATE_LEVELS=10 LEVEL_DURATION_SECONDS=60 \
P95_THRESHOLD_MS=30000 MAX_KO_PERCENT=0 ./run-step-rate.sh
```

Optional `RAMP_DURATION_SECONDS` inserts a ramp between stable levels. Leave it
at zero to get discrete plateaus. The last plateau before p95 crosses the chosen
threshold or KO responses appear is the capacity ceiling. Gatling assertions
also make the process exit unsuccessfully when the global p95/KO limits fail.

To point the identical experiment back at vLLM on the HPC (directly or through
an SSH tunnel), only change the target URL:

```bash
LLM_URL=http://localhost:8000 ENDPOINT_PATH=/v1/completions \
MODELS_ENDPOINT=/v1/models ./run-step-rate.sh
```

Both backends must expose an OpenAI-compatible completions endpoint. The request
uses the portable fields `model`, `prompt`, `max_tokens`, and `stream=false`.

## Notes

The initial simulation sends a simple GET request. Update the scenario to POST payloads when you are ready to test LLM inference requests.
