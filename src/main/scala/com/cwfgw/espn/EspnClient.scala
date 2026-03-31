package com.cwfgw.espn

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.*
import io.circe.*
import io.circe.parser.*
import org.typelevel.log4cats.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.security.KeyStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.net.ssl.{SSLContext, TrustManagerFactory}

/** ESPN PGA Tour scoreboard API client.
  * Uses Java's built-in HttpClient which respects the system trust store,
  * avoiding issues with corporate SSL proxies (e.g. Zscaler). */
class EspnClient(httpClient: JHttpClient)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger
  private val baseUrl = "https://site.api.espn.com/apis/site/v2/sports/golf/pga/scoreboard"

  /** Fetch the scoreboard for a specific date. Returns the raw ESPN JSON. */
  def fetchScoreboard(date: LocalDate): IO[Json] =
    val dateStr = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    val uri = URI.create(s"$baseUrl?dates=$dateStr")
    val request = HttpRequest.newBuilder(uri).GET().build()
    for
      _ <- logger.info(s"Fetching ESPN scoreboard for $dateStr")
      response <- IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
      body = response.body()
      _ <- IO.raiseWhen(response.statusCode() != 200)(
        new RuntimeException(s"ESPN returned ${response.statusCode()}: ${body.take(200)}"))
      json <- IO.fromEither(parse(body).left.map(e => new RuntimeException(s"ESPN JSON parse error: ${e.message}")))
      _ <- logger.info(s"ESPN response received for $dateStr")
    yield json

  /** Parse all tournaments from ESPN scoreboard JSON. */
  def parseAllLeaderboards(json: Json): Either[String, List[EspnTournament]] =
    val cursor = json.hcursor
    for
      events <- cursor.downField("events").as[List[Json]].left.map(_.message)
      tournaments <- events.traverse(parseEvent)
    yield tournaments

  /** Parse leaderboard competitors from ESPN scoreboard JSON. */
  def parseLeaderboard(json: Json): Either[String, EspnTournament] =
    val cursor = json.hcursor
    for
      events <- cursor.downField("events").as[List[Json]].left.map(_.message)
      event <- events.headOption.toRight("No events in ESPN response")
      tournament <- parseEvent(event)
    yield tournament

  private def parseEvent(event: Json): Either[String, EspnTournament] =
    for
      eventName <- event.hcursor.downField("name").as[String].left.map(_.message)
      eventId <- event.hcursor.downField("id").as[String].left.map(_.message)
      completed = event.hcursor.downField("status").downField("type").downField("completed")
        .as[Boolean].getOrElse(false)
      competitorsJson <- event.hcursor.downField("competitions").downArray
        .downField("competitors").as[List[Json]].left.map(_.message)
      competitors = competitorsJson.flatMap(parseCompetitor)
    yield EspnTournament(
      espnId = eventId,
      name = eventName,
      completed = completed,
      competitors = assignPositions(competitors)
    )

  private def parseCompetitor(json: Json): Option[EspnCompetitor] =
    val c = json.hcursor
    for
      athleteId <- c.downField("id").as[String].toOption
      order <- c.downField("order").as[Int].toOption
      fullName <- c.downField("athlete").downField("displayName").as[String].toOption
      score = c.downField("score").as[String].toOption
      scoreToPar = score.flatMap: s =>
        s match
          case "E" => Some(0)
          case _ => s.replaceAll("\\+", "").toIntOption
      roundScores = c.downField("linescores").as[List[Json]].toOption.map: rounds =>
        rounds.flatMap(r => r.hcursor.downField("value").as[Double].toOption.map(_.toInt))
      // ESPN status.type.id: "1"=active, "2"=cut, "3"=withdrawn, "4"=disqualified
      statusId = c.downField("status").downField("type").downField("id").as[String].toOption
      didMakeCut = statusId match
        case Some("2") => false // explicitly cut
        case Some("3") | Some("4") => false // WD or DQ
        case _ => roundScores.exists(_.size >= 3) // active or completed with 3+ rounds
    yield EspnCompetitor(
      espnId = athleteId,
      name = fullName,
      order = order,
      scoreStr = score,
      scoreToPar = scoreToPar,
      totalStrokes = roundScores.map(_.sum),
      roundScores = roundScores.getOrElse(Nil),
      position = order, // will be reassigned by assignPositions
      status = statusId.getOrElse("1")
    )

  /** Assign tied positions based on score. ESPN's `order` is sequential;
    * players with the same score share a position (e.g., T4). */
  private def assignPositions(competitors: List[EspnCompetitor]): List[EspnCompetitor] =
    val sorted = competitors.sortBy(_.order)
    if sorted.isEmpty then return sorted

    // Group consecutive players by scoreStr to detect ties
    val grouped = sorted.foldLeft(List.empty[List[EspnCompetitor]]): (acc, c) =>
      acc match
        case head :: tail if head.head.scoreStr == c.scoreStr && c.scoreStr.isDefined =>
          (head :+ c) :: tail
        case _ =>
          List(c) :: acc
    .reverse

    var pos = 1
    grouped.flatMap: group =>
      val assignedPos = pos
      pos += group.size
      group.map(_.copy(position = assignedPos))

  /** Fetch the season calendar from ESPN (embedded in any scoreboard response). */
  def fetchCalendar: IO[List[EspnCalendarEntry]] =
    val request = HttpRequest.newBuilder(URI.create(baseUrl)).GET().build()
    for
      response <- IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
      json <- IO.fromEither(parse(response.body()).left.map(e => new RuntimeException(e.message)))
      entries = json.hcursor.downField("leagues").downArray.downField("calendar")
        .as[List[Json]].toOption.getOrElse(Nil).flatMap: j =>
          for
            id <- j.hcursor.downField("id").as[String].toOption
            label <- j.hcursor.downField("label").as[String].toOption
            startDate <- j.hcursor.downField("startDate").as[String].toOption
          yield EspnCalendarEntry(id, label, startDate)
    yield entries

object EspnClient:
  def resource(using LoggerFactory[IO]): Resource[IO, EspnClient] =
    Resource.eval(IO.blocking {
      // Use macOS KeychainStore to trust Zscaler/corporate proxy certs
      val sslContext = SSLContext.getInstance("TLS")
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      try
        val ks = KeyStore.getInstance("KeychainStore", "Apple")
        ks.load(null, null)
        tmf.init(ks)
      catch
        case _: Exception =>
          // Not on macOS or KeychainStore unavailable, use JDK default
          tmf.init(null: KeyStore)
      sslContext.init(null, tmf.getTrustManagers, null)
      val client = JHttpClient.newBuilder().sslContext(sslContext).build()
      EspnClient(client)
    })
