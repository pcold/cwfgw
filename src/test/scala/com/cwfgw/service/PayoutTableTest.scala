package com.cwfgw.service

import munit.FunSuite
import com.cwfgw.domain.SeasonRules

class PayoutTableTest extends FunSuite:

  private val rules = SeasonRules.default
  private val x1 = BigDecimal(1)
  private val x2 = BigDecimal(2)

  // ---------- Normal (non-tied) positions ----------

  test("1st place pays $18") {
    assertEquals(PayoutTable.tieSplitPayout(1, 1, x1, rules), BigDecimal(18))
  }

  test("2nd place pays $12") {
    assertEquals(PayoutTable.tieSplitPayout(2, 1, x1, rules), BigDecimal(12))
  }

  test("5th place pays $7") {
    assertEquals(PayoutTable.tieSplitPayout(5, 1, x1, rules), BigDecimal(7))
  }

  test("10th place pays $2") {
    assertEquals(PayoutTable.tieSplitPayout(10, 1, x1, rules), BigDecimal(2))
  }

  // ---------- Outside top 10 ----------

  test("11th place pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(11, 1, x1, rules), BigDecimal(0))
  }

  test("50th place pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(50, 1, x1, rules), BigDecimal(0))
  }

  test("position outside top 10 with ties still pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(11, 3, x1, rules), BigDecimal(0))
  }

  // ---------- Simple ties ----------

  test("T4 with 3 tied averages positions 4,5,6 = ($8+$7+$6)/3") {
    assertEquals(PayoutTable.tieSplitPayout(4, 3, x1, rules), BigDecimal(7))
  }

  test("T1 with 2 tied averages positions 1,2 = ($18+$12)/2 = $15") {
    assertEquals(PayoutTable.tieSplitPayout(1, 2, x1, rules), BigDecimal(15))
  }

  test("T9 with 2 tied averages positions 9,10 = ($3+$2)/2 = $2.5") {
    assertEquals(PayoutTable.tieSplitPayout(9, 2, x1, rules), BigDecimal("2.5"))
  }

  // ---------- Ties spanning past position 10 ----------

  test("T9 with 5 tied: only positions 9,10 have payouts, average = ($3+$2)/5 = $1 (hits floor)") {
    assertEquals(PayoutTable.tieSplitPayout(9, 5, x1, rules), BigDecimal(1))
  }

  test("T10 with 3 tied: only position 10 has payout, average = $2/3 rounds up to $1 floor") {
    assertEquals(PayoutTable.tieSplitPayout(10, 3, x1, rules), BigDecimal(1))
  }

  test("T8 with 10 tied: positions 8,9,10 have payouts, average = ($4+$3+$2)/10 = $0.9, floor => $1") {
    assertEquals(PayoutTable.tieSplitPayout(8, 10, x1, rules), BigDecimal(1))
  }

  // ---------- 2x multiplier (majors) ----------

  test("1st place at 2x pays $36") {
    assertEquals(PayoutTable.tieSplitPayout(1, 1, x2, rules), BigDecimal(36))
  }

  test("10th place at 2x pays $4") {
    assertEquals(PayoutTable.tieSplitPayout(10, 1, x2, rules), BigDecimal(4))
  }

  test("T4 with 3 tied at 2x = $7 * 2 = $14") {
    assertEquals(PayoutTable.tieSplitPayout(4, 3, x2, rules), BigDecimal(14))
  }

  test("T10 with 3 tied at 2x: floor $1 * 2 = $2") {
    assertEquals(PayoutTable.tieSplitPayout(10, 3, x2, rules), BigDecimal(2))
  }

  test("outside top 10 at 2x still pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(11, 1, x2, rules), BigDecimal(0))
  }

  // ---------- 1.5x multiplier ----------

  test("1st place at 1.5x pays $27") {
    assertEquals(PayoutTable.tieSplitPayout(1, 1, BigDecimal("1.5"), rules), BigDecimal(27))
  }

  // ---------- Custom rules ----------

  test("custom payouts with 5 places") {
    val custom = SeasonRules(
      payouts = List(10, 8, 6, 4, 2).map(BigDecimal(_)),
      tieFloor = BigDecimal("0.5"),
      sideBetRounds = Nil,
      sideBetAmount = BigDecimal(0)
    )
    assertEquals(PayoutTable.tieSplitPayout(1, 1, x1, custom), BigDecimal(10))
    assertEquals(PayoutTable.tieSplitPayout(5, 1, x1, custom), BigDecimal(2))
    assertEquals(PayoutTable.tieSplitPayout(6, 1, x1, custom), BigDecimal(0))
  }

  test("custom tie floor applied") {
    val custom = SeasonRules(
      payouts = List(10, 5).map(BigDecimal(_)),
      tieFloor = BigDecimal(2),
      sideBetRounds = Nil,
      sideBetAmount = BigDecimal(0)
    )
    // T2 with 3 tied: only position 2 pays $5, avg = 5/3 ≈ 1.67, floor = $2
    assertEquals(PayoutTable.tieSplitPayout(2, 3, x1, custom), BigDecimal(2))
  }

  // ---------- Edge: numTied <= 0 treated as solo ----------

  test("numTied=0 treated same as solo (position 1)") {
    assertEquals(PayoutTable.tieSplitPayout(1, 0, x1, rules), BigDecimal(18))
  }
