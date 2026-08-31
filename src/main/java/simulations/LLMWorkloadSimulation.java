package simulations;

import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.core.structure.ScenarioContext;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import com.typesafe.scalalogging.Logger;
import com.typesafe.scalalogging.Logger$;
import io.gatling.commons.util.Clock;
import io.gatling.core.action.Action;
import io.gatling.core.stats.StatsEngine;
import org.slf4j.LoggerFactory;
import scala.Option;
import simulations.schedule.UniformWorkloadTrendStrategy;
import simulations.schedule.UserWorkloadSchedule;
import simulations.schedule.WorkloadScheduleGenerator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/** Capacity test: one virtual user is one request, so users/s equals requests/s. */
public class LLMWorkloadSimulation extends Simulation {
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

  private static String resolveModelId(String baseUrl, String modelsEndpoint) {
    String modelsUrl = joinUrl(baseUrl, modelsEndpoint);
    HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(modelsUrl))
      .timeout(Duration.ofSeconds(10))
      .GET()
      .build();

    final HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to fetch model id from " + modelsUrl + ".", ex);
    }

    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
        "Failed to fetch model id from " + modelsUrl + " (status " + response.statusCode() + ")."
      );
    }

    Matcher matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
    if (matcher.find()) {
      return matcher.group(1);
    }
    throw new IllegalStateException("Could not parse model id from " + modelsUrl + " response.");
  }

  private final SimulationConfig config = SimulationConfig.load();
  private final List<String> baseUrls = config.effectiveBaseUrls();
  private final String endpoint = config.getEndpointPath();
  private final String model = config.getModelId();
  private final int unitsPerRequest = intValue("UNITS_PER_REQUEST", config.getUnitsPerRequest());
  
  // Generate workload schedules before simulation starts
  private final Map<Integer, UserWorkloadSchedule> workloadSchedules = generateWorkloadSchedules();

  private Map<Integer, UserWorkloadSchedule> generateWorkloadSchedules() {
    WorkloadScheduleGenerator generator = new WorkloadScheduleGenerator(
      new Random(42), // Use fixed seed for reproducibility
      new UniformWorkloadTrendStrategy(new Random(42)),
      config.getLooseness(),
      config.getSimulationMinutes()
    );
    
    List<UserWorkloadSchedule> schedules = generator.generateSchedules(config.userAssignments());
    Map<Integer, UserWorkloadSchedule> scheduleMap = new HashMap<>(schedules.size());
    for (UserWorkloadSchedule schedule : schedules) {
      scheduleMap.put(schedule.getUserIndex(), schedule);
    }
    return scheduleMap;
  }

  private final String body = String.format(
    "{\"model\":\"%s\",\"prompt\":\"%s\",\"min_tokens\":%d,\"max_tokens\":%d}",
    escapeJson(model), escapeJson(config.getPrompt()), config.getMinOutputLength(), config.getMaxOutputLength()
  );

  private final Iterator<Map<String, Object>> userFeeder = config.userAssignments().stream()
    .map(assignment -> {
      String selectedBaseUrl = baseUrls.get(assignment.getIndex() % baseUrls.size());
      UserWorkloadSchedule schedule = workloadSchedules.get(assignment.getIndex());
      Map<String, Object> record = new HashMap<>();
      record.put("userIndex", assignment.getIndex());
      record.put("requestUrl", joinUrl(selectedBaseUrl, endpoint));
      record.put("targetRequests", schedule.getTargetRequests());
      record.put("requestIndex", 0);
      record.put("remainingUnits", assignment.getInitialUnits());
      record.put("workloadSchedule", schedule);
      return record;
    }).iterator();
  private final List<CheckBuilder> checks = Arrays.asList(status().in(200, 201, 202, 204));

  private final HttpProtocolBuilder protocol = http
    .acceptHeader("application/json")
    .contentTypeHeader("application/json");

  private final ChainBuilder requestAttempt =
    doIfOrElse(session -> session.getInt("remainingUnits") >= unitsPerRequest)
      .then(exec(http("llm-request").post("#{requestUrl}").body(StringBody(body)).check(checks))
        .exec(session -> session.set("remainingUnits", session.getInt("remainingUnits") - unitsPerRequest)))
      .orElse(exec(new InsufficientUnitsActionBuilder("llm-request-insufficient-units")).exitHere());

  private final ScenarioBuilder scenario = scenario("LLM step-rate capacity")
    .feed(userFeeder)
    .exec(rendezVous(config.getTotalUsers()))
    .pause(session -> {
      UserWorkloadSchedule schedule = (UserWorkloadSchedule) session.get("workloadSchedule");
      return Duration.ofMillis(schedule.getFirstRequestDelayMs());
    })
    .repeat("#{targetRequests}").on(
      doIf(session -> session.getInt("targetRequests") > 0)
        .then(
          doIf(session -> session.getInt("requestIndex") > 0)
            .then(pause(session -> {
              UserWorkloadSchedule schedule = (UserWorkloadSchedule) session.get("workloadSchedule");
              int requestIndex = session.getInt("requestIndex");
              return Duration.ofMillis(schedule.getRequestIntervalMs(requestIndex - 1));
            }))
          .exec(requestAttempt)
          .exec(session -> session.set("requestIndex", session.getInt("requestIndex") + 1))
        )
    );

  public LLMWorkloadSimulation() {
    if (config.getSimulationMinutes() <= 0 || config.getTotalUsers() < 0 || unitsPerRequest <= 0
      || config.getUserRampMinutes() <= 0 || config.getLooseness() < 0) {
      throw new IllegalArgumentException("SIMULATION_MINUTES, TOTAL_USERS, USER_RAMP_MINUTES, and LOOSENESS must be non-negative");
    }
    setUp(scenario.injectOpen(
      rampUsers(config.getTotalUsers()).during(Duration.ofMinutes(config.getUserRampMinutes()))
    )).protocols(protocol);
  }

  private static final class InsufficientUnitsAction implements Action {
    private final String requestName;
    private final StatsEngine statsEngine;
    private final Clock clock;
    private final Action next;
    private Logger logger;

    private InsufficientUnitsAction(String requestName, StatsEngine statsEngine, Clock clock, Action next) {
      this.requestName = requestName; this.statsEngine = statsEngine; this.clock = clock; this.next = next;
    }

    @Override public String name() { return "insufficient-units"; }

    @Override public void execute(io.gatling.core.session.Session session) {
      long now = clock.nowMillis();
      statsEngine.logResponse(session.scenario(), session.groups(), requestName, now, now,
        io.gatling.commons.stats.KO$.MODULE$, Option.apply(null), Option.apply("insufficient-units"));
      next.$bang(session);
    }

    @Override public Logger logger() {
      if (logger == null) logger = Logger$.MODULE$.apply(LoggerFactory.getLogger(getClass()));
      return logger;
    }

    public void com$typesafe$scalalogging$StrictLogging$_setter_$logger_$eq(Logger logger) { this.logger = logger; }
  }

  private static final class InsufficientUnitsActionBuilder implements ActionBuilder {
    private final String requestName;

    private InsufficientUnitsActionBuilder(String requestName) { this.requestName = requestName; }

    @Override public io.gatling.core.action.builder.ActionBuilder asScala() {
      return new io.gatling.core.action.builder.ActionBuilder() {
        @Override public Action build(ScenarioContext ctx, Action next) {
          return new InsufficientUnitsAction(requestName, ctx.coreComponents().statsEngine(), ctx.coreComponents().clock(), next);
        }
      };
    }
  }
}
