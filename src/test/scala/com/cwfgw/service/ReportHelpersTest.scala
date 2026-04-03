package com.cwfgw.service

import munit.FunSuite
import java.util.UUID
import java.time.{Instant, LocalDate}
import com.cwfgw.domain.*

class ReportHelpersTest extends FunSuite:

  private val now = Instant.now()
  private val seasonId = UUID.randomUUID()

  private def mkTournament(name: String, date: String, pgaId: Option[String] = None): Tournament = Tournament(
    id = UUID.randomUUID(),
    pgaTournamentId = pgaId,
    name = name,
    seasonId = seasonId,
    startDate = LocalDate.parse(date),
    endDate = LocalDate.parse(date),
    courseName = None,
    status = "completed",
    purseAmount = None,
    payoutMultiplier = BigDecimal(1),
    week = None,
    createdAt = now
  )

  // ---- formatStp ----

  test("formatStp: even par") { assertEquals(ReportHelpers.formatStp(0), "E") }

  test("formatStp: over par") { assertEquals(ReportHelpers.formatStp(5), "+5") }

  test("formatStp: under par") { assertEquals(ReportHelpers.formatStp(-3), "-3") }

  // ---- buildStandingsOrder ----

  test("buildStandingsOrder sorts by totalCash descending") {
    val teamA = UUID.randomUUID()
    val teamB = UUID.randomUUID()
    val teamC = UUID.randomUUID()
    val columns = List(
      mkTeamColumn(teamA, "Team A", totalCash = BigDecimal(50)),
      mkTeamColumn(teamB, "Team B", totalCash = BigDecimal(100)),
      mkTeamColumn(teamC, "Team C", totalCash = BigDecimal(75))
    )

    val standings = ReportHelpers.buildStandingsOrder(columns)

    assertEquals(standings.size, 3)
    assertEquals(standings(0), StandingsEntry(1, "Team B", BigDecimal(100)))
    assertEquals(standings(1), StandingsEntry(2, "Team C", BigDecimal(75)))
    assertEquals(standings(2), StandingsEntry(3, "Team A", BigDecimal(50)))
  }

  test("buildStandingsOrder handles empty list") { assertEquals(ReportHelpers.buildStandingsOrder(Nil), Nil) }

  // ---- recomputeSideBetPayouts ----

  test("recomputeSideBetPayouts: single winner") {
    val teamA = UUID.randomUUID()
    val teamB = UUID.randomUUID()
    val teamC = UUID.randomUUID()
    val entries = List(
      ReportSideBetTeamEntry(teamA, "GOLFER_A", BigDecimal(30), BigDecimal(0)),
      ReportSideBetTeamEntry(teamB, "GOLFER_B", BigDecimal(20), BigDecimal(0)),
      ReportSideBetTeamEntry(teamC, "GOLFER_C", BigDecimal(10), BigDecimal(0))
    )

    val result = ReportHelpers.recomputeSideBetPayouts(entries, numTeams = 3, sideBetPerTeam = BigDecimal(15))

    val payoutA = result.find(_.teamId == teamA).get.payout
    val payoutB = result.find(_.teamId == teamB).get.payout
    val payoutC = result.find(_.teamId == teamC).get.payout

    // winner gets (3-1)*15 = 30
    assertEquals(payoutA, BigDecimal(30))
    assertEquals(payoutB, BigDecimal(-15))
    assertEquals(payoutC, BigDecimal(-15))
    // zero-sum
    assertEquals(payoutA + payoutB + payoutC, BigDecimal(0))
  }

  test("recomputeSideBetPayouts: tied winners split") {
    val teamA = UUID.randomUUID()
    val teamB = UUID.randomUUID()
    val teamC = UUID.randomUUID()
    val entries = List(
      ReportSideBetTeamEntry(teamA, "GOLFER_A", BigDecimal(25), BigDecimal(0)),
      ReportSideBetTeamEntry(teamB, "GOLFER_B", BigDecimal(25), BigDecimal(0)),
      ReportSideBetTeamEntry(teamC, "GOLFER_C", BigDecimal(10), BigDecimal(0))
    )

    val result = ReportHelpers.recomputeSideBetPayouts(entries, numTeams = 3, sideBetPerTeam = BigDecimal(15))

    val payoutA = result.find(_.teamId == teamA).get.payout
    val payoutB = result.find(_.teamId == teamB).get.payout
    val payoutC = result.find(_.teamId == teamC).get.payout

    // 2 winners split: 15*(3-2)/2 = 7.5 each
    assertEquals(payoutA, BigDecimal("7.5"))
    assertEquals(payoutB, BigDecimal("7.5"))
    assertEquals(payoutC, BigDecimal(-15))
    assertEquals(payoutA + payoutB + payoutC, BigDecimal(0))
  }

  test("recomputeSideBetPayouts: all zero earnings → all zero payouts") {
    val teamA = UUID.randomUUID()
    val teamB = UUID.randomUUID()
    val entries = List(
      ReportSideBetTeamEntry(teamA, "GOLFER_A", BigDecimal(0), BigDecimal(0)),
      ReportSideBetTeamEntry(teamB, "GOLFER_B", BigDecimal(0), BigDecimal(0))
    )

    val result = ReportHelpers.recomputeSideBetPayouts(entries, numTeams = 2, sideBetPerTeam = BigDecimal(15))
    assert(result.forall(_.payout == BigDecimal(0)))
  }

  // ---- matchPreview ----

  test("matchPreview: matches by pgaTournamentId") {
    val preview1 = mkPreview("espn-1", "Tournament A")
    val preview2 = mkPreview("espn-2", "Tournament B")
    val tournament = mkTournament("Test", "2026-03-01", pgaId = Some("espn-2"))

    val result = ReportHelpers.matchPreview(List(preview1, preview2), tournament)
    assertEquals(result.map(_.espnId), Some("espn-2"))
  }

  test("matchPreview: falls back to first when no pgaId match") {
    val preview1 = mkPreview("espn-1", "Tournament A")
    val preview2 = mkPreview("espn-2", "Tournament B")
    val tournament = mkTournament("Test", "2026-03-01", pgaId = Some("espn-99"))

    val result = ReportHelpers.matchPreview(List(preview1, preview2), tournament)
    assertEquals(result.map(_.espnId), Some("espn-1"))
  }

  test("matchPreview: uses headOption when no pgaTournamentId") {
    val preview1 = mkPreview("espn-1", "Tournament A")
    val tournament = mkTournament("Test", "2026-03-01", pgaId = None)

    val result = ReportHelpers.matchPreview(List(preview1), tournament)
    assertEquals(result.map(_.espnId), Some("espn-1"))
  }

  test("matchPreview: empty list returns None") {
    val tournament = mkTournament("Test", "2026-03-01")
    assertEquals(ReportHelpers.matchPreview(Nil, tournament), None)
  }

  // ---- helpers ----

  private def mkTeamColumn(teamId: UUID, teamName: String, totalCash: BigDecimal): ReportTeamColumn = ReportTeamColumn(
    teamId = teamId,
    teamName = teamName,
    ownerName = "Owner",
    rows = Nil,
    topTens = BigDecimal(0),
    weeklyTotal = BigDecimal(0),
    previous = BigDecimal(0),
    subtotal = BigDecimal(0),
    topTenCount = 0,
    topTenMoney = BigDecimal(0),
    sideBets = BigDecimal(0),
    totalCash = totalCash
  )

  private def mkPreview(espnId: String, name: String): EspnLivePreview = EspnLivePreview(
    espnName = name,
    espnId = espnId,
    completed = false,
    payoutMultiplier = BigDecimal(1),
    totalCompetitors = 0,
    teams = Nil,
    leaderboard = Nil
  )
