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

- baseUrl or BASE_URL (default: http://localhost:8080)
- endpointPath or ENDPOINT_PATH (default: /)

Example:

```
mvn gatling:test -DbaseUrl=http://localhost:8080 -DendpointPath=/v1/chat/completions
```

## Notes

The initial simulation sends a simple GET request. Update the scenario to POST payloads when you are ready to test LLM inference requests.
