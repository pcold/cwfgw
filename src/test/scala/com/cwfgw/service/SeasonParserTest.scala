package com.cwfgw.service

import munit.FunSuite
import java.time.LocalDate

class SeasonParserTest extends FunSuite:

  // ---------- Basic single-line parsing ----------

  test("parse a simple tournament line") {
    val input = "1 1 Jan 15-18 Sony Open"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val tournaments = result.toOption.get
    assertEquals(tournaments.size, 1)
    val t = tournaments.head
    assertEquals(t.week, "1")
    assertEquals(t.eventNumber, 1)
    assertEquals(t.startDate, LocalDate.of(2026, 1, 15))
    assertEquals(t.endDate, LocalDate.of(2026, 1, 18))
    assertEquals(t.name, "Sony Open")
    assertEquals(t.payoutMultiplier, BigDecimal(1))
    assertEquals(t.isSignature, false)
    assertEquals(t.notes, None)
  }

  test("parse a multi-word tournament name") {
    val input = "5 5 Feb 12-15 AT&T Pebble Beach Pro-Am"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    assertEquals(result.toOption.get.head.name, "AT&T Pebble Beach Pro-Am")
  }

  // ---------- Month variants ----------

  test("parse full month name") {
    val input = "3 3 February 5-8 Waste Management Phoenix Open"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.startDate, LocalDate.of(2026, 2, 5))
    assertEquals(t.endDate, LocalDate.of(2026, 2, 8))
  }

  test("parse abbreviated month") {
    val input = "10 10 Mar 12-15 The Players Championship"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    assertEquals(result.toOption.get.head.startDate.getMonthValue, 3)
  }

  // ---------- Cross-month dates ----------

  test("parse cross-month date range like 29-Feb1") {
    val input = "4 4 Jan 29-Feb1 Farmers Insurance Open"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.startDate, LocalDate.of(2026, 1, 29))
    assertEquals(t.endDate, LocalDate.of(2026, 2, 1))
  }

  test("parse cross-month with full month name") {
    val input = "8 8 Nov 30-Dec3 Hero World Challenge"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.startDate, LocalDate.of(2026, 11, 30))
    assertEquals(t.endDate, LocalDate.of(2026, 12, 3))
  }

  // ---------- Same-month rollover ----------

  test("end day smaller than start day implies next month") {
    val input = "6 6 March 28-1 Valero Texas Open"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.startDate, LocalDate.of(2026, 3, 28))
    assertEquals(t.endDate, LocalDate.of(2026, 4, 1))
  }

  // ---------- Multiplier (Nx) ----------

  test("2x marks a 2x multiplier tournament") {
    val input = "15 15 April 9-12 The Masters 2x"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.payoutMultiplier, BigDecimal(2))
    assertEquals(t.name, "The Masters")
    assert(t.notes.exists(_.contains("2x")))
  }

  test("1.5x marks a 1.5x multiplier tournament") {
    val input = "8a 8 March 5-8 Arnold Palmer Invitational 1.5x"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.payoutMultiplier, BigDecimal("1.5"))
    assertEquals(t.name, "Arnold Palmer Invitational")
  }

  test("no multiplier suffix defaults to 1x") {
    val input = "1 1 Jan 15-18 Sony Open"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    assertEquals(result.toOption.get.head.payoutMultiplier, BigDecimal(1))
  }

  test("legacy Double $$ still sets 2x multiplier") {
    val input = "15 15 April 9-12 The Masters Double $$"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.payoutMultiplier, BigDecimal(2))
    assertEquals(t.name, "The Masters")
  }

  // ---------- Signature events ----------

  test("*** marks a signature event") {
    val input = "8a 8 March 5-8 Arnold Palmer Invitational***"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.isSignature, true)
    assertEquals(t.name, "Arnold Palmer Invitational")
  }

  // ---------- Multi-event week ----------

  test("week can be alphanumeric like 8a") {
    val input = "8a 8 March 5-8 Arnold Palmer Invitational*** 2 event week"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.week, "8a")
    assertEquals(t.isSignature, true)
    assert(t.notes.exists(_.contains("2 event week")))
  }

  // ---------- Multiple lines ----------

  test("parse multiple tournament lines") {
    val input =
      """1 1 Jan 15-18 Sony Open
        |2 2 Jan 22-25 American Express
        |3 3 Jan 29-Feb1 Farmers Insurance Open""".stripMargin
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    assertEquals(result.toOption.get.size, 3)
    assertEquals(result.toOption.get(2).name, "Farmers Insurance Open")
  }

  test("blank lines are skipped") {
    val input =
      """1 1 Jan 15-18 Sony Open
        |
        |2 2 Jan 22-25 American Express""".stripMargin
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    assertEquals(result.toOption.get.size, 2)
  }

  // ---------- Combined markers ----------

  test("signature event with multiplier and 2-event week") {
    val input = "9 10 March 12-15 The Players Championship*** 2x"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isRight)
    val t = result.toOption.get.head
    assertEquals(t.payoutMultiplier, BigDecimal(2))
    assertEquals(t.isSignature, true)
    assertEquals(t.name, "The Players Championship")
  }

  // ---------- Error cases ----------

  test("invalid line format returns Left") {
    val input = "garbage data"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isLeft)
  }

  test("invalid month returns Left") {
    val input = "1 1 Smarch 15-18 Springfield Open"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Unknown month"))
  }

  test("invalid date range returns Left") {
    val input = "1 1 Jan abc Tournament Name"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isLeft)
  }

  test("error message includes line number") {
    val input =
      """1 1 Jan 15-18 Sony Open
        |2 2 Smarch 22-25 Bad Month Open""".stripMargin
    val result = SeasonParser.parse(input, 2026)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Line 2"))
  }

  test("missing event number returns Left") {
    val input = "1 Jan 15-18 Missing EventNum"
    val result = SeasonParser.parse(input, 2026)
    assert(result.isLeft)
  }
