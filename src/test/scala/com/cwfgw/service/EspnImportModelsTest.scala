package com.cwfgw.service

import munit.FunSuite

import java.util.UUID

/**
 * Tests for the data model case classes defined in EspnImportService.
 * Verifies construction, defaults, and copy semantics for the preview models.
 */
class EspnImportModelsTest extends FunSuite:

  private val teamId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val golferId = UUID.fromString("00000000-0000-0000-0000-000000000011")
  private val tournamentId = UUID.fromString("00000000-0000-0000-0000-000000000099")

  // ---- EspnImportResult ----

  test("EspnImportResult stores all fields") {
    val result = EspnImportResult(
      tournamentId = tournamentId,
      espnName = "Masters Tournament",
      espnId = "401580123",
      completed = true,
      totalCompetitors = 87,
      matched = 85,
      unmatched = List("Unknown Player", "New Golfer"),
      created = 2
    )
    assertEquals(result.tournamentId, tournamentId)
    assertEquals(result.espnName, "Masters Tournament")
    assertEquals(result.espnId, "401580123")
    assertEquals(result.completed, true)
    assertEquals(result.totalCompetitors, 87)
    assertEquals(result.matched, 85)
    assertEquals(result.unmatched, List("Unknown Player", "New Golfer"))
    assertEquals(result.created, 2)
  }

  test("EspnImportResult unmatched can be empty") {
    val result = EspnImportResult(tournamentId, "Event", "1", true, 50, 50, Nil, 0)
    assert(result.unmatched.isEmpty)
    assertEquals(result.matched, result.totalCompetitors)
  }

  // ---- PreviewTeamScore ----

  test("PreviewTeamScore weeklyTotal defaults to zero") {
    val team = PreviewTeamScore(teamId, "Team Alpha", "Alice", BigDecimal(18), Nil)
    assertEquals(team.weeklyTotal, BigDecimal(0))
  }

  test("PreviewTeamScore.copy updates weeklyTotal") {
    val team = PreviewTeamScore(teamId, "Team Alpha", "Alice", BigDecimal(18), Nil)
    val updated = team.copy(weeklyTotal = BigDecimal(36))
    assertEquals(updated.weeklyTotal, BigDecimal(36))
    assertEquals(updated.topTenEarnings, BigDecimal(18)) // unchanged
  }

  // ---- PreviewGolferScore ----

  test("PreviewGolferScore stores payout fields correctly") {
    val gs = PreviewGolferScore(
      golferName = "Scottie Scheffler",
      golferId = golferId,
      position = 1,
      numTied = 1,
      scoreToPar = Some(-15),
      basePayout = BigDecimal(18),
      ownershipPct = BigDecimal(50),
      payout = BigDecimal(9)
    )
    assertEquals(gs.golferName, "Scottie Scheffler")
    assertEquals(gs.position, 1)
    assertEquals(gs.basePayout, BigDecimal(18))
    assertEquals(gs.ownershipPct, BigDecimal(50))
    assertEquals(gs.payout, BigDecimal(9))
  }

  test("PreviewGolferScore scoreToPar can be None for in-progress") {
    val gs = PreviewGolferScore("Tiger Woods", golferId, 5, 1, None, BigDecimal(7), BigDecimal(100), BigDecimal(7))
    assert(gs.scoreToPar.isEmpty)
  }

  // ---- PreviewLeaderboardEntry ----

  test("PreviewLeaderboardEntry rostered=true includes team name") {
    val entry = PreviewLeaderboardEntry(
      name = "Scottie Scheffler",
      position = 1,
      scoreToPar = Some(-15),
      thru = Some("4 rounds"),
      rostered = true,
      teamName = Some("Team Alpha")
    )
    assert(entry.rostered)
    assertEquals(entry.teamName, Some("Team Alpha"))
  }

  test("PreviewLeaderboardEntry rostered=false has no team name") {
    val entry = PreviewLeaderboardEntry(
      name = "Undrafted Player",
      position = 3,
      scoreToPar = Some(-10),
      thru = Some("3 rounds"),
      rostered = false,
      teamName = None
    )
    assert(!entry.rostered)
    assert(entry.teamName.isEmpty)
  }

  // ---- EspnLivePreview ----

  test("EspnLivePreview aggregates teams and leaderboard") {
    val teamScore = PreviewTeamScore(teamId, "Team Alpha", "Alice", BigDecimal(18),
      List(PreviewGolferScore("Scheffler", golferId, 1, 1, Some(-15), BigDecimal(18), BigDecimal(100), BigDecimal(18))),
      weeklyTotal = BigDecimal(36))
    val lbEntry = PreviewLeaderboardEntry("Scheffler", 1, Some(-15), Some("4 rounds"), true, Some("Team Alpha"))
    val preview = EspnLivePreview(
      espnName = "The Players",
      espnId = "401580123",
      completed = false,
      isMajor = false,
      totalCompetitors = 144,
      teams = List(teamScore),
      leaderboard = List(lbEntry)
    )
    assertEquals(preview.teams.size, 1)
    assertEquals(preview.leaderboard.size, 1)
    assertEquals(preview.totalCompetitors, 144)
    assert(!preview.completed)
  }

  // ---- ImportedPlayer ----

  test("ImportedPlayer tracks match and create status") {
    val matched = ImportedPlayer("Tiger Woods", 5, matched = true, created = false)
    val created = ImportedPlayer("New Golfer", 50, matched = true, created = true)
    val unmatched = ImportedPlayer("Unknown", 99, matched = false, created = false)

    assert(matched.matched && !matched.created)
    assert(created.matched && created.created)
    assert(!unmatched.matched && !unmatched.created)
  }
