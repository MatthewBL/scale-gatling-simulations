package simulations

import io.gatling.commons.stats.KO
import io.gatling.core.Predef._
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.action.{Action, ExitableAction}
import io.gatling.core.stats.StatsEngine
import io.gatling.core.structure.ScenarioContext
import io.gatling.commons.util.Clock
import io.gatling.http.Predef._
import io.gatling.http.check.HttpCheck
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration._
import scala.util.Random
import scala.jdk.CollectionConverters._

class BasicLLMUsersSimulation extends Simulation {
  private val config = SimulationConfig.load()
  private val random = new Random()
  private val baseUrls = config.effectiveBaseUrls

  private val httpProtocol = http
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val requestBody = s"""{"model":"${escapeJson(config.modelId)}","prompt":"${escapeJson(config.prompt)}","min_tokens":${config.minOutputLength},"max_tokens":${config.maxOutputLength}}"""
  private val responseWriteLock = new Object
  private val requestWriteLock = new Object
  private val unitsWriteLock = new Object
  private lazy val resultsDir = resolveResultsDir()
  private lazy val responseOutputPath = {
    val resolvedPath = resolveResponsesFile(config.responsesFile)
    val path = Paths.get(resolvedPath)
    Option(path.getParent).foreach(path => Files.createDirectories(path))
    path
  }
  private lazy val requestOutputPath = {
    val resolvedPath = resolveRequestsFile(config.requestsFile)
    val path = Paths.get(resolvedPath)
    Option(path.getParent).foreach(path => Files.createDirectories(path))
    path
  }
  private lazy val unitsSummaryOutputPath = {
    val resolvedPath = resolveUnitsSummaryFile(config.unitsSummaryFile)
    val path = Paths.get(resolvedPath)
    Option(path.getParent).foreach(path => Files.createDirectories(path))
    path
  }
  private val responseChecks: Seq[HttpCheck] = if (config.captureResponses) {
    Seq(status.in(200, 201, 202, 204), bodyString.saveAs("responseBody"))
  } else {
    Seq(status.in(200, 201, 202, 204))
  }
  private val userFeeder = config.userAssignments.iterator.map { assignment =>
    val baseUrl = baseUrls(assignment.index % baseUrls.size)
    val requestUrl = joinUrl(baseUrl, config.endpointPath)
    Map(
      "userIndex" -> assignment.index,
      "baseUrl" -> baseUrl,
      "requestUrl" -> requestUrl,
      "userType" -> assignment.userType,
      "usageType" -> assignment.usageType,
      "hourlyRequests" -> assignment.hourlyRequests,
      "remainingUnits" -> assignment.initialUnits,
      "initialUnits" -> assignment.initialUnits
    )
  }

  private def nextIrregularPauseSeconds(hourlyRequests: Double): Double = {
    if (hourlyRequests <= 0) {
      3600.0
    } else {
      val averageSeconds = 3600.0 / hourlyRequests
      val sample = -math.log(1.0 - random.nextDouble())
      math.max(0.0, averageSeconds * sample)
    }
  }

  private val requestChain = pause(session => nextIrregularPauseSeconds(session("hourlyRequests").as[Double]).seconds)
    .doIfOrElse(session => session("remainingUnits").as[Int] > 0) {
      exec { session =>
        if (config.captureRequests) {
          appendRequest(session)
        } else {
          session
        }
      }.exec(
        http("llm-request")
          .post("#{requestUrl}")
          .body(StringBody(requestBody))
          .check(responseChecks: _*)
      ).exec { session =>
        val remaining = session("remainingUnits").as[Int] - config.unitsPerRequest
        val updated = session.set("remainingUnits", remaining)
        if (config.captureResponses) {
          appendResponse(updated)
        } else {
          updated
        }
      }
    } {
      exec(new InsufficientUnitsActionBuilder("llm-request-insufficient-units"))
    }

  private val workloadChain = during(config.simulationMinutes.minutes) {
    requestChain
  }

  private val scn = scenario("Basic LLM Users")
    .feed(userFeeder)
    .exec(workloadChain)
    .exec(session => appendUnitsSummary(session))

  setUp(
    scn.inject(
      atOnceUsers(config.totalUsers)
    )
  ).protocols(httpProtocol)

  private def escapeJson(value: String): String = {
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }

  private def joinUrl(baseUrl: String, endpointPath: String): String = {
    val trimmedBase = if (baseUrl.endsWith("/")) baseUrl.dropRight(1) else baseUrl
    val trimmedPath = if (endpointPath.startsWith("/")) endpointPath else s"/$endpointPath"
    s"$trimmedBase$trimmedPath"
  }

