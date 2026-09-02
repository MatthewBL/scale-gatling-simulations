package simulations;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SimulationConfig {
  public static final String DefaultResponsesFile = "target/gatling/response-bodies.jsonl";
  public static final String DefaultRequestsFile = "target/gatling/request-bodies.jsonl";
  public static final String DefaultUnitsSummaryFile = "target/gatling/units-summary.jsonl";

  private static final Dotenv DOTENV;

  static {
    Optional<String> dotenvDirectory = findDotenvDirectory();
    DotenvBuilder builder = Dotenv.configure().ignoreIfMissing();
    if (dotenvDirectory.isPresent()) {
      builder = builder.directory(dotenvDirectory.get());
    }
    DOTENV = builder.load();
  }

  private final String baseUrl;
  private final List<String> tunnelUrls;
  private final String endpointPath;
  private final String modelId;
  private final boolean captureResponses;
  private final String responsesFile;
  private final boolean captureRequests;
  private final String requestsFile;
  private final String unitsSummaryFile;
  private final int simulationMinutes;
  private final int totalUsers;
  private final double lowUsageHourlyRequests;
  private final double highUsageHourlyRequests;
  private final double highUsageUserShare;
  private final double basicShare;
  private final double standardShare;
  private final double proShare;
  private final int basicUnitsPerMinute;
  private final int standardUnitsPerMinute;
  private final int proUnitsPerMinute;
  private final int unitsPerRequest;
  private final int maxAccumulatedRequests;
  private final String prompt;
  private final int minOutputLength;
  private final int maxOutputLength;
  private final int userRampMinutes;
  private final int firstRequestBatchSize;
  private final int firstRequestTurnIntervalSeconds;
  private final int looseness;

  public SimulationConfig(
    String baseUrl,
    List<String> tunnelUrls,
    String endpointPath,
    String modelId,
    boolean captureResponses,
    String responsesFile,
    boolean captureRequests,
    String requestsFile,
    String unitsSummaryFile,
    int simulationMinutes,
    int totalUsers,
    double lowUsageHourlyRequests,
    double highUsageHourlyRequests,
    double highUsageUserShare,
    double basicShare,
    double standardShare,
    double proShare,
    int basicUnitsPerMinute,
    int standardUnitsPerMinute,
    int proUnitsPerMinute,
    int unitsPerRequest,
    int maxAccumulatedRequests,
    String prompt,
    int minOutputLength,
    int maxOutputLength,
    int userRampMinutes,
    int firstRequestBatchSize,
    int firstRequestTurnIntervalSeconds,
    int looseness
  ) {
    this.baseUrl = baseUrl;
    this.tunnelUrls = Collections.unmodifiableList(new ArrayList<>(tunnelUrls));
    this.endpointPath = endpointPath;
    this.modelId = modelId;
    this.captureResponses = captureResponses;
    this.responsesFile = responsesFile;
    this.captureRequests = captureRequests;
    this.requestsFile = requestsFile;
    this.unitsSummaryFile = unitsSummaryFile;
    this.simulationMinutes = simulationMinutes;
    this.totalUsers = totalUsers;
    this.lowUsageHourlyRequests = lowUsageHourlyRequests;
    this.highUsageHourlyRequests = highUsageHourlyRequests;
    this.highUsageUserShare = highUsageUserShare;
    this.basicShare = basicShare;
    this.standardShare = standardShare;
    this.proShare = proShare;
    this.basicUnitsPerMinute = basicUnitsPerMinute;
    this.standardUnitsPerMinute = standardUnitsPerMinute;
    this.proUnitsPerMinute = proUnitsPerMinute;
    this.unitsPerRequest = unitsPerRequest;
    this.maxAccumulatedRequests = maxAccumulatedRequests;
    this.prompt = prompt;
    this.minOutputLength = minOutputLength;
    this.maxOutputLength = maxOutputLength;
    this.userRampMinutes = userRampMinutes;
    this.firstRequestBatchSize = firstRequestBatchSize;
    this.firstRequestTurnIntervalSeconds = firstRequestTurnIntervalSeconds;
    this.looseness = looseness;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public List<String> getTunnelUrls() {
    return tunnelUrls;
  }

  public String getEndpointPath() {
    return endpointPath;
  }

  public String getModelId() {
    return modelId;
  }

  public boolean isCaptureResponses() {
    return captureResponses;
  }

  public String getResponsesFile() {
    return responsesFile;
  }

  public boolean isCaptureRequests() {
    return captureRequests;
  }

  public String getRequestsFile() {
    return requestsFile;
  }

  public String getUnitsSummaryFile() {
    return unitsSummaryFile;
  }

  public int getSimulationMinutes() {
    return simulationMinutes;
  }

  public int getTotalUsers() {
    return totalUsers;
  }

  public double getLowUsageHourlyRequests() {
    return lowUsageHourlyRequests;
  }

  public double getHighUsageHourlyRequests() {
    return highUsageHourlyRequests;
  }

  public double getHighUsageUserShare() {
    return highUsageUserShare;
  }

  public double getBasicShare() {
    return basicShare;
  }

  public double getStandardShare() {
    return standardShare;
  }

  public double getProShare() {
    return proShare;
  }

  public int getBasicUnitsPerMinute() {
    return basicUnitsPerMinute;
  }

  public int getStandardUnitsPerMinute() {
    return standardUnitsPerMinute;
  }

  public int getProUnitsPerMinute() {
    return proUnitsPerMinute;
  }

  public int getUnitsPerRequest() {
    return unitsPerRequest;
  }

  public int getMaxAccumulatedRequests() {
    return maxAccumulatedRequests;
  }

  public String getPrompt() {
    return prompt;
  }

  public int getMinOutputLength() {
    return minOutputLength;
  }

  public int getMaxOutputLength() {
    return maxOutputLength;
  }

  public int getUserRampMinutes() {
    return userRampMinutes;
  }

  public int getFirstRequestBatchSize() {
    return firstRequestBatchSize;
  }

  public int getFirstRequestTurnIntervalSeconds() {
    return firstRequestTurnIntervalSeconds;
  }

  public int getLooseness() {
    return looseness;
  }

  public List<String> effectiveBaseUrls() {
    return tunnelUrls.isEmpty() ? List.of(baseUrl) : tunnelUrls;
  }

  public List<UserAssignment> userAssignments() {
    int total = Math.max(0, totalUsers);
    int basicCount = Math.min(total, (int) Math.floor(total * basicShare));
    int standardCount = Math.min(total - basicCount, (int) Math.floor(total * standardShare));
    int proCount = Math.max(0, total - basicCount - standardCount);
    int basicUnits = Math.max(0, basicUnitsPerMinute);
    int standardUnits = Math.max(0, standardUnitsPerMinute);
    int proUnits = Math.max(0, proUnitsPerMinute);
    double clampedHighShare = clamp(highUsageUserShare, 0.0, 1.0);
    double lowHourly = Math.max(0.0, lowUsageHourlyRequests);
    double highHourly = Math.max(0.0, highUsageHourlyRequests);

    List<UserAssignment> assignments = new ArrayList<>(total);
    appendAssignments(assignments, 0, basicCount, "basic", basicUnits, clampedHighShare, lowHourly, highHourly);
    appendAssignments(assignments, basicCount, standardCount, "standard", standardUnits, clampedHighShare, lowHourly, highHourly);
    appendAssignments(assignments, basicCount + standardCount, proCount, "pro", proUnits, clampedHighShare, lowHourly, highHourly);
    return assignments;
  }

  private void appendAssignments(
    List<UserAssignment> target,
    int startIndex,
    int count,
    String userType,
    int totalUnits,
    double highShare,
    double lowHourly,
    double highHourly
  ) {
    if (count <= 0) {
      return;
    }
    int highCount = (int) Math.round(count * highShare);
    for (int offset = 0; offset < count; offset += 1) {
      boolean isHighUsage = offset < highCount;
      String usageType = isHighUsage ? "high" : "low";
      double hourlyRequests = isHighUsage ? highHourly : lowHourly;
      target.add(new UserAssignment(startIndex + offset, userType, usageType, hourlyRequests, totalUnits));
    }
  }

  private static double clamp(double value, double minValue, double maxValue) {
    return Math.max(minValue, Math.min(maxValue, value));
  }

  public static SimulationConfig load() {
    String rawBaseUrl = read("LLM_URL", "http://localhost:8080");
    String rawEndpointPath = read("ENDPOINT_PATH", "/");
    String rawModelsEndpoint = read("MODELS_ENDPOINT", "/v1/models");
    String baseUrl = normalizeBaseUrl(rawBaseUrl);

    List<String> rawTunnelUrls = readList("SSH_TUNNELS");
    if (rawTunnelUrls.isEmpty()) {
      rawTunnelUrls = readList("TUNNEL_URLS");
    }
    List<String> normalized = new ArrayList<>();
    for (String url : rawTunnelUrls) {
      normalized.add(normalizeBaseUrl(url));
    }
    List<String> tunnelUrls = normalized.stream().distinct().collect(Collectors.toList());

    String modelResolutionBaseUrl = tunnelUrls.isEmpty() ? baseUrl : tunnelUrls.get(0);
    String endpointPath = normalizeEndpointPath(rawEndpointPath);
    // An explicit model keeps the same simulation portable across OpenAI-compatible
    // servers (for example a local llama/Ollama instance and vLLM on the HPC).
    String configuredModelId = read("MODEL_ID", "").trim();
    String modelId = configuredModelId.isEmpty()
      ? resolveModelId(modelResolutionBaseUrl, rawModelsEndpoint)
      : configuredModelId;

    return new SimulationConfig(
      baseUrl,
      tunnelUrls,
      endpointPath,
      modelId,
      readBoolean("CAPTURE_RESPONSES", false),
      read("RESPONSES_FILE", DefaultResponsesFile),
      readBoolean("CAPTURE_REQUESTS", false),
      read("REQUESTS_FILE", DefaultRequestsFile),
      read("UNITS_SUMMARY_FILE", DefaultUnitsSummaryFile),
      readInt("SIMULATION_MINUTES", 10),
      readInt("TOTAL_USERS", 10),
      readDouble("LOW_USAGE_REQUESTS_PER_HOUR", 360.0),
      readDouble("HIGH_USAGE_REQUESTS_PER_HOUR", 360.0),
      readDouble("HIGH_USAGE_USER_SHARE", 0.5),
      readDouble("BASIC_SHARE", 0.7),
      readDouble("STANDARD_SHARE", 0.2),
      readDouble("PRO_SHARE", 0.1),
      readInt("BASIC_UNITS_PER_MINUTE", 10),
      readInt("STANDARD_UNITS_PER_MINUTE", 20),
      readInt("PRO_UNITS_PER_MINUTE", 40),
      readInt("UNITS_PER_REQUEST", 1),
      readInt("MAX_ACCUMULATED_REQUESTS", 1),
      read("LLM_PROMPT", "Hello"),
      readInt("MIN_OUTPUT_LENGTH", 0),
      readInt("MAX_OUTPUT_LENGTH", 256),
      readInt("USER_RAMP_MINUTES", 30),
      readInt("FIRST_REQUEST_BATCH_SIZE", 500),
      readInt("FIRST_REQUEST_TURN_INTERVAL_SECONDS", 2),
      readInt("LOOSENESS", 0)
    );
  }

  private static String read(String key, String defaultValue) {
    String value = System.getProperty(key);
    if (value == null) {
      value = DOTENV.get(key);
    }
    if (value == null) {
      value = System.getenv(key);
    }
    return value != null ? value : defaultValue;
  }

  private static int readInt(String key, int defaultValue) {
    try {
      return Integer.parseInt(read(key, Integer.toString(defaultValue)).trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static double readDouble(String key, double defaultValue) {
    try {
      return Double.parseDouble(read(key, Double.toString(defaultValue)).trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private static boolean readBoolean(String key, boolean defaultValue) {
    String value = read(key, Boolean.toString(defaultValue)).trim().toLowerCase();
    if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value)) {
      return true;
    }
    if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "n".equals(value)) {
      return false;
    }
    return defaultValue;
  }

  private static List<String> readList(String key) {
    String value = read(key, "");
    if (value.isBlank()) {
      return Collections.emptyList();
    }
    return Arrays.stream(value.split(","))
      .map(String::trim)
      .filter(entry -> !entry.isEmpty())
      .collect(Collectors.toList());
  }

  private static String normalizeBaseUrl(String value) {
    String trimmed = value.trim();
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return trimmed;
    }
    return "http://" + trimmed;
  }

  private static String normalizeEndpointPath(String value) {
    String trimmed = value.trim();
    return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  private static String resolveModelId(String baseUrl, String rawModelsEndpoint) {
    String modelsUrl = normalizeModelsUrl(baseUrl, rawModelsEndpoint);
    HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(modelsUrl))
      .timeout(Duration.ofSeconds(10))
      .GET()
      .build();

    HttpResponse<String> response;
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

    Pattern idPattern = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    Matcher matcher = idPattern.matcher(response.body());
    if (matcher.find()) {
      return matcher.group(1);
    }
    throw new IllegalStateException("Could not parse model id from " + modelsUrl + " response.");
  }

  private static String normalizeModelsUrl(String baseUrl, String rawModelsEndpoint) {
    String trimmed = rawModelsEndpoint.trim();
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return trimmed;
    }
    String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String normalizedPath = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    return normalizedBase + normalizedPath;
  }

  private static Optional<String> findDotenvDirectory() {
    Path start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    Path current = start;
    Optional<Path> firstEnv = Optional.empty();
    while (current != null) {
      Path candidate = current.resolve(".env");
      if (firstEnv.isEmpty() && Files.isRegularFile(candidate)) {
        firstEnv = Optional.of(current);
      }
      Path pom = current.resolve("pom.xml");
      if (Files.isRegularFile(pom)) {
        if (Files.isRegularFile(candidate)) {
          return Optional.of(current.toString());
        }
        return firstEnv.map(Path::toString);
      }
      current = current.getParent();
    }
    return firstEnv.map(Path::toString);
  }
}
