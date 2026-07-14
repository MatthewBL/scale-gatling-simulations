package simulations;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/** Capacity test: one virtual user is one request, so users/s equals requests/s. */
public class StepRateLLMSimulation extends Simulation {
  private static String value(String key, String fallback) {
    String property = System.getProperty(key);
    if (property != null && !property.isBlank()) return property;
    String environment = System.getenv(key);
    return environment == null || environment.isBlank() ? fallback : environment;
  }

  private static int intValue(String key, int fallback) {
    try {
      return Integer.parseInt(value(key, Integer.toString(fallback)).trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static double doubleValue(String key, double fallback) {
    try {
      return Double.parseDouble(value(key, Double.toString(fallback)).trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String escapeJson(String input) {
    return input.replace("\\", "\\\\").replace("\"", "\\\"")
      .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  private static String joinUrl(String base, String path) {
    String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    String normalizedPath = path.startsWith("/") ? path : "/" + path;
    return normalizedBase + normalizedPath;
  }

  private final String baseUrl = value("LLM_URL", "http://localhost:11434");
  private final String endpoint = value("ENDPOINT_PATH", "/v1/completions");
  private final String model = value("MODEL_ID", "gemma4:31b");
  private final int initialRate = intValue("INITIAL_RATE", 1);
  private final int rateIncrement = intValue("RATE_INCREMENT", 1);
  private final int levels = intValue("RATE_LEVELS", 5);
  private final int levelSeconds = intValue("LEVEL_DURATION_SECONDS", 60);
  private final int rampSeconds = intValue("RAMP_DURATION_SECONDS", 0);
  private final int maxTokens = intValue("MAX_OUTPUT_LENGTH", 256);
  private final int p95ThresholdMs = intValue("P95_THRESHOLD_MS", 30000);
  private final double maxKoPercent = doubleValue("MAX_KO_PERCENT", 0.0);

  private final String body = String.format(
    "{\"model\":\"%s\",\"prompt\":\"%s\",\"max_tokens\":%d,\"stream\":false}",
    escapeJson(model), escapeJson(value("LLM_PROMPT", "Hello")), maxTokens
  );

  private final HttpProtocolBuilder protocol = http
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .disableWarmUp();

  private final ScenarioBuilder scenario = scenario("LLM step-rate capacity")
    .exec(
      http("llm-request")
        .post(joinUrl(baseUrl, endpoint))
        .body(StringBody(body))
        .check(status().is(200))
    );

  public StepRateLLMSimulation() {
    if (initialRate < 0 || rateIncrement <= 0 || levels <= 0 || levelSeconds <= 0 || rampSeconds < 0) {
      throw new IllegalArgumentException(
        "INITIAL_RATE >= 0, RATE_INCREMENT/RATE_LEVELS/LEVEL_DURATION_SECONDS > 0 and RAMP_DURATION_SECONDS >= 0 are required"
      );
    }

    List<OpenInjectionStep> steps = new ArrayList<>();
    if (levels == 1) {
      steps.add(constantUsersPerSec(initialRate).during(Duration.ofSeconds(levelSeconds)));
    } else {
      OpenInjectionStep.Stairs.Composite staircase = incrementUsersPerSec(rateIncrement)
        .times(levels)
        .eachLevelLasting(Duration.ofSeconds(levelSeconds))
        .startingFrom(initialRate);
      if (rampSeconds > 0) {
        staircase = staircase.separatedByRampsLasting(Duration.ofSeconds(rampSeconds));
      }
      steps.add(staircase);
    }

    setUp(scenario.injectOpen(steps)).protocols(protocol).assertions(
      global().responseTime().percentile(95.0).lt(p95ThresholdMs),
      global().failedRequests().percent().lte(maxKoPercent)
    );
  }
}
