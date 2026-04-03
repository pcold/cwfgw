package com.cwfgw.service

import munit.FunSuite
import java.util.UUID

class TeamServiceTest extends FunSuite:

  private val service = new TeamService(null)

  private def teamId(n: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-0000000000$n%02d")
  private def golferId(n: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-000000000f$n%02d")

  private type RosterRow = (UUID, String, Int, String, String, BigDecimal, UUID)

  private def mkRow(
    teamId: UUID,
    teamName: String,
    round: Int,
    firstName: String,
    lastName: String,
    ownershipPct: BigDecimal,
    golferId: UUID
  ): RosterRow = (teamId, teamName, round, firstName, lastName, ownershipPct, golferId)

  // ================================================================
  // buildRosterView — basic grouping
  // ================================================================

  test("buildRosterView: groups rows by team") {
    val rows = List(
      mkRow(teamId(1), "Team A", 1, "Tiger", "Woods", BigDecimal(100), golferId(1)),
      mkRow(teamId(1), "Team A", 2, "Rory", "McIlroy", BigDecimal(100), golferId(2)),
      mkRow(teamId(2), "Team B", 1, "Jon", "Rahm", BigDecimal(100), golferId(3))
    )
    val result = service.buildRosterView(rows)
    assertEquals(result.size, 2)
    assertEquals(result(0).teamName, "Team A")
    assertEquals(result(0).picks.size, 2)
    assertEquals(result(1).teamName, "Team B")
    assertEquals(result(1).picks.size, 1)
  }

  test("buildRosterView: preserves team order from rows") {
    val rows = List(
      mkRow(teamId(2), "Team B", 1, "Jon", "Rahm", BigDecimal(100), golferId(3)),
      mkRow(teamId(1), "Team A", 1, "Tiger", "Woods", BigDecimal(100), golferId(1))
    )
    val result = service.buildRosterView(rows)
    assertEquals(result(0).teamName, "Team B")
    assertEquals(result(1).teamName, "Team A")
  }

  // ================================================================
  // buildRosterView — golfer name formatting
  // ================================================================

  test("buildRosterView: formats full name with first and last") {
    val rows = List(mkRow(teamId(1), "Team A", 1, "Tiger", "Woods", BigDecimal(100), golferId(1)))
    val result = service.buildRosterView(rows)
    assertEquals(result.head.picks.head.golferName, "Tiger Woods")
  }

  test("buildRosterView: uses last name only when first is empty") {
    val rows = List(mkRow(teamId(1), "Team A", 1, "", "Woods", BigDecimal(100), golferId(1)))
    val result = service.buildRosterView(rows)
    assertEquals(result.head.picks.head.golferName, "Woods")
  }

  // ================================================================
  // buildRosterView — pick fields
  // ================================================================

  test("buildRosterView: maps round, ownershipPct, and golferId") {
    val gid = golferId(1)
    val rows = List(mkRow(teamId(1), "Team A", 3, "Tiger", "Woods", BigDecimal(75), gid))
    val result = service.buildRosterView(rows)
    val pick = result.head.picks.head
    assertEquals(pick.round, 3)
    assertEquals(pick.ownershipPct, BigDecimal(75))
    assertEquals(pick.golferId, gid)
  }

  // ================================================================
  // buildRosterView — edge cases
  // ================================================================

  test("buildRosterView: empty rows returns empty list") {
    assertEquals(service.buildRosterView(Nil), Nil)
  }

  test("buildRosterView: single team single pick") {
    val rows = List(mkRow(teamId(1), "Solo", 1, "Adam", "Scott", BigDecimal(50), golferId(1)))
    val result = service.buildRosterView(rows)
    assertEquals(result.size, 1)
    assertEquals(result.head.teamId, teamId(1))
    assertEquals(result.head.picks.size, 1)
  }

  test("buildRosterView: multiple teams with multiple picks each") {
    val rows = List(
      mkRow(teamId(1), "A", 1, "Tiger", "Woods", BigDecimal(100), golferId(1)),
      mkRow(teamId(1), "A", 2, "Rory", "McIlroy", BigDecimal(100), golferId(2)),
      mkRow(teamId(1), "A", 3, "Jon", "Rahm", BigDecimal(100), golferId(3)),
      mkRow(teamId(2), "B", 1, "Scottie", "Scheffler", BigDecimal(100), golferId(4)),
      mkRow(teamId(2), "B", 2, "Xander", "Schauffele", BigDecimal(100), golferId(5)),
      mkRow(teamId(3), "C", 1, "Collin", "Morikawa", BigDecimal(100), golferId(6))
    )
    val result = service.buildRosterView(rows)
    assertEquals(result.size, 3)
    assertEquals(result(0).picks.size, 3)
    assertEquals(result(1).picks.size, 2)
    assertEquals(result(2).picks.size, 1)
  }
