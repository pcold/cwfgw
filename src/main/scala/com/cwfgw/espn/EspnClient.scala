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
  private val tourUrls = List(
    baseUrl,                                                                              // PGA Tour
    "https://site.api.espn.com/apis/site/v2/sports/golf/liv/scoreboard",                  // LIV Golf
    "https://site.api.espn.com/apis/site/v2/sports/golf/eur/scoreboard"                   // DP World Tour
  )

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
      competitors = competitorsJson.zipWithIndex.flatMap((j, i) =>
        parseCompetitor(j, i)
      )
    yield EspnTournament(
      espnId = eventId,
      name = eventName,
      completed = completed,
      competitors = assignPositions(competitors)
    )

  /** Parse a single competitor from ESPN JSON.
    * Falls back to list index for `order` when ESPN omits it. */
  private def parseCompetitor(json: Json, index: Int): Option[EspnCompetitor] =
    val c = json.hcursor
    for
      athleteId <- c.downField("id").as[String].toOption
      order = c.downField("order").as[Int].getOrElse(index + 1)
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

  /** Fetch athletes from PGA, LIV, and DP World Tour scoreboards.
    * Pulls recent tournaments from each tour to build a comprehensive player pool. */
  def fetchActivePlayers: IO[List[EspnAthlete]] =
    for
      _ <- logger.info("Fetching athletes from PGA, LIV, and DP World Tour")

      // Fetch from each tour's current scoreboard (gets the most recent/active event)
      currentAthletes <- tourUrls.traverse: url =>
        fetchAthletesFromUrl(url).handleErrorWith: e =>
          logger.warn(s"Failed to fetch from $url: ${e.getMessage}") >>
            IO.pure(Nil)

      // Also fetch from recent PGA tournament dates for broader coverage
      calendar <- fetchCalendar
      recentDates = calendar
        .flatMap(e => scala.util.Try(LocalDate.parse(e.startDate.take(10))).toOption)
        .filter(_.isBefore(LocalDate.now().plusDays(1)))
        .sorted.reverse
        .take(6)

      _ <- logger.info(s"Fetching PGA athletes from ${recentDates.size} recent tournaments")
      historicalAthletes <- recentDates.traverse: date =>
        fetchScoreboardAthletes(date).handleErrorWith: e =>
          logger.warn(s"Failed to fetch athletes for $date: ${e.getMessage}") >>
            IO.pure(Nil)

      combined = (currentAthletes.flatten ++ historicalAthletes.flatten).distinctBy(_.espnId)
      _ <- logger.info(s"Found ${combined.size} unique athletes across all tours")
    yield combined

  private def fetchAthletesFromUrl(url: String): IO[List[EspnAthlete]] =
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    for
      response <- IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
      json <- IO.fromEither(parse(response.body()).left.map(e => new RuntimeException(e.message)))
    yield parseAthletesFromJson(json)

  private def fetchScoreboardAthletes(date: LocalDate): IO[List[EspnAthlete]] =
    val dateStr = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    val uri = URI.create(s"$baseUrl?dates=$dateStr")
    val request = HttpRequest.newBuilder(uri).GET().build()
    for
      response <- IO.blocking(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
      json <- IO.fromEither(parse(response.body()).left.map(e => new RuntimeException(e.message)))
    yield parseAthletesFromJson(json)

  private def parseAthletesFromJson(json: Json): List[EspnAthlete] =
    json.hcursor.downField("events").as[List[Json]].toOption.getOrElse(Nil).flatMap: event =>
      event.hcursor.downField("competitions").downArray
        .downField("competitors").as[List[Json]].toOption.getOrElse(Nil).flatMap: c =>
          for
            id <- c.hcursor.downField("id").as[String].toOption
            name <- c.hcursor.downField("athlete").downField("displayName").as[String].toOption
          yield EspnAthlete(id, name)

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
