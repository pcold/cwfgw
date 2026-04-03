package com.cwfgw.routes

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import com.cwfgw.domain.*
import com.cwfgw.service.*

/** Route-level tests using stubbed services. Tests HTTP status codes, error handling, and path matching.
  */
class RouteTest extends FunSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val sampleId = UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val sampleInstant = Instant.parse("2026-01-15T12:00:00Z")
  private val sampleLeague = League(sampleId, "Test League", sampleInstant)
  private val sampleSeason = Season(
    sampleId,
    sampleId,
    "Season 1",
    2026,
    1,
    "active",
    BigDecimal(1),
    BigDecimal(15),
    13,
    sampleInstant,
    sampleInstant
  )
  private val sampleTeam = Team(sampleId, sampleId, "Alice", "Team A", Some(1), sampleInstant, sampleInstant)

  // ================================================================
  // LeagueRoutes
  // ================================================================

  private val leagueService = new LeagueService(null):
    override def list: IO[List[League]] = IO.pure(List(sampleLeague))
    override def get(id: UUID): IO[Option[League]] =
      if id == sampleId then IO.pure(Some(sampleLeague)) else IO.pure(None)
    override def create(req: CreateLeague): IO[League] = IO.pure(League(sampleId, req.name, sampleInstant))

  private val leagueRoutes = LeagueRoutes.routes(leagueService).orNotFound

  test("GET /api/v1/leagues returns 200 with list") {
    val req = Request[IO](Method.GET, uri"/api/v1/leagues")
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.isArray)
  }

  test("GET /api/v1/leagues/:id returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("name").as[String], Right("Test League"))
  }

  test("POST /api/v1/leagues returns 201") {
    val body = Json.obj("name" -> "New League".asJson)
    val req = Request[IO](Method.POST, uri"/api/v1/leagues").withEntity(body)
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Created)
    val respBody = response.as[Json].unsafeRunSync()
    assertEquals(respBody.hcursor.downField("name").as[String], Right("New League"))
  }

  test("GET /api/v1/leagues/:id returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$otherId"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  // ================================================================
  // SeasonRoutes
  // ================================================================

  private val seasonService = new SeasonService(null):
    override def list(leagueId: Option[UUID], seasonYear: Option[Int]): IO[List[Season]] = IO.pure(List(sampleSeason))
    override def get(id: UUID): IO[Option[Season]] =
      if id == sampleId then IO.pure(Some(sampleSeason)) else IO.pure(None)
    override def create(req: CreateSeason): IO[Season] = IO.pure(sampleSeason)
    override def standings(seasonId: UUID): IO[List[SeasonStanding]] = IO.pure(Nil)

  private val seasonRoutes = SeasonRoutes.routes(seasonService).orNotFound

  test("GET /api/v1/seasons returns 200 with list") {
    val req = Request[IO](Method.GET, uri"/api/v1/seasons")
    val response = seasonRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.isArray)
  }

  test("GET /api/v1/seasons with year param returns 200") {
    val req = Request[IO](Method.GET, uri"/api/v1/seasons?year=2026")
    val response = seasonRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/seasons/:id returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId"))
    val response = seasonRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("name").as[String], Right("Season 1"))
  }

  test("POST /api/v1/seasons returns 201") {
    val body = Json.obj("league_id" -> sampleId.asJson, "name" -> "Spring".asJson, "season_year" -> 2026.asJson)
    val req = Request[IO](Method.POST, uri"/api/v1/seasons").withEntity(body)
    val response = seasonRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Created)
  }

  test("GET /api/v1/seasons/:id returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$otherId"))
    val response = seasonRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/seasons/:id/standings returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/standings"))
    val response = seasonRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  // ================================================================
  // TeamRoutes
  // ================================================================

  private val teamService = new TeamService(null):
    override def listBySeason(seasonId: UUID): IO[List[Team]] = IO.pure(List(sampleTeam))
    override def get(id: UUID): IO[Option[Team]] = if id == sampleId then IO.pure(Some(sampleTeam)) else IO.pure(None)
    override def getRoster(teamId: UUID): IO[List[RosterEntry]] = IO.pure(Nil)
    override def dropFromRoster(teamId: UUID, golferId: UUID): IO[Boolean] = IO.pure(false)
    override def getRosterView(seasonId: UUID): IO[List[RosterViewTeam]] = IO.pure(Nil)

  private val teamRoutes = TeamRoutes.routes(teamService).orNotFound

  test("GET /api/v1/seasons/:id/teams returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/teams"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/seasons/:id/teams/:teamId returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/teams/$sampleId"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/seasons/:id/teams/:teamId returns 404") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/teams/$otherId"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/seasons/:id/teams/:id/roster returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/teams/$sampleId/roster"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("DELETE roster entry returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req =
      Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/teams/$sampleId/roster/$otherId"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/seasons/:id/rosters returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/rosters"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  // ================================================================
  // ScoringRoutes
  // ================================================================

  private val scoringService = new ScoringService(null):
    override def getScores(seasonId: UUID, tournamentId: UUID): IO[List[FantasyScore]] = IO.pure(Nil)
    override def calculateScores(seasonId: UUID, tournamentId: UUID): IO[Either[String, WeeklyScoreResult]] =
      if seasonId == sampleId then IO.pure(Right(WeeklyScoreResult(tournamentId, BigDecimal(1), 0, BigDecimal(0), Nil)))
      else IO.pure(Left("Season not found"))
    override def getSideBetStandings(seasonId: UUID): IO[Either[String, SideBetStandings]] = IO
      .pure(Right(SideBetStandings(Nil, Nil)))
    override def refreshStandings(seasonId: UUID): IO[List[SeasonStanding]] = IO.pure(Nil)

  private val scoringRoutes = ScoringRoutes.routes(scoringService).orNotFound

  test("GET scoring returns 200") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/scoring/$tid"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST calculate scores returns 200 on success") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/scoring/calculate/$tid"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST calculate scores returns 400 on error") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$otherId/scoring/calculate/$tid"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("error").as[String].isRight)
  }

  test("GET side-bets returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/scoring/side-bets"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST refresh-standings returns 200") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/scoring/refresh-standings"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  // ================================================================
  // DraftRoutes
  // ================================================================

  private val draftService = new DraftService(null):
    override def get(seasonId: UUID): IO[Option[Draft]] =
      if seasonId == sampleId then IO.pure(Some(Draft(sampleId, sampleId, "pending", "snake", None, None, sampleInstant)))
      else IO.pure(None)
    override def start(seasonId: UUID): IO[Either[String, Draft]] = IO.pure(Left("Draft is already in_progress"))
    override def getPicks(seasonId: UUID): IO[Either[String, List[DraftPick]]] =
      if seasonId == sampleId then IO.pure(Right(Nil)) else IO.pure(Left("No draft found for this season"))
    override def getAvailableGolfers(seasonId: UUID): IO[Either[String, List[Golfer]]] = IO.pure(Right(Nil))
    override def initializePicks(seasonId: UUID, rounds: Int): IO[Either[String, List[DraftPick]]] = IO
      .pure(Left("Draft picks can only be initialized when draft is pending"))

  private val draftRoutes = DraftRoutes.routes(draftService).orNotFound

  test("GET draft returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/draft"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET draft returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$otherId/draft"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("POST draft/start returns 400 on failure") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/draft/start"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("error").as[String], Right("Draft is already in_progress"))
  }

  test("GET draft/picks returns 200 on success") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/draft/picks"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET draft/picks returns 400 when no draft exists") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$otherId/draft/picks"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
  }

  test("GET draft/available returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/draft/available"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST draft/initialize returns 400 with error") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/draft/initialize"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("error").as[String].toOption.get.contains("pending"))
  }

  // ================================================================
  // ReportRoutes
  // ================================================================

  private val reportService = new WeeklyReportService(null, null):
    override def getReport(seasonId: UUID, tournamentId: UUID, live: Boolean): IO[WeeklyReport] = IO.pure(WeeklyReport(
      tournament = ReportTournamentInfo(None, Some("Test Open"), None, None, None, BigDecimal(1), None),
      teams = Nil,
      undraftedTopTens = Nil,
      sideBetDetail = Nil,
      standingsOrder = Nil
    ))
    override def getRankings(seasonId: UUID, live: Boolean, throughTournamentId: Option[UUID]): IO[Rankings] = IO
      .pure(Rankings(teams = Nil, weeks = Nil, tournamentNames = Nil))
    override def getGolferHistory(seasonId: UUID, golferId: UUID): IO[GolferHistory] = IO.pure(GolferHistory(
      golferName = "Scottie Scheffler",
      golferId = golferId,
      totalEarnings = BigDecimal(0),
      topTens = 0,
      results = Nil
    ))

  private val reportRoutes = ReportRoutes.routes(reportService).orNotFound

  test("GET report returns 200") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/report/$tid"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET report with live param returns 200") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/report/$tid?live=true"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET rankings returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/rankings"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET golfer history returns 200") {
    val golferId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/golfer/$golferId/history"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("golfer_name").as[String], Right("Scottie Scheffler"))
  }

  // ================================================================
  // TournamentRoutes
  // ================================================================

  private val sampleTournament = Tournament(
    sampleId,
    Some("401"),
    "The Masters",
    sampleId,
    LocalDate.of(2026, 4, 9),
    LocalDate.of(2026, 4, 12),
    Some("Augusta National"),
    "completed",
    Some(20000000L),
    BigDecimal(2),
    None,
    sampleInstant
  )

  private val tournamentService = new TournamentService(null, null, null, Semaphore[IO](1).unsafeRunSync()):
    override def list(seasonId: Option[UUID], status: Option[String]): IO[List[Tournament]] =
      val filtered = if seasonId.contains(sampleId) then List(sampleTournament) else Nil
      IO.pure(filtered)
    override def get(id: UUID): IO[Option[Tournament]] =
      if id == sampleId then IO.pure(Some(sampleTournament)) else IO.pure(None)
    override def getResults(tournamentId: UUID): IO[List[TournamentResult]] = IO.pure(Nil)
    override def importResults(tournamentId: UUID, results: List[CreateTournamentResult]): IO[List[TournamentResult]] =
      IO.pure(Nil)
    override def finalizeTournament(tournamentId: UUID): IO[Either[String, String]] =
      if tournamentId == sampleId then IO.pure(Right("Tournament finalized")) else IO.pure(Left("Tournament not found"))
    override def resetTournament(tournamentId: UUID): IO[Either[String, String]] =
      if tournamentId == sampleId then IO.pure(Right("Tournament reset")) else IO.pure(Left("Tournament not found"))
    override def finalizeSeason(seasonId: UUID): IO[Either[String, String]] =
      if seasonId == sampleId then IO.pure(Right("Season finalized")) else IO.pure(Left("Season not found"))
    override def cleanSeasonResults(seasonId: UUID): IO[Either[String, String]] =
      if seasonId == sampleId then IO.pure(Right("Season cleaned")) else IO.pure(Left("Season not found"))

  private val tournamentRoutes = TournamentRoutes.routes(tournamentService).orNotFound
  private val tournamentAdminRoutes = TournamentRoutes.adminRoutes(tournamentService).orNotFound

  test("GET /api/v1/tournaments returns 200") {
    val req = Request[IO](Method.GET, uri"/api/v1/tournaments")
    val response = tournamentRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/tournaments?season_id filters by season") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/tournaments?season_id=$sampleId"))
    val response = tournamentRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.asArray.exists(_.nonEmpty))
  }

  test("GET /api/v1/tournaments?season_id returns empty for unknown") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/tournaments?season_id=$otherId"))
    val response = tournamentRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.asArray.exists(_.isEmpty))
  }

  test("GET /api/v1/tournaments/:id returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/tournaments/$sampleId"))
    val response = tournamentRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("name").as[String], Right("The Masters"))
  }

  test("GET /api/v1/tournaments/:id returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/tournaments/$otherId"))
    val response = tournamentRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/tournaments/:id/results returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/tournaments/$sampleId/results"))
    val response = tournamentRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST finalize tournament returns 200 on success") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/tournaments/$sampleId/finalize"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("message").as[String].isRight)
  }

  test("POST finalize tournament returns 400 on failure") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/tournaments/$otherId/finalize"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("error").as[String].isRight)
  }

  test("POST reset tournament returns 200 on success") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/tournaments/$sampleId/reset"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("message").as[String].isRight)
  }

  test("POST reset tournament returns 400 on failure") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/tournaments/$otherId/reset"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
  }

  test("POST finalize season returns 200 on success") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/finalize"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("message").as[String].isRight)
  }

  test("POST finalize season returns 400 on failure") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$otherId/finalize"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
  }

  test("POST clean-results returns 200 on success") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$sampleId/clean-results"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("message").as[String].isRight)
  }

  test("POST clean-results returns 400 on failure") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/seasons/$otherId/clean-results"))
    val response = tournamentAdminRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
  }
