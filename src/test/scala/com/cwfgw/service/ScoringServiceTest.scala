package com.cwfgw.service

import io.circe.Json
import munit.FunSuite

import java.time.Instant
import java.util.UUID

import com.cwfgw.domain.{SeasonRules, TournamentResult}

class ScoringServiceTest extends FunSuite:

  private val service = new ScoringService(null)
  private val rules = SeasonRules.default
  private val x1 = BigDecimal(1)
  private val x2 = BigDecimal(2)

  private def teamId(n: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-0000000000$n%02d")

  private val tid = UUID.fromString("00000000-0000-0000-0000-000000000099")

  private def makeResult(golferId: UUID, position: Option[Int]): TournamentResult = TournamentResult(
    UUID.randomUUID(),
    tid,
    golferId,
    position,
    Some(-5),
    Some(270),
    None,
    None,
    position.isDefined,
    Json.obj()
  )

  // ================================================================
  // calculateGolferPayout
  // ================================================================

  test("calculateGolferPayout: 1st place solo, 100% ownership") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(100), x1, rules)
    assert(result.isDefined)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(18))
    assertEquals(owner, BigDecimal(18))
  }

  test("calculateGolferPayout: 1st place, 75% ownership") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(75), x1, rules)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(18))
    assertEquals(owner, BigDecimal("13.5"))
  }

  test("calculateGolferPayout: 2x multiplier doubles payout") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(100), x2, rules)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(36))
    assertEquals(owner, BigDecimal(36))
  }

  test("calculateGolferPayout: 1.5x multiplier") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(100), BigDecimal("1.5"), rules)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(27))
    assertEquals(owner, BigDecimal(27))
  }

  test("calculateGolferPayout: T3 with 2 tied") {
    val gid1 = UUID.randomUUID()
    val gid2 = UUID.randomUUID()
    val results = List(makeResult(gid1, Some(3)), makeResult(gid2, Some(3)))
    val result = service.calculateGolferPayout(Some(3), results, BigDecimal(100), x1, rules)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(9))
    assertEquals(owner, BigDecimal(9))
  }

  test("calculateGolferPayout: position 11 returns None") {
    val result = service.calculateGolferPayout(Some(11), Nil, BigDecimal(100), x1, rules)
    assertEquals(result, None)
  }

  test("calculateGolferPayout: no position returns None") {
    val result = service.calculateGolferPayout(None, Nil, BigDecimal(100), x1, rules)
    assertEquals(result, None)
  }

  test("calculateGolferPayout: breakdown JSON has correct fields") {
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(5)))
    val result = service.calculateGolferPayout(Some(5), results, BigDecimal(50), x2, rules)
    val (_, _, breakdown) = result.get
    assertEquals(breakdown.hcursor.downField("position").as[Int], Right(5))
    assertEquals(breakdown.hcursor.downField("num_tied").as[Int], Right(1))
    assertEquals(breakdown.hcursor.downField("base_payout").as[BigDecimal], Right(BigDecimal(14)))
    assertEquals(breakdown.hcursor.downField("ownership_pct").as[BigDecimal], Right(BigDecimal(50)))
    assertEquals(breakdown.hcursor.downField("payout").as[BigDecimal], Right(BigDecimal(7)))
    assertEquals(breakdown.hcursor.downField("multiplier").as[BigDecimal], Right(x2))
  }

  test("calculateGolferPayout: 50% ownership of T10 at 2x with floor") {
    val gid1 = UUID.randomUUID()
    val gid2 = UUID.randomUUID()
    val gid3 = UUID.randomUUID()
    val results = List(makeResult(gid1, Some(10)), makeResult(gid2, Some(10)), makeResult(gid3, Some(10)))
    val result = service.calculateGolferPayout(Some(10), results, BigDecimal(50), x2, rules)
    val (base, owner, _) = result.get
    assertEquals(base, BigDecimal(2))
    assertEquals(owner, BigDecimal(1))
  }

  test("calculateGolferPayout: custom rules with fewer places") {
    val custom = SeasonRules(
      payouts = List(20, 10, 5).map(BigDecimal(_)),
      tieFloor = BigDecimal(2),
      sideBetRounds = Nil,
      sideBetAmount = BigDecimal(0)
    )
    val gid = UUID.randomUUID()
    val results = List(makeResult(gid, Some(1)))
    val result = service.calculateGolferPayout(Some(1), results, BigDecimal(100), x1, custom)
    assertEquals(result.get._1, BigDecimal(20))

    // Position 4 is outside custom payouts
    val none = service.calculateGolferPayout(Some(4), results, BigDecimal(100), x1, custom)
    assertEquals(none, None)
  }

  // ================================================================
  // zeroSumWeekly
  // ================================================================

  test("zeroSumWeekly: two teams, sum is always zero") {
    val result = service.zeroSumWeekly(List((teamId(1), BigDecimal(18)), (teamId(2), BigDecimal(0))))
    val weeklyMap = result.toMap
    assertEquals(weeklyMap(teamId(1)), BigDecimal(18))
    assertEquals(weeklyMap(teamId(2)), BigDecimal(-18))
    assertEquals(result.map(_._2).sum, BigDecimal(0))
  }

  test("zeroSumWeekly: three teams") {
    val result = service
      .zeroSumWeekly(List((teamId(1), BigDecimal(18)), (teamId(2), BigDecimal(12)), (teamId(3), BigDecimal(0))))
    val weeklyMap = result.toMap
    assertEquals(weeklyMap(teamId(1)), BigDecimal(24))
    assertEquals(weeklyMap(teamId(2)), BigDecimal(6))
    assertEquals(weeklyMap(teamId(3)), BigDecimal(-30))
    assertEquals(result.map(_._2).sum, BigDecimal(0))
  }

  test("zeroSumWeekly: all teams earn same amount") {
    val result = service
      .zeroSumWeekly(List((teamId(1), BigDecimal(10)), (teamId(2), BigDecimal(10)), (teamId(3), BigDecimal(10))))
    assert(result.forall(_._2 == BigDecimal(0)))
  }

  test("zeroSumWeekly: all teams earn zero") {
    val result = service.zeroSumWeekly(List((teamId(1), BigDecimal(0)), (teamId(2), BigDecimal(0))))
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

  test("sideBetPnl: clear winner gets +$15*(N-1)") {
    val earnings = Map(teamId(1) -> BigDecimal(50), teamId(2) -> BigDecimal(30), teamId(3) -> BigDecimal(10))
    val result = service.sideBetPnl(earnings)
    assertEquals(result(teamId(1)), BigDecimal(30))
    assertEquals(result(teamId(2)), BigDecimal(-15))
    assertEquals(result(teamId(3)), BigDecimal(-15))
    assertEquals(result.values.sum, BigDecimal(0))
  }

  test("sideBetPnl: tied winners split the pot") {
    val earnings = Map(teamId(1) -> BigDecimal(50), teamId(2) -> BigDecimal(50), teamId(3) -> BigDecimal(10))
    val result = service.sideBetPnl(earnings)
    assertEquals(result(teamId(1)), BigDecimal("7.5"))
    assertEquals(result(teamId(2)), BigDecimal("7.5"))
    assertEquals(result(teamId(3)), BigDecimal(-15))
    assertEquals(result.values.sum, BigDecimal(0))
  }

  test("sideBetPnl: all tied -> all get zero") {
    val earnings = Map(teamId(1) -> BigDecimal(20), teamId(2) -> BigDecimal(20))
    val result = service.sideBetPnl(earnings)
    assertEquals(result(teamId(1)), BigDecimal(0))
    assertEquals(result(teamId(2)), BigDecimal(0))
  }

  test("sideBetPnl: all zero earnings -> all get zero") {
    val earnings = Map(teamId(1) -> BigDecimal(0), teamId(2) -> BigDecimal(0))
    val result = service.sideBetPnl(earnings)
    assert(result.values.forall(_ == BigDecimal(0)))
  }

  test("sideBetPnl: empty map -> empty result") {
    val result = service.sideBetPnl(Map.empty)
    assert(result.isEmpty)
  }

  test("sideBetPnl: custom bet amount") {
    val earnings = Map(teamId(1) -> BigDecimal(50), teamId(2) -> BigDecimal(10))
    val result = service.sideBetPnl(earnings, sideBetAmount = BigDecimal(20))
    assertEquals(result(teamId(1)), BigDecimal(20))
    assertEquals(result(teamId(2)), BigDecimal(-20))
    assertEquals(result.values.sum, BigDecimal(0))
  }

  test("sideBetPnl: zero-sum with 13 teams") {
    val earnings = (1 to 13).map(i => teamId(i) -> BigDecimal(i)).toMap
    val result = service.sideBetPnl(earnings)
    assertEquals(result(teamId(13)), BigDecimal(180))
    (1 to 12).foreach(i => assertEquals(result(teamId(i)), BigDecimal(-15)))
    assertEquals(result.values.sum, BigDecimal(0))
  }
