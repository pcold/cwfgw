package com.cwfgw.espn

import munit.FunSuite

class EspnModelsTest extends FunSuite:

  private def competitor(status: String): EspnCompetitor = EspnCompetitor(
    espnId = "1",
    name = "Test Player",
    order = 1,
    scoreStr = Some("-5"),
    scoreToPar = Some(-5),
    totalStrokes = Some(275),
    roundScores = List(68, 69, 70, 68),
    position = 1,
    status = status
  )

  test("madeCut returns true for status '1' (active)") { assert(competitor("1").madeCut) }

  test("madeCut returns false for status '2' (cut)") { assert(!competitor("2").madeCut) }

  test("madeCut returns false for status '3' (withdrawn)") { assert(!competitor("3").madeCut) }

  test("madeCut returns false for status '4' (disqualified)") { assert(!competitor("4").madeCut) }

  test("madeCut returns true for default status") {
    val c = competitor("1").copy(status = "1")
    assert(c.madeCut)
  }

  test("madeCut returns true for unknown status codes") {
    // Any status that isn't 2, 3, or 4 means the player is still in
    assert(competitor("5").madeCut)
    assert(competitor("0").madeCut)
  }
