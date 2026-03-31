package com.cwfgw.service

import io.circe.Json
import munit.FunSuite

import java.time.Instant
import java.util.UUID

import com.cwfgw.domain.TournamentResult

class ScoringServiceTest extends FunSuite:

  private val service = new ScoringService(null)

  private def teamId(n: Int): UUID =
    UUID.fromString(f"00000000-0000-0000-0000-0000000000$n%02d")

  private val tid = UUID.fromString("00000000-0000-0000-0000-000000000099")

  private def makeResult(golferId: UUID, position: Option[Int]): TournamentResult =
    TournamentResult(UUID.randomUUID(), tid, golferId, position, Some(-5), Some(270),
      None, None, position.isDefined, Json.obj())

  // ================================================================
  // calculateGolferPayout
  // ================================================================

  test("calculateGolferPayout: 1st place solo, 100% ownership") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(100), isMajor = false)
    assert(result.isDefined)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(18))
    assertEquals(owner, BigDecimal(18))
  }

  test("calculateGolferPayout: 1st place, 75% ownership") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(75), isMajor = false)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(18))
    assertEquals(owner, BigDecimal("13.5"))
  }

  test("calculateGolferPayout: major doubles the payout") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(100), isMajor = true)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(36))
    assertEquals(owner, BigDecimal(36))
  }

  test("calculateGolferPayout: T3 with 2 tied") {
    val gid1 = UUID.randomUUID()
    val gid2 = UUID.randomUUID()
    val results = List(makeResult(gid1, Some(3)), makeResult(gid2, Some(3)))
    val result = service.calculateGolferPayout(Some(3), results, BigDecimal(100), isMajor = false)
    val (base, owner, _) = result.get
    // T3 with 2: avg of positions 3,4 = ($10+$8)/2 = $9
    assertEquals(base, BigDecimal(9))
    assertEquals(owner, BigDecimal(9))
  }

  test("calculateGolferPayout: position 11 returns None") {
    val result = service.calculateGolferPayout(Some(11), Nil, BigDecimal(100), isMajor = false)
    assertEquals(result, None)
  }

  test("calculateGolferPayout: no position returns None") {
    val result = service.calculateGolferPayout(None, Nil, BigDecimal(100), isMajor = false)
    assertEquals(result, None)
  }

  test("calculateGolferPayout: breakdown JSON has correct fields") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(5)))
    val result = service.calculateGolferPayout(Some(5), results, BigDecimal(50), isMajor = true)
    val (_, _, breakdown) = result.get
    assertEquals(breakdown.hcursor.downField("position").as[Int], Right(5))
    assertEquals(breakdown.hcursor.downField("num_tied").as[Int], Right(1))
    assertEquals(breakdown.hcursor.downField("base_payout").as[BigDecimal], Right(BigDecimal(14))) // $7 * 2x
    assertEquals(breakdown.hcursor.downField("ownership_pct").as[BigDecimal], Right(BigDecimal(50)))
    assertEquals(breakdown.hcursor.downField("payout").as[BigDecimal], Right(BigDecimal(7))) // $14 * 50%
    assertEquals(breakdown.hcursor.downField("is_major").as[Boolean], Right(true))
  }

  test("calculateGolferPayout: 50% ownership of T10 at major with floor") {
    val gid1 = UUID.randomUUID()
    val gid2 = UUID.randomUUID()
    val gid3 = UUID.randomUUID()
    val results = List(makeResult(gid1, Some(10)), makeResult(gid2, Some(10)), makeResult(gid3, Some(10)))
    // T10 with 3 tied: $2/3 = $0.67, floor to $1, major 2x = $2
    val result = service.calculateGolferPayout(Some(10), results, BigDecimal(50), isMajor = true)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(2))   // $1 floor * 2x
    assertEquals(owner, BigDecimal(1))  // $2 * 50%
  }

  // ================================================================
  // zeroSumWeekly
  // ================================================================

  test("zeroSumWeekly: two teams, sum is always zero") {
    val result = service.zeroSumWeekly(List(
      (teamId(1), BigDecimal(18)),
      (teamId(2), BigDecimal(0))
    ))
    val weeklyMap = result.toMap
    // team1: 18*2 - 18 = 18, team2: 0*2 - 18 = -18
    assertEquals(weeklyMap(teamId(1)), BigDecimal(18))
    assertEquals(weeklyMap(teamId(2)), BigDecimal(-18))
    assertEquals(result.map(_._2).sum, BigDecimal(0))
  }

  test("zeroSumWeekly: three teams") {
    val result = service.zeroSumWeekly(List(
      (teamId(1), BigDecimal(18)),
      (teamId(2), BigDecimal(12)),
      (teamId(3), BigDecimal(0))
    ))
    val weeklyMap = result.toMap
    // totalPot = 30, numTeams = 3
    // team1: 18*3 - 30 = 24
    // team2: 12*3 - 30 = 6
    // team3: 0*3 - 30 = -30
    assertEquals(weeklyMap(teamId(1)), BigDecimal(24))
    assertEquals(weeklyMap(teamId(2)), BigDecimal(6))
    assertEquals(weeklyMap(teamId(3)), BigDecimal(-30))
    assertEquals(result.map(_._2).sum, BigDecimal(0))
  }

  test("zeroSumWeekly: all teams earn same amount → all weekly = 0") {
    val result = service.zeroSumWeekly(List(
      (teamId(1), BigDecimal(10)),
      (teamId(2), BigDecimal(10)),
      (teamId(3), BigDecimal(10))
    ))
    assert(result.forall(_._2 == BigDecimal(0)))
  }

  test("zeroSumWeekly: all teams earn zero → all weekly = 0") {
    val result = service.zeroSumWeekly(List(
      (teamId(1), BigDecimal(0)),
      (teamId(2), BigDecimal(0))
    ))
    assert(result.forall(_._2 == BigDecimal(0)))
  }

  test("zeroSumWeekly: single team always gets 0") {
    val result = service.zeroSumWeekly(List((teamId(1), BigDecimal(50))))
    assertEquals(result.head._2, BigDecimal(0))
  }

  test("zeroSumWeekly: 13 teams sums to zero") {
    val teams = (1 to 13).toList.map(i => (teamId(i), BigDecimal(i * 3)))
    val result = service.zeroSumWeekly(teams)
    assertEquals(result.map(_._2).sum, BigDecimal(0))
  }

  // ================================================================
  // sideBetPnl
  // ================================================================

  test("sideBetPnl: clear winner gets +$15*(N-1), losers get -$15") {
    val earnings = Map(teamId(1) -> BigDecimal(50), teamId(2) -> BigDecimal(30), teamId(3) -> BigDecimal(10))
    val result = service.sideBetPnl(earnings)
    assertEquals(result(teamId(1)), BigDecimal(30))  // $15 * 2
    assertEquals(result(teamId(2)), BigDecimal(-15))
    assertEquals(result(teamId(3)), BigDecimal(-15))
    assertEquals(result.values.sum, BigDecimal(0))
  }

  test("sideBetPnl: tied winners split the pot") {
    val earnings = Map(teamId(1) -> BigDecimal(50), teamId(2) -> BigDecimal(50), teamId(3) -> BigDecimal(10))
    val result = service.sideBetPnl(earnings)
    // 2 winners, 1 loser: each winner gets $15*1/2 = $7.5
    assertEquals(result(teamId(1)), BigDecimal("7.5"))
    assertEquals(result(teamId(2)), BigDecimal("7.5"))
    assertEquals(result(teamId(3)), BigDecimal(-15))
    assertEquals(result.values.sum, BigDecimal(0))
  }

  test("sideBetPnl: all tied → all get zero (everyone is a winner)") {
    val earnings = Map(teamId(1) -> BigDecimal(20), teamId(2) -> BigDecimal(20))
    val result = service.sideBetPnl(earnings)
    // 2 winners, 0 losers: each winner gets $15*0/2 = $0
    assertEquals(result(teamId(1)), BigDecimal(0))
    assertEquals(result(teamId(2)), BigDecimal(0))
  }

  test("sideBetPnl: all zero earnings → all get zero") {
    val earnings = Map(teamId(1) -> BigDecimal(0), teamId(2) -> BigDecimal(0))
    val result = service.sideBetPnl(earnings)
    assert(result.values.forall(_ == BigDecimal(0)))
  }

  test("sideBetPnl: empty map → empty result") {
    val result = service.sideBetPnl(Map.empty)
    assert(result.isEmpty)
  }

  test("sideBetPnl: custom bet amount") {
    val earnings = Map(teamId(1) -> BigDecimal(50), teamId(2) -> BigDecimal(10))
    val result = service.sideBetPnl(earnings, sideBetAmount = BigDecimal(20))
    assertEquals(result(teamId(1)), BigDecimal(20))  // $20 * 1
    assertEquals(result(teamId(2)), BigDecimal(-20))
    assertEquals(result.values.sum, BigDecimal(0))
  }

  test("sideBetPnl: zero-sum with 13 teams and clear winner") {
    val earnings = (1 to 13).map(i => teamId(i) -> BigDecimal(i)).toMap
    val result = service.sideBetPnl(earnings)
    // team13 wins: gets $15*12 = $180, everyone else loses $15
    assertEquals(result(teamId(13)), BigDecimal(180))
    (1 to 12).foreach(i => assertEquals(result(teamId(i)), BigDecimal(-15)))
    assertEquals(result.values.sum, BigDecimal(0))
  }
