package com.cwfgw.service

import io.circe.syntax.*
import io.circe.parser.decode
import munit.FunSuite

import java.time.LocalDate
import java.util.UUID

import com.cwfgw.domain.given
import com.cwfgw.espn.EspnCalendarEntry

/** Round-trip codec tests for service-layer DTOs. Verifies snake_case encoding and lossless encode/decode for all
  * ConfiguredCodec derivations.
  */
class ServiceCodecTest extends FunSuite:

  private val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val id2 = UUID.fromString("22222222-2222-2222-2222-222222222222")

  // ==============================================================
  // AdminService DTOs
  // ==============================================================

  test("TournamentCreated round-trips") {
    val tc = TournamentCreated(
      id1,
      "The Masters",
      "1",
      LocalDate.of(2026, 4, 9),
      LocalDate.of(2026, 4, 12),
      BigDecimal(2),
      Some("401580"),
      Some("Masters")
    )
    val json = tc.asJson
    assertEquals(json.hcursor.downField("payout_multiplier").as[BigDecimal], Right(BigDecimal(2)))
    assertEquals(json.hcursor.downField("start_date").as[String].map(_.nonEmpty), Right(true))
    assertEquals(decode[TournamentCreated](json.noSpaces), Right(tc))
  }

  test("SeasonUploadResult round-trips with nested tournaments") {
    val tc =
      TournamentCreated(id1, "Open", "5", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 4), BigDecimal(1), None, None)
    val result = SeasonUploadResult(2026, 1, List(tc), 0, List("Unmatched Event"))
    val json = result.asJson
    assertEquals(json.hcursor.downField("season_year").as[Int], Right(2026))
    assertEquals(json.hcursor.downField("tournaments_created").as[Int], Right(1))
    assertEquals(json.hcursor.downField("espn_unmatched").as[List[String]], Right(List("Unmatched Event")))
    assertEquals(decode[SeasonUploadResult](json.noSpaces), Right(result))
  }

  test("RosterPickPreview round-trips with suggestions") {
    val pick = RosterPickPreview(
      1,
      "Tiger Woods",
      100,
      "exact",
      Some("123"),
      Some("Tiger Woods"),
      List(EspnSuggestion("123", "Tiger Woods"))
    )
    val json = pick.asJson
    assertEquals(json.hcursor.downField("input_name").as[String], Right("Tiger Woods"))
    assertEquals(json.hcursor.downField("match_status").as[String], Right("exact"))
    assertEquals(json.hcursor.downField("ownership_pct").as[Int], Right(100))
    assertEquals(decode[RosterPickPreview](json.noSpaces), Right(pick))
  }

  test("RosterTeamPreview round-trips") {
    val team = RosterTeamPreview(
      1,
      "Team Alpha",
      List(RosterPickPreview(
        1,
        "Rory",
        50,
        "ambiguous",
        None,
        None,
        List(EspnSuggestion("a1", "Rory McIlroy"), EspnSuggestion("a2", "Rory Sabbatini"))
      ))
    )
    val json = team.asJson
    assertEquals(json.hcursor.downField("team_number").as[Int], Right(1))
    assertEquals(decode[RosterTeamPreview](json.noSpaces), Right(team))
  }

  test("RosterPreviewResult round-trips") {
    val result = RosterPreviewResult(teams = Nil, totalPicks = 104, exactMatches = 100, ambiguous = 3, noMatch = 1)
    val json = result.asJson
    assertEquals(json.hcursor.downField("total_picks").as[Int], Right(104))
    assertEquals(json.hcursor.downField("exact_matches").as[Int], Right(100))
    assertEquals(json.hcursor.downField("no_match").as[Int], Right(1))
    assertEquals(decode[RosterPreviewResult](json.noSpaces), Right(result))
  }

  test("EspnSuggestion round-trips") {
    val s = EspnSuggestion("456", "Scottie Scheffler")
    val json = s.asJson
    assertEquals(json.hcursor.downField("espn_id").as[String], Right("456"))
    assertEquals(decode[EspnSuggestion](json.noSpaces), Right(s))
  }

  test("ConfirmedTeam round-trips") {
    val team = ConfirmedTeam(3, "Team C", List(ConfirmedPick(1, "Jordan Spieth", 100, Some("789"), Some("J. Spieth"))))
    val json = team.asJson
    assertEquals(json.hcursor.downField("team_number").as[Int], Right(3))
    assertEquals(json.hcursor.downField("team_name").as[String], Right("Team C"))
    assertEquals(decode[ConfirmedTeam](json.noSpaces), Right(team))
  }

  test("ConfirmedPick round-trips with optional fields") {
    val pick = ConfirmedPick(2, "Brooks Koepka", 50, None, None)
    val json = pick.asJson
    assertEquals(json.hcursor.downField("player_name").as[String], Right("Brooks Koepka"))
    assertEquals(json.hcursor.downField("espn_id").as[Option[String]], Right(None))
    assertEquals(decode[ConfirmedPick](json.noSpaces), Right(pick))
  }

  test("RosterUploadResult round-trips") {
    val result = RosterUploadResult(
      teamsCreated = 2,
      golfersCreated = 1,
      teams = List(TeamUploadResult(id1, 1, "Team A", List(RosterPickResult(1, "Xander", id2, 100, created = false))))
    )
    val json = result.asJson
    assertEquals(json.hcursor.downField("teams_created").as[Int], Right(2))
    assertEquals(json.hcursor.downField("golfers_created").as[Int], Right(1))
    assertEquals(decode[RosterUploadResult](json.noSpaces), Right(result))
  }

  test("TeamUploadResult round-trips") {
    val t = TeamUploadResult(id1, 5, "Team E", List(RosterPickResult(3, "Dustin Johnson", id2, 75, created = true)))
    val json = t.asJson
    assertEquals(json.hcursor.downField("team_id").as[String], Right(id1.toString))
    assertEquals(json.hcursor.downField("team_number").as[Int], Right(5))
    assertEquals(decode[TeamUploadResult](json.noSpaces), Right(t))
  }

  test("RosterPickResult round-trips") {
    val p = RosterPickResult(8, "Viktor Hovland", id1, 50, created = true)
    val json = p.asJson
    assertEquals(json.hcursor.downField("golfer_name").as[String], Right("Viktor Hovland"))
    assertEquals(json.hcursor.downField("golfer_id").as[String], Right(id1.toString))
    assertEquals(decode[RosterPickResult](json.noSpaces), Right(p))
  }

  // ==============================================================
  // EspnImportService DTOs
  // ==============================================================

  test("EspnImportResult round-trips") {
    val r = EspnImportResult(
      id1,
      "The Players",
      "401580",
      completed = true,
      totalCompetitors = 144,
      matched = 130,
      unmatched = List("Unknown Guy"),
      created = 5,
      collisions = List("John Smith")
    )
    val json = r.asJson
    assertEquals(json.hcursor.downField("tournament_id").as[String], Right(id1.toString))
    assertEquals(json.hcursor.downField("espn_name").as[String], Right("The Players"))
    assertEquals(json.hcursor.downField("total_competitors").as[Int], Right(144))
    assertEquals(decode[EspnImportResult](json.noSpaces), Right(r))
  }

  test("EspnImportResult defaults collisions to empty") {
    val r = EspnImportResult(
      id1,
      "Open",
      "123",
      completed = false,
      totalCompetitors = 50,
      matched = 40,
      unmatched = Nil,
      created = 3
    )
    val json = r.asJson
    assertEquals(json.hcursor.downField("collisions").as[List[String]], Right(Nil))
    assertEquals(decode[EspnImportResult](json.noSpaces), Right(r))
  }

  test("PreviewGolferScore round-trips") {
    val gs = PreviewGolferScore(
      "Scottie Scheffler",
      id1,
      position = 1,
      numTied = 2,
      scoreToPar = Some(-12),
      basePayout = BigDecimal(18),
      ownershipPct = BigDecimal(100),
      payout = BigDecimal(18)
    )
    val json = gs.asJson
    assertEquals(json.hcursor.downField("golfer_name").as[String], Right("Scottie Scheffler"))
    assertEquals(json.hcursor.downField("num_tied").as[Int], Right(2))
    assertEquals(json.hcursor.downField("score_to_par").as[Option[Int]], Right(Some(-12)))
    assertEquals(json.hcursor.downField("base_payout").as[BigDecimal], Right(BigDecimal(18)))
    assertEquals(decode[PreviewGolferScore](json.noSpaces), Right(gs))
  }

  test("PreviewTeamScore round-trips with golfer scores") {
    val gs = PreviewGolferScore(
      "Rory McIlroy",
      id2,
      position = 5,
      numTied = 1,
      scoreToPar = Some(-8),
      basePayout = BigDecimal(7),
      ownershipPct = BigDecimal(50),
      payout = BigDecimal("3.5")
    )
    val ts = PreviewTeamScore(
      id1,
      "Team Alpha",
      "Alice",
      topTenEarnings = BigDecimal("3.5"),
      golferScores = List(gs),
      weeklyTotal = BigDecimal("-10.5")
    )
    val json = ts.asJson
    assertEquals(json.hcursor.downField("team_name").as[String], Right("Team Alpha"))
    assertEquals(json.hcursor.downField("owner_name").as[String], Right("Alice"))
    assertEquals(json.hcursor.downField("top_ten_earnings").as[BigDecimal], Right(BigDecimal("3.5")))
    assertEquals(decode[PreviewTeamScore](json.noSpaces), Right(ts))
  }

  test("PreviewLeaderboardEntry round-trips") {
    val e = PreviewLeaderboardEntry(
      "Collin Morikawa",
      position = 3,
      scoreToPar = Some(-10),
      thru = Some("F"),
      rostered = true,
      teamName = Some("Team B")
    )
    val json = e.asJson
    assertEquals(json.hcursor.downField("score_to_par").as[Option[Int]], Right(Some(-10)))
    assertEquals(json.hcursor.downField("team_name").as[Option[String]], Right(Some("Team B")))
    assertEquals(decode[PreviewLeaderboardEntry](json.noSpaces), Right(e))
  }

  test("EspnLivePreview round-trips") {
    val preview = EspnLivePreview(
      espnName = "The Masters",
      espnId = "401580",
      completed = false,
      payoutMultiplier = BigDecimal(2),
      totalCompetitors = 88,
      teams = Nil,
      leaderboard = Nil
    )
    val json = preview.asJson
    assertEquals(json.hcursor.downField("espn_name").as[String], Right("The Masters"))
    assertEquals(json.hcursor.downField("payout_multiplier").as[BigDecimal], Right(BigDecimal(2)))
    assertEquals(decode[EspnLivePreview](json.noSpaces), Right(preview))
  }

  // ==============================================================
  // TeamService DTOs
  // ==============================================================

  test("RosterViewPick round-trips") {
    val p = RosterViewPick(1, "Tiger Woods", BigDecimal(100), id1)
    val json = p.asJson
    assertEquals(json.hcursor.downField("golfer_name").as[String], Right("Tiger Woods"))
    assertEquals(json.hcursor.downField("ownership_pct").as[BigDecimal], Right(BigDecimal(100)))
    assertEquals(decode[RosterViewPick](json.noSpaces), Right(p))
  }

  test("RosterViewTeam round-trips with picks") {
    val team = RosterViewTeam(id1, "Team A", List(RosterViewPick(1, "Phil Mickelson", BigDecimal(50), id2)))
    val json = team.asJson
    assertEquals(json.hcursor.downField("team_id").as[String], Right(id1.toString))
    assertEquals(json.hcursor.downField("team_name").as[String], Right("Team A"))
    assertEquals(decode[RosterViewTeam](json.noSpaces), Right(team))
  }

  // ==============================================================
  // CalendarEntryResponse
  // ==============================================================

  test("CalendarEntryResponse round-trips") {
    val r = CalendarEntryResponse("401580", "The Masters", "2026-04-09")
    val json = r.asJson
    assertEquals(json.hcursor.downField("espn_id").as[String], Right("401580"))
    assertEquals(json.hcursor.downField("name").as[String], Right("The Masters"))
    assertEquals(json.hcursor.downField("start_date").as[String], Right("2026-04-09"))
    assertEquals(decode[CalendarEntryResponse](json.noSpaces), Right(r))
  }

  test("CalendarEntryResponse.from maps fields correctly") {
    val entry = EspnCalendarEntry("401580", "The Masters", "2026-04-09")
    val response = CalendarEntryResponse.from(entry)
    assertEquals(response.espnId, "401580")
    assertEquals(response.name, "The Masters")
    assertEquals(response.startDate, "2026-04-09")
  }

  // ==============================================================
  // EspnCalendarEntry codec
  // ==============================================================

  test("EspnCalendarEntry round-trips with snake_case") {
    val e = EspnCalendarEntry("401580", "The Open", "2026-07-16")
    val json = e.asJson
    assertEquals(json.hcursor.downField("start_date").as[String], Right("2026-07-16"))
    assertEquals(decode[EspnCalendarEntry](json.noSpaces), Right(e))
  }

  // ==============================================================
  // ScoringService DTOs
  // ==============================================================

  test("ScoreBreakdown round-trips") {
    val bd = ScoreBreakdown(
      position = 3,
      numTied = 2,
      basePayout = BigDecimal(9),
      ownershipPct = BigDecimal(75),
      payout = BigDecimal("6.75"),
      multiplier = BigDecimal(2)
    )
    val json = bd.asJson
    assertEquals(json.hcursor.downField("num_tied").as[Int], Right(2))
    assertEquals(json.hcursor.downField("base_payout").as[BigDecimal], Right(BigDecimal(9)))
    assertEquals(json.hcursor.downField("ownership_pct").as[BigDecimal], Right(BigDecimal(75)))
    assertEquals(decode[ScoreBreakdown](json.noSpaces), Right(bd))
  }

  test("GolferScoreEntry round-trips") {
    val bd = ScoreBreakdown(1, 1, BigDecimal(18), BigDecimal(100), BigDecimal(18), BigDecimal(1))
    val entry = GolferScoreEntry(id1, BigDecimal(18), bd)
    val json = entry.asJson
    assertEquals(json.hcursor.downField("golfer_id").as[String], Right(id1.toString))
    assertEquals(decode[GolferScoreEntry](json.noSpaces), Right(entry))
  }

  test("TeamWeeklyResult round-trips") {
    val result = TeamWeeklyResult(id1, "Team A", BigDecimal(18), BigDecimal(6), Nil)
    val json = result.asJson
    assertEquals(json.hcursor.downField("team_name").as[String], Right("Team A"))
    assertEquals(json.hcursor.downField("top_tens").as[BigDecimal], Right(BigDecimal(18)))
    assertEquals(json.hcursor.downField("weekly_total").as[BigDecimal], Right(BigDecimal(6)))
    assertEquals(decode[TeamWeeklyResult](json.noSpaces), Right(result))
  }

  test("WeeklyScoreResult round-trips") {
    val result = WeeklyScoreResult(id1, BigDecimal(2), 13, BigDecimal(50), Nil)
    val json = result.asJson
    assertEquals(json.hcursor.downField("tournament_id").as[String], Right(id1.toString))
    assertEquals(json.hcursor.downField("num_teams").as[Int], Right(13))
    assertEquals(json.hcursor.downField("total_pot").as[BigDecimal], Right(BigDecimal(50)))
    assertEquals(decode[WeeklyScoreResult](json.noSpaces), Right(result))
  }

  test("SideBetEntry round-trips") {
    val entry = SideBetEntry(id1, "Team A", id2, BigDecimal("42.5"))
    val json = entry.asJson
    assertEquals(json.hcursor.downField("cumulative_earnings").as[BigDecimal], Right(BigDecimal("42.5")))
    assertEquals(decode[SideBetEntry](json.noSpaces), Right(entry))
  }

  test("SideBetWinner round-trips") {
    val winner = SideBetWinner(id1, "Team A", id2, BigDecimal(50), BigDecimal(180))
    val json = winner.asJson
    assertEquals(json.hcursor.downField("net_winnings").as[BigDecimal], Right(BigDecimal(180)))
    assertEquals(decode[SideBetWinner](json.noSpaces), Right(winner))
  }

  test("SideBetRound round-trips with winner") {
    val round = SideBetRound(
      5,
      active = true,
      Some(SideBetWinner(id1, "Team A", id2, BigDecimal(50), BigDecimal(180))),
      List(SideBetEntry(id1, "Team A", id2, BigDecimal(50)))
    )
    val json = round.asJson
    assertEquals(json.hcursor.downField("round").as[Int], Right(5))
    assertEquals(json.hcursor.downField("active").as[Boolean], Right(true))
    assertEquals(decode[SideBetRound](json.noSpaces), Right(round))
  }

  test("SideBetRound round-trips without winner") {
    val round = SideBetRound(6, active = false, None, Nil)
    val json = round.asJson
    assertEquals(json.hcursor.downField("winner").as[Option[SideBetWinner]], Right(None))
    assertEquals(decode[SideBetRound](json.noSpaces), Right(round))
  }

  test("SideBetTeamTotal round-trips") {
    val total = SideBetTeamTotal(id1, "Team A", 3, BigDecimal(150))
    val json = total.asJson
    assertEquals(json.hcursor.downField("wins").as[Int], Right(3))
    assertEquals(json.hcursor.downField("net").as[BigDecimal], Right(BigDecimal(150)))
    assertEquals(decode[SideBetTeamTotal](json.noSpaces), Right(total))
  }

  test("SideBetStandings round-trips") {
    val standings = SideBetStandings(
      List(SideBetRound(5, active = false, None, Nil)),
      List(SideBetTeamTotal(id1, "Team A", 0, BigDecimal(0)))
    )
    val json = standings.asJson
    assertEquals(json.hcursor.downField("team_totals").as[List[SideBetTeamTotal]], Right(standings.teamTotals))
    assertEquals(decode[SideBetStandings](json.noSpaces), Right(standings))
  }
