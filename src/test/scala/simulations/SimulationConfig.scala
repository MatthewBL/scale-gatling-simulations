package simulations

import io.github.cdimascio.dotenv.Dotenv
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.jdk.CollectionConverters._
import scala.util.Try

final case class SimulationConfig(
  baseUrl: String,
  endpointPath: String,
  modelId: String,
  captureResponses: Boolean,
  responsesFile: String,
  captureRequests: Boolean,
  requestsFile: String,
  unitsSummaryFile: String,
  simulationMinutes: Int,
  totalUsers: Int,
  lowUsageHourlyRequests: Double,
  highUsageHourlyRequests: Double,
  highUsageUserShare: Double,
  basicShare: Double,
  standardShare: Double,
  proShare: Double,
  basicUnitsPerMinute: Int,
  standardUnitsPerMinute: Int,
  proUnitsPerMinute: Int,
  unitsPerRequest: Int,
  prompt: String,
  minOutputLength: Int,
  maxOutputLength: Int
) {
  def userAssignments: Vector[UserAssignment] = {
    val total = math.max(0, totalUsers)
    val basicCount = math.min(total, math.floor(total * basicShare).toInt)
    val standardCount = math.min(total - basicCount, math.floor(total * standardShare).toInt)
    val proCount = math.max(0, total - basicCount - standardCount)
    val basicUnits = math.max(0, basicUnitsPerMinute) * math.max(0, simulationMinutes)
    val standardUnits = math.max(0, standardUnitsPerMinute) * math.max(0, simulationMinutes)
    val proUnits = math.max(0, proUnitsPerMinute) * math.max(0, simulationMinutes)
    val clampedHighShare = clamp(highUsageUserShare, 0.0, 1.0)
    val lowHourly = math.max(0.0, lowUsageHourlyRequests)
    val highHourly = math.max(0.0, highUsageHourlyRequests)

    def assignmentsForTier(
      startIndex: Int,
      count: Int,
      userType: String,
      totalUnits: Int
    ): Vector[UserAssignment] = {
      if (count <= 0) {
        Vector.empty
      } else {
        val highCount = math.round(count * clampedHighShare).toInt
        Vector.tabulate(count) { offset =>
          val isHighUsage = offset < highCount
          val usageType = if (isHighUsage) "high" else "low"
          val hourlyRequests = if (isHighUsage) highHourly else lowHourly
          UserAssignment(startIndex + offset, userType, usageType, hourlyRequests, totalUnits)
        }
      }
    }

    val basicAssignments = assignmentsForTier(0, basicCount, "basic", basicUnits)
    val standardAssignments = assignmentsForTier(basicCount, standardCount, "standard", standardUnits)
    val proAssignments = assignmentsForTier(basicCount + standardCount, proCount, "pro", proUnits)

    basicAssignments ++ standardAssignments ++ proAssignments
  }

  private def clamp(value: Double, minValue: Double, maxValue: Double): Double = {
    math.max(minValue, math.min(maxValue, value))
  }
}

final case class UserAssignment(
  index: Int,
  userType: String,
  usageType: String,
  hourlyRequests: Double,
  initialUnits: Int
)

object SimulationConfig {
  private val dotenvDirectory = findDotenvDirectory()
  private val dotenv = {
    val builder = Dotenv.configure().ignoreIfMissing()
    val configured = dotenvDirectory
      .map(directory => builder.directory(directory))
      .getOrElse(builder)
    configured.load()
  }
  private val dotenvEntries: Map[String, String] =
    dotenv.entries().asScala.map(entry => entry.getKey -> entry.getValue).toMap
  val DefaultResponsesFile: String = "target/gatling/response-bodies.jsonl"
  val DefaultRequestsFile: String = "target/gatling/request-bodies.jsonl"
  val DefaultUnitsSummaryFile: String = "target/gatling/units-summary.jsonl"

