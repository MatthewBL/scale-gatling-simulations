# Gatling LLM Simulations

This project runs a simple Gatling simulation against a local URL (via your SSH tunnel).

## Quick start

1. Ensure your SSH tunnel is up and the target is reachable locally.
2. Run the default simulation:

```
mvn gatling:test
```

## Configuration

You can override the defaults with system properties or environment variables:

- LLM_URL (default: http://localhost:8080)
- ENDPOINT_PATH (default: /)
- SSH_TUNNELS (optional comma-separated list of base URLs; if set, users are evenly distributed across them)

Example:

```
mvn gatling:test -DLLM_URL=http://localhost:8080 -DENDPOINT_PATH=/v1/chat/completions
```

## Notes

The initial simulation sends a simple GET request. Update the scenario to POST payloads when you are ready to test LLM inference requests.
