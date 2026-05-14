package simulations;

import com.typesafe.scalalogging.Logger;
import com.typesafe.scalalogging.Logger$;
import io.gatling.commons.util.Clock;
import io.gatling.core.action.Action;
import io.gatling.core.stats.StatsEngine;
import io.gatling.core.structure.ScenarioContext;
import io.gatling.http.check.HttpCheck;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import scala.Option;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class BasicLLMUsersSimulation extends Simulation {
  private final SimulationConfig config = SimulationConfig.load();
  private final Random random = new Random();
  private final List<String> baseUrls = config.effectiveBaseUrls();

  private final HttpProtocolBuilder httpProtocol = http
    .acceptHeader("application/json")
    .contentTypeHeader("application/json");

  private final String requestBody = String.format(
    "{\"model\":\"%s\",\"prompt\":\"%s\",\"min_tokens\":%d,\"max_tokens\":%d}",
    escapeJson(config.getModelId()),
    escapeJson(config.getPrompt()),
    config.getMinOutputLength(),
    config.getMaxOutputLength()
  );

  private final Object responseWriteLock = new Object();
  private final Object requestWriteLock = new Object();
  private final Object unitsWriteLock = new Object();
  private final String resultsDir = resolveResultsDir();
  private final Path responseOutputPath = ensureParent(Paths.get(resolveResponsesFile(config.getResponsesFile())));
  private final Path requestOutputPath = ensureParent(Paths.get(resolveRequestsFile(config.getRequestsFile())));
  private final Path unitsSummaryOutputPath = ensureParent(Paths.get(resolveUnitsSummaryFile(config.getUnitsSummaryFile())));

  private final List<CheckBuilder> responseChecks = config.isCaptureResponses()
    ? Arrays.asList(status().in(200, 201, 202, 204), bodyString().saveAs("responseBody"))
    : Collections.singletonList(status().in(200, 201, 202, 204));

  private final Iterator<Map<String, Object>> userFeeder = config.userAssignments().stream()
    .map(assignment -> {
      String baseUrl = baseUrls.get(assignment.getIndex() % baseUrls.size());
      String requestUrl = joinUrl(baseUrl, config.getEndpointPath());
      Map<String, Object> record = new HashMap<>();
      record.put("userIndex", assignment.getIndex());
      record.put("baseUrl", baseUrl);
      record.put("requestUrl", requestUrl);
      record.put("userType", assignment.getUserType());
      record.put("usageType", assignment.getUsageType());
      record.put("hourlyRequests", assignment.getHourlyRequests());
      record.put("remainingUnits", assignment.getInitialUnits());
      record.put("initialUnits", assignment.getInitialUnits());
      return record;
    })
    .iterator();

  private final ChainBuilder requestChain =
    pause(session -> Duration.ofMillis(Math.max(0, Math.round(nextIrregularPauseSeconds(session.getDouble("hourlyRequests")) * 1000.0))))
      .exec(
        doIfOrElse(session -> session.getInt("remainingUnits") > 0)
          .then(
            exec(session -> config.isCaptureRequests() ? appendRequest(session) : session)
              .exec(
                http("llm-request")
                  .post("#{requestUrl}")
                  .body(StringBody(requestBody))
                  .check(responseChecks)
              )
              .exec(session -> {
                int remaining = session.getInt("remainingUnits") - config.getUnitsPerRequest();
                Session updated = session.set("remainingUnits", remaining);
                return config.isCaptureResponses() ? appendResponse(updated) : updated;
              })
          )
          .orElse(exec(new InsufficientUnitsActionBuilder("llm-request-insufficient-units")))
      );

  private final ChainBuilder workloadChain =
    during(Duration.ofMinutes(config.getSimulationMinutes())).on(requestChain);

  private final ScenarioBuilder scn = scenario("Basic LLM Users")
    .feed(userFeeder)
    .exec(workloadChain)
    .exec(this::appendUnitsSummary);

  public BasicLLMUsersSimulation() {
    setUp(
      scn.injectOpen(atOnceUsers(config.getTotalUsers()))
    ).protocols(httpProtocol);
  }

  private double nextIrregularPauseSeconds(double hourlyRequests) {
    if (hourlyRequests <= 0.0) {
      return 3600.0;
    }
    double averageSeconds = 3600.0 / hourlyRequests;
    double sample = -Math.log(1.0 - random.nextDouble());
    return Math.max(0.0, averageSeconds * sample);
  }

  private static String escapeJson(String value) {
    return value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t");
  }

  private static String joinUrl(String baseUrl, String endpointPath) {
    String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String trimmedPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
    return trimmedBase + trimmedPath;
  }

  private static final String FALLBACK_RUN_ID = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
    .format(LocalDateTime.now());

  private String resolveResponsesFile(String configuredPath) {
    if (!SimulationConfig.DefaultResponsesFile.equals(configuredPath)) {
      return configuredPath;
    }
    return Paths.get(resultsDir, "response-bodies.jsonl").toString();
  }

  private String resolveRequestsFile(String configuredPath) {
    if (!SimulationConfig.DefaultRequestsFile.equals(configuredPath)) {
      return configuredPath;
    }
    return Paths.get(resultsDir, "request-bodies.jsonl").toString();
  }

  private String resolveUnitsSummaryFile(String configuredPath) {
    if (!SimulationConfig.DefaultUnitsSummaryFile.equals(configuredPath)) {
      return configuredPath;
    }
    return Paths.get(resultsDir, "units-summary.jsonl").toString();
  }

  private String resolveResultsDir() {
    String baseResultsDir = Optional.ofNullable(System.getProperty("gatling.resultsFolder"))
      .orElseGet(() -> Optional.ofNullable(System.getProperty("gatling.resultsDirectory"))
        .orElseGet(() -> Optional.ofNullable(System.getProperty("gatling.resultsDir")).orElse("target/gatling")));

    String simulationId = Optional.ofNullable(System.getProperty("gatling.simulationId"))
      .orElseGet(() -> Optional.ofNullable(System.getProperty("gatling.simulationClass"))
        .map(value -> {
          String[] parts = value.split("\\.");
          return parts[parts.length - 1];
        })
        .orElse(defaultSimulationId()))
      .toLowerCase();

    Path basePath = Paths.get(baseResultsDir);
    String baseName = basePath.getFileName() != null ? basePath.getFileName().toString() : "";
    String prefix = simulationId + "-";
    if (baseName.startsWith(prefix)) {
      return baseResultsDir;
    }

    String runId = Optional.ofNullable(System.getProperty("gatling.runId"))
      .orElseGet(() -> Optional.ofNullable(System.getenv("GATLING_RUN_ID"))
        .orElseGet(() -> findLatestRunId(basePath, simulationId).orElse(FALLBACK_RUN_ID)));

    String runFolder = simulationId + "-" + runId;
    return Paths.get(baseResultsDir, runFolder).toString();
  }

  private Optional<String> findLatestRunId(Path basePath, String simulationId) {
    if (!Files.isDirectory(basePath)) {
      return Optional.empty();
    }
    String prefix = simulationId + "-";
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
      Path newest = null;
      long newestTime = Long.MIN_VALUE;
      for (Path path : entries) {
        if (!Files.isDirectory(path)) {
          continue;
        }
        String name = path.getFileName().toString();
        if (!name.startsWith(prefix)) {
          continue;
        }
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        if (lastModified > newestTime) {
          newestTime = lastModified;
          newest = path;
        }
      }
      if (newest == null) {
        return Optional.empty();
      }
      String name = newest.getFileName().toString();
      return Optional.of(name.substring(prefix.length()));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private String defaultSimulationId() {
    String name = getClass().getSimpleName();
    if (name.endsWith("$")) {
      name = name.substring(0, name.length() - 1);
    }
    return name.toLowerCase();
  }

  private Path ensureParent(Path path) {
    Path parent = path.getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to create output directory: " + parent, e);
      }
    }
    return path;
  }

  private void appendLine(Path path, Object lock, String line) {
    synchronized (lock) {
      try {
        Files.writeString(
          path,
          line + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        );
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to write output to " + path, e);
      }
    }
  }

  private Session appendResponse(Session session) {
    if (!session.contains("responseBody")) {
      return session;
    }
    String value = session.getString("responseBody");
    int userIndex = session.getInt("userIndex");
    String userType = session.getString("userType");
    String line = String.format(
      "{\"userIndex\":%d,\"userType\":\"%s\",\"body\":\"%s\"}",
      userIndex,
      escapeJson(userType),
      escapeJson(value)
    );
    appendLine(responseOutputPath, responseWriteLock, line);
    return session.remove("responseBody");
  }

  private Session appendRequest(Session session) {
    int userIndex = session.getInt("userIndex");
    String userType = session.getString("userType");
    String usageType = session.getString("usageType");
    String line = String.format(
      "{\"userIndex\":%d,\"userType\":\"%s\",\"usageType\":\"%s\",\"endpointPath\":\"%s\",\"body\":\"%s\"}",
      userIndex,
      escapeJson(userType),
      escapeJson(usageType),
      escapeJson(config.getEndpointPath()),
      escapeJson(requestBody)
    );
    appendLine(requestOutputPath, requestWriteLock, line);
    return session;
  }

  private Session appendUnitsSummary(Session session) {
    int userIndex = session.getInt("userIndex");
    String userType = session.getString("userType");
    String usageType = session.getString("usageType");
    int initialUnits = session.getInt("initialUnits");
    int remainingUnits = session.getInt("remainingUnits");
    int consumedUnits = Math.max(0, initialUnits - remainingUnits);
    String line = String.format(
      "{\"userIndex\":%d,\"userType\":\"%s\",\"usageType\":\"%s\",\"initialUnits\":%d,\"remainingUnits\":%d,\"consumedUnits\":%d}",
      userIndex,
      escapeJson(userType),
      escapeJson(usageType),
      initialUnits,
      remainingUnits,
      consumedUnits
    );
    appendLine(unitsSummaryOutputPath, unitsWriteLock, line);
    return session;
  }

  private static final class InsufficientUnitsAction implements Action {
    private final String requestName;
    private final StatsEngine statsEngine;
    private final Clock clock;
    private final Action next;
    private Logger logger;

    private InsufficientUnitsAction(
      String requestName,
      StatsEngine statsEngine,
      Clock clock,
      Action next
    ) {
      this.requestName = requestName;
      this.statsEngine = statsEngine;
      this.clock = clock;
      this.next = next;
    }

    @Override
    public String name() {
      return "insufficient-units";
    }

    @Override
    public void execute(io.gatling.core.session.Session session) {
      long now = clock.nowMillis();
      statsEngine.logResponse(
        session.scenario(),
        session.groups(),
        requestName,
        now,
        now,
        io.gatling.commons.stats.KO$.MODULE$,
        Option.apply(null),
        Option.apply("insufficient-units")
      );
      next.$bang(session);
    }

    @Override
    public Logger logger() {
      if (logger == null) {
        logger = Logger$.MODULE$.apply(LoggerFactory.getLogger(getClass()));
      }
      return logger;
    }

    public void com$typesafe$scalalogging$StrictLogging$_setter_$logger_$eq(Logger logger) {
      this.logger = logger;
    }
  }

  private static final class InsufficientUnitsActionBuilder implements ActionBuilder {
    private final String requestName;

    private InsufficientUnitsActionBuilder(String requestName) {
      this.requestName = requestName;
    }

    @Override
    public io.gatling.core.action.builder.ActionBuilder asScala() {
      return new io.gatling.core.action.builder.ActionBuilder() {
        @Override
        public Action build(ScenarioContext ctx, Action next) {
          return new InsufficientUnitsAction(
            requestName,
            ctx.coreComponents().statsEngine(),
            ctx.coreComponents().clock(),
            next
          );
        }
      };
    }
  }
}
