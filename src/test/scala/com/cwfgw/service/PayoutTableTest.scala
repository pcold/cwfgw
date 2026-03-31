package com.cwfgw.service

import munit.FunSuite

class PayoutTableTest extends FunSuite:

  // ---------- Normal (non-tied) positions ----------

  test("1st place pays $18") {
    assertEquals(PayoutTable.tieSplitPayout(1, 1, isMajor = false), BigDecimal(18))
  }

  test("2nd place pays $12") {
    assertEquals(PayoutTable.tieSplitPayout(2, 1, isMajor = false), BigDecimal(12))
  }

  test("5th place pays $7") {
    assertEquals(PayoutTable.tieSplitPayout(5, 1, isMajor = false), BigDecimal(7))
  }

  test("10th place pays $2") {
    assertEquals(PayoutTable.tieSplitPayout(10, 1, isMajor = false), BigDecimal(2))
  }

  // ---------- Outside top 10 ----------

  test("11th place pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(11, 1, isMajor = false), BigDecimal(0))
  }

  test("50th place pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(50, 1, isMajor = false), BigDecimal(0))
  }

  test("position outside top 10 with ties still pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(11, 3, isMajor = false), BigDecimal(0))
  }

  // ---------- Simple ties ----------

  test("T4 with 3 tied averages positions 4,5,6 = ($8+$7+$6)/3") {
    // (8+7+6)/3 = 21/3 = 7
    assertEquals(PayoutTable.tieSplitPayout(4, 3, isMajor = false), BigDecimal(7))
  }

  test("T1 with 2 tied averages positions 1,2 = ($18+$12)/2 = $15") {
    assertEquals(PayoutTable.tieSplitPayout(1, 2, isMajor = false), BigDecimal(15))
  }

  test("T9 with 2 tied averages positions 9,10 = ($3+$2)/2 = $2.5") {
    assertEquals(PayoutTable.tieSplitPayout(9, 2, isMajor = false), BigDecimal("2.5"))
  }

  // ---------- Ties spanning past position 10 ----------

  test("T9 with 5 tied: only positions 9,10 have payouts, average = ($3+$2)/5 = $1 (hits floor)") {
    // (3+2)/5 = 1.0, which equals the floor
    assertEquals(PayoutTable.tieSplitPayout(9, 5, isMajor = false), BigDecimal(1))
  }

  test("T10 with 3 tied: only position 10 has payout, average = $2/3 rounds up to $1 floor") {
    // 2/3 ~ 0.667, floor kicks in => $1
    assertEquals(PayoutTable.tieSplitPayout(10, 3, isMajor = false), BigDecimal(1))
  }

  test("T8 with 10 tied: positions 8,9,10 have payouts, average = ($4+$3+$2)/10 = $0.9, floor => $1") {
    assertEquals(PayoutTable.tieSplitPayout(8, 10, isMajor = false), BigDecimal(1))
  }

  // ---------- Major multiplier (2x) ----------

  test("1st place at a major pays $36 (2x $18)") {
    assertEquals(PayoutTable.tieSplitPayout(1, 1, isMajor = true), BigDecimal(36))
  }

  test("10th place at a major pays $4 (2x $2)") {
    assertEquals(PayoutTable.tieSplitPayout(10, 1, isMajor = true), BigDecimal(4))
  }

  test("T4 with 3 tied at a major = $7 * 2 = $14") {
    assertEquals(PayoutTable.tieSplitPayout(4, 3, isMajor = true), BigDecimal(14))
  }

  test("T10 with 3 tied at a major: floor $1 * 2 = $2") {
    assertEquals(PayoutTable.tieSplitPayout(10, 3, isMajor = true), BigDecimal(2))
  }

  test("outside top 10 at a major still pays $0") {
    assertEquals(PayoutTable.tieSplitPayout(11, 1, isMajor = true), BigDecimal(0))
  }

  // ---------- Edge: numTied <= 0 treated as solo ----------

  test("numTied=0 treated same as solo (position 1)") {
    assertEquals(PayoutTable.tieSplitPayout(1, 0, isMajor = false), BigDecimal(18))
  }
