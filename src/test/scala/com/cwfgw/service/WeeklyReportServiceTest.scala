package com.cwfgw.service

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import java.util.UUID

/**
 * Tests for WeeklyReportService.mergeLiveData — a pure JSON -> JSON
 * transformation that overlays ESPN live data onto a base report.
 *
 * We construct minimal base-report JSON and EspnLivePreview objects, then
 * verify the merged output has correct weekly totals, subtotals, and rankings.
 */
class WeeklyReportServiceTest extends FunSuite:

  // We only need a WeeklyReportService instance to call mergeLiveData.
  // The constructor needs an EspnImportService and Transactor, but mergeLiveData
  // is a pure method that doesn't touch them — so we pass nulls.
  private val service = new WeeklyReportService(
    espnImportService = null,
    xa = null
  )

  // ---- Helpers ----

  private val teamAId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val teamBId = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val golfer1Id = UUID.fromString("00000000-0000-0000-0000-000000000011")
  private val golfer2Id = UUID.fromString("00000000-0000-0000-0000-000000000012")
  private val golfer3Id = UUID.fromString("00000000-0000-0000-0000-000000000013")
  private val golfer4Id = UUID.fromString("00000000-0000-0000-0000-000000000014")

  private def baseRow(round: Int, golferId: UUID, golferName: String): Json =
    Json.obj(
      "round" -> round.asJson,
      "golfer_name" -> golferName.asJson,
      "golfer_id" -> golferId.toString.asJson,
      "position_str" -> Json.Null,
      "score_to_par" -> Json.Null,
      "earnings" -> BigDecimal(0).asJson,
      "top_tens" -> 0.asJson,
      "ownership_pct" -> BigDecimal(100).asJson
    )

  private def teamJson(
      teamId: UUID,
      teamName: String,
      ownerName: String,
      rows: List[Json],
      previous: BigDecimal = BigDecimal(0),
      sideBets: BigDecimal = BigDecimal(0),
      topTenCount: Int = 0
  ): Json =
    val topTens = rows.foldLeft(BigDecimal(0))((acc, r) =>
      acc + r.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(0)))
    Json.obj(
      "team_id" -> teamId.toString.asJson,
      "team_name" -> teamName.asJson,
      "owner_name" -> ownerName.asJson,
      "rows" -> rows.asJson,
      "top_tens" -> topTens.asJson,
      "weekly_total" -> BigDecimal(0).asJson,
      "previous" -> previous.asJson,
      "subtotal" -> previous.asJson,
      "top_ten_count" -> topTenCount.asJson,
      "side_bets" -> sideBets.asJson,
      "total_cash" -> (previous + sideBets).asJson
    )

  private def baseReport(teams: List[Json]): Json =
    Json.obj(
      "tournament" -> Json.obj(
        "id" -> Json.Null,
        "name" -> "Test Open".asJson,
        "start_date" -> "2026-03-26".asJson,
        "end_date" -> "2026-03-29".asJson,
        "status" -> "in_progress".asJson,
        "is_major" -> false.asJson
      ),
      "teams" -> teams.asJson,
      "undrafted_top_tens" -> Json.arr(),
      "standings_order" -> Json.arr()
    )

  private def golferScore(
      golferName: String,
      golferId: UUID,
      position: Int,
      numTied: Int,
      scoreToPar: Option[Int],
      basePayout: BigDecimal,
      ownershipPct: BigDecimal,
      payout: BigDecimal
  ): PreviewGolferScore =
    PreviewGolferScore(golferName, golferId, position, numTied, scoreToPar, basePayout, ownershipPct, payout)

  // ---- Tests ----

  test("mergeLiveData returns report unchanged when previews list is empty") {
    val rows = List(baseRow(1, golfer1Id, "SCHEFFLER"))
    val report = baseReport(List(teamJson(teamAId, "Team A", "Alice", rows)))
    val result = service.mergeLiveData(report, Nil)
    assertEquals(result, report)
  }

  test("mergeLiveData overlays earnings for matched golfers") {
    val rows = List(baseRow(1, golfer1Id, "SCHEFFLER"))
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", rows),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")))
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(18),
          List(golferScore("Scottie Scheffler", golfer1Id, 1, 1, Some(-10), BigDecimal(18), BigDecimal(100), BigDecimal(18)))),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(0), Nil)
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val resultTeams = result.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)

    // Team A's golfer should have earnings = 18
    val teamARows = resultTeams.head.hcursor.downField("rows").as[List[Json]].getOrElse(Nil)
    val earnings = teamARows.head.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(-1))
    assertEquals(earnings, BigDecimal(18))

    // Report should be marked as live
    assert(result.hcursor.downField("live").as[Boolean].getOrElse(false))
  }

  test("mergeLiveData computes zero-sum weekly totals correctly") {
    // Two teams, one earns $18 (1st place), other earns $0
    // totalPot = $18, numTeams = 2
    // Team A weekly: $18 * 2 - $18 = $18
    // Team B weekly: $0 * 2 - $18 = -$18
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", List(baseRow(1, golfer1Id, "SCHEFFLER"))),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")))
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open", espnId = "123", completed = false, isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(18),
          List(golferScore("Scottie Scheffler", golfer1Id, 1, 1, Some(-10), BigDecimal(18), BigDecimal(100), BigDecimal(18)))),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(0), Nil)
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val resultTeams = result.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)
    val weeklyA = resultTeams(0).hcursor.downField("weekly_total").as[BigDecimal].getOrElse(BigDecimal(-999))
    val weeklyB = resultTeams(1).hcursor.downField("weekly_total").as[BigDecimal].getOrElse(BigDecimal(-999))

    assertEquals(weeklyA, BigDecimal(18))
    assertEquals(weeklyB, BigDecimal(-18))
    // Zero-sum check
    assertEquals(weeklyA + weeklyB, BigDecimal(0))
  }

  test("mergeLiveData incorporates previous earnings into subtotal") {
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", List(baseRow(1, golfer1Id, "SCHEFFLER")), previous = BigDecimal(50)),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")), previous = BigDecimal(30))
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open", espnId = "123", completed = false, isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(12),
          List(golferScore("Scottie Scheffler", golfer1Id, 2, 1, Some(-8), BigDecimal(12), BigDecimal(100), BigDecimal(12)))),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(0), Nil)
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val resultTeams = result.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)

    // Team A: weekly = $12*2 - $12 = $12, subtotal = $50 + $12 = $62
    val subtotalA = resultTeams(0).hcursor.downField("subtotal").as[BigDecimal].getOrElse(BigDecimal(-999))
    assertEquals(subtotalA, BigDecimal(62))

    // Team B: weekly = $0*2 - $12 = -$12, subtotal = $30 + (-$12) = $18
    val subtotalB = resultTeams(1).hcursor.downField("subtotal").as[BigDecimal].getOrElse(BigDecimal(-999))
    assertEquals(subtotalB, BigDecimal(18))
  }

  test("mergeLiveData includes side bets in total cash") {
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", List(baseRow(1, golfer1Id, "SCHEFFLER")),
        previous = BigDecimal(50), sideBets = BigDecimal(20)),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")),
        previous = BigDecimal(30), sideBets = BigDecimal(-10))
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open", espnId = "123", completed = false, isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(0), Nil),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(0), Nil)
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val resultTeams = result.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)

    // Team A: weekly = 0, subtotal = 50 + 0 = 50, total_cash = 50 + 20 = 70
    val totalCashA = resultTeams(0).hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(-999))
    assertEquals(totalCashA, BigDecimal(70))

    // Team B: weekly = 0, subtotal = 30 + 0 = 30, total_cash = 30 + (-10) = 20
    val totalCashB = resultTeams(1).hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(-999))
    assertEquals(totalCashB, BigDecimal(20))
  }

  test("mergeLiveData recomputes standings_order by total_cash descending") {
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", List(baseRow(1, golfer1Id, "SCHEFFLER")), previous = BigDecimal(10)),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")), previous = BigDecimal(100))
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open", espnId = "123", completed = false, isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(0), Nil),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(0), Nil)
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val standings = result.hcursor.downField("standings_order").as[List[Json]].getOrElse(Nil)

    // Team B has more total_cash, should be rank 1
    val rank1Name = standings(0).hcursor.downField("team_name").as[String].getOrElse("")
    assertEquals(rank1Name, "Team B")
    val rank1 = standings(0).hcursor.downField("rank").as[Int].getOrElse(-1)
    assertEquals(rank1, 1)
  }

  test("mergeLiveData zeroes earnings for unmatched golfers") {
    // golfer3Id is on a roster but not in the live preview data
    val rows = List(
      baseRow(1, golfer1Id, "SCHEFFLER"),
      baseRow(2, golfer3Id, "NOBODY")
    )
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", rows),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")))
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open", espnId = "123", completed = false, isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(18),
          List(golferScore("Scottie Scheffler", golfer1Id, 1, 1, Some(-10), BigDecimal(18), BigDecimal(100), BigDecimal(18)))),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(0), Nil)
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val resultTeams = result.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)
    val teamARows = resultTeams(0).hcursor.downField("rows").as[List[Json]].getOrElse(Nil)

    // golfer3Id (round 2) should have earnings = 0
    val earningsR2 = teamARows(1).hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(-1))
    assertEquals(earningsR2, BigDecimal(0))
  }

  test("mergeLiveData sets position_str with T prefix for ties") {
    val report = baseReport(List(
      teamJson(teamAId, "Team A", "Alice", List(baseRow(1, golfer1Id, "SCHEFFLER"))),
      teamJson(teamBId, "Team B", "Bob", List(baseRow(1, golfer2Id, "MCILROY")))
    ))

    // Both golfers at position 3 (tied)
    val preview = EspnLivePreview(
      espnName = "Test Open", espnId = "123", completed = false, isMajor = false,
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(teamAId, "Team A", "Alice", BigDecimal(10),
          List(golferScore("Scottie Scheffler", golfer1Id, 3, 2, Some(-5), BigDecimal(10), BigDecimal(100), BigDecimal(10)))),
        PreviewTeamScore(teamBId, "Team B", "Bob", BigDecimal(10),
          List(golferScore("Rory McIlroy", golfer2Id, 3, 2, Some(-5), BigDecimal(10), BigDecimal(100), BigDecimal(10))))
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(report, List(preview))
    val resultTeams = result.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)
    val posStr = resultTeams(0).hcursor.downField("rows").downArray
      .downField("position_str").as[String].getOrElse("")

    // Two golfers at position 3 => T3
    assertEquals(posStr, "T3")
  }
