package com.cwfgw.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

import java.time.Instant
import java.util.UUID

import com.cwfgw.domain.*
import com.cwfgw.service.*

/** Route-level tests using stubbed services.
  * Tests HTTP status codes, error handling, and path matching. */
class RouteTest extends FunSuite:

  private val sampleId = UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val sampleInstant = Instant.parse("2026-01-15T12:00:00Z")
  private val sampleLeague = League(sampleId, "Test League", 2026, "active", Json.obj(), 13, sampleInstant, sampleInstant)
  private val sampleTeam = Team(sampleId, sampleId, "Alice", "Team A", Some(1), sampleInstant, sampleInstant)

  // ================================================================
  // LeagueRoutes
  // ================================================================

  private val leagueService = new LeagueService(null):
    override def list(seasonYear: Option[Int]): IO[List[League]] =
      IO.pure(List(sampleLeague))
    override def get(id: UUID): IO[Option[League]] =
      if id == sampleId then IO.pure(Some(sampleLeague)) else IO.pure(None)
    override def delete(id: UUID): IO[Boolean] =
      if id == sampleId then IO.pure(true) else IO.pure(false)
    override def standings(leagueId: UUID): IO[List[LeagueStanding]] =
      IO.pure(Nil)

  private val leagueRoutes = LeagueRoutes.routes(leagueService).orNotFound

  test("GET /api/v1/leagues returns 200 with list") {
    val req = Request[IO](Method.GET, uri"/api/v1/leagues")
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assert(body.isArray)
  }

  test("GET /api/v1/leagues with season param returns 200") {
    val req = Request[IO](Method.GET, uri"/api/v1/leagues?season=2026")
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/leagues/:id returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("name").as[String], Right("Test League"))
  }

  test("GET /api/v1/leagues/:id returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$otherId"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("DELETE /api/v1/leagues/:id returns 204 on success") {
    val req = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NoContent)
  }

  test("DELETE /api/v1/leagues/:id returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/leagues/$otherId"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/leagues/:id/standings returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/standings"))
    val response = leagueRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  // ================================================================
  // TeamRoutes
  // ================================================================

  private val teamService = new TeamService(null):
    override def listByLeague(leagueId: UUID): IO[List[Team]] =
      IO.pure(List(sampleTeam))
    override def get(id: UUID): IO[Option[Team]] =
      if id == sampleId then IO.pure(Some(sampleTeam)) else IO.pure(None)
    override def getRoster(teamId: UUID): IO[List[RosterEntry]] =
      IO.pure(Nil)
    override def dropFromRoster(teamId: UUID, golferId: UUID): IO[Boolean] =
      IO.pure(false)
    override def getRosterView(leagueId: UUID): IO[List[RosterViewTeam]] =
      IO.pure(Nil)

  private val teamRoutes = TeamRoutes.routes(teamService).orNotFound

  test("GET /api/v1/leagues/:id/teams returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/teams"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/leagues/:id/teams/:teamId returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/teams/$sampleId"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/leagues/:id/teams/:teamId returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/teams/$otherId"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/leagues/:id/teams/:teamId/roster returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/teams/$sampleId/roster"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("DELETE roster entry returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/teams/$sampleId/roster/$otherId"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("GET /api/v1/leagues/:id/rosters returns 200 with roster view") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/rosters"))
    val response = teamRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  // ================================================================
  // ScoringRoutes
  // ================================================================

  private val scoringService = new ScoringService(null):
    override def getScores(leagueId: UUID, tournamentId: UUID): IO[List[FantasyScore]] =
      IO.pure(Nil)
    override def calculateScores(leagueId: UUID, tournamentId: UUID): IO[Either[String, Json]] =
      if leagueId == sampleId then IO.pure(Right(Json.obj("ok" -> true.asJson)))
      else IO.pure(Left("League not found"))
    override def getSideBetStandings(leagueId: UUID): IO[Either[String, Json]] =
      IO.pure(Right(Json.obj("rounds" -> Json.arr())))
    override def refreshStandings(leagueId: UUID): IO[List[LeagueStanding]] =
      IO.pure(Nil)

  private val scoringRoutes = ScoringRoutes.routes(scoringService).orNotFound

  test("GET /api/v1/leagues/:id/scoring/:tid returns 200") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/scoring/$tid"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST calculate scores returns 200 on success") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/scoring/calculate/$tid"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST calculate scores returns 400 on error") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/leagues/$otherId/scoring/calculate/$tid"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("error").as[String].isRight)
  }

  test("GET side-bets returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/scoring/side-bets"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST refresh-standings returns 200") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/scoring/refresh-standings"))
    val response = scoringRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  // ================================================================
  // DraftRoutes — Either-based error handling
  // ================================================================

  private val draftService = new DraftService(null):
    override def get(leagueId: UUID): IO[Option[Draft]] =
      if leagueId == sampleId then
        IO.pure(Some(Draft(sampleId, sampleId, "pending", "snake", Json.obj(), None, None, sampleInstant)))
      else IO.pure(None)
    override def start(leagueId: UUID): IO[Either[String, Draft]] =
      IO.pure(Left("Draft is already in_progress"))
    override def getPicks(leagueId: UUID): IO[Either[String, List[DraftPick]]] =
      if leagueId == sampleId then IO.pure(Right(Nil))
      else IO.pure(Left("No draft found for this league"))
    override def getAvailableGolfers(leagueId: UUID): IO[Either[String, List[Golfer]]] =
      IO.pure(Right(Nil))
    override def initializePicks(leagueId: UUID, rounds: Int): IO[Either[String, List[DraftPick]]] =
      IO.pure(Left("Draft picks can only be initialized when draft is pending"))

  private val draftRoutes = DraftRoutes.routes(draftService).orNotFound

  test("GET draft returns 200 when found") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/draft"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET draft returns 404 when not found") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$otherId/draft"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("POST draft/start returns 400 with error message on failure") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/draft/start"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("error").as[String], Right("Draft is already in_progress"))
  }

  test("GET draft/picks returns 200 on success") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/draft/picks"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET draft/picks returns 400 when no draft exists") {
    val otherId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$otherId/draft/picks"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
  }

  test("GET draft/available returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/draft/available"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("POST draft/initialize returns 400 with error") {
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/draft/initialize"))
    val response = draftRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.BadRequest)
    val body = response.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("error").as[String].toOption.get.contains("pending"))
  }

  // ================================================================
  // ReportRoutes
  // ================================================================

  private val reportService = new WeeklyReportService(null, null):
    override def getReport(leagueId: UUID, tournamentId: UUID, live: Boolean): IO[Json] =
      IO.pure(Json.obj("tournament" -> Json.obj("name" -> "Test Open".asJson)))
    override def getRankings(leagueId: UUID, live: Boolean): IO[Json] =
      IO.pure(Json.obj("teams" -> Json.arr()))
    override def getGolferHistory(leagueId: UUID, golferId: UUID): IO[Json] =
      IO.pure(Json.obj("golfer_name" -> "Scottie Scheffler".asJson))

  private val reportRoutes = ReportRoutes.routes(reportService).orNotFound

  test("GET report returns 200") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/report/$tid"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET report with live param returns 200") {
    val tid = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/report/$tid?live=true"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET rankings returns 200") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/rankings"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET golfer history returns 200") {
    val golferId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/leagues/$sampleId/golfer/$golferId/history"))
    val response = reportRoutes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("golfer_name").as[String], Right("Scottie Scheffler"))
  }