  def load(): SimulationConfig = {
    val rawBaseUrl = read("LLM_URL", "http://localhost:8080")
    val rawEndpointPath = read("ENDPOINT_PATH", "/")
    val rawModelsEndpoint = read("MODELS_ENDPOINT", "/v1/models")
    val baseUrl = normalizeBaseUrl(rawBaseUrl)
    val endpointPath = normalizeEndpointPath(rawEndpointPath)
    val modelId = resolveModelId(baseUrl, rawModelsEndpoint)

    SimulationConfig(
      baseUrl = baseUrl,
      endpointPath = endpointPath,
      modelId = modelId,
      captureResponses = readBoolean("CAPTURE_RESPONSES", false),
      responsesFile = read("RESPONSES_FILE", DefaultResponsesFile),
      captureRequests = readBoolean("CAPTURE_REQUESTS", false),
      requestsFile = read("REQUESTS_FILE", DefaultRequestsFile),
      unitsSummaryFile = read("UNITS_SUMMARY_FILE", DefaultUnitsSummaryFile),
      simulationMinutes = readInt("SIMULATION_MINUTES", 10),
      totalUsers = readInt("TOTAL_USERS", 10),
      lowUsageHourlyRequests = readDouble("LOW_USAGE_REQUESTS_PER_HOUR", 360.0),
      highUsageHourlyRequests = readDouble("HIGH_USAGE_REQUESTS_PER_HOUR", 360.0),
      highUsageUserShare = readDouble("HIGH_USAGE_USER_SHARE", 0.5),
      basicShare = readDouble("BASIC_SHARE", 0.7),
      standardShare = readDouble("STANDARD_SHARE", 0.2),
      proShare = readDouble("PRO_SHARE", 0.1),
      basicUnitsPerMinute = readInt("BASIC_UNITS_PER_MINUTE", 10),
      standardUnitsPerMinute = readInt("STANDARD_UNITS_PER_MINUTE", 20),
      proUnitsPerMinute = readInt("PRO_UNITS_PER_MINUTE", 40),
      unitsPerRequest = readInt("UNITS_PER_REQUEST", 1),
      prompt = read("LLM_PROMPT", "Hello"),
      minOutputLength = readInt("MIN_OUTPUT_LENGTH", 0),
      maxOutputLength = readInt("MAX_OUTPUT_LENGTH", 256)
    )
  }

  private def read(key: String, defaultValue: String): String = {
    sys.props
      .get(key)
      .orElse(dotenvEntries.get(key))
      .orElse(sys.env.get(key))
      .getOrElse(defaultValue)
  }

  private def readInt(key: String, defaultValue: Int): Int = {
    Try(read(key, defaultValue.toString).trim.toInt).getOrElse(defaultValue)
  }

  private def readDouble(key: String, defaultValue: Double): Double = {
    Try(read(key, defaultValue.toString).trim.toDouble).getOrElse(defaultValue)
  }

  private def readBoolean(key: String, defaultValue: Boolean): Boolean = {
    read(key, defaultValue.toString).trim.toLowerCase match {
      case "true" | "1" | "yes" | "y" => true
      case "false" | "0" | "no" | "n" => false
      case _ => defaultValue
    }
  }

  private def normalizeBaseUrl(value: String): String = {
    val trimmed = value.trim
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else s"http://$trimmed"
  }

  private def normalizeEndpointPath(value: String): String = {
    val trimmed = value.trim
    if (trimmed.startsWith("/")) trimmed else s"/$trimmed"
  }

  private def resolveModelId(baseUrl: String, rawModelsEndpoint: String): String = {
    val modelsUrl = normalizeModelsUrl(baseUrl, rawModelsEndpoint)
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build()
    val request = HttpRequest.newBuilder()
      .uri(URI.create(modelsUrl))
      .timeout(Duration.ofSeconds(10))
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(s"Failed to fetch model id from $modelsUrl (status ${response.statusCode()}).")
    }

    val idRegex = "\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"".r
    idRegex.findFirstMatchIn(response.body()).map(_.group(1)).getOrElse {
      throw new IllegalStateException(s"Could not parse model id from $modelsUrl response.")
    }
  }

  private def normalizeModelsUrl(baseUrl: String, rawModelsEndpoint: String): String = {
    val trimmed = rawModelsEndpoint.trim
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      trimmed
    } else {
      val normalizedBase = if (baseUrl.endsWith("/")) baseUrl.dropRight(1) else baseUrl
      val normalizedPath = if (trimmed.startsWith("/")) trimmed else s"/$trimmed"
      s"$normalizedBase$normalizedPath"
    }
  }

  private def findDotenvDirectory(): Option[String] = {
    val start = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath.normalize
    var current: Path = start
    var firstEnv: Option[Path] = None
    while (current != null) {
      val candidate = current.resolve(".env")
      if (firstEnv.isEmpty && Files.isRegularFile(candidate)) {
        firstEnv = Some(current)
      }
      val pom = current.resolve("pom.xml")
      if (Files.isRegularFile(pom)) {
        return if (Files.isRegularFile(candidate)) Some(current.toString) else firstEnv.map(_.toString)
      }
      current = current.getParent
    }
    firstEnv.map(_.toString)
  }
}
