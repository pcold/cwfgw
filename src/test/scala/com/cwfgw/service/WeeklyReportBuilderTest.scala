package com.cwfgw.service

import munit.FunSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import cats.effect.IO

import java.util.UUID
import java.time.{Instant, LocalDate}
import com.cwfgw.domain.*

/** Tests for the pure builder methods in WeeklyReportService. These methods operate on domain objects with no I/O or DB
  * access.
  */
class WeeklyReportBuilderTest extends FunSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]
  private val service = new WeeklyReportService(liveOverlay = null, xa = null)

  private val now = Instant.now()
  private val seasonId = UUID.randomUUID()
  private val tournamentId = UUID.randomUUID()

  private val teamAId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val teamBId = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val teamCId = UUID.fromString("00000000-0000-0000-0000-000000000003")

  private val golfer1Id = UUID.fromString("00000000-0000-0000-0000-000000000011")
  private val golfer2Id = UUID.fromString("00000000-0000-0000-0000-000000000012")
  private val golfer3Id = UUID.fromString("00000000-0000-0000-0000-000000000013")
  private val golfer4Id = UUID.fromString("00000000-0000-0000-0000-000000000014")
  private val golfer5Id = UUID.fromString("00000000-0000-0000-0000-000000000015")

  private val rules = SeasonRules.default

  // ---- Test data builders ----

  private def mkTeam(id: UUID, name: String): Team = Team(id, seasonId, s"Owner of $name", name, None, now, now)

  private def mkGolfer(id: UUID, first: String, last: String): Golfer =
    Golfer(id, None, first, last, None, None, active = true, now)

  private def mkRoster(teamId: UUID, golferId: UUID, round: Int, pct: BigDecimal = 100): RosterEntry =
    RosterEntry(UUID.randomUUID(), teamId, golferId, "draft", Some(round), pct, now, None, true)

  private def mkResult(
    golferId: UUID,
    position: Option[Int],
    scoreToPar: Option[Int] = None,
    tId: UUID = tournamentId
  ): TournamentResult = TournamentResult(
    UUID.randomUUID(),
    tId,
    golferId,
    position,
    scoreToPar,
    None,
    None,
    None,
    None,
    None,
    None,
    madeCut = position.isDefined
  )

  private def mkScore(teamId: UUID, golferId: UUID, points: BigDecimal, tId: UUID = tournamentId): FantasyScore =
    FantasyScore(
      UUID.randomUUID(),
      seasonId,
      teamId,
      tId,
      golferId,
      points,
      1,
      1,
      points,
      BigDecimal(100),
      points,
      BigDecimal(1),
      now
    )

  private def mkTournament(
    name: String,
    date: String,
    id: UUID = UUID.randomUUID(),
    multiplier: BigDecimal = BigDecimal(1)
  ): Tournament = Tournament(
    id,
    None,
    name,
    seasonId,
    LocalDate.parse(date),
    LocalDate.parse(date),
    None,
    "completed",
    None,
    multiplier,
    Some(name),
    now
  )

  private val teamA = mkTeam(teamAId, "Team A")
  private val teamB = mkTeam(teamBId, "Team B")
  private val teamC = mkTeam(teamCId, "Team C")
  private val teams = List(teamA, teamB, teamC)

  private val golfer1 = mkGolfer(golfer1Id, "Tiger", "Woods")
  private val golfer2 = mkGolfer(golfer2Id, "Rory", "McIlroy")
  private val golfer3 = mkGolfer(golfer3Id, "Jon", "Rahm")
  private val golfer4 = mkGolfer(golfer4Id, "Scottie", "Scheffler")
  private val golfer5 = mkGolfer(golfer5Id, "Collin", "Morikawa")
  private val golferMap =
    Map(golfer1Id -> golfer1, golfer2Id -> golfer2, golfer3Id -> golfer3, golfer4Id -> golfer4, golfer5Id -> golfer5)

  // ================================================================
  // buildTournamentInfo
  // ================================================================

  test("buildTournamentInfo: Some(tournament) maps fields") {
    val t = mkTournament("The Masters", "2026-04-09", multiplier = BigDecimal(2))
    val info = service.buildTournamentInfo(Some(t))

    assertEquals(info.id, Some(t.id))
    assertEquals(info.name, Some("The Masters"))
    assertEquals(info.startDate, Some("2026-04-09"))
    assertEquals(info.status, Some("completed"))
    assertEquals(info.payoutMultiplier, BigDecimal(2))
    assertEquals(info.week, Some("The Masters"))
  }

  test("buildTournamentInfo: None produces defaults") {
    val info = service.buildTournamentInfo(None)

    assertEquals(info.id, None)
    assertEquals(info.name, None)
    assertEquals(info.payoutMultiplier, BigDecimal(1))
  }

  // ================================================================
  // emptyRow
  // ================================================================

  test("emptyRow produces correct defaults") {
    val row = service.emptyRow(3)

    assertEquals(row.round, 3)
    assertEquals(row.golferName, None)
    assertEquals(row.golferId, None)
    assertEquals(row.earnings, BigDecimal(0))
    assertEquals(row.topTens, 0)
    assertEquals(row.ownershipPct, BigDecimal(100))
  }

  // ================================================================
  // buildWeeklyRows
  // ================================================================

  test("buildWeeklyRows: always returns 8 rows") {
    val rows = service.buildWeeklyRows(Nil, Map.empty, Map.empty, Nil, Map.empty, Map.empty, teamAId)
    assertEquals(rows.size, 8)
    assertEquals(rows.map(_.round), (1 to 8).toList)
  }

  test("buildWeeklyRows: rostered golfer with result") {
    val roster = List(mkRoster(teamAId, golfer1Id, round = 1))
    val result = mkResult(golfer1Id, position = Some(3), scoreToPar = Some(-5))
    val score = mkScore(teamAId, golfer1Id, BigDecimal(10))

    val rows = service.buildWeeklyRows(
      roster,
      golferMap,
      resultsByGolfer = Map(golfer1Id -> result),
      allResults = List(result),
      scoresByTeamGolfer = Map((teamAId, golfer1Id) -> score),
      cumulativeByTeamGolfer = Map((teamAId, golfer1Id) -> (BigDecimal(25), 3)),
      teamId = teamAId
    )

    val row1 = rows.find(_.round == 1).get
    assertEquals(row1.golferName, Some("WOODS"))
    assertEquals(row1.positionStr, Some("3"))
    assertEquals(row1.scoreToPar, Some("-5"))
    assertEquals(row1.earnings, BigDecimal(10))
    assertEquals(row1.seasonEarnings, BigDecimal(25))
    assertEquals(row1.seasonTopTens, 3)
  }

  test("buildWeeklyRows: tied position shows T prefix") {
    val result1 = mkResult(golfer1Id, position = Some(4))
    val result2 = mkResult(golfer2Id, position = Some(4))
    val roster = List(mkRoster(teamAId, golfer1Id, round = 1))

    val rows = service.buildWeeklyRows(
      roster,
      golferMap,
      resultsByGolfer = Map(golfer1Id -> result1),
      allResults = List(result1, result2),
      scoresByTeamGolfer = Map.empty,
      cumulativeByTeamGolfer = Map.empty,
      teamId = teamAId
    )

    assertEquals(rows.find(_.round == 1).get.positionStr, Some("T4"))
  }

  test("buildWeeklyRows: unrostered round is empty") {
    val roster = List(mkRoster(teamAId, golfer1Id, round = 3))
    val rows = service.buildWeeklyRows(roster, golferMap, Map.empty, Nil, Map.empty, Map.empty, teamAId)

    val row1 = rows.find(_.round == 1).get
    assertEquals(row1.golferName, None)
    assertEquals(row1.earnings, BigDecimal(0))

    val row3 = rows.find(_.round == 3).get
    assertEquals(row3.golferName, Some("WOODS"))
  }

  // ================================================================
  // buildPriorWeekly — zero-sum calculations
  // ================================================================

  test("buildPriorWeekly: empty scores → empty map") {
    val result = service.buildPriorWeekly(Nil, Nil, None, teams, 3)
    assertEquals(result, Map.empty[UUID, BigDecimal])
  }

  test("buildPriorWeekly: zero-sum across teams") {
    val t1 = mkTournament("Week 1", "2026-01-01")
    val current = mkTournament("Week 2", "2026-01-08")

    val scores =
      List(mkScore(teamAId, golfer1Id, BigDecimal(18), t1.id), mkScore(teamBId, golfer2Id, BigDecimal(12), t1.id))

    val result = service.buildPriorWeekly(
      throughTournaments = List(t1, current),
      throughScores = scores,
      tournament = Some(current),
      teams = teams,
      numTeams = 3
    )

    // zero-sum: all prior weekly totals must sum to 0
    val total = teams.map(t => result.getOrElse(t.id, BigDecimal(0))).sum
    assertEquals(total, BigDecimal(0))
  }

  // ================================================================
  // buildSideBetPerRound
  // ================================================================

  test("buildSideBetPerRound: winner gets positive, losers negative") {
    val rosters = List(
      mkRoster(teamAId, golfer1Id, round = 5),
      mkRoster(teamBId, golfer2Id, round = 5),
      mkRoster(teamCId, golfer3Id, round = 5)
    )
    val scores = List(
      mkScore(teamAId, golfer1Id, BigDecimal(18)),
      mkScore(teamBId, golfer2Id, BigDecimal(10)),
      mkScore(teamCId, golfer3Id, BigDecimal(0))
    )

    val result = service.buildSideBetPerRound(rules, rosters, scores, numTeams = 3, BigDecimal(15))

    // round 5 is a side bet round in default rules
    val round5 = result.find(_._1 == 5)
    assert(round5.isDefined, "round 5 should be in results")

    val (_, _, payouts) = round5.get
    val payoutA = payouts.getOrElse(teamAId, BigDecimal(0))
    val payoutB = payouts.getOrElse(teamBId, BigDecimal(0))
    val payoutC = payouts.getOrElse(teamCId, BigDecimal(0))

    // team A wins: gets 15*(3-1)/1 = 30
    assertEquals(payoutA, BigDecimal(30))
    assertEquals(payoutB, BigDecimal(-15))
    assertEquals(payoutC, BigDecimal(-15))
    // zero-sum
    assertEquals(payoutA + payoutB + payoutC, BigDecimal(0))
  }

  test("buildSideBetPerRound: all zero → no payouts") {
    val rosters = List(mkRoster(teamAId, golfer1Id, round = 5), mkRoster(teamBId, golfer2Id, round = 5))
    // no scores → all zero
    val result = service.buildSideBetPerRound(rules, rosters, Nil, numTeams = 2, BigDecimal(15))
    val round5 = result.find(_._1 == 5).get
    assertEquals(round5._3, Map.empty[UUID, BigDecimal])
  }

  // ================================================================
  // aggregateSideBets
  // ================================================================

  test("aggregateSideBets: sums across rounds") {
    val perRound = List(
      (5, Map.empty[UUID, BigDecimal], Map(teamAId -> BigDecimal(30), teamBId -> BigDecimal(-15))),
      (6, Map.empty[UUID, BigDecimal], Map(teamAId -> BigDecimal(-15), teamBId -> BigDecimal(30)))
    )

    val result = service.aggregateSideBets(perRound)
    assertEquals(result(teamAId), BigDecimal(15))
    assertEquals(result(teamBId), BigDecimal(15))
  }

  test("aggregateSideBets: empty rounds → empty map") {
    assertEquals(service.aggregateSideBets(Nil), Map.empty[UUID, BigDecimal])
  }

  // ================================================================
  // buildSideBetDetail
  // ================================================================

  test("buildSideBetDetail: maps golfer names and earnings") {
    val rosters = List(mkRoster(teamAId, golfer1Id, round = 5))
    val perRound = List((5, Map(teamAId -> BigDecimal(18)), Map(teamAId -> BigDecimal(30))))

    val detail = service.buildSideBetDetail(perRound, List(teamA), rosters, golferMap)

    assertEquals(detail.size, 1)
    assertEquals(detail.head.round, 5)
    val entry = detail.head.teams.find(_.teamId == teamAId).get
    assertEquals(entry.golferName, "WOODS")
    assertEquals(entry.cumulativeEarnings, BigDecimal(18))
    assertEquals(entry.payout, BigDecimal(30))
  }

  test("buildSideBetDetail: missing roster entry shows dash") {
    // no roster for round 5
    val perRound = List((5, Map(teamAId -> BigDecimal(0)), Map.empty[UUID, BigDecimal]))

    val detail = service.buildSideBetDetail(perRound, List(teamA), Nil, golferMap)
    val entry = detail.head.teams.find(_.teamId == teamAId).get
    assertEquals(entry.golferName, "—")
  }

  // ================================================================
  // buildUndraftedForTournament
  // ================================================================

  test("buildUndraftedForTournament: excludes rostered golfers") {
    val results = List(
      mkResult(golfer1Id, position = Some(1)),
      mkResult(golfer2Id, position = Some(2)),
      mkResult(golfer3Id, position = Some(3))
    )
    val rosteredIds = Set(golfer1Id) // golfer1 is rostered

    val undrafted = service.buildUndraftedForTournament(results, rosteredIds, golferMap, BigDecimal(1), rules)

    assertEquals(undrafted.size, 2)
    assert(!undrafted.exists(_.name.contains("Woods")))
    assertEquals(undrafted.head.name, "R. McIlroy") // position 2
  }

  test("buildUndraftedForTournament: excludes positions > 10") {
    val results = List(mkResult(golfer1Id, position = Some(5)), mkResult(golfer2Id, position = Some(11)))

    val undrafted = service.buildUndraftedForTournament(results, Set.empty, golferMap, BigDecimal(1), rules)

    assertEquals(undrafted.size, 1)
    assertEquals(undrafted.head.position, Some(5))
  }

  test("buildUndraftedForTournament: sorted by position") {
    val results = List(
      mkResult(golfer3Id, position = Some(8)),
      mkResult(golfer1Id, position = Some(2)),
      mkResult(golfer2Id, position = Some(5))
    )

    val undrafted = service.buildUndraftedForTournament(results, Set.empty, golferMap, BigDecimal(1), rules)

    assertEquals(undrafted.map(_.position), List(Some(2), Some(5), Some(8)))
  }

  // ================================================================
  // buildUndraftedAgg
  // ================================================================

  test("buildUndraftedAgg: aggregates across tournaments, sorted by payout desc") {
    val t1 = mkTournament("Week 1", "2026-01-01")
    val t2 = mkTournament("Week 2", "2026-01-08")

    val results = List(
      mkResult(golfer1Id, position = Some(1), tId = t1.id),
      mkResult(golfer1Id, position = Some(2), tId = t2.id),
      mkResult(golfer2Id, position = Some(1), tId = t2.id)
    )

    val undrafted = service.buildUndraftedAgg(results, List(t1, t2), Set.empty, golferMap, rules)

    // golfer1 has 2 top-10s, golfer2 has 1
    assertEquals(undrafted.size, 2)
    // golfer1 (1st + 2nd) should have higher payout than golfer2 (1st only)
    assert(undrafted.head.payout > undrafted.last.payout)
    assertEquals(undrafted.head.name, "T. Woods")
  }

  // ================================================================
  // buildSeasonRows
  // ================================================================

  test("buildSeasonRows: shows cumulative earnings and topTens count") {
    val roster = List(mkRoster(teamAId, golfer1Id, round = 1))
    val cumulative = Map((teamAId, golfer1Id) -> (BigDecimal(45), 3))

    val rows = service.buildSeasonRows(roster, golferMap, cumulative, teamAId)

    val row1 = rows.find(_.round == 1).get
    assertEquals(row1.golferName, Some("WOODS"))
    assertEquals(row1.earnings, BigDecimal(45))
    assertEquals(row1.topTens, 3)
    assertEquals(row1.positionStr, Some("3x"))
  }

  test("buildSeasonRows: zero topTens shows no position") {
    val roster = List(mkRoster(teamAId, golfer1Id, round = 1))
    val cumulative = Map((teamAId, golfer1Id) -> (BigDecimal(0), 0))

    val rows = service.buildSeasonRows(roster, golferMap, cumulative, teamAId)
    assertEquals(rows.find(_.round == 1).get.positionStr, None)
  }

  // ================================================================
  // buildCumulativeHistory
  // ================================================================

  test("buildCumulativeHistory: running totals across tournaments") {
    val t1 = mkTournament("Week 1", "2026-01-01")
    val t2 = mkTournament("Week 2", "2026-01-08")
    val sorted = List(t1, t2)

    val scores = List(
      mkScore(teamAId, golfer1Id, BigDecimal(18), t1.id),
      mkScore(teamBId, golfer2Id, BigDecimal(12), t1.id),
      mkScore(teamAId, golfer1Id, BigDecimal(10), t2.id)
    )

    val history = service.buildCumulativeHistory(sorted, scores, List(teamA, teamB), numTeams = 2)

    assertEquals(history.size, 2) // one entry per tournament

    // Week 1: teamA earns 18, teamB earns 12, pot=30
    // teamA weekly = 18*2 - 30 = 6, teamB weekly = 12*2 - 30 = -6
    assertEquals(history(0)(teamAId), BigDecimal(6))
    assertEquals(history(0)(teamBId), BigDecimal(-6))

    // Week 2: teamA earns 10, teamB earns 0, pot=10
    // teamA weekly = 10*2 - 10 = 10, teamB weekly = 0*2 - 10 = -10
    // Cumulative: teamA = 6+10 = 16, teamB = -6+(-10) = -16
    assertEquals(history(1)(teamAId), BigDecimal(16))
    assertEquals(history(1)(teamBId), BigDecimal(-16))
  }

  test("buildCumulativeHistory: zero-sum per tournament") {
    val t1 = mkTournament("Week 1", "2026-01-01")
    val scores = List(
      mkScore(teamAId, golfer1Id, BigDecimal(18), t1.id),
      mkScore(teamBId, golfer2Id, BigDecimal(12), t1.id),
      mkScore(teamCId, golfer3Id, BigDecimal(8), t1.id)
    )

    val history = service.buildCumulativeHistory(List(t1), scores, teams, numTeams = 3)

    // all cumulative totals should sum to 0 (zero-sum game)
    val total = teams.map(t => history.head.getOrElse(t.id, BigDecimal(0))).foldLeft(BigDecimal(0))(_ + _)
    assertEquals(total, BigDecimal(0))
  }

  test("buildCumulativeHistory: empty tournaments → empty list") {
    assertEquals(service.buildCumulativeHistory(Nil, Nil, teams, 3), Nil)
  }

  // ================================================================
  // buildReportTeamColumn — integration of above helpers
  // ================================================================

  test("buildReportTeamColumn: totalCash = subtotal + sideBets") {
    val roster = List(mkRoster(teamAId, golfer1Id, round = 1))
    val result = mkResult(golfer1Id, position = Some(1))
    val score = mkScore(teamAId, golfer1Id, BigDecimal(18))

    val column = service.buildReportTeamColumn(
      team = teamA,
      allRosters = roster,
      golferMap = golferMap,
      resultsByGolfer = Map(golfer1Id -> result),
      allResults = List(result),
      scoresByTeamGolfer = Map((teamAId, golfer1Id) -> score),
      allScores = List(score),
      cumulativeByTeamGolfer = Map.empty,
      priorWeeklyByTeam = Map(teamAId -> BigDecimal(10)),
      cumulativeTopTenCounts = Map(teamAId -> 5),
      cumulativeTopTenEarnings = Map(teamAId -> BigDecimal(50)),
      sideBetResults = Map(teamAId -> BigDecimal(20)),
      numTeams = 1
    )

    assertEquals(column.teamName, "Team A")
    assertEquals(column.previous, BigDecimal(10))
    assertEquals(column.topTenCount, 5)
    assertEquals(column.topTenMoney, BigDecimal(50))
    assertEquals(column.sideBets, BigDecimal(20))
    assertEquals(column.totalCash, column.subtotal + BigDecimal(20))
    assertEquals(column.rows.size, 8)
  }