  private lazy val fallbackRunId: String =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now)

  private def resolveResponsesFile(configuredPath: String): String = {
    if (configuredPath != SimulationConfig.DefaultResponsesFile) {
      configuredPath
    } else {
      Paths.get(resultsDir, "response-bodies.jsonl").toString
    }
  }

  private def resolveRequestsFile(configuredPath: String): String = {
    if (configuredPath != SimulationConfig.DefaultRequestsFile) {
      configuredPath
    } else {
      Paths.get(resultsDir, "request-bodies.jsonl").toString
    }
  }

  private def resolveUnitsSummaryFile(configuredPath: String): String = {
    if (configuredPath != SimulationConfig.DefaultUnitsSummaryFile) {
      configuredPath
    } else {
      Paths.get(resultsDir, "units-summary.jsonl").toString
    }
  }

  private def resolveResultsDir(): String = {
    val baseResultsDir = sys.props
      .get("gatling.resultsFolder")
      .orElse(sys.props.get("gatling.resultsDirectory"))
      .orElse(sys.props.get("gatling.resultsDir"))
      .getOrElse("target/gatling")
    val simulationId = sys.props
      .get("gatling.simulationId")
      .orElse(sys.props.get("gatling.simulationClass").map(_.split("\\.").last))
      .map(_.toLowerCase)
      .getOrElse(defaultSimulationId)
    val basePath = Paths.get(baseResultsDir)
    val baseName = Option(basePath.getFileName).map(_.toString).getOrElse("")
    val prefix = s"$simulationId-"
    if (baseName.startsWith(prefix)) {
      baseResultsDir
    } else {
      val runId = sys.props
        .get("gatling.runId")
        .orElse(sys.env.get("GATLING_RUN_ID"))
        .orElse(findLatestRunId(basePath, simulationId))
        .getOrElse(fallbackRunId)
      val runFolder = s"$simulationId-$runId"
      Paths.get(baseResultsDir, runFolder).toString
    }
  }

  private def findLatestRunId(basePath: Path, simulationId: String): Option[String] = {
    if (!Files.isDirectory(basePath)) {
      None
    } else {
      val prefix = s"$simulationId-"
      val entries = Files.newDirectoryStream(basePath)
      try {
        entries.asScala
          .filter(path => Files.isDirectory(path))
          .map(path => path -> path.getFileName.toString)
          .filter { case (_, name) => name.startsWith(prefix) }
          .toVector
          .sortBy { case (path, _) => Files.getLastModifiedTime(path).toMillis }
          .lastOption
          .map { case (_, name) => name.stripPrefix(prefix) }
      } catch {
        case _: Exception => None
      } finally {
        entries.close()
      }
    }
  }

  private def defaultSimulationId: String = {
    getClass.getSimpleName.stripSuffix("$").toLowerCase
  }

  private def appendResponse(session: Session): Session = {
    val body = session("responseBody").asOption[String]
    body.foreach { value =>
      val userIndex = session("userIndex").as[Int]
      val userType = session("userType").as[String]
      val line = s"""{"userIndex":$userIndex,"userType":"${escapeJson(userType)}","body":"${escapeJson(value)}"}"""
      responseWriteLock.synchronized {
        Files.writeString(
          responseOutputPath,
          line + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      }
    }

    session.remove("responseBody")
  }

  private def appendRequest(session: Session): Session = {
    val userIndex = session("userIndex").as[Int]
    val userType = session("userType").as[String]
    val usageType = session("usageType").as[String]
    val line = s"""{"userIndex":$userIndex,"userType":"${escapeJson(userType)}","usageType":"${escapeJson(usageType)}","endpointPath":"${escapeJson(config.endpointPath)}","body":"${escapeJson(requestBody)}"}"""
    requestWriteLock.synchronized {
      Files.writeString(
        requestOutputPath,
        line + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    }

    session
  }

  private def appendUnitsSummary(session: Session): Session = {
    val userIndex = session("userIndex").as[Int]
    val userType = session("userType").as[String]
    val usageType = session("usageType").as[String]
    val initialUnits = session("initialUnits").as[Int]
    val remainingUnits = session("remainingUnits").as[Int]
    val consumedUnits = math.max(0, initialUnits - remainingUnits)
    val line = s"""{"userIndex":$userIndex,"userType":"${escapeJson(userType)}","usageType":"${escapeJson(usageType)}","initialUnits":$initialUnits,"remainingUnits":$remainingUnits,"consumedUnits":$consumedUnits}"""
    unitsWriteLock.synchronized {
      Files.writeString(
        unitsSummaryOutputPath,
        line + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    }

    session
  }

  private final class InsufficientUnitsAction(
    requestName: String,
    override val statsEngine: StatsEngine,
    override val clock: Clock,
    val next: Action
  ) extends ExitableAction {
    override def name: String = "insufficient-units"

    override def execute(session: Session): Unit = {
      val now = clock.nowMillis
      statsEngine.logResponse(
        session.scenario,
        session.groups,
        requestName,
        now,
        now,
        KO,
        None,
        Some("insufficient-units")
      )
      next ! session
    }
  }

  private final class InsufficientUnitsActionBuilder(requestName: String) extends ActionBuilder {
    override def build(ctx: ScenarioContext, next: Action): Action = {
      new InsufficientUnitsAction(requestName, ctx.coreComponents.statsEngine, ctx.coreComponents.clock, next)
    }
  }
}
