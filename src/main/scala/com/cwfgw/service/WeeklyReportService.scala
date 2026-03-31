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

      // Side bet data: cumulative earnings per team per round 5-8
      // For each round, find all tournament results for each team's round-N pick
      sideBetCumulative <- (5 to 8).toList.traverse: round =>
        val roundPicks = allRosters.filter(_.draftRound.contains(round))
        roundPicks.traverse: entry =>
          // Sum all tournament payouts for this golfer across all completed tournaments
          sql"""SELECT COALESCE(SUM(fs.points), 0)
                FROM fantasy_scores fs
                WHERE fs.league_id = $leagueId AND fs.team_id = ${entry.teamId} AND fs.golfer_id = ${entry.golferId}"""
            .query[BigDecimal].unique.map(total => (entry.teamId, total * entry.ownershipPct / 100))
        .map: entries =>
          (round, entries.map((tid, amt) => tid -> amt).toMap)
    yield
      val golferMap = allGolfers.map(g => g.id -> g).toMap
      val resultsByGolfer = results.map(r => r.golferId -> r).toMap
      val scoresByTeamGolfer = scores.map(s => (s.teamId, s.golferId) -> s).toMap
      val standingsByTeam = standings.map(s => s.teamId -> s).toMap
      val isMajor = tournament.exists(_.isMajor)
      val numTeams = teams.size

      // Previous = sum of zero-sum weekly results for all prior tournaments
      val priorTournaments = allTournaments.filter(t => tournament.forall(cur => t.startDate.isBefore(cur.startDate)))
      val priorWeeklyByTeam: Map[UUID, BigDecimal] =
        val priorScoresByTournament = allScores
          .filter(s => priorTournaments.exists(_.id == s.tournamentId))
          .groupBy(_.tournamentId)
        // For each prior tournament, compute the zero-sum weekly result per team
        priorScoresByTournament.toList.flatMap: (tid, tScores) =>
          val teamTopTens = tScores.groupBy(_.teamId).view.mapValues(_.map(_.points).sum).toMap
          val totalPot = teamTopTens.values.sum
          teams.map(t => t.id -> (teamTopTens.getOrElse(t.id, BigDecimal(0)) * numTeams - totalPot))
        .groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap

      // All-time cumulative top-ten counts and earnings per team
      val cumulativeTopTenCounts = allScores
        .groupBy(_.teamId)
        .view.mapValues(_.size).toMap
      val cumulativeTopTenEarnings = allScores
        .groupBy(_.teamId)
        .view.mapValues(_.map(_.points).sum).toMap

      // Compute side bet results: for each round, winner gets +$15*(N-1), losers get -$15
      val sideBetPerTeam = 15
      val sideBetPerRound: List[(Int, Map[UUID, BigDecimal], Map[UUID, BigDecimal])] =
        sideBetCumulative.map: (round, teamTotals) =>
          if teamTotals.isEmpty || teamTotals.values.forall(_ == BigDecimal(0)) then
            (round, teamTotals, Map.empty[UUID, BigDecimal])
          else
            val maxEarnings = teamTotals.values.max
            val winners = teamTotals.filter(_._2 == maxEarnings).keys.toList
            val numWinners = winners.size
            val loserPay = BigDecimal(sideBetPerTeam)
            val winnerCollects = loserPay * (numTeams - numWinners) / numWinners
            val payouts = teamTotals.map: (tid, _) =>
              if winners.contains(tid) then tid -> winnerCollects
              else tid -> -loserPay
            (round, teamTotals, payouts)

      val sideBetResults: Map[UUID, BigDecimal] =
        sideBetPerRound.map(_._3).foldLeft(Map.empty[UUID, BigDecimal]): (acc, roundMap) =>
          roundMap.foldLeft(acc): (a, entry) =>
            a.updated(entry._1, a.getOrElse(entry._1, BigDecimal(0)) + entry._2)

      // Per-golfer/team cumulative stats: season earnings and top-10 count
      val cumulativeByTeamGolfer = allScores
        .groupBy(s => (s.teamId, s.golferId))
        .view.mapValues(ss => (ss.map(_.points).sum, ss.size)).toMap

      // Build team columns
      val teamColumns = teams.map: team =>
        val roster = allRosters.filter(_.teamId == team.id).sortBy(_.draftRound)

        // Build rows 1-8 (draft rounds)
        val rows = (1 to 8).toList.map: round =>
          roster.find(_.draftRound.contains(round)) match
            case None => Json.obj("round" -> round.asJson, "golfer_name" -> Json.Null, "golfer_id" -> Json.Null,
              "position" -> Json.Null, "earnings" -> Json.fromBigDecimal(0), "top_tens" -> 0.asJson,
              "season_earnings" -> BigDecimal(0).asJson, "season_top_tens" -> 0.asJson)
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
              // Cumulative season stats for this golfer/team
              val (seasonEarnings, seasonTopTens) = cumulativeByTeamGolfer.getOrElse((team.id, entry.golferId), (BigDecimal(0), 0))
              Json.obj(
                "round" -> round.asJson,
                "golfer_name" -> golferName.asJson,
                "golfer_id" -> entry.golferId.asJson,
                "position_str" -> posStr.asJson,
                "score_to_par" -> scoreToPar.asJson,
                "earnings" -> earnings.asJson,
                "top_tens" -> topTenCount.asJson,
                "ownership_pct" -> entry.ownershipPct.asJson,
                "season_earnings" -> seasonEarnings.asJson,
                "season_top_tens" -> seasonTopTens.asJson
              )

        val weeklyTopTens = rows.foldLeft(BigDecimal(0))((acc, r) =>
          acc + r.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(0)))
        val totalPot = teams.map: t =>
          scores.filter(_.teamId == t.id).map(_.points).sum
        .sum
        val weeklyTotal = weeklyTopTens * numTeams - totalPot
        val previous = priorWeeklyByTeam.getOrElse(team.id, BigDecimal(0))
        val subtotal = previous + weeklyTotal
        val topTenCount = cumulativeTopTenCounts.getOrElse(team.id, 0)
        val topTenMoney = cumulativeTopTenEarnings.getOrElse(team.id, BigDecimal(0))

        // Side bets: zero-sum result across rounds 5-8
        val sideBetTotal = sideBetResults.getOrElse(team.id, BigDecimal(0))

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
          "top_ten_money" -> topTenMoney.asJson,
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

      // Build side bet detail per round
      val sideBetDetail = sideBetPerRound.map: (round, cumEarnings, payouts) =>
        val teamEntries = teams.map: team =>
          val rosterEntry = allRosters.find(e => e.teamId == team.id && e.draftRound.contains(round))
          val golferName = rosterEntry.flatMap(e => golferMap.get(e.golferId)).map(_.lastName.toUpperCase).getOrElse("—")
          val earnings = cumEarnings.getOrElse(team.id, BigDecimal(0))
          val payout = payouts.getOrElse(team.id, BigDecimal(0))
          Json.obj(
            "team_id" -> team.id.asJson,
            "golfer_name" -> golferName.asJson,
            "cumulative_earnings" -> earnings.asJson,
            "payout" -> payout.asJson
          )
        Json.obj("round" -> round.asJson, "teams" -> teamEntries.asJson)

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
        "side_bet_detail" -> sideBetDetail.asJson,
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

  /** Get a golfer's season top-10 history: tournament, position, earnings. */
  def getGolferHistory(leagueId: UUID, golferId: UUID): IO[Json] =
    val action = for
      golfer <- GolferRepository.findById(golferId)
      scores <- ScoreRepository.getGolferSeasonScores(leagueId, golferId)
    yield
      val name = golfer.map(g => s"${g.firstName} ${g.lastName}").getOrElse("Unknown")
      val totalEarnings = scores.map(_._3).sum
      val results = scores.map: (tournamentName, position, earnings, basePayout) =>
        Json.obj(
          "tournament" -> tournamentName.asJson,
          "position" -> position.asJson,
          "earnings" -> earnings.asJson
        )
      Json.obj(
        "golfer_name" -> name.asJson,
        "golfer_id" -> golferId.asJson,
        "total_earnings" -> totalEarnings.asJson,
        "top_tens" -> scores.size.asJson,
        "results" -> results.asJson
      )
    action.transact(xa)

  /** Rankings with historical cumulative totals per team across all tournaments. */
  def getRankings(leagueId: UUID, live: Boolean = false): IO[Json] =
    val action = for
      teams <- TeamRepository.findByLeague(leagueId)
      allTournaments <- TournamentRepository.findAll(seasonYear = None, status = Some("completed"))
      allRosters <- TeamRepository.getRosterByLeague(leagueId)
      allScores <- allTournaments.flatTraverse(t => ScoreRepository.getScores(leagueId, t.id))

      // Side bet cumulative per round for final totals
      sideBetCumulative <- (5 to 8).toList.traverse: round =>
        val roundPicks = allRosters.filter(_.draftRound.contains(round))
        roundPicks.traverse: entry =>
          sql"""SELECT COALESCE(SUM(fs.points), 0)
                FROM fantasy_scores fs
                WHERE fs.league_id = $leagueId AND fs.team_id = ${entry.teamId} AND fs.golfer_id = ${entry.golferId}"""
            .query[BigDecimal].unique.map(total => (entry.teamId, total * entry.ownershipPct / 100))
        .map(entries => (round, entries.map((tid, amt) => tid -> amt).toMap))

      // Find in-progress tournaments for live view
      inProgressTournaments <- TournamentRepository.findAll(seasonYear = None, status = Some("upcoming"))
        .map(_ ++ allTournaments) // include completed for the query
      upcomingAndInProgress <- TournamentRepository.findAll(seasonYear = None, status = Some("in_progress"))
    yield
      val numTeams = teams.size
      val sideBetPerTeam = BigDecimal(15)
      val sortedTournaments = allTournaments.sortBy(_.startDate)

      // Compute side bet totals
      val sideBetResults: Map[UUID, BigDecimal] =
        val perRound = sideBetCumulative.map: (round, teamTotals) =>
          if teamTotals.isEmpty || teamTotals.values.forall(_ == BigDecimal(0)) then Map.empty[UUID, BigDecimal]
          else
            val maxEarnings = teamTotals.values.max
            val winners = teamTotals.filter(_._2 == maxEarnings).keys.toSet
            val numWinners = winners.size
            val winnerCollects = sideBetPerTeam * (numTeams - numWinners) / numWinners
            teamTotals.map((tid, _) => if winners.contains(tid) then tid -> winnerCollects else tid -> -sideBetPerTeam)
        perRound.foldLeft(Map.empty[UUID, BigDecimal]): (acc, roundMap) =>
          roundMap.foldLeft(acc)((a, entry) => a.updated(entry._1, a.getOrElse(entry._1, BigDecimal(0)) + entry._2))

      // Build cumulative weekly totals over time
      val history = sortedTournaments.scanLeft(teams.map(t => t.id -> BigDecimal(0)).toMap): (cumulative, tournament) =>
        val tScores = allScores.filter(_.tournamentId == tournament.id)
        val teamTopTens = tScores.groupBy(_.teamId).view.mapValues(_.map(_.points).sum).toMap
        val totalPot = teamTopTens.values.sum
        teams.map: t =>
          val weeklyTotal = teamTopTens.getOrElse(t.id, BigDecimal(0)) * numTeams - totalPot
          t.id -> (cumulative.getOrElse(t.id, BigDecimal(0)) + weeklyTotal)
        .toMap
      .tail // drop the initial zeros

      val weekLabels = sortedTournaments.map(t => t.metadata.hcursor.downField("week").as[String].getOrElse(""))
      val tournamentLabels = sortedTournaments.map(_.name)

      // Current totals (last point in history, or zeros if no tournaments)
      val currentTotals = history.lastOption.getOrElse(teams.map(t => t.id -> BigDecimal(0)).toMap)

      val teamRankings = teams.map: team =>
        val subtotal = currentTotals.getOrElse(team.id, BigDecimal(0))
        val sideBets = sideBetResults.getOrElse(team.id, BigDecimal(0))
        val totalCash = subtotal + sideBets
        val seriesData = history.map(h => h.getOrElse(team.id, BigDecimal(0)))
        Json.obj(
          "team_id" -> team.id.asJson,
          "team_name" -> team.teamName.asJson,
          "subtotal" -> subtotal.asJson,
          "side_bets" -> sideBets.asJson,
          "total_cash" -> totalCash.asJson,
          "series" -> seriesData.asJson
        )
      .sortBy(t => t.hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(0))).reverse

      // Find in-progress tournament for live option
      val liveCandidate = upcomingAndInProgress.sortBy(_.startDate).headOption

      (Json.obj(
        "teams" -> teamRankings.asJson,
        "weeks" -> weekLabels.asJson,
        "tournament_names" -> tournamentLabels.asJson
      ), liveCandidate)

    action.transact(xa).flatMap: (baseRankings, liveCandidate) =>
      if live && liveCandidate.isDefined then
        val tournament = liveCandidate.get
        val teams = baseRankings.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)
        val numTeams = teams.size
        espnImportService.previewByDate(leagueId, tournament.startDate).map: previews =>
          previews.headOption match
            case None => baseRankings
            case Some(preview) =>
              val totalPot = preview.teams.map(_.topTenEarnings).sum
              val liveWeekly = preview.teams.map(t => t.teamId.toString -> (t.topTenEarnings * numTeams - totalPot)).toMap
              val weekLabel = tournament.metadata.hcursor.downField("week").as[String].getOrElse("")

              val updatedTeams = teams.map: t =>
                val teamId = t.hcursor.downField("team_id").as[String].getOrElse("")
                val subtotal = t.hcursor.downField("subtotal").as[BigDecimal].getOrElse(BigDecimal(0))
                val sideBets = t.hcursor.downField("side_bets").as[BigDecimal].getOrElse(BigDecimal(0))
                val liveWeeklyTotal = liveWeekly.getOrElse(teamId, BigDecimal(0))
                val newSubtotal = subtotal + liveWeeklyTotal
                val newTotal = newSubtotal + sideBets
                val series = t.hcursor.downField("series").as[List[BigDecimal]].getOrElse(Nil)
                t.mapObject(_
                  .add("subtotal", newSubtotal.asJson)
                  .add("total_cash", newTotal.asJson)
                  .add("series", (series :+ newSubtotal).asJson)
                  .add("live_weekly", liveWeeklyTotal.asJson))

              val weeks = baseRankings.hcursor.downField("weeks").as[List[String]].getOrElse(Nil)
              val names = baseRankings.hcursor.downField("tournament_names").as[List[String]].getOrElse(Nil)
              val sortedTeams = updatedTeams.sortBy(t => t.hcursor.downField("total_cash").as[BigDecimal].getOrElse(BigDecimal(0))).reverse
              baseRankings.mapObject(_
                .add("teams", sortedTeams.asJson)
                .add("weeks", (weeks :+ weekLabel).asJson)
                .add("tournament_names", (names :+ (preview.espnName + " *")).asJson)
                .add("live", Json.True))
        .handleError(_ => baseRankings)
      else IO.pure(baseRankings)

  /** Overlay ESPN live preview data onto the base report.
    * Replaces per-golfer earnings with live projected payouts and recomputes
    * weekly totals, subtotals, and total cash. */
  private[service] def mergeLiveData(report: Json, previews: List[EspnLivePreview]): Json =
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
            val baseSeasonEarnings = row.hcursor.downField("season_earnings").as[BigDecimal].getOrElse(BigDecimal(0))
            val baseSeasonTopTens = row.hcursor.downField("season_top_tens").as[Int].getOrElse(0)
            livePayout.get((teamId, golferId)) match
              case Some((payout, pos, stp)) =>
                val numTied = livePayout.values.count(_._2 == pos)
                val posStr = if numTied > 1 then s"T$pos" else s"$pos"
                val stpStr = stp.map(s => if s == 0 then "E" else if s > 0 then s"+$s" else s.toString)
                row.deepMerge(Json.obj(
                  "earnings" -> payout.asJson,
                  "position_str" -> posStr.asJson,
                  "score_to_par" -> stpStr.asJson,
                  "top_tens" -> 1.asJson,
                  "season_earnings" -> (baseSeasonEarnings + payout).asJson,
                  "season_top_tens" -> (baseSeasonTopTens + 1).asJson
                ))
              case None =>
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

        // Recompute side bets with live data: add live payouts to cumulative earnings for rounds 5-8
        val sideBetPerTeam = BigDecimal(15)
        val baseSideBetDetail = report.hcursor.downField("side_bet_detail").as[List[Json]].getOrElse(Nil)
        val liveSideBetDetail = baseSideBetDetail.map: rdJson =>
          val round = rdJson.hcursor.downField("round").as[Int].getOrElse(0)
          val rdTeams = rdJson.hcursor.downField("teams").as[List[Json]].getOrElse(Nil)
          // For each team in this round, find the golfer's live payout and add to cumulative
          val updatedRdTeams = rdTeams.map: entry =>
            val teamId = entry.hcursor.downField("team_id").as[String].getOrElse("")
            val cumEarnings = entry.hcursor.downField("cumulative_earnings").as[BigDecimal].getOrElse(BigDecimal(0))
            // Find the golfer_id for this team's round-N pick from the team rows
            val golferIdOpt = updatedTeams.find(t =>
              t.hcursor.downField("team_id").as[String].getOrElse("") == teamId
            ).flatMap: t =>
              val rows = t.hcursor.downField("rows").as[List[Json]].getOrElse(Nil)
              rows.find(r => r.hcursor.downField("round").as[Int].getOrElse(0) == round)
                .flatMap(r => r.hcursor.downField("golfer_id").as[String].toOption)
            val liveEarnings = golferIdOpt.flatMap(gid => livePayout.get((teamId, gid))).map(_._1).getOrElse(BigDecimal(0))
            // Also need to account for ownership_pct
            val ownershipPct = updatedTeams.find(t =>
              t.hcursor.downField("team_id").as[String].getOrElse("") == teamId
            ).flatMap: t =>
              val rows = t.hcursor.downField("rows").as[List[Json]].getOrElse(Nil)
              rows.find(r => r.hcursor.downField("round").as[Int].getOrElse(0) == round)
                .flatMap(r => r.hcursor.downField("ownership_pct").as[BigDecimal].toOption)
            .getOrElse(BigDecimal(100))
            val adjustedLive = liveEarnings * ownershipPct / 100
            val newCum = cumEarnings + adjustedLive
            entry.mapObject(_.add("cumulative_earnings", newCum.asJson))

          // Recompute round winner/loser payouts
          val teamEarnings = updatedRdTeams.map(e =>
            e.hcursor.downField("team_id").as[String].getOrElse("") ->
            e.hcursor.downField("cumulative_earnings").as[BigDecimal].getOrElse(BigDecimal(0))
          ).toMap
          val allZero = teamEarnings.values.forall(_ == BigDecimal(0))
          val finalRdTeams = if allZero then
            updatedRdTeams.map(_.mapObject(_.add("payout", BigDecimal(0).asJson)))
          else
            val maxEarnings = teamEarnings.values.max
            val winners = teamEarnings.filter(_._2 == maxEarnings).keys.toSet
            val numWinners = winners.size
            val winnerCollects = sideBetPerTeam * (numTeams - numWinners) / numWinners
            updatedRdTeams.map: e =>
              val tid = e.hcursor.downField("team_id").as[String].getOrElse("")
              val payout = if winners.contains(tid) then winnerCollects else -sideBetPerTeam
              e.mapObject(_.add("payout", payout.asJson))

          rdJson.mapObject(_.add("teams", finalRdTeams.asJson))

        // Aggregate live side bet totals per team
        val liveSideBetTotals: Map[String, BigDecimal] =
          liveSideBetDetail.flatMap: rdJson =>
            rdJson.hcursor.downField("teams").as[List[Json]].getOrElse(Nil).map: e =>
              e.hcursor.downField("team_id").as[String].getOrElse("") ->
              e.hcursor.downField("payout").as[BigDecimal].getOrElse(BigDecimal(0))
          .groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap

        val finalTeams = updatedTeams.map: t =>
          val teamId = t.hcursor.downField("team_id").as[String].getOrElse("")
          val weeklyTopTens = t.hcursor.downField("_weekly_top_tens").as[BigDecimal].getOrElse(BigDecimal(0))
          val previous = t.hcursor.downField("_previous").as[BigDecimal].getOrElse(BigDecimal(0))
          val originalSideBets = t.hcursor.downField("_side_bets").as[BigDecimal].getOrElse(BigDecimal(0))
          val sideBets = if liveSideBetTotals.nonEmpty then liveSideBetTotals.getOrElse(teamId, BigDecimal(0))
            else originalSideBets
          val topTenCount = t.hcursor.downField("_top_ten_count").as[Int].getOrElse(0)
          val weeklyTotal = weeklyTopTens * numTeams - totalPot
          val subtotal = previous + weeklyTotal
          val totalCash = subtotal + sideBets
          t.mapObject(_
            .add("weekly_total", weeklyTotal.asJson)
            .add("subtotal", subtotal.asJson)
            .add("side_bets", sideBets.asJson)
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
          .add("side_bet_detail", liveSideBetDetail.asJson)
          .add("standings_order", standingsOrder.asJson)
          .add("live", Json.True))
