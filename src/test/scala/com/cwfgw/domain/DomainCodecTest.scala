package com.cwfgw.domain

import io.circe.{Json, Encoder, Decoder}
import io.circe.syntax.*
import io.circe.parser.decode
import munit.FunSuite

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Tests that domain model Circe codecs correctly round-trip through JSON
  * with snake_case field naming as configured. */
class DomainCodecTest extends FunSuite:

  private val sampleId =
    UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val sampleInstant = Instant.parse("2026-01-15T12:00:00Z")

  // ================================================================
  // League
  // ================================================================

  test("League encodes to snake_case JSON") {
    val league = League(sampleId, "Test League", sampleInstant)
    val json = league.asJson
    assert(json.hcursor.downField("name").as[String].isRight)
    assert(json.hcursor.downField("created_at").as[String].isRight)
  }

  test("League round-trips through JSON") {
    val league = League(sampleId, "Test League", sampleInstant)
    val decoded = decode[League](league.asJson.noSpaces)
    assertEquals(decoded, Right(league))
  }

  test("CreateLeague decodes from JSON") {
    val json = """{"name":"New League"}"""
    val result = decode[CreateLeague](json)
    assert(result.isRight)
    assertEquals(result.toOption.get.name, "New League")
  }

  // ================================================================
  // Season
  // ================================================================

  test("Season encodes to snake_case JSON") {
    val season = Season(
      sampleId, sampleId, "Season 1", 2026, 1,
      "active", Json.obj(), 13, sampleInstant, sampleInstant
    )
    val json = season.asJson
    assert(json.hcursor.downField("league_id").as[String].isRight)
    assert(json.hcursor.downField("season_year").as[Int].isRight)
    assert(json.hcursor.downField("season_number").as[Int].isRight)
    assert(json.hcursor.downField("max_teams").as[Int].isRight)
    assert(json.hcursor.downField("created_at").as[String].isRight)
    assert(json.hcursor.downField("updated_at").as[String].isRight)
    // Verify camelCase is NOT used
    assert(json.hcursor.downField("seasonYear").failed)
    assert(json.hcursor.downField("maxTeams").failed)
  }

  test("Season round-trips through JSON") {
    val season = Season(
      sampleId, sampleId, "Season 1", 2026, 1,
      "active", Json.obj(), 13, sampleInstant, sampleInstant
    )
    val decoded = decode[Season](season.asJson.noSpaces)
    assertEquals(decoded, Right(season))
  }

  test("CreateSeason decodes from snake_case JSON") {
    val json =
      s"""{"league_id":"$sampleId","name":"S1","season_year":2026,"max_teams":10}"""
    val result = decode[CreateSeason](json)
    assert(result.isRight)
    val cs = result.toOption.get
    assertEquals(cs.name, "S1")
    assertEquals(cs.seasonYear, 2026)
    assertEquals(cs.maxTeams, Some(10))
    assertEquals(cs.rules, None)
  }

  test("UpdateSeason decodes partial fields") {
    val json = """{"name":"Updated","status":"completed"}"""
    val result = decode[UpdateSeason](json)
    assert(result.isRight)
    val us = result.toOption.get
    assertEquals(us.name, Some("Updated"))
    assertEquals(us.status, Some("completed"))
    assertEquals(us.rules, None)
    assertEquals(us.maxTeams, None)
  }

  // ================================================================
  // Golfer
  // ================================================================

  test("Golfer encodes with snake_case fields") {
    val golfer = Golfer(
      sampleId, Some("12345"), "Scottie", "Scheffler",
      Some("USA"), Some(1), true, Json.obj(), sampleInstant
    )
    val json = golfer.asJson
    assert(json.hcursor.downField("pga_player_id").as[String].isRight)
    assert(json.hcursor.downField("first_name").as[String].isRight)
    assert(json.hcursor.downField("last_name").as[String].isRight)
    assert(json.hcursor.downField("world_ranking").as[Int].isRight)
    assert(json.hcursor.downField("updated_at").as[String].isRight)
  }

  test("Golfer round-trips through JSON") {
    val golfer = Golfer(
      sampleId, Some("12345"), "Scottie", "Scheffler",
      Some("USA"), Some(1), true, Json.obj(), sampleInstant
    )
    val decoded = decode[Golfer](golfer.asJson.noSpaces)
    assertEquals(decoded, Right(golfer))
  }

  test("CreateGolfer decodes with optional fields missing") {
    val json = """{"first_name":"Rory","last_name":"McIlroy"}"""
    val result = decode[CreateGolfer](json)
    assert(result.isRight)
    val cg = result.toOption.get
    assertEquals(cg.firstName, "Rory")
    assertEquals(cg.lastName, "McIlroy")
    assertEquals(cg.pgaPlayerId, None)
    assertEquals(cg.country, None)
    assertEquals(cg.worldRanking, None)
  }

  // ================================================================
  // Tournament
  // ================================================================

  test("Tournament encodes with snake_case fields") {
    val t = Tournament(
      sampleId, Some("401"), "The Masters", sampleId,
      LocalDate.of(2026, 4, 9), LocalDate.of(2026, 4, 12),
      Some("Augusta National"), "completed", Some(20000000L),
      BigDecimal(2), Json.obj(), sampleInstant
    )
    val json = t.asJson
    assert(json.hcursor.downField("pga_tournament_id").as[String].isRight)
    assert(json.hcursor.downField("season_id").as[String].isRight)
    assert(json.hcursor.downField("start_date").as[String].isRight)
    assert(json.hcursor.downField("end_date").as[String].isRight)
    assert(json.hcursor.downField("course_name").as[String].isRight)
    assert(json.hcursor.downField("purse_amount").as[Long].isRight)
    assert(json.hcursor.downField("payout_multiplier").as[BigDecimal].isRight)
    assert(json.hcursor.downField("created_at").as[String].isRight)
  }

  test("Tournament round-trips through JSON") {
    val t = Tournament(
      sampleId, Some("401"), "The Masters", sampleId,
      LocalDate.of(2026, 4, 9), LocalDate.of(2026, 4, 12),
      Some("Augusta National"), "completed", Some(20000000L),
      BigDecimal(2), Json.obj(), sampleInstant
    )
    val decoded = decode[Tournament](t.asJson.noSpaces)
    assertEquals(decoded, Right(t))
  }

  test("CreateTournament decodes with minimal fields") {
    val json =
      s"""{"name":"Test Open","season_id":"$sampleId","start_date":"2026-01-15","end_date":"2026-01-18"}"""
    val result = decode[CreateTournament](json)
    assert(result.isRight)
    val ct = result.toOption.get
    assertEquals(ct.name, "Test Open")
    assertEquals(ct.seasonId, sampleId)
    assertEquals(ct.pgaTournamentId, None)
    assertEquals(ct.payoutMultiplier, None)
  }

  test("TournamentResult round-trips") {
    val tr = TournamentResult(
      sampleId, sampleId, sampleId, Some(1), Some(-10),
      Some(270), Some(3600000L),
      Some(Json.arr(
        Json.fromInt(68), Json.fromInt(67),
        Json.fromInt(66), Json.fromInt(69)
      )),
      true, Json.obj()
    )
    val decoded = decode[TournamentResult](tr.asJson.noSpaces)
    assertEquals(decoded, Right(tr))
  }

  // ================================================================
  // Team
  // ================================================================

  test("Team encodes with snake_case fields") {
    val team = Team(
      sampleId, sampleId, "Alice", "Team A",
      Some(1), sampleInstant, sampleInstant
    )
    val json = team.asJson
    assert(json.hcursor.downField("season_id").as[String].isRight)
    assert(json.hcursor.downField("owner_name").as[String].isRight)
    assert(json.hcursor.downField("team_name").as[String].isRight)
    assert(json.hcursor.downField("team_number").as[Int].isRight)
    assert(json.hcursor.downField("created_at").as[String].isRight)
  }

  test("Team round-trips through JSON") {
    val team = Team(
      sampleId, sampleId, "Alice", "Team A",
      Some(1), sampleInstant, sampleInstant
    )
    val decoded = decode[Team](team.asJson.noSpaces)
    assertEquals(decoded, Right(team))
  }

  test("CreateTeam decodes from JSON") {
    val json = """{"owner_name":"Bob","team_name":"Team B"}"""
    val result = decode[CreateTeam](json)
    assert(result.isRight)
    assertEquals(result.toOption.get.ownerName, "Bob")
    assertEquals(result.toOption.get.teamNumber, None)
  }

  test("RosterEntry round-trips with all fields") {
    val entry = RosterEntry(
      sampleId, sampleId, sampleId, "draft", Some(1),
      BigDecimal(75), sampleInstant, None, true
    )
    val decoded = decode[RosterEntry](entry.asJson.noSpaces)
    assertEquals(decoded, Right(entry))
  }

  test("AddToRoster decodes with optional fields") {
    val json =
      s"""{"golfer_id":"$sampleId","draft_round":3,"ownership_pct":50}"""
    val result = decode[AddToRoster](json)
    assert(result.isRight)
    val atr = result.toOption.get
    assertEquals(atr.golferId, sampleId)
    assertEquals(atr.draftRound, Some(3))
    assertEquals(atr.ownershipPct, Some(BigDecimal(50)))
  }

  // ================================================================
  // Draft
  // ================================================================

  test("Draft round-trips through JSON") {
    val draft = Draft(
      sampleId, sampleId, "in_progress", "snake",
      Json.obj(), Some(sampleInstant), None, sampleInstant
    )
    val decoded = decode[Draft](draft.asJson.noSpaces)
    assertEquals(decoded, Right(draft))
  }

  test("Draft encodes with snake_case") {
    val draft = Draft(
      sampleId, sampleId, "pending", "snake",
      Json.obj(), None, None, sampleInstant
    )
    val json = draft.asJson
    assert(json.hcursor.downField("season_id").as[String].isRight)
    assert(json.hcursor.downField("draft_type").as[String].isRight)
    assert(
      json.hcursor.downField("started_at").focus.exists(_.isNull)
    )
    assert(
      json.hcursor.downField("completed_at").focus.exists(_.isNull)
    )
    assert(json.hcursor.downField("created_at").as[String].isRight)
  }

  test("DraftPick round-trips") {
    val pick = DraftPick(
      sampleId, sampleId, sampleId, Some(sampleId),
      1, 1, Some(sampleInstant)
    )
    val decoded = decode[DraftPick](pick.asJson.noSpaces)
    assertEquals(decoded, Right(pick))
  }

  test("MakePick decodes from JSON") {
    val json = s"""{"team_id":"$sampleId","golfer_id":"$sampleId"}"""
    val result = decode[MakePick](json)
    assert(result.isRight)
    assertEquals(result.toOption.get.teamId, sampleId)
  }

  // ================================================================
  // Score
  // ================================================================

  test("FantasyScore round-trips through JSON") {
    val score = FantasyScore(
      sampleId, sampleId, sampleId, sampleId, sampleId,
      BigDecimal("18.50"),
      Json.obj("position" -> Json.fromInt(1)), sampleInstant
    )
    val decoded = decode[FantasyScore](score.asJson.noSpaces)
    assertEquals(decoded, Right(score))
  }

  test("FantasyScore encodes with snake_case") {
    val score = FantasyScore(
      sampleId, sampleId, sampleId, sampleId, sampleId,
      BigDecimal(18), Json.obj(), sampleInstant
    )
    val json = score.asJson
    assert(json.hcursor.downField("season_id").as[String].isRight)
    assert(json.hcursor.downField("team_id").as[String].isRight)
    assert(
      json.hcursor.downField("tournament_id").as[String].isRight
    )
    assert(json.hcursor.downField("golfer_id").as[String].isRight)
    assert(
      json.hcursor.downField("calculated_at").as[String].isRight
    )
  }

  test("SeasonStanding round-trips through JSON") {
    val standing = SeasonStanding(
      sampleId, sampleId, sampleId,
      BigDecimal("125.50"), 8, sampleInstant
    )
    val decoded = decode[SeasonStanding](standing.asJson.noSpaces)
    assertEquals(decoded, Right(standing))
  }

  test("SeasonStanding encodes with snake_case") {
    val standing = SeasonStanding(
      sampleId, sampleId, sampleId,
      BigDecimal(100), 5, sampleInstant
    )
    val json = standing.asJson
    assert(
      json.hcursor.downField("total_points").as[BigDecimal].isRight
    )
    assert(
      json.hcursor.downField("tournaments_played").as[Int].isRight
    )
    assert(
      json.hcursor.downField("last_updated").as[String].isRight
    )
  }

  // ================================================================
  // Edge cases
  // ================================================================

  test("Golfer with null optional fields encodes nulls") {
    val golfer = Golfer(
      sampleId, None, "Tiger", "Woods", None, None,
      true, Json.obj(), sampleInstant
    )
    val json = golfer.asJson
    assert(
      json.hcursor.downField("pga_player_id").focus.exists(_.isNull)
    )
    assert(json.hcursor.downField("country").focus.exists(_.isNull))
    assert(
      json.hcursor.downField("world_ranking").focus.exists(_.isNull)
    )
  }

  test("Tournament with zero purse amount") {
    val t = Tournament(
      sampleId, None, "Charity Open", sampleId,
      LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 4),
      None, "upcoming", Some(0L), BigDecimal(1), Json.obj(), sampleInstant
    )
    val decoded = decode[Tournament](t.asJson.noSpaces)
    assertEquals(decoded, Right(t))
  }

  test("BigDecimal precision preserved in FantasyScore") {
    val score = FantasyScore(
      sampleId, sampleId, sampleId, sampleId, sampleId,
      BigDecimal("7.333333333"), Json.obj(), sampleInstant
    )
    val decoded = decode[FantasyScore](score.asJson.noSpaces)
    assertEquals(
      decoded.toOption.get.points,
      BigDecimal("7.333333333")
    )
  }
