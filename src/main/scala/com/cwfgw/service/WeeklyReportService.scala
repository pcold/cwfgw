package com.cwfgw.service

import cats.data.NonEmptyList
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

import org.typelevel.log4cats.LoggerFactory

import com.cwfgw.domain.*
import com.cwfgw.repository.{GolferRepository, ScoreRepository, SeasonRepository, TeamRepository, TournamentRepository}

/** Assembles the full weekly report data matching the PDF layout.
  * Returns a grid: 13 team columns x 8 draft round rows, with golfer results
  * in each cell, plus summary rows (top tens, weekly +/-, previous, subtotal,
  * side bets, total cash).
  *
  * When `live=true`, merges ESPN live leaderboard data for in-progress
  * tournaments to show projected standings. */
class WeeklyReportService(
    espnImportService: EspnImportService,
    xa: Transactor[IO]
)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger

  /** Context needed to recompute side bets during live overlay. */
  private case class RankingsContext(
      allRosters: List[RosterEntry],
      rules: SeasonRules,
      sideBetCumulative: List[(Int, Map[UUID, BigDecimal])],
      numTeams: Int
  )

  /** Orders tournaments by (startDate, name) to handle
    * same-date multi-events like Week 8A / 8B. */
  private val tournamentOrd: Ordering[Tournament] =
    Ordering.by(t => (t.startDate, t.name))

  /** Finds the preview matching a tournament by ESPN ID. */
  private def matchPreview(
      previews: List[EspnLivePreview],
      tournament: Tournament
  ): Option[EspnLivePreview] =
    tournament.pgaTournamentId match
      case Some(pgaId) =>
        previews.find(_.espnId == pgaId).orElse(previews.headOption)
      case None => previews.headOption

  /** True when `a` precedes `b` in tournament order. */
  private[service] def tBefore(a: Tournament, b: Tournament): Boolean =
    tournamentOrd.lt(a, b)

  /** True when `a` precedes or equals `b` in tournament order. */
  private[service] def tOnOrBefore(a: Tournament, b: Tournament): Boolean =
    tournamentOrd.lteq(a, b)

  def getReport(seasonId: UUID, tournamentId: UUID, live: Boolean = false): IO[Json] =
    val action = for
      seasonOpt <- SeasonRepository.findById(seasonId)
      teams <- TeamRepository.findBySeason(seasonId)
      tournament <- TournamentRepository.findById(tournamentId)
      results <- TournamentRepository.findResults(tournamentId)
      allGolfers <- GolferRepository.findAll(activeOnly = false, search = None)
      scores <- ScoreRepository.getScores(seasonId, tournamentId)
      standings <- ScoreRepository.getStandings(seasonId)
      allTournaments <- TournamentRepository.findAll(seasonId = tournament.map(_.seasonId), status = Some("completed"))
      allSeasonTournaments <- TournamentRepository.findAll(seasonId = tournament.map(_.seasonId), status = None)

      allRosters <- TeamRepository.getRosterBySeason(seasonId)
      allScores <- allTournaments.flatTraverse(t => ScoreRepository.getScores(seasonId, t.id))
    yield
      val rules = seasonOpt.map(_.seasonRules).getOrElse(SeasonRules.default)
      val golferMap = allGolfers.map(g => g.id -> g).toMap
      val resultsByGolfer = results.map(r => r.golferId -> r).toMap
      val scoresByTeamGolfer = scores.map(s => (s.teamId, s.golferId) -> s).toMap
      val standingsByTeam = standings.map(s => s.teamId -> s).toMap
      val multiplier = tournament.map(_.payoutMultiplier).getOrElse(BigDecimal(1))
      val numTeams = teams.size

      // Tournaments and scores through the selected tournament
      val throughTournaments = allTournaments
        .filter(t => tournament.forall(cur => tOnOrBefore(t, cur)))
      val throughScores = allScores
        .filter(s => throughTournaments.exists(_.id == s.tournamentId))

      // Previous = sum of zero-sum weekly results for prior tournaments
      val priorTournaments = throughTournaments
        .filter(t => tournament.forall(cur => tBefore(t, cur)))
      val priorWeeklyByTeam: Map[UUID, BigDecimal] =
        val priorScoresByTournament = throughScores
          .filter(s => priorTournaments.exists(_.id == s.tournamentId))
          .groupBy(_.tournamentId)
        priorScoresByTournament.toList.flatMap { (_, tScores) =>
          val teamTopTens = tScores.groupBy(_.teamId).view
            .mapValues(_.map(_.points).sum).toMap
          val totalPot = teamTopTens.values.sum
          teams.map(t =>
            t.id -> (teamTopTens.getOrElse(t.id, BigDecimal(0)) *
              numTeams - totalPot))
        }.groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap

      // Cumulative top-ten counts and earnings through this tournament
      val cumulativeTopTenCounts = throughScores
        .groupBy(_.teamId)
        .view.mapValues(_.size).toMap
      val cumulativeTopTenEarnings = throughScores
        .groupBy(_.teamId)
        .view.mapValues(_.map(_.points).sum).toMap

      // Side bet results scoped through this tournament
      val sideBetPerTeam = rules.sideBetAmount
      val sideBetPerRound = rules.sideBetRounds.map { round =>
        val roundPicks =
          allRosters.filter(_.draftRound.contains(round))
        val teamTotals = roundPicks.map { entry =>
          val total = throughScores
            .filter(s =>
              s.teamId == entry.teamId &&
              s.golferId == entry.golferId)
            .map(_.points).sum
          entry.teamId -> (total * entry.ownershipPct / 100)
        }.toMap
        if teamTotals.isEmpty
          || teamTotals.values.forall(_ == BigDecimal(0))
        then (round, teamTotals, Map.empty[UUID, BigDecimal])
        else
          val maxEarnings = teamTotals.values.max
          val winners = teamTotals
            .filter(_._2 == maxEarnings).keys.toList
          val numWinners = winners.size
          val winnerCollects =
            sideBetPerTeam * (numTeams - numWinners) / numWinners
          val payouts = teamTotals.map((tid, _) =>
            if winners.contains(tid) then tid -> winnerCollects
            else tid -> -sideBetPerTeam)
          (round, teamTotals, payouts)
      }

      val sideBetResults: Map[UUID, BigDecimal] =
        sideBetPerRound.map(_._3)
          .foldLeft(Map.empty[UUID, BigDecimal]) { (acc, roundMap) =>
            roundMap.foldLeft(acc)((a, entry) =>
              a.updated(entry._1,
                a.getOrElse(entry._1, BigDecimal(0)) + entry._2))
          }

      // Per-golfer/team cumulative stats through this tournament
      val cumulativeByTeamGolfer = throughScores
        .groupBy(s => (s.teamId, s.golferId))
        .view.mapValues(ss =>
          (ss.map(_.points).sum, ss.size)).toMap

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
          val payout = PayoutTable.tieSplitPayout(r.position.getOrElse(99), numTied, multiplier, rules)
          val stpStr = r.scoreToPar.map(s =>
            if s == 0 then "E" else if s > 0 then s"+$s" else s.toString
          )
          Json.obj(
            "name" -> name.asJson,
            "position" -> r.position.asJson,
            "payout" -> payout.asJson,
            "score_to_par" -> stpStr.asJson
          )

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

      // Prior non-completed tournaments (for live overlay)
      val priorNonCompleted = allSeasonTournaments.filter(t =>
        t.status != "completed" &&
        t.id != tournamentId &&
        tournament.forall(cur => tBefore(t, cur)))

      val report = Json.obj(
        "tournament" -> Json.obj(
          "id" -> tournament.map(_.id).asJson,
          "name" -> tournament.map(_.name).asJson,
          "start_date" -> tournament.map(_.startDate).asJson,
          "end_date" -> tournament.map(_.endDate).asJson,
          "status" -> tournament.map(_.status).asJson,
          "payout_multiplier" -> multiplier.asJson,
          "week" -> tournament.flatMap(
            _.metadata.hcursor.downField("week").focus
          ).asJson
        ),
        "teams" -> teamColumns.asJson,
        "undrafted_top_tens" -> undraftedTopTens.asJson,
        "side_bet_detail" -> sideBetDetail.asJson,
        "standings_order" -> teamColumns.sortBy(t =>
          t.hcursor.downField("total_cash")
            .as[BigDecimal].getOrElse(BigDecimal(0))
        ).reverse.zipWithIndex.map((t, i) =>
          Json.obj(
            "rank" -> (i + 1).asJson,
            "team_name" -> t.hcursor
              .downField("team_name")
              .as[String].getOrElse("").asJson,
            "total_cash" -> t.hcursor
              .downField("total_cash")
              .as[BigDecimal]
              .getOrElse(BigDecimal(0)).asJson
          )
        ).asJson
      )

      (report, rules, priorNonCompleted, tournament)
    action.transact(xa).flatMap {
      (baseReport, txRules, priorNonCompleted, selectedTournament) =>
        if !live then IO.pure(baseReport)
        else
          // 1. Overlay prior non-completed tournaments
          val withPrior = priorNonCompleted
            .sorted(tournamentOrd)
            .foldLeft(IO.pure(baseReport)) { (accIO, t) =>
              accIO.flatMap { acc =>
                espnImportService
                  .previewByDate(seasonId, t.startDate)
                  .map(previews =>
                    matchPreview(previews, t)
                      .map(overlayPriorLivePreview(
                        acc, _, txRules))
                      .getOrElse(acc))
                  .handleErrorWith(e =>
                    logger.warn(e)(
                      s"Prior live overlay failed: ${t.name}"
                    ).as(acc))
              }
            }
          // 2. Overlay selected tournament (if not completed)
          val status = baseReport.hcursor
            .downField("tournament").downField("status")
            .as[String].getOrElse("")
          if status != "completed" then
            val startDate = baseReport.hcursor
              .downField("tournament")
              .downField("start_date")
              .as[String].getOrElse("")
            if startDate.isEmpty then withPrior
            else withPrior.flatMap { rpt =>
              espnImportService
                .previewByDate(
                  seasonId, LocalDate.parse(startDate))
                .map { previews =>
                  val matched = selectedTournament
                    .flatMap(matchPreview(previews, _))
                    .toList
                  mergeLiveData(rpt, matched, txRules)
                }
                .handleErrorWith(e =>
                  logger.warn(e)(
                    s"Live overlay failed for $tournamentId"
                  ).as(rpt))
            }
          else withPrior
    }

  /** Season-wide report compiling data from all completed tournaments.
    * When `live=true`, overlays ESPN data from in-progress tournaments. */
  def getSeasonReport(
      seasonId: UUID,
      live: Boolean = false
  ): IO[Json] =
    val action = for
      seasonOpt <- SeasonRepository.findById(seasonId)
      teams <- TeamRepository.findBySeason(seasonId)
      allGolfers <- GolferRepository.findAll(
        activeOnly = false, search = None
      )
      completed <- TournamentRepository.findAll(
        seasonId = Some(seasonId), status = Some("completed")
      )
      allRosters <- TeamRepository.getRosterBySeason(seasonId)
      allScores <- completed.flatTraverse(t =>
        ScoreRepository.getScores(seasonId, t.id)
      )
      allResults <- completed.flatTraverse(t =>
        TournamentRepository.findResults(t.id)
      )
      allSeasonTournaments <- TournamentRepository.findAll(
        seasonId = Some(seasonId), status = None
      )
    yield
      val nonCompleted = allSeasonTournaments
        .filter(_.status != "completed")
      val rules = seasonOpt.map(_.seasonRules)
        .getOrElse(SeasonRules.default)
      val golferMap = allGolfers.map(g => g.id -> g).toMap
      val numTeams = teams.size

      val cumulativeByTeamGolfer = allScores
        .groupBy(s => (s.teamId, s.golferId))
        .view.mapValues(ss =>
          (ss.map(_.points).sum, ss.size)).toMap

      val topTensByTeam = allScores.groupBy(_.teamId)
        .view.mapValues(_.map(_.points).sum).toMap
      val topTenCountByTeam = allScores.groupBy(_.teamId)
        .view.mapValues(_.size).toMap
      val totalPot = topTensByTeam.values.sum

      val sideBetPerTeam = rules.sideBetAmount
      val sideBetPerRound = buildSideBetPerRound(
        rules, allRosters, allScores, numTeams, sideBetPerTeam
      )
      val sideBetResults = aggregateSideBets(sideBetPerRound)

      val teamColumns = teams.map { team =>
        val roster = allRosters.filter(_.teamId == team.id)
          .sortBy(_.draftRound)
        val rows = buildSeasonRows(
          roster, golferMap, cumulativeByTeamGolfer, team.id
        )
        val teamTopTens = topTensByTeam
          .getOrElse(team.id, BigDecimal(0))
        val weeklyTotal = teamTopTens * numTeams - totalPot
        val topTenCount = topTenCountByTeam.getOrElse(team.id, 0)
        val sideBetTotal = sideBetResults
          .getOrElse(team.id, BigDecimal(0))

        Json.obj(
          "team_id" -> team.id.asJson,
          "team_name" -> team.teamName.asJson,
          "owner_name" -> team.ownerName.asJson,
          "rows" -> rows.asJson,
          "top_tens" -> teamTopTens.asJson,
          "weekly_total" -> weeklyTotal.asJson,
          "previous" -> BigDecimal(0).asJson,
          "subtotal" -> weeklyTotal.asJson,
          "top_ten_count" -> topTenCount.asJson,
          "top_ten_money" -> teamTopTens.asJson,
          "side_bets" -> sideBetTotal.asJson,
          "total_cash" -> (weeklyTotal + sideBetTotal).asJson
        )
      }

      val rosteredGolferIds = allRosters.map(_.golferId).toSet
      val undraftedAgg = buildUndraftedAgg(
        allResults, completed, rosteredGolferIds,
        golferMap, rules
      )

      val sideBetDetail = buildSideBetDetail(
        sideBetPerRound, teams, allRosters, golferMap
      )

      val standingsOrder = teamColumns.sortBy(t =>
        t.hcursor.downField("total_cash")
          .as[BigDecimal].getOrElse(BigDecimal(0))
      ).reverse.zipWithIndex.map((t, i) =>
        Json.obj(
          "rank" -> (i + 1).asJson,
          "team_name" -> t.hcursor.downField("team_name")
            .as[String].getOrElse("").asJson,
          "total_cash" -> t.hcursor.downField("total_cash")
            .as[BigDecimal].getOrElse(BigDecimal(0)).asJson
        )
      )

      val report = Json.obj(
        "tournament" -> Json.obj(
          "id" -> Json.Null,
          "name" -> "All Tournaments".asJson,
          "start_date" -> Json.Null,
          "end_date" -> Json.Null,
          "status" -> "season".asJson,
          "payout_multiplier" -> BigDecimal(1).asJson,
          "week" -> Json.Null
        ),
        "teams" -> teamColumns.asJson,
        "undrafted_top_tens" -> undraftedAgg.asJson,
        "side_bet_detail" -> sideBetDetail.asJson,
        "standings_order" -> standingsOrder.asJson
      )
      (report, rules, nonCompleted)

    action.transact(xa).flatMap {
      (baseReport, rules, nonCompleted) =>
        if live && nonCompleted.nonEmpty then
          val sorted = nonCompleted.sorted(tournamentOrd)
          logger.info(
            s"Season report live overlay: " +
            s"${sorted.size} non-completed tournaments"
          ) >>
          sorted.foldLeft(IO.pure(baseReport)) {
            (accIO, tournament) =>
              accIO.flatMap { acc =>
                espnImportService
                  .previewByDate(seasonId, tournament.startDate)
                  .map { previews =>
                    val matched = matchPreview(previews, tournament)
                      .toList
                    mergeLiveData(acc, matched, rules,
                      additive = true)
                  }
                  .handleErrorWith(e =>
                    logger.warn(e)(
                      s"Live overlay failed for " +
                      s"${tournament.name}"
                    ).as(acc))
              }
          }
        else
          if live then
            logger.info(
              "Season report: live requested but " +
              "no non-completed tournaments found"
            ).as(baseReport)
          else IO.pure(baseReport)
    }

  private def buildSeasonRows(
      roster: List[RosterEntry],
      golferMap: Map[UUID, Golfer],
      cumulative: Map[(UUID, UUID), (BigDecimal, Int)],
      teamId: UUID
  ): List[Json] =
    (1 to 8).toList.map { round =>
      roster.find(_.draftRound.contains(round)) match
        case None =>
          Json.obj(
            "round" -> round.asJson,
            "golfer_name" -> Json.Null,
            "golfer_id" -> Json.Null,
            "position_str" -> Json.Null,
            "score_to_par" -> Json.Null,
            "earnings" -> BigDecimal(0).asJson,
            "top_tens" -> 0.asJson,
            "ownership_pct" -> BigDecimal(100).asJson,
            "season_earnings" -> BigDecimal(0).asJson,
            "season_top_tens" -> 0.asJson
          )
        case Some(entry) =>
          val golferName = golferMap.get(entry.golferId)
            .map(_.lastName.toUpperCase).getOrElse("?")
          val (earnings, topTens) = cumulative
            .getOrElse((teamId, entry.golferId),
              (BigDecimal(0), 0))
          val posStr =
            if topTens > 0 then Some(s"${topTens}x")
            else None
          Json.obj(
            "round" -> round.asJson,
            "golfer_name" -> golferName.asJson,
            "golfer_id" -> entry.golferId.asJson,
            "position_str" -> posStr.asJson,
            "score_to_par" -> Json.Null,
            "earnings" -> earnings.asJson,
            "top_tens" -> topTens.asJson,
            "ownership_pct" -> entry.ownershipPct.asJson,
            "season_earnings" -> earnings.asJson,
            "season_top_tens" -> topTens.asJson
          )
    }

  private def buildSideBetPerRound(
      rules: SeasonRules,
      allRosters: List[RosterEntry],
      allScores: List[FantasyScore],
      numTeams: Int,
      sideBetPerTeam: BigDecimal
  ): List[(Int, Map[UUID, BigDecimal], Map[UUID, BigDecimal])] =
    rules.sideBetRounds.map { round =>
      val roundPicks =
        allRosters.filter(_.draftRound.contains(round))
      val teamTotals = roundPicks.map { entry =>
        val total = allScores
          .filter(s =>
            s.teamId == entry.teamId &&
            s.golferId == entry.golferId)
          .map(_.points).sum
        entry.teamId -> (total * entry.ownershipPct / 100)
      }.toMap
      if teamTotals.isEmpty
        || teamTotals.values.forall(_ == BigDecimal(0))
      then (round, teamTotals, Map.empty[UUID, BigDecimal])
      else
        val maxEarnings = teamTotals.values.max
        val winners = teamTotals
          .filter(_._2 == maxEarnings).keys.toList
        val numWinners = winners.size
        val winnerCollects =
          sideBetPerTeam * (numTeams - numWinners) / numWinners
        val payouts = teamTotals.map((tid, _) =>
          if winners.contains(tid) then tid -> winnerCollects
          else tid -> -sideBetPerTeam)
        (round, teamTotals, payouts)
    }

  private def aggregateSideBets(
      perRound: List[(Int, Map[UUID, BigDecimal],
        Map[UUID, BigDecimal])]
  ): Map[UUID, BigDecimal] =
    perRound.map(_._3)
      .foldLeft(Map.empty[UUID, BigDecimal]) { (acc, roundMap) =>
        roundMap.foldLeft(acc)((a, entry) =>
          a.updated(entry._1,
            a.getOrElse(entry._1, BigDecimal(0)) + entry._2))
      }

  private def buildSideBetDetail(
      perRound: List[(Int, Map[UUID, BigDecimal],
        Map[UUID, BigDecimal])],
      teams: List[Team],
      allRosters: List[RosterEntry],
      golferMap: Map[UUID, Golfer]
  ): List[Json] =
    perRound.map { (round, cumEarnings, payouts) =>
      val teamEntries = teams.map { team =>
        val entry = allRosters.find(e =>
          e.teamId == team.id &&
          e.draftRound.contains(round))
        val golferName = entry.flatMap(e =>
          golferMap.get(e.golferId))
          .map(_.lastName.toUpperCase).getOrElse("—")
        val earnings = cumEarnings
          .getOrElse(team.id, BigDecimal(0))
        val payout = payouts
          .getOrElse(team.id, BigDecimal(0))
        Json.obj(
          "team_id" -> team.id.asJson,
          "golfer_name" -> golferName.asJson,
          "cumulative_earnings" -> earnings.asJson,
          "payout" -> payout.asJson
        )
      }
      Json.obj(
        "round" -> round.asJson,
        "teams" -> teamEntries.asJson
      )
    }

  private def buildUndraftedAgg(
      allResults: List[TournamentResult],
      completed: List[Tournament],
      rosteredGolferIds: Set[UUID],
      golferMap: Map[UUID, Golfer],
      rules: SeasonRules
  ): List[Json] =
    allResults
      .filter(r =>
        r.position.exists(_ <= 10) &&
        !rosteredGolferIds.contains(r.golferId))
      .groupBy(_.golferId)
      .toList.map { (golferId, results) =>
        val name = golferMap.get(golferId)
          .map(g => s"${g.firstName.head}. ${g.lastName}")
          .getOrElse("?")
        val totalPayout = results.map { r =>
          val tournament = completed
            .find(_.id == r.tournamentId)
          val multiplier = tournament
            .map(_.payoutMultiplier)
            .getOrElse(BigDecimal(1))
          val tResults = allResults
            .filter(_.tournamentId == r.tournamentId)
          val numTied = tResults
            .count(_.position == r.position)
          PayoutTable.tieSplitPayout(
            r.position.getOrElse(99), numTied,
            multiplier, rules
          )
        }.sum
        Json.obj(
          "name" -> name.asJson,
          "position" -> Json.Null,
          "payout" -> totalPayout.asJson
        )
      }
      .sortBy(j => j.hcursor.downField("payout")
        .as[BigDecimal].getOrElse(BigDecimal(0)))
      .reverse

  /** Get a golfer's season top-10 history: tournament, position, earnings. */
  def getGolferHistory(seasonId: UUID, golferId: UUID): IO[Json] =
    val action = for
      golfer <- GolferRepository.findById(golferId)
      scores <- ScoreRepository.getGolferSeasonScores(seasonId, golferId)
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

  /** Rankings with historical cumulative totals per team
    * through an optional tournament cutoff.
    */
  def getRankings(
      seasonId: UUID,
      live: Boolean = false,
      throughTournamentId: Option[UUID] = None
  ): IO[Json] =
    val action = for
      seasonOpt <- SeasonRepository.findById(seasonId)
      teams <- TeamRepository.findBySeason(seasonId)
      completedTournaments <- TournamentRepository
        .findAll(seasonId = None, status = Some("completed"))
      allRosters <- TeamRepository.getRosterBySeason(seasonId)
      throughTournament <- throughTournamentId
        .traverse(TournamentRepository.findById).map(_.flatten)

      // Filter completed tournaments through the selected cutoff
      includedTournaments = filterThroughTournament(
        completedTournaments, throughTournament
      )
      allScores <- includedTournaments
        .flatTraverse(t => ScoreRepository.getScores(seasonId, t.id))

      rules = seasonOpt.map(_.seasonRules).getOrElse(SeasonRules.default)
      includedIds = includedTournaments.map(_.id).toSet

      // Side bet cumulative — scoped to included tournaments only
      sideBetCumulative <- rules.sideBetRounds.traverse { round =>
        val roundPicks = allRosters.filter(_.draftRound.contains(round))
        roundPicks.traverse { entry =>
          scopedSideBetTotal(
            seasonId, entry.teamId, entry.golferId, includedIds
          ).map(total =>
            (entry.teamId, total * entry.ownershipPct / 100)
          )
        }.map(entries =>
          (round, entries.map((tid, amt) => tid -> amt).toMap)
        )
      }

      // Determine live candidates: if through-tournament is
      // not completed, use it; otherwise find all non-completed
      allTournamentsForSeason <- TournamentRepository
        .findAll(seasonId = Some(seasonId), status = None)
    yield
      val nonCompleted = allTournamentsForSeason
        .filter(_.status != "completed")
      val liveCandidates = throughTournament match
        case Some(t) =>
          val priorNonCompleted = nonCompleted
            .filter(tBefore(_, t))
          val selectedIfLive =
            if t.status != "completed" then List(t)
            else Nil
          (priorNonCompleted ++ selectedIfLive)
            .sorted(tournamentOrd)
        case None =>
          nonCompleted.sorted(tournamentOrd)

      val numTeams = teams.size
      val sideBetPerTeam = rules.sideBetAmount
      val sorted = includedTournaments.sorted(tournamentOrd)

      val sideBetResults = computeSideBets(
        sideBetCumulative, numTeams, sideBetPerTeam
      )

      val history = buildCumulativeHistory(
        sorted, allScores, teams, numTeams
      )

      val weekLabels = sorted.map(t =>
        t.metadata.hcursor.downField("week")
          .as[String].getOrElse("")
      )
      val tournamentLabels = sorted.map(_.name)
      val currentTotals = history.lastOption
        .getOrElse(teams.map(t => t.id -> BigDecimal(0)).toMap)

      val teamRankings = teams.map { team =>
        val subtotal = currentTotals.getOrElse(team.id, BigDecimal(0))
        val sideBets = sideBetResults.getOrElse(team.id, BigDecimal(0))
        val totalCash = subtotal + sideBets
        val seriesData = history
          .map(h => h.getOrElse(team.id, BigDecimal(0)) + sideBets)
        Json.obj(
          "team_id" -> team.id.asJson,
          "team_name" -> team.teamName.asJson,
          "subtotal" -> subtotal.asJson,
          "side_bets" -> sideBets.asJson,
          "total_cash" -> totalCash.asJson,
          "series" -> seriesData.asJson
        )
      }.sortBy(t =>
        t.hcursor.downField("total_cash")
          .as[BigDecimal].getOrElse(BigDecimal(0))
      ).reverse

      val ctx = RankingsContext(
        allRosters, rules, sideBetCumulative, numTeams
      )
      (Json.obj(
        "teams" -> teamRankings.asJson,
        "weeks" -> weekLabels.asJson,
        "tournament_names" -> tournamentLabels.asJson
      ), liveCandidates, ctx)

    action.transact(xa).flatMap: (baseRankings, liveCandidates, ctx) =>
      if live && liveCandidates.nonEmpty then
        liveCandidates.foldLeft(IO.pure((baseRankings, ctx))) {
          (accIO, tournament) =>
            accIO.flatMap((acc, accCtx) =>
              overlayLiveRankings(seasonId, acc, tournament, accCtx)
            )
        }.map(_._1)
      else IO.pure(baseRankings)

  private[service] def filterThroughTournament(
      completed: List[Tournament],
      through: Option[Tournament]
  ): List[Tournament] =
    through match
      case None => completed
      case Some(t) => completed.filter(tOnOrBefore(_, t))

  private def scopedSideBetTotal(
      seasonId: UUID,
      teamId: UUID,
      golferId: UUID,
      tournamentIds: Set[UUID]
  ): ConnectionIO[BigDecimal] =
    if tournamentIds.isEmpty then BigDecimal(0).pure[ConnectionIO]
    else
      val inClause = Fragments.in(
        fr"fs.tournament_id",
        cats.data.NonEmptyList.fromListUnsafe(tournamentIds.toList)
      )
      (fr"""SELECT COALESCE(SUM(fs.points), 0)
            FROM fantasy_scores fs
            WHERE fs.season_id = $seasonId
              AND fs.team_id = $teamId
              AND fs.golfer_id = $golferId
              AND""" ++ inClause)
        .query[BigDecimal].unique

  private def computeSideBets(
      sideBetCumulative: List[(Int, Map[UUID, BigDecimal])],
      numTeams: Int,
      sideBetPerTeam: BigDecimal
  ): Map[UUID, BigDecimal] =
    val perRound = sideBetCumulative.map: (_, teamTotals) =>
      if teamTotals.isEmpty
        || teamTotals.values.forall(_ == BigDecimal(0))
      then Map.empty[UUID, BigDecimal]
      else
        val maxEarnings = teamTotals.values.max
        val winners =
          teamTotals.filter(_._2 == maxEarnings).keys.toSet
        val numWinners = winners.size
        val winnerCollects =
          sideBetPerTeam * (numTeams - numWinners) / numWinners
        teamTotals.map((tid, _) =>
          if winners.contains(tid) then tid -> winnerCollects
          else tid -> -sideBetPerTeam
        )
    perRound.foldLeft(Map.empty[UUID, BigDecimal]): (acc, roundMap) =>
      roundMap.foldLeft(acc)((a, entry) =>
        a.updated(
          entry._1,
          a.getOrElse(entry._1, BigDecimal(0)) + entry._2
        )
      )

  private def buildCumulativeHistory(
      sortedTournaments: List[Tournament],
      allScores: List[FantasyScore],
      teams: List[Team],
      numTeams: Int
  ): List[Map[UUID, BigDecimal]] =
    sortedTournaments.scanLeft(
      teams.map(t => t.id -> BigDecimal(0)).toMap
    ) { (cumulative, tournament) =>
      val tScores = allScores.filter(_.tournamentId == tournament.id)
      val teamTopTens = tScores.groupBy(_.teamId).view
        .mapValues(_.map(_.points).sum).toMap
      val totalPot = teamTopTens.values.sum
      teams.map { t =>
        val weeklyTotal =
          teamTopTens.getOrElse(t.id, BigDecimal(0)) * numTeams -
            totalPot
        t.id -> (cumulative.getOrElse(t.id, BigDecimal(0)) +
          weeklyTotal)
      }.toMap
    }.tail

  private def overlayLiveRankings(
      seasonId: UUID,
      baseRankings: Json,
      tournament: Tournament,
      ctx: RankingsContext
  ): IO[(Json, RankingsContext)] =
    val teams = baseRankings.hcursor
      .downField("teams").as[List[Json]].getOrElse(Nil)
    val numTeams = ctx.numTeams
    espnImportService
      .previewByDate(seasonId, tournament.startDate)
      .map { previews =>
        matchPreview(previews, tournament) match
          case None => (baseRankings, ctx)
          case Some(preview) =>
            val totalPot = preview.teams.map(_.topTenEarnings).sum
            val liveWeekly = preview.teams.map(t =>
              t.teamId.toString ->
                (t.topTenEarnings * numTeams - totalPot)
            ).toMap
            val weekLabel = tournament.metadata.hcursor
              .downField("week").as[String].getOrElse("")

            // Build live earnings by (teamId, golferId)
            val liveByGolfer: Map[(UUID, UUID), BigDecimal] =
              preview.teams.flatMap(t =>
                t.golferScores.map(gs =>
                  (t.teamId, gs.golferId) -> gs.payout)
              ).toMap

            // Update side bet cumulative with live earnings
            val updatedCumulative = ctx.sideBetCumulative.map {
              (round, teamTotals) =>
                val withLive = ctx.allRosters
                  .filter(_.draftRound.contains(round))
                  .foldLeft(teamTotals) { (acc, entry) =>
                    val liveEarnings = liveByGolfer
                      .getOrElse((entry.teamId, entry.golferId),
                        BigDecimal(0))
                    val adjusted =
                      liveEarnings * entry.ownershipPct / 100
                    if adjusted == BigDecimal(0) then acc
                    else acc.updated(entry.teamId,
                      acc.getOrElse(entry.teamId,
                        BigDecimal(0)) + adjusted)
                  }
                (round, withLive)
            }
            val newSideBets = computeSideBets(
              updatedCumulative, numTeams, ctx.rules.sideBetAmount
            )

            val updatedTeams = teams.map { t =>
              val teamId = t.hcursor
                .downField("team_id").as[String].getOrElse("")
              val teamUuid = UUID.fromString(teamId)
              val subtotal = t.hcursor.downField("subtotal")
                .as[BigDecimal].getOrElse(BigDecimal(0))
              val sideBets = newSideBets
                .getOrElse(teamUuid, BigDecimal(0))
              val liveWeeklyTotal =
                liveWeekly.getOrElse(teamId, BigDecimal(0))
              val newSubtotal = subtotal + liveWeeklyTotal
              val newTotal = newSubtotal + sideBets
              val series = t.hcursor.downField("series")
                .as[List[BigDecimal]].getOrElse(Nil)
              t.mapObject(_
                .add("subtotal", newSubtotal.asJson)
                .add("side_bets", sideBets.asJson)
                .add("total_cash", newTotal.asJson)
                .add("series", (series :+ newTotal).asJson)
                .add("live_weekly", liveWeeklyTotal.asJson))
            }

            val weeks = baseRankings.hcursor
              .downField("weeks").as[List[String]].getOrElse(Nil)
            val names = baseRankings.hcursor
              .downField("tournament_names")
              .as[List[String]].getOrElse(Nil)
            val sortedTeams = updatedTeams.sortBy(t =>
              t.hcursor.downField("total_cash")
                .as[BigDecimal].getOrElse(BigDecimal(0))
            ).reverse
            val updatedRankings = baseRankings.mapObject(_
              .add("teams", sortedTeams.asJson)
              .add("weeks", (weeks :+ weekLabel).asJson)
              .add("tournament_names",
                (names :+ (preview.espnName + " *")).asJson)
              .add("live", Json.True))
            (updatedRankings, ctx.copy(
              sideBetCumulative = updatedCumulative))
      }.handleError(_ => (baseRankings, ctx))

  /** Overlay a prior non-completed tournament's ESPN data onto
    * a report. Adds the tournament's zero-sum totals to `previous`
    * and live golfer earnings to `season_earnings`/`season_top_tens`.
    * Does NOT modify per-golfer `earnings` (those belong to the
    * selected tournament). Also updates side bet cumulative data. */
  private[service] def overlayPriorLivePreview(
      report: Json,
      preview: EspnLivePreview,
      rules: SeasonRules
  ): Json =
    val teams = report.hcursor.downField("teams")
      .as[List[Json]].getOrElse(Nil)
    val numTeams = teams.size
    if numTeams == 0 then return report

    val totalPot = preview.teams.map(_.topTenEarnings).sum
    val zeroSumByTeam: Map[String, BigDecimal] =
      preview.teams.map(t =>
        t.teamId.toString ->
          (t.topTenEarnings * numTeams - totalPot)
      ).toMap

    val golferPayouts
        : Map[(String, String), (BigDecimal, Int)] =
      preview.teams.flatMap(t =>
        t.golferScores.map(gs =>
          (t.teamId.toString, gs.golferId.toString) ->
            (gs.payout, gs.position))
      ).toMap

    val updatedTeams = teams.map { teamJson =>
      val teamId = teamJson.hcursor
        .downField("team_id").as[String].getOrElse("")
      val previous = teamJson.hcursor
        .downField("previous")
        .as[BigDecimal].getOrElse(BigDecimal(0))
      val weeklyTotal = teamJson.hcursor
        .downField("weekly_total")
        .as[BigDecimal].getOrElse(BigDecimal(0))
      val sideBets = teamJson.hcursor
        .downField("side_bets")
        .as[BigDecimal].getOrElse(BigDecimal(0))
      val topTenCount = teamJson.hcursor
        .downField("top_ten_count").as[Int].getOrElse(0)
      val topTenMoney = teamJson.hcursor
        .downField("top_ten_money")
        .as[BigDecimal].getOrElse(BigDecimal(0))

      val priorWeekly =
        zeroSumByTeam.getOrElse(teamId, BigDecimal(0))
      val newPrevious = previous + priorWeekly
      val newSubtotal = newPrevious + weeklyTotal

      // Update golfer season stats
      val rows = teamJson.hcursor
        .downField("rows").as[List[Json]].getOrElse(Nil)
      var addedTopTens = 0
      var addedMoney = BigDecimal(0)
      val updatedRows = rows.map { row =>
        val golferId = row.hcursor
          .downField("golfer_id").as[String].getOrElse("")
        golferPayouts.get((teamId, golferId)) match
          case Some((payout, _)) =>
            addedTopTens += 1
            addedMoney += payout
            val se = row.hcursor
              .downField("season_earnings")
              .as[BigDecimal].getOrElse(BigDecimal(0))
            val st = row.hcursor
              .downField("season_top_tens")
              .as[Int].getOrElse(0)
            row.mapObject(_
              .add("season_earnings",
                (se + payout).asJson)
              .add("season_top_tens",
                (st + 1).asJson))
          case None => row
      }

      teamJson.mapObject(_
        .add("previous", newPrevious.asJson)
        .add("subtotal", newSubtotal.asJson)
        .add("total_cash",
          (newSubtotal + sideBets).asJson)
        .add("rows", updatedRows.asJson)
        .add("top_ten_count",
          (topTenCount + addedTopTens).asJson)
        .add("top_ten_money",
          (topTenMoney + addedMoney).asJson))
    }

    // Update side bet detail with prior tournament data
    val sideBetPerTeam = rules.sideBetAmount
    val baseSideBetDetail = report.hcursor
      .downField("side_bet_detail")
      .as[List[Json]].getOrElse(Nil)
    val updatedSideBetDetail = baseSideBetDetail.map {
      rdJson =>
        val round = rdJson.hcursor
          .downField("round").as[Int].getOrElse(0)
        val rdTeams = rdJson.hcursor
          .downField("teams").as[List[Json]].getOrElse(Nil)
        val updatedRdTeams = rdTeams.map { entry =>
          val teamId = entry.hcursor
            .downField("team_id").as[String].getOrElse("")
          val cumEarnings = entry.hcursor
            .downField("cumulative_earnings")
            .as[BigDecimal].getOrElse(BigDecimal(0))
          val gidOpt = updatedTeams.find(t =>
            t.hcursor.downField("team_id")
              .as[String].getOrElse("") == teamId
          ).flatMap { t =>
            t.hcursor.downField("rows")
              .as[List[Json]].getOrElse(Nil)
              .find(r => r.hcursor.downField("round")
                .as[Int].getOrElse(0) == round)
              .flatMap(r => r.hcursor
                .downField("golfer_id")
                .as[String].toOption)
          }
          val liveEarnings = gidOpt
            .flatMap(gid =>
              golferPayouts.get((teamId, gid)))
            .map(_._1).getOrElse(BigDecimal(0))
          val ownershipPct = updatedTeams.find(t =>
            t.hcursor.downField("team_id")
              .as[String].getOrElse("") == teamId
          ).flatMap { t =>
            t.hcursor.downField("rows")
              .as[List[Json]].getOrElse(Nil)
              .find(r => r.hcursor.downField("round")
                .as[Int].getOrElse(0) == round)
              .flatMap(r => r.hcursor
                .downField("ownership_pct")
                .as[BigDecimal].toOption)
          }.getOrElse(BigDecimal(100))
          val adjusted = liveEarnings * ownershipPct / 100
          entry.mapObject(_.add("cumulative_earnings",
            (cumEarnings + adjusted).asJson))
        }
        // Recompute round payouts
        val teamEarnings = updatedRdTeams.map(e =>
          e.hcursor.downField("team_id")
            .as[String].getOrElse("") ->
          e.hcursor.downField("cumulative_earnings")
            .as[BigDecimal].getOrElse(BigDecimal(0))
        ).toMap
        val allZero =
          teamEarnings.values.forall(_ == BigDecimal(0))
        val finalRdTeams =
          if allZero then updatedRdTeams.map(
            _.mapObject(
              _.add("payout", BigDecimal(0).asJson)))
          else
            val maxE = teamEarnings.values.max
            val winners =
              teamEarnings.filter(_._2 == maxE).keys.toSet
            val nw = winners.size
            val winnerCollects =
              sideBetPerTeam * (numTeams - nw) / nw
            updatedRdTeams.map { e =>
              val tid = e.hcursor.downField("team_id")
                .as[String].getOrElse("")
              val payout =
                if winners.contains(tid) then winnerCollects
                else -sideBetPerTeam
              e.mapObject(
                _.add("payout", payout.asJson))
            }
        rdJson.mapObject(
          _.add("teams", finalRdTeams.asJson))
    }

    // Recompute side bet totals per team
    val sideBetTotals: Map[String, BigDecimal] =
      updatedSideBetDetail.flatMap { rdJson =>
        rdJson.hcursor.downField("teams")
          .as[List[Json]].getOrElse(Nil).map { e =>
            e.hcursor.downField("team_id")
              .as[String].getOrElse("") ->
            e.hcursor.downField("payout")
              .as[BigDecimal].getOrElse(BigDecimal(0))
          }
      }.groupBy(_._1).view
        .mapValues(_.map(_._2).sum).toMap

    // Apply updated side bets and recompute total_cash
    val finalTeams = updatedTeams.map { t =>
      val teamId = t.hcursor.downField("team_id")
        .as[String].getOrElse("")
      val subtotal = t.hcursor.downField("subtotal")
        .as[BigDecimal].getOrElse(BigDecimal(0))
      val newSideBets = sideBetTotals
        .getOrElse(teamId, BigDecimal(0))
      t.mapObject(_
        .add("side_bets", newSideBets.asJson)
        .add("total_cash",
          (subtotal + newSideBets).asJson))
    }

    val standingsOrder = finalTeams.sortBy(t =>
      t.hcursor.downField("total_cash")
        .as[BigDecimal].getOrElse(BigDecimal(0))
    ).reverse.zipWithIndex.map((t, i) =>
      Json.obj(
        "rank" -> (i + 1).asJson,
        "team_name" -> t.hcursor.downField("team_name")
          .as[String].getOrElse("").asJson,
        "total_cash" -> t.hcursor.downField("total_cash")
          .as[BigDecimal].getOrElse(BigDecimal(0)).asJson
      )
    )

    report.mapObject(_
      .add("teams", finalTeams.asJson)
      .add("side_bet_detail", updatedSideBetDetail.asJson)
      .add("standings_order", standingsOrder.asJson)
      .add("live", Json.True))

  /** Overlay ESPN live preview data onto the base report.
    * Replaces per-golfer earnings with live projected payouts and recomputes
    * weekly totals, subtotals, and total cash. */
  private[service] def mergeLiveData(
      report: Json,
      previews: List[EspnLivePreview],
      rules: SeasonRules,
      additive: Boolean = false
  ): Json =
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
            val baseEarnings = row.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(0))
            val baseSeasonEarnings = row.hcursor.downField("season_earnings").as[BigDecimal].getOrElse(BigDecimal(0))
            val baseSeasonTopTens = row.hcursor.downField("season_top_tens").as[Int].getOrElse(0)
            val baseTopTens = row.hcursor.downField("top_tens").as[Int].getOrElse(0)
            livePayout.get((teamId, golferId)) match
              case Some((payout, pos, stp)) =>
                val numTied = livePayout.values.count(_._2 == pos)
                val posStr = if numTied > 1 then s"T$pos" else s"$pos"
                val stpStr = stp.map(s => if s == 0 then "E" else if s > 0 then s"+$s" else s.toString)
                val newEarnings =
                  if additive then baseEarnings + payout
                  else payout
                val newTopTens =
                  if additive then baseTopTens + 1
                  else 1
                row.deepMerge(Json.obj(
                  "earnings" -> newEarnings.asJson,
                  "position_str" -> posStr.asJson,
                  "score_to_par" -> stpStr.asJson,
                  "top_tens" -> newTopTens.asJson,
                  "season_earnings" -> (baseSeasonEarnings + payout).asJson,
                  "season_top_tens" -> (baseSeasonTopTens + 1).asJson
                ))
              case None =>
                if additive then row
                else row.deepMerge(Json.obj(
                  "earnings" -> BigDecimal(0).asJson,
                  "top_tens" -> 0.asJson))

          val weeklyTopTens = updatedRows.foldLeft(BigDecimal(0))((acc, r) =>
            acc + r.hcursor.downField("earnings").as[BigDecimal].getOrElse(BigDecimal(0)))
          val liveTopTenCount =
            if additive then
              updatedRows.map(r =>
                r.hcursor.downField("top_tens")
                  .as[Int].getOrElse(0)).sum
            else
              topTenCount + updatedRows.count(r =>
                r.hcursor.downField("top_tens")
                  .as[Int].getOrElse(0) > 0)

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
        val sideBetPerTeam = rules.sideBetAmount
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
