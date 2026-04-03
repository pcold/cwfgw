package com.cwfgw.service

import cats.effect.IO
import io.circe.Json
import munit.FunSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.util.UUID
import java.time.{Instant, LocalDate}

import com.cwfgw.domain.SeasonRules

/** Tests for WeeklyReportService.mergeLiveData and
  * overlayPriorLivePreview — pure typed transformations
  * that overlay ESPN live data onto a base report.
  *
  * We construct minimal WeeklyReport and EspnLivePreview
  * objects, then verify the merged output has correct
  * weekly totals, subtotals, and rankings.
  */
class WeeklyReportServiceTest extends FunSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val service = new WeeklyReportService(
    espnImportService = null, xa = null
  )

  // ---- IDs ----

  private val teamAId = UUID.fromString(
    "00000000-0000-0000-0000-000000000001"
  )
  private val teamBId = UUID.fromString(
    "00000000-0000-0000-0000-000000000002"
  )
  private val golfer1Id = UUID.fromString(
    "00000000-0000-0000-0000-000000000011"
  )
  private val golfer2Id = UUID.fromString(
    "00000000-0000-0000-0000-000000000012"
  )
  private val golfer3Id = UUID.fromString(
    "00000000-0000-0000-0000-000000000013"
  )
  private val golfer4Id = UUID.fromString(
    "00000000-0000-0000-0000-000000000014"
  )

  // ---- Helpers ----

  private def baseRow(
    round: Int,
    golferId: UUID,
    golferName: String,
    earnings: BigDecimal = BigDecimal(0),
    topTens: Int = 0,
    ownershipPct: BigDecimal = BigDecimal(100),
    seasonEarnings: BigDecimal = BigDecimal(0),
    seasonTopTens: Int = 0
  ): ReportRow = ReportRow(
    round = round,
    golferName = Some(golferName),
    golferId = Some(golferId),
    positionStr = None,
    scoreToPar = None,
    earnings = earnings,
    topTens = topTens,
    ownershipPct = ownershipPct,
    seasonEarnings = seasonEarnings,
    seasonTopTens = seasonTopTens
  )

  private def teamColumn(
    teamId: UUID,
    teamName: String,
    ownerName: String,
    rows: List[ReportRow],
    previous: BigDecimal = BigDecimal(0),
    sideBets: BigDecimal = BigDecimal(0),
    topTenCount: Int = 0
  ): ReportTeamColumn =
    val topTens = rows.map(_.earnings).sum
    ReportTeamColumn(
      teamId = teamId,
      teamName = teamName,
      ownerName = ownerName,
      rows = rows,
      topTens = topTens,
      weeklyTotal = BigDecimal(0),
      previous = previous,
      subtotal = previous,
      topTenCount = topTenCount,
      topTenMoney = BigDecimal(0),
      sideBets = sideBets,
      totalCash = previous + sideBets
    )

  private def baseReport(
    teams: List[ReportTeamColumn]
  ): WeeklyReport = WeeklyReport(
    tournament = ReportTournamentInfo(
      id = None,
      name = Some("Test Open"),
      startDate = Some("2026-03-26"),
      endDate = Some("2026-03-29"),
      status = Some("in_progress"),
      payoutMultiplier = BigDecimal(1),
      week = None
    ),
    teams = teams,
    undraftedTopTens = Nil,
    sideBetDetail = Nil,
    standingsOrder = Nil
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
  ): PreviewGolferScore = PreviewGolferScore(
    golferName, golferId, position, numTied,
    scoreToPar, basePayout, ownershipPct, payout
  )

  // ---- Tests ----

  test("mergeLiveData returns report unchanged " +
    "when previews list is empty") {
    val rows = List(baseRow(1, golfer1Id, "SCHEFFLER"))
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", rows)
    ))
    val result = service.mergeLiveData(
      report, Nil, SeasonRules.default
    )
    assertEquals(result, report)
  }

  test("mergeLiveData overlays earnings " +
    "for matched golfers") {
    val rows = List(baseRow(1, golfer1Id, "SCHEFFLER"))
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", rows),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )

    val earnings =
      result.teams.head.rows.head.earnings
    assertEquals(earnings, BigDecimal(18))

    assert(result.live.contains(true))
  }

  test("mergeLiveData computes zero-sum " +
    "weekly totals correctly") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER"))
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val weeklyA = result.teams(0).weeklyTotal
    val weeklyB = result.teams(1).weeklyTotal

    assertEquals(weeklyA, BigDecimal(18))
    assertEquals(weeklyB, BigDecimal(-18))
    assertEquals(weeklyA + weeklyB, BigDecimal(0))
  }

  test("mergeLiveData incorporates previous " +
    "earnings into subtotal") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER")),
        previous = BigDecimal(50)
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY")),
        previous = BigDecimal(30)
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(12),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 2, 1,
            Some(-8), BigDecimal(12),
            BigDecimal(100), BigDecimal(12)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )

    // Team A: weekly=$12*2-$12=$12, subtotal=$50+$12=$62
    assertEquals(result.teams(0).subtotal, BigDecimal(62))
    // Team B: weekly=$0*2-$12=-$12, subtotal=$30-$12=$18
    assertEquals(result.teams(1).subtotal, BigDecimal(18))
  }

  test("mergeLiveData includes side bets " +
    "in total cash") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER")),
        previous = BigDecimal(50),
        sideBets = BigDecimal(20)
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY")),
        previous = BigDecimal(30),
        sideBets = BigDecimal(-10)
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(0), Nil
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )

    // Team A: weekly=0, subtotal=50, total=50+20=70
    assertEquals(
      result.teams(0).totalCash, BigDecimal(70)
    )
    // Team B: weekly=0, subtotal=30, total=30-10=20
    assertEquals(
      result.teams(1).totalCash, BigDecimal(20)
    )
  }

  test("mergeLiveData recomputes standings_order " +
    "by total_cash descending") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER")),
        previous = BigDecimal(10)
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY")),
        previous = BigDecimal(100)
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(0), Nil
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )

    // Team B has more totalCash, should be rank 1
    assertEquals(
      result.standingsOrder.head.teamName, "Team B"
    )
    assertEquals(result.standingsOrder.head.rank, 1)
  }

  test("mergeLiveData zeroes earnings " +
    "for unmatched golfers") {
    val rows = List(
      baseRow(1, golfer1Id, "SCHEFFLER"),
      baseRow(2, golfer3Id, "NOBODY")
    )
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", rows),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val teamARows = result.teams(0).rows

    // golfer3Id (round 2) should have earnings = 0
    assertEquals(teamARows(1).earnings, BigDecimal(0))
  }

  test("mergeLiveData sets position_str " +
    "with T prefix for ties") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER"))
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    // Both golfers at position 3 (tied)
    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(10),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 3, 2,
            Some(-5), BigDecimal(10),
            BigDecimal(100), BigDecimal(10)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(10),
          List(golferScore(
            "Rory McIlroy", golfer2Id, 3, 2,
            Some(-5), BigDecimal(10),
            BigDecimal(100), BigDecimal(10)
          ))
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val posStr = result.teams(0).rows.head.positionStr

    // Two golfers at position 3 => T3
    assertEquals(posStr, Some("T3"))
  }

  test("mergeLiveData sets position_str " +
    "without T prefix for solo position") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER"))
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-12), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(12),
          List(golferScore(
            "Rory McIlroy", golfer2Id, 2, 1,
            Some(-10), BigDecimal(12),
            BigDecimal(100), BigDecimal(12)
          ))
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val posStr = result.teams(0).rows.head.positionStr
    assertEquals(posStr, Some("1"))
  }

  test("mergeLiveData handles partial " +
    "ownership correctly") {
    val row = baseRow(
      1, golfer1Id, "SCHEFFLER",
      ownershipPct = BigDecimal(75)
    )
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", List(row)),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    // golfer1 earns $18 but Team A only owns 75%
    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal("13.5"),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(75), BigDecimal("13.5")
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val earnings = result.teams(0).rows.head.earnings
    assertEquals(earnings, BigDecimal("13.5"))
  }

  test("mergeLiveData with multiple golfers " +
    "on same team") {
    val rows = List(
      baseRow(1, golfer1Id, "SCHEFFLER"),
      baseRow(2, golfer2Id, "MCILROY")
    )
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", rows),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer3Id, "RAHM"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(30),
          List(
            golferScore(
              "Scottie Scheffler", golfer1Id, 1, 1,
              Some(-12), BigDecimal(18),
              BigDecimal(100), BigDecimal(18)
            ),
            golferScore(
              "Rory McIlroy", golfer2Id, 2, 1,
              Some(-10), BigDecimal(12),
              BigDecimal(100), BigDecimal(12)
            )
          )
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val topTens = result.teams(0).topTens
    assertEquals(topTens, BigDecimal(30))

    // Zero-sum: A=$30*2-$30=$30, B=$0*2-$30=-$30
    val weeklyA = result.teams(0).weeklyTotal
    val weeklyB = result.teams(1).weeklyTotal
    assertEquals(weeklyA, BigDecimal(30))
    assertEquals(weeklyB, BigDecimal(-30))
    assertEquals(weeklyA + weeklyB, BigDecimal(0))
  }

  test("mergeLiveData with three teams " +
    "maintains zero-sum") {
    val teamCId = UUID.fromString(
      "00000000-0000-0000-0000-000000000003"
    )
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER"))
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      ),
      teamColumn(
        teamCId, "Team C", "Charlie",
        List(baseRow(1, golfer3Id, "RAHM"))
      )
    ))

    // Team A: $18, Team B: $12, Team C: $0
    // totalPot=$30, numTeams=3
    // A=$18*3-$30=$24, B=$12*3-$30=$6, C=$0*3-$30=-$30
    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-12), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(12),
          List(golferScore(
            "Rory McIlroy", golfer2Id, 2, 1,
            Some(-10), BigDecimal(12),
            BigDecimal(100), BigDecimal(12)
          ))
        ),
        PreviewTeamScore(
          teamCId, "Team C", "Charlie",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val weeklyA = result.teams(0).weeklyTotal
    val weeklyB = result.teams(1).weeklyTotal
    val weeklyC = result.teams(2).weeklyTotal

    assertEquals(weeklyA, BigDecimal(24))
    assertEquals(weeklyB, BigDecimal(6))
    assertEquals(weeklyC, BigDecimal(-30))
    assertEquals(
      weeklyA + weeklyB + weeklyC, BigDecimal(0)
    )
  }

  test("mergeLiveData updates season_earnings " +
    "and season_top_tens") {
    val row = baseRow(
      1, golfer1Id, "SCHEFFLER",
      seasonEarnings = BigDecimal(50),
      seasonTopTens = 3
    )
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", List(row)),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )
    val r = result.teams.head.rows.head

    // season_earnings = prior 50 + live 18 = 68
    assertEquals(r.seasonEarnings, BigDecimal(68))
    // season_top_tens = prior 3 + 1 = 4
    assertEquals(r.seasonTopTens, 4)
  }

  test("mergeLiveData sets score_to_par string") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER"))
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(12),
          List(golferScore(
            "Rory McIlroy", golfer2Id, 2, 1,
            Some(0), BigDecimal(12),
            BigDecimal(100), BigDecimal(12)
          ))
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default
    )

    // Team A golfer: -10 should be "-10"
    assertEquals(
      result.teams(0).rows.head.scoreToPar,
      Some("-10")
    )
    // Team B golfer: 0 should be "E"
    assertEquals(
      result.teams(1).rows.head.scoreToPar,
      Some("E")
    )
  }

  // ---- Additive mode tests (season report overlay) ----

  test("mergeLiveData additive mode " +
    "adds live earnings to base") {
    val rows = List(baseRow(
      1, golfer1Id, "SCHEFFLER",
      earnings = BigDecimal(20),
      topTens = 2,
      seasonEarnings = BigDecimal(20),
      seasonTopTens = 2
    ))
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", rows),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(
          1, golfer2Id, "MCILROY",
          earnings = BigDecimal(15),
          topTens = 1,
          seasonEarnings = BigDecimal(15),
          seasonTopTens = 1
        ))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default,
      additive = true
    )

    // Team A: base $20 + live $18 = $38
    assertEquals(
      result.teams(0).rows.head.earnings,
      BigDecimal(38)
    )
    // Top tens: base 2 + 1 live = 3
    assertEquals(result.teams(0).rows.head.topTens, 3)
    // Season earnings: 20 + 18 = 38
    assertEquals(
      result.teams(0).rows.head.seasonEarnings,
      BigDecimal(38)
    )
  }

  test("additive mode keeps base earnings " +
    "for unmatched golfers") {
    val rows = List(
      baseRow(
        1, golfer1Id, "SCHEFFLER",
        earnings = BigDecimal(20), topTens = 2
      ),
      baseRow(
        2, golfer3Id, "NOBODY",
        earnings = BigDecimal(10), topTens = 1
      )
    )
    val report = baseReport(List(
      teamColumn(teamAId, "Team A", "Alice", rows),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default,
      additive = true
    )
    val teamARows = result.teams(0).rows

    // golfer3 not in live data — keeps base $10
    assertEquals(teamARows(1).earnings, BigDecimal(10))
    // golfer3 keeps base top_tens = 1
    assertEquals(teamARows(1).topTens, 1)
  }

  test("additive mode computes correct " +
    "zero-sum totals") {
    // Season cumulative: A=$20, B=$15, Pot=$35
    // Live overlay: A gets $18, B gets $0
    // Combined: A=$38, B=$15, Pot=$53
    // A zero-sum = $38*2 - $53 = $23
    // B zero-sum = $15*2 - $53 = -$23
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(
          1, golfer1Id, "SCHEFFLER",
          earnings = BigDecimal(20)
        ))
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(
          1, golfer2Id, "MCILROY",
          earnings = BigDecimal(15)
        ))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default,
      additive = true
    )

    val weeklyA = result.teams(0).weeklyTotal
    val weeklyB = result.teams(1).weeklyTotal

    assertEquals(weeklyA, BigDecimal(23))
    assertEquals(weeklyB, BigDecimal(-23))
    assertEquals(weeklyA + weeklyB, BigDecimal(0))
  }

  test("additive mode top_ten_count " +
    "sums row-level counts") {
    // Two golfers on Team A: one with 3 cumulative
    // top-10s, one with 1. Live adds 1 to the first.
    // Expected top_ten_count = (3+1) + 1 = 5
    val rows = List(
      baseRow(
        1, golfer1Id, "SCHEFFLER",
        earnings = BigDecimal(30), topTens = 3
      ),
      baseRow(
        2, golfer3Id, "RAHM",
        earnings = BigDecimal(10), topTens = 1
      )
    )
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice", rows,
        topTenCount = 4
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY"))
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Test Open",
      espnId = "123",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.mergeLiveData(
      report, List(preview), SeasonRules.default,
      additive = true
    )
    val topTenCount = result.teams(0).topTenCount

    // 3+1 (golfer1 + live) + 1 (golfer3 kept) = 5
    assertEquals(topTenCount, 5)
  }

  // ---- overlayPriorLivePreview tests ----

  test("overlayPriorLivePreview adds to previous, " +
    "not earnings") {
    val rows = List(baseRow(
      1, golfer1Id, "SCHEFFLER",
      earnings = BigDecimal(5),
      topTens = 1,
      seasonEarnings = BigDecimal(20),
      seasonTopTens = 2
    ))
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice", rows,
        previous = BigDecimal(10),
        topTenCount = 2
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(
          1, golfer2Id, "MCILROY",
          seasonEarnings = BigDecimal(15),
          seasonTopTens = 1
        )),
        previous = BigDecimal(-10),
        topTenCount = 1
      )
    ))

    // Prior tournament: Team A golfer1 earned $18
    val preview = EspnLivePreview(
      espnName = "Prior Open",
      espnId = "999",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(18),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 1, 1,
            Some(-10), BigDecimal(18),
            BigDecimal(100), BigDecimal(18)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(0), Nil
        )
      ),
      leaderboard = Nil
    )

    val result = service.overlayPriorLivePreview(
      report, preview, SeasonRules.default
    )

    // Earnings should be UNCHANGED (still $5)
    assertEquals(
      result.teams(0).rows.head.earnings,
      BigDecimal(5)
    )

    // Previous: $10 + $18 (zero-sum from prior)
    // Prior zero-sum: A=$18*2-$18=$18, B=$0*2-$18=-$18
    assertEquals(
      result.teams(0).previous, BigDecimal(28)
    )
    assertEquals(
      result.teams(1).previous, BigDecimal(-28)
    )

    // Season earnings updated for golfer1
    assertEquals(
      result.teams(0).rows.head.seasonEarnings,
      BigDecimal(38)
    )
    // Season top tens updated
    assertEquals(
      result.teams(0).rows.head.seasonTopTens, 3
    )
    // Top ten count updated
    assertEquals(result.teams(0).topTenCount, 3)

    // Report marked as live
    assert(result.live.contains(true))
  }

  test("overlayPriorLivePreview maintains " +
    "zero-sum in previous") {
    val report = baseReport(List(
      teamColumn(
        teamAId, "Team A", "Alice",
        List(baseRow(1, golfer1Id, "SCHEFFLER")),
        previous = BigDecimal(0)
      ),
      teamColumn(
        teamBId, "Team B", "Bob",
        List(baseRow(1, golfer2Id, "MCILROY")),
        previous = BigDecimal(0)
      )
    ))

    val preview = EspnLivePreview(
      espnName = "Prior Open",
      espnId = "999",
      completed = false,
      payoutMultiplier = BigDecimal(1),
      totalCompetitors = 100,
      teams = List(
        PreviewTeamScore(
          teamAId, "Team A", "Alice",
          BigDecimal(12),
          List(golferScore(
            "Scottie Scheffler", golfer1Id, 2, 1,
            Some(-8), BigDecimal(12),
            BigDecimal(100), BigDecimal(12)
          ))
        ),
        PreviewTeamScore(
          teamBId, "Team B", "Bob",
          BigDecimal(10),
          List(golferScore(
            "Rory McIlroy", golfer2Id, 3, 1,
            Some(-6), BigDecimal(10),
            BigDecimal(100), BigDecimal(10)
          ))
        )
      ),
      leaderboard = Nil
    )

    val result = service.overlayPriorLivePreview(
      report, preview, SeasonRules.default
    )

    val prevA = result.teams(0).previous
    val prevB = result.teams(1).previous

    // A: $12*2-$22=$2, B: $10*2-$22=-$2
    assertEquals(prevA, BigDecimal(2))
    assertEquals(prevB, BigDecimal(-2))
    assertEquals(prevA + prevB, BigDecimal(0))
  }

  // ---- Tournament ordering tests ----

  private val seasonId = UUID.randomUUID()

  private def mkTournament(
    name: String,
    startDate: String,
    status: String = "completed"
  ): com.cwfgw.domain.Tournament =
    com.cwfgw.domain.Tournament(
      id = UUID.randomUUID(),
      pgaTournamentId = None,
      name = name,
      seasonId = seasonId,
      startDate = LocalDate.parse(startDate),
      endDate = LocalDate.parse(startDate).plusDays(3),
      courseName = None,
      status = status,
      purseAmount = None,
      payoutMultiplier = BigDecimal(1),
      week = None,
      createdAt = Instant.now()
    )

  test("tBefore orders same-date tournaments by name") {
    val week8a = mkTournament("Week 8A", "2026-03-05")
    val week8b = mkTournament("Week 8B", "2026-03-05")

    assert(service.tBefore(week8a, week8b))
    assert(!service.tBefore(week8b, week8a))
    assert(!service.tBefore(week8a, week8a))
  }

  test("tBefore orders different-date " +
    "tournaments by date") {
    val week7 = mkTournament("Week 7", "2026-02-26")
    val week8a = mkTournament("Week 8A", "2026-03-05")

    assert(service.tBefore(week7, week8a))
    assert(!service.tBefore(week8a, week7))
  }

  test("tOnOrBefore includes same tournament") {
    val week8a = mkTournament("Week 8A", "2026-03-05")
    val week8b = mkTournament("Week 8B", "2026-03-05")

    assert(service.tOnOrBefore(week8a, week8a))
    assert(service.tOnOrBefore(week8a, week8b))
    assert(!service.tOnOrBefore(week8b, week8a))
  }

  test("filterThroughTournament includes 8A " +
    "but not 8B when through=8A") {
    val week7 = mkTournament("Week 7", "2026-02-26")
    val week8a = mkTournament("Week 8A", "2026-03-05")
    val week8b = mkTournament("Week 8B", "2026-03-05")
    val all = List(week7, week8a, week8b)

    val through8a = service.filterThroughTournament(
      all, Some(week8a)
    )
    assertEquals(
      through8a.map(_.name).toSet,
      Set("Week 7", "Week 8A")
    )
  }

  test("filterThroughTournament includes both " +
    "8A and 8B when through=8B") {
    val week7 = mkTournament("Week 7", "2026-02-26")
    val week8a = mkTournament("Week 8A", "2026-03-05")
    val week8b = mkTournament("Week 8B", "2026-03-05")
    val all = List(week7, week8a, week8b)

    val through8b = service.filterThroughTournament(
      all, Some(week8b)
    )
    assertEquals(
      through8b.map(_.name).toSet,
      Set("Week 7", "Week 8A", "Week 8B")
    )
  }

  test("filterThroughTournament returns all " +
    "when through=None") {
    val week7 = mkTournament("Week 7", "2026-02-26")
    val week8a = mkTournament("Week 8A", "2026-03-05")
    val all = List(week7, week8a)

    val result = service.filterThroughTournament(all, None)
    assertEquals(result.size, 2)
  }
