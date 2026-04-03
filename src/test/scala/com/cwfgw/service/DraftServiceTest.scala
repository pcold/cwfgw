package com.cwfgw.service

import munit.FunSuite
import java.util.UUID

class DraftServiceTest extends FunSuite:

  private val service = new DraftService(null)
  private val draftId = UUID.fromString("00000000-0000-0000-0000-000000000099")

  private def teamId(n: Int): UUID = UUID.fromString(s"00000000-0000-0000-0000-00000000000$n")

  private val team1 = teamId(1)
  private val team2 = teamId(2)
  private val team3 = teamId(3)

  // ================================================================
  // snakeDraftOrder — basic structure
  // ================================================================

  test("snakeDraftOrder generates correct number of picks") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 8, draftId)
    assertEquals(picks.size, 24) // 3 teams * 8 rounds
  }

  test("snakeDraftOrder with 2 teams and 2 rounds") {
    val picks = service.snakeDraftOrder(List(team1, team2), 2, draftId)
    assertEquals(picks.size, 4)
  }

  test("snakeDraftOrder pick numbers are sequential 1..N") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 4, draftId)
    val pickNums = picks.map(_._4)
    assertEquals(pickNums, (1 to 12).toList)
  }

  test("snakeDraftOrder all picks reference the correct draftId") {
    val picks = service.snakeDraftOrder(List(team1, team2), 3, draftId)
    assert(picks.forall(_._1 == draftId))
  }

  // ================================================================
  // snakeDraftOrder — snake ordering
  // ================================================================

  test("round 1 (odd) goes in original team order") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 2, draftId)
    val round1 = picks.filter(_._3 == 1).map(_._2)
    assertEquals(round1, List(team1, team2, team3))
  }

  test("round 2 (even) goes in reversed team order") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 2, draftId)
    val round2 = picks.filter(_._3 == 2).map(_._2)
    assertEquals(round2, List(team3, team2, team1))
  }

  test("round 3 (odd) goes back to original order") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 3, draftId)
    val round3 = picks.filter(_._3 == 3).map(_._2)
    assertEquals(round3, List(team1, team2, team3))
  }

  test("round 4 (even) reverses again") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 4, draftId)
    val round4 = picks.filter(_._3 == 4).map(_._2)
    assertEquals(round4, List(team3, team2, team1))
  }

  // ================================================================
  // snakeDraftOrder — full 8-round snake with 3 teams
  // ================================================================

  test("full snake draft: first team picks first in odd rounds, last in even") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 8, draftId)
    // team1 should pick at positions: 1, 6, 7, 12, 13, 18, 19, 24
    val team1Picks = picks.filter(_._2 == team1).map(_._4)
    assertEquals(team1Picks, List(1, 6, 7, 12, 13, 18, 19, 24))
  }

  test("full snake draft: middle team picks in middle every round") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 4, draftId)
    val team2Picks = picks.filter(_._2 == team2).map(_._4)
    assertEquals(team2Picks, List(2, 5, 8, 11))
  }

  test("full snake draft: last team picks last in odd rounds, first in even") {
    val picks = service.snakeDraftOrder(List(team1, team2, team3), 4, draftId)
    val team3Picks = picks.filter(_._2 == team3).map(_._4)
    assertEquals(team3Picks, List(3, 4, 9, 10))
  }

  // ================================================================
  // snakeDraftOrder — edge cases
  // ================================================================

  test("single team gets all picks in order") {
    val picks = service.snakeDraftOrder(List(team1), 3, draftId)
    assertEquals(picks.size, 3)
    assert(picks.forall(_._2 == team1))
    assertEquals(picks.map(_._3), List(1, 2, 3))
    assertEquals(picks.map(_._4), List(1, 2, 3))
  }

  test("zero rounds produces empty list") {
    val picks = service.snakeDraftOrder(List(team1, team2), 0, draftId)
    assertEquals(picks, Nil)
  }

  test("each team gets exactly N picks for N rounds") {
    val teams = List(team1, team2, team3)
    val picks = service.snakeDraftOrder(teams, 6, draftId)
    teams.foreach: tid =>
      val count = picks.count(_._2 == tid)
      assertEquals(count, 6, s"Team $tid should have exactly 6 picks")
  }

  test("round numbers are correct for each pick") {
    val picks = service.snakeDraftOrder(List(team1, team2), 3, draftId)
    assertEquals(picks.map(_._3), List(1, 1, 2, 2, 3, 3))
  }
