package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*

import java.util.UUID

import java.time.LocalDate

import com.cwfgw.domain.*
import com.cwfgw.repository.{GolferRepository, ScoreRepository, TeamRepository, TournamentRepository}

/** Assembles the full weekly report data matching the PDF layout.
  * Returns a grid: 13 team columns x 8 draft round rows, with golfer results
  * in each cell, plus summary rows (top tens, weekly +/-, previous, subtotal,
  * side bets, total cash).
  *
  * When `live=true`, merges ESPN live leaderboard data for in-progress
  * tournaments to show projected standings. */
class WeeklyReportService(espnImportService: EspnImportService, xa: Transactor[IO]):

  def getReport(leagueId: UUID, tournamentId: UUID, live: Boolean = false): IO[Json] =
    val action = for
      teams <- TeamRepository.findByLeague(leagueId)
      tournament <- TournamentRepository.findById(tournamentId)
      results <- TournamentRepository.findResults(tournamentId)
      allGolfers <- GolferRepository.findAll(activeOnly = false, search = None)
      scores <- ScoreRepository.getScores(leagueId, tournamentId)
      standings <- ScoreRepository.getStandings(leagueId)
      allTournaments <- TournamentRepository.findAll(seasonYear = tournament.map(_.seasonYear), status = Some("completed"))

      // Get all rosters and all scores across all tournaments for cumulative data
      allRosters <- TeamRepository.getRosterByLeague(leagueId)
      allScores <- allTournaments.flatTraverse: t =>
        ScoreRepository.getScores(leagueId, t.id)

      // Side bet data
      sideBetScores <- (5 to 8).toList.traverse: round =>
        val roundPicks = allRosters.filter(_.draftRound.contains(round))
        roundPicks.traverse: entry =>
          sql"SELECT COALESCE(SUM(points), 0) FROM fantasy_scores WHERE league_id = $leagueId AND team_id = ${entry.teamId} AND golfer_id = ${entry.golferId}"
            .query[BigDecimal].unique.map(total => (entry.teamId, round, total))
        .map(_.groupBy(_._1).view.mapValues(_.map(_._3).sum).toMap)
    yield
      val golferMap = allGolfers.map(g => g.id -> g).toMap
      val resultsByGolfer = results.map(r => r.golferId -> r).toMap
      val scoresByTeamGolfer = scores.map(s => (s.teamId, s.golferId) -> s).toMap
      val standingsByTeam = standings.map(s => s.teamId -> s).toMap
      val isMajor = tournament.exists(_.isMajor)
      val numTeams = teams.size

      // Previous cumulative scores per team (all tournaments BEFORE this one)
      val priorTournaments = allTournaments.filter(t => tournament.forall(cur => t.startDate.isBefore(cur.startDate)))
      val priorScoresByTeam = allScores
        .filter(s => priorTournaments.exists(_.id == s.tournamentId))
        .groupBy(_.teamId)
        .view.mapValues(_.map(_.points).sum).toMap

      // All-time cumulative top-ten counts per team
      val cumulativeTopTenCounts = allScores
        .groupBy(_.teamId)
        .view.mapValues(_.size).toMap

      // Build team columns
      val teamColumns = teams.map: team =>
        val roster = allRosters.filter(_.teamId == team.id).sortBy(_.draftRound)

        // Build rows 1-8 (draft rounds)
        val rows = (1 to 8).toList.map: round =>
          roster.find(_.draftRound.contains(round)) match
            case None => Json.obj("round" -> round.asJson, "golfer_name" -> Json.Null, "golfer_id" -> Json.Null,
              "position" -> Json.Null, "earnings" -> Json.fromBigDecimal(0), "top_tens" -> 0.asJson)
            case Some(entry) =>
              val golfer = golferMap.get(entry.golferId)
              val golferName = golfer.map(g => g.lastName.toUpperCase).getOrElse("?")
              val result = resultsByGolfer.get(entry.golferId)
              val score = scoresByTeamGolfer.get((team.id, entry.golferId))
              val earnings = score.map(_.points).getOrElse(BigDecimal(0))
              val position = result.flatMap(_.position)
              val posStr = result.flatMap(_.position).map: pos =>
                val numTied = results.count(_.position == result.flatMap(_.position))
                if numTied > 1 then s"T$pos" else s"$pos"
              val scoreToPar = result.flatMap(_.scoreToPar).map: s =>
                if s == 0 then "E" else if s > 0 then s"+$s" else s.toString
              // Count top-10s this tournament for this golfer/team
              val topTenCount = if position.exists(_ <= 10) then 1 else 0
              Json.obj(
                "round" -> round.asJson,
                "golfer_name" -> golferName.asJson,
                "golfer_id" -> entry.golferId.asJson,
                "position_str" -> posStr.asJson,
                "score_to_par" -> scoreToPar.asJson,
                "earnings" -> earnings.asJson,
                "top_tens" -> topTenCount.asJson,
                "ownership_pct" -> entry.ownershipPct.asJson
              )

        val weeklyTopTens = rows.foldLeft(BigDecimal(0))((acc, r) =>
          acc + r.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(0)))
        val totalPot = teams.map: t =>
          scores.filter(_.teamId == t.id).map(_.points).sum
        .sum
        val weeklyTotal = weeklyTopTens * numTeams - totalPot
        val previous = priorScoresByTeam.getOrElse(team.id, BigDecimal(0))

        // Cumulative subtotal (all-time top tens dollar amount)
        val subtotal = standingsByTeam.get(team.id).map(_.totalPoints).getOrElse(BigDecimal(0))
        val topTenCount = cumulativeTopTenCounts.getOrElse(team.id, 0)

        // Side bets: sum of cumulative earnings for rounds 5-8 picks
        val sideBetTotal = sideBetScores.zipWithIndex.map: (roundMap, i) =>
          roundMap.getOrElse(team.id, BigDecimal(0))
        .sum

        Json.obj(
          "team_id" -> team.id.asJson,
          "team_name" -> team.teamName.asJson,
          "owner_name" -> team.ownerName.asJson,
          "rows" -> rows.asJson,
          "top_tens" -> weeklyTopTens.asJson,
          "weekly_total" -> weeklyTotal.asJson,
          "previous" -> previous.asJson,
          "subtotal" -> subtotal.asJson,
          "top_ten_count" -> topTenCount.asJson,
          "side_bets" -> sideBetTotal.asJson,
          "total_cash" -> (subtotal + sideBetTotal).asJson
        )

      // Undrafted top-10 golfers
      val rosteredGolferIds = allRosters.map(_.golferId).toSet
      val undraftedTopTens = results
        .filter(r => r.position.exists(_ <= 10) && !rosteredGolferIds.contains(r.golferId))
        .sortBy(_.position)
        .map: r =>
          val golfer = golferMap.get(r.golferId)
          val name = golfer.map(g => s"${g.firstName.head}. ${g.lastName}").getOrElse("?")
          val numTied = results.count(_.position == r.position)
          val payout = PayoutTable.tieSplitPayout(r.position.getOrElse(99), numTied, isMajor)
          Json.obj("name" -> name.asJson, "position" -> r.position.asJson, "payout" -> payout.asJson)

      Json.obj(
        "tournament" -> Json.obj(
          "id" -> tournament.map(_.id).asJson,
          "name" -> tournament.map(_.name).asJson,
          "start_date" -> tournament.map(_.startDate).asJson,
          "end_date" -> tournament.map(_.endDate).asJson,
          "status" -> tournament.map(_.status).asJson,
          "is_major" -> isMajor.asJson,
          "week" -> tournament.flatMap(_.metadata.hcursor.downField("week").focus).asJson
        ),
        "teams" -> teamColumns.asJson,
        "undrafted_top_tens" -> undraftedTopTens.asJson,
        "standings_order" -> teamColumns.sortBy(t =>
          t.hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(0))
        ).reverse.zipWithIndex.map((t, i) =>
          Json.obj(
            "rank" -> (i + 1).asJson,
            "team_name" -> t.hcursor.downField("team_name").as[String].getOrElse("").asJson,
            "total_cash" -> t.hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(0)).asJson
          )
        ).asJson
      )
    action.transact(xa).flatMap: baseReport =>
      val status = baseReport.hcursor.downField("tournament").downField("status").as[String].getOrElse("")
      if live && status != "completed" then
        val startDate = baseReport.hcursor.downField("tournament").downField("start_date").as[String].getOrElse("")
        if startDate.isEmpty then IO.pure(baseReport)
        else
          espnImportService.previewByDate(leagueId, LocalDate.parse(startDate)).map: previews =>
            mergeLiveData(baseReport, previews)
          .handleError(_ => baseReport) // fall back to base report if ESPN fails
      else IO.pure(baseReport)

  /** Overlay ESPN live preview data onto the base report.
    * Replaces per-golfer earnings with live projected payouts and recomputes
    * weekly totals, subtotals, and total cash. */
  private def mergeLiveData(report: Json, previews: List[EspnLivePreview]): Json =
    val preview = previews.headOption
    preview match
      case None => report
      case Some(liveData) =>
        // Build a map of (teamId, golferId) -> live payout from the preview
        val livePayout: Map[(String, String), (BigDecimal, Int, Option[Int])] =
          liveData.teams.flatMap: team =>
            team.golferScores.map: gs =>
              (team.teamId.toString, gs.golferId.toString) -> (gs.payout, gs.position, gs.scoreToPar)
          .toMap

        val teams = report.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)
        val numTeams = teams.size

        val updatedTeams = teams.map: teamJson =>
          val teamId = teamJson.hcursor.downField("team_id").as[String].getOrElse("")
          val rows = teamJson.hcursor.downField("rows").as[List[Json]].getOrElse(Nil)
          val previous = teamJson.hcursor.downField("previous").as[BigDecimal].getOrElse(BigDecimal(0))
          val sideBets = teamJson.hcursor.downField("side_bets").as[BigDecimal].getOrElse(BigDecimal(0))
          val topTenCount = teamJson.hcursor.downField("top_ten_count").as[Int].getOrElse(0)

          val updatedRows = rows.map: row =>
            val golferId = row.hcursor.downField("golfer_id").as[String].getOrElse("")
            livePayout.get((teamId, golferId)) match
              case Some((payout, pos, stp)) =>
                val numTied = livePayout.values.count(_._2 == pos)
                val posStr = if numTied > 1 then s"T$pos" else s"$pos"
                val stpStr = stp.map(s => if s == 0 then "E" else if s > 0 then s"+$s" else s.toString)
                row.deepMerge(Json.obj(
                  "earnings" -> payout.asJson,
                  "position_str" -> posStr.asJson,
                  "score_to_par" -> stpStr.asJson,
                  "top_tens" -> 1.asJson
                ))
              case None =>
                // Check if golfer has a position on the leaderboard but outside top 10
                row.deepMerge(Json.obj("earnings" -> BigDecimal(0).asJson, "top_tens" -> 0.asJson))

          val weeklyTopTens = updatedRows.foldLeft(BigDecimal(0))((acc, r) =>
            acc + r.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(0)))
          val liveTopTenCount = topTenCount + updatedRows.count(r =>
            r.hcursor.downField("top_tens").as[Int].getOrElse(0) > 0)

          teamJson.deepMerge(Json.obj("rows" -> updatedRows.asJson, "top_tens" -> weeklyTopTens.asJson))
            // weekly_total, subtotal, total_cash will be recomputed below
            .mapObject(_.add("_weekly_top_tens", weeklyTopTens.asJson)
              .add("_previous", previous.asJson)
              .add("_side_bets", sideBets.asJson)
              .add("_top_ten_count", liveTopTenCount.asJson))

        // Recompute weekly totals (zero-sum)
        val totalPot = updatedTeams.foldLeft(BigDecimal(0))((acc, t) =>
          acc + t.hcursor.downField("_weekly_top_tens").as[BigDecimal].getOrElse(BigDecimal(0)))

        val finalTeams = updatedTeams.map: t =>
          val weeklyTopTens = t.hcursor.downField("_weekly_top_tens").as[BigDecimal].getOrElse(BigDecimal(0))
          val previous = t.hcursor.downField("_previous").as[BigDecimal].getOrElse(BigDecimal(0))
          val sideBets = t.hcursor.downField("_side_bets").as[BigDecimal].getOrElse(BigDecimal(0))
          val topTenCount = t.hcursor.downField("_top_ten_count").as[Int].getOrElse(0)
          val weeklyTotal = weeklyTopTens * numTeams - totalPot
          val subtotal = previous + weeklyTotal
          val totalCash = subtotal + sideBets
          t.mapObject(_
            .add("weekly_total", weeklyTotal.asJson)
            .add("subtotal", subtotal.asJson)
            .add("total_cash", totalCash.asJson)
            .add("top_ten_count", topTenCount.asJson)
            .remove("_weekly_top_tens").remove("_previous").remove("_side_bets").remove("_top_ten_count"))

        // Recompute standings order
        val standingsOrder = finalTeams
          .sortBy(t => t.hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(0))).reverse
          .zipWithIndex.map: (t, i) =>
            Json.obj(
              "rank" -> (i + 1).asJson,
              "team_name" -> t.hcursor.downField("team_name").as[String].getOrElse("").asJson,
              "total_cash" -> t.hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(0)).asJson
            )

        report.mapObject(_
          .add("teams", finalTeams.asJson)
          .add("standings_order", standingsOrder.asJson)
          .add("live", Json.True))
