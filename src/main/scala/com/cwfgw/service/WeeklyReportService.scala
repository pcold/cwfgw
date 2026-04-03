package com.cwfgw.service

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits.*
import com.cwfgw.domain.*
import com.cwfgw.repository.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.LoggerFactory

import java.time.LocalDate
import java.util.UUID

/** Assembles the full weekly report data matching the
  * PDF layout. Returns a grid: 13 team columns x 8 draft
  * round rows, with golfer results in each cell, plus
  * summary rows (top tens, weekly +/-, previous, subtotal,
  * side bets, total cash).
  *
  * When `live=true`, merges ESPN live leaderboard data for
  * in-progress tournaments to show projected standings.
  */
class WeeklyReportService(
  espnImportService: EspnImportService,
  xa: Transactor[IO]
)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger

  /** Context needed to recompute side bets during live
    * overlay.
    */
  private case class RankingsContext(
    allRosters: List[RosterEntry],
    rules: SeasonRules,
    sideBetCumulative: List[(Int, Map[UUID, BigDecimal])],
    numTeams: Int
  )

  /** Orders tournaments by (startDate, name) to handle
    * same-date multi-events like Week 8A / 8B.
    */
  private val tournamentOrd: Ordering[Tournament] =
    Ordering.by(t => (t.startDate, t.name))

  /** Finds the preview matching a tournament by ESPN ID. */
  private def matchPreview(
    previews: List[EspnLivePreview],
    tournament: Tournament
  ): Option[EspnLivePreview] =
    tournament.pgaTournamentId match
      case Some(pgaId) =>
        previews.find(_.espnId == pgaId)
          .orElse(previews.headOption)
      case None => previews.headOption

  /** True when `a` precedes `b` in tournament order. */
  private[service] def tBefore(
    a: Tournament,
    b: Tournament
  ): Boolean = tournamentOrd.lt(a, b)

  /** True when `a` precedes or equals `b` in tournament
    * order.
    */
  private[service] def tOnOrBefore(
    a: Tournament,
    b: Tournament
  ): Boolean = tournamentOrd.lteq(a, b)

  // --------------------------------------------------
  // Shared helpers
  // --------------------------------------------------

  /** Build standings from a list of team columns sorted by
    * totalCash descending.
    */
  private def buildStandingsOrder(
    teams: List[ReportTeamColumn]
  ): List[StandingsEntry] =
    teams.sortBy(_.totalCash).reverse.zipWithIndex.map {
      (t, i) =>
        StandingsEntry(i + 1, t.teamName, t.totalCash)
    }

  /** Compute side bet round payouts from a map of
    * teamId -> cumulative earnings. Returns the updated
    * entries list with recomputed payouts.
    */
  private def recomputeSideBetPayouts(
    entries: List[ReportSideBetTeamEntry],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): List[ReportSideBetTeamEntry] =
    val teamEarnings = entries.map(e =>
      e.teamId -> e.cumulativeEarnings
    ).toMap
    val allZero =
      teamEarnings.values.forall(_ == BigDecimal(0))
    if allZero then entries.map(_.copy(payout = BigDecimal(0)))
    else
      val maxE = teamEarnings.values.max
      val winners =
        teamEarnings.filter(_._2 == maxE).keys.toSet
      val nw = winners.size
      val winnerCollects =
        sideBetPerTeam * (numTeams - nw) / nw
      entries.map { e =>
        val payout =
          if winners.contains(e.teamId) then winnerCollects
          else -sideBetPerTeam
        e.copy(payout = payout)
      }

  /** Format a score-to-par integer as a display string. */
  private def formatStp(s: Int): String =
    if s == 0 then "E"
    else if s > 0 then s"+$s"
    else s.toString

  // --------------------------------------------------
  // getReport
  // --------------------------------------------------

  def getReport(
    seasonId: UUID,
    tournamentId: UUID,
    live: Boolean = false
  ): IO[WeeklyReport] =
    val action =
      for
        seasonOpt <- SeasonRepository.findById(seasonId)
        teams <- TeamRepository.findBySeason(seasonId)
        tournament <- TournamentRepository
          .findById(tournamentId)
        results <- TournamentRepository
          .findResults(tournamentId)
        allGolfers <- GolferRepository
          .findAll(activeOnly = false, search = None)
        scores <- ScoreRepository
          .getScores(seasonId, tournamentId)
        standings <- ScoreRepository
          .getStandings(seasonId)
        allTournaments <- TournamentRepository.findAll(
          seasonId = tournament.map(_.seasonId),
          status = Some("completed")
        )
        allSeasonTournaments <- TournamentRepository
          .findAll(
            seasonId = tournament.map(_.seasonId),
            status = None
          )
        allRosters <- TeamRepository
          .getRosterBySeason(seasonId)
        allScores <- allTournaments.flatTraverse(t =>
          ScoreRepository.getScores(seasonId, t.id)
        )
      yield
        val rules = seasonOpt.map(_.seasonRules)
          .getOrElse(SeasonRules.default)
        val golferMap = allGolfers.map(g => g.id -> g).toMap
        val resultsByGolfer =
          results.map(r => r.golferId -> r).toMap
        val scoresByTeamGolfer =
          scores.map(s => (s.teamId, s.golferId) -> s).toMap
        val multiplier = tournament
          .map(_.payoutMultiplier)
          .getOrElse(BigDecimal(1))
        val numTeams = teams.size

        val throughTournaments = allTournaments.filter(t =>
          tournament.forall(cur => tOnOrBefore(t, cur))
        )
        val throughScores = allScores.filter(s =>
          throughTournaments.exists(_.id == s.tournamentId)
        )

        val priorWeeklyByTeam =
          buildPriorWeekly(
            throughTournaments, throughScores,
            tournament, teams, numTeams
          )

        val cumulativeTopTenCounts = throughScores
          .groupBy(_.teamId).view.mapValues(_.size).toMap
        val cumulativeTopTenEarnings = throughScores
          .groupBy(_.teamId).view
          .mapValues(_.map(_.points).sum).toMap

        val cumulativeByTeamGolfer = throughScores
          .groupBy(s => (s.teamId, s.golferId)).view
          .mapValues(ss =>
            (ss.map(_.points).sum, ss.size)
          ).toMap

        val sideBetPerTeam = rules.sideBetAmount
        val sideBetPerRound = buildSideBetPerRound(
          rules, allRosters, throughScores,
          numTeams, sideBetPerTeam
        )
        val sideBetResults = aggregateSideBets(sideBetPerRound)

        val teamColumns = teams.map { team =>
          buildReportTeamColumn(
            team, allRosters, golferMap,
            resultsByGolfer, results,
            scoresByTeamGolfer, scores,
            cumulativeByTeamGolfer,
            priorWeeklyByTeam, cumulativeTopTenCounts,
            cumulativeTopTenEarnings, sideBetResults,
            numTeams
          )
        }

        val rosteredGolferIds =
          allRosters.map(_.golferId).toSet
        val undraftedTopTens = buildUndraftedForTournament(
          results, rosteredGolferIds, golferMap,
          multiplier, rules
        )

        val sideBetDetail = buildSideBetDetail(
          sideBetPerRound, teams, allRosters, golferMap
        )

        val priorNonCompleted = allSeasonTournaments
          .filter(t =>
            t.status != "completed" &&
            t.id != tournamentId &&
            tournament.forall(cur => tBefore(t, cur))
          )

        val info = buildTournamentInfo(tournament)

        val report = WeeklyReport(
          tournament = info,
          teams = teamColumns,
          undraftedTopTens = undraftedTopTens,
          sideBetDetail = sideBetDetail,
          standingsOrder = buildStandingsOrder(teamColumns)
        )
        (report, rules, priorNonCompleted, tournament)

    action.transact(xa).flatMap {
      (baseReport, txRules, priorNonCompleted, selTournament) =>
        if !live then IO.pure(baseReport)
        else
          val withPrior = priorNonCompleted
            .sorted(tournamentOrd)
            .foldLeft(IO.pure(baseReport)) { (accIO, t) =>
              accIO.flatMap { acc =>
                espnImportService
                  .previewByDate(seasonId, t.startDate)
                  .map { previews =>
                    matchPreview(previews, t)
                      .map(overlayPriorLivePreview(
                        acc, _, txRules
                      ))
                      .getOrElse(acc)
                  }
                  .handleErrorWith { e =>
                    logger.warn(e)(
                      s"Prior live overlay failed: ${t.name}"
                    ).as(acc)
                  }
              }
            }
          val status = baseReport.tournament.status
            .getOrElse("")
          if status != "completed" then
            val startDate = baseReport.tournament.startDate
              .getOrElse("")
            if startDate.isEmpty then withPrior
            else
              withPrior.flatMap { rpt =>
                espnImportService.previewByDate(
                  seasonId, LocalDate.parse(startDate)
                ).map { previews =>
                  val matched = selTournament
                    .flatMap(matchPreview(previews, _))
                    .toList
                  mergeLiveData(rpt, matched, txRules)
                }.handleErrorWith { e =>
                  logger.warn(e)(
                    s"Live overlay failed for $tournamentId"
                  ).as(rpt)
                }
              }
          else withPrior
    }

  /** Build prior weekly zero-sum totals for tournaments
    * before the selected one.
    */
  private def buildPriorWeekly(
    throughTournaments: List[Tournament],
    throughScores: List[FantasyScore],
    tournament: Option[Tournament],
    teams: List[Team],
    numTeams: Int
  ): Map[UUID, BigDecimal] =
    val priorTournaments = throughTournaments.filter(t =>
      tournament.forall(cur => tBefore(t, cur))
    )
    val priorScoresByTournament = throughScores
      .filter(s =>
        priorTournaments.exists(_.id == s.tournamentId)
      ).groupBy(_.tournamentId)
    priorScoresByTournament.toList.flatMap { (_, tScores) =>
      val teamTopTens = tScores.groupBy(_.teamId).view
        .mapValues(_.map(_.points).sum).toMap
      val totalPot = teamTopTens.values.sum
      teams.map { t =>
        t.id -> (teamTopTens.getOrElse(
          t.id, BigDecimal(0)
        ) * numTeams - totalPot)
      }
    }.groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap

  /** Build tournament info from an optional Tournament. */
  private def buildTournamentInfo(
    tournament: Option[Tournament]
  ): ReportTournamentInfo =
    ReportTournamentInfo(
      id = tournament.map(_.id),
      name = tournament.map(_.name),
      startDate = tournament.map(_.startDate.toString),
      endDate = tournament.map(_.endDate.toString),
      status = tournament.map(_.status),
      payoutMultiplier = tournament
        .map(_.payoutMultiplier)
        .getOrElse(BigDecimal(1)),
      week = tournament.flatMap(
        _.metadata.hcursor.downField("week")
          .as[String].toOption
      )
    )

  /** Build a single team's report column for getReport. */
  private def buildReportTeamColumn(
    team: Team,
    allRosters: List[RosterEntry],
    golferMap: Map[UUID, Golfer],
    resultsByGolfer: Map[UUID, TournamentResult],
    allResults: List[TournamentResult],
    scoresByTeamGolfer: Map[(UUID, UUID), FantasyScore],
    allScores: List[FantasyScore],
    cumulativeByTeamGolfer: Map[(UUID, UUID), (BigDecimal, Int)],
    priorWeeklyByTeam: Map[UUID, BigDecimal],
    cumulativeTopTenCounts: Map[UUID, Int],
    cumulativeTopTenEarnings: Map[UUID, BigDecimal],
    sideBetResults: Map[UUID, BigDecimal],
    numTeams: Int
  ): ReportTeamColumn =
    val roster = allRosters
      .filter(_.teamId == team.id)
      .sortBy(_.draftRound)

    val rows = buildWeeklyRows(
      roster, golferMap, resultsByGolfer,
      allResults, scoresByTeamGolfer,
      cumulativeByTeamGolfer, team.id
    )

    val weeklyTopTens = rows.map(_.earnings).sum
    val totalPot = allScores.groupBy(_.teamId).view
      .mapValues(_.map(_.points).sum).values.sum
    val weeklyTotal = weeklyTopTens * numTeams - totalPot
    val previous = priorWeeklyByTeam
      .getOrElse(team.id, BigDecimal(0))
    val subtotal = previous + weeklyTotal
    val topTenCount = cumulativeTopTenCounts
      .getOrElse(team.id, 0)
    val topTenMoney = cumulativeTopTenEarnings
      .getOrElse(team.id, BigDecimal(0))
    val sideBetTotal = sideBetResults
      .getOrElse(team.id, BigDecimal(0))

    ReportTeamColumn(
      teamId = team.id,
      teamName = team.teamName,
      ownerName = team.ownerName,
      rows = rows,
      topTens = weeklyTopTens,
      weeklyTotal = weeklyTotal,
      previous = previous,
      subtotal = subtotal,
      topTenCount = topTenCount,
      topTenMoney = topTenMoney,
      sideBets = sideBetTotal,
      totalCash = subtotal + sideBetTotal
    )

  /** Build rows 1-8 for a single team in a weekly report. */
  private def buildWeeklyRows(
    roster: List[RosterEntry],
    golferMap: Map[UUID, Golfer],
    resultsByGolfer: Map[UUID, TournamentResult],
    allResults: List[TournamentResult],
    scoresByTeamGolfer: Map[(UUID, UUID), FantasyScore],
    cumulativeByTeamGolfer: Map[(UUID, UUID), (BigDecimal, Int)],
    teamId: UUID
  ): List[ReportRow] = (1 to 8).toList.map { round =>
    roster.find(_.draftRound.contains(round)) match
      case None => emptyRow(round)
      case Some(entry) =>
        val golferName = golferMap.get(entry.golferId)
          .map(_.lastName.toUpperCase).getOrElse("?")
        val result = resultsByGolfer.get(entry.golferId)
        val score = scoresByTeamGolfer
          .get((teamId, entry.golferId))
        val earnings = score.map(_.points)
          .getOrElse(BigDecimal(0))
        val position = result.flatMap(_.position)
        val posStr = position.map { pos =>
          val numTied = allResults.count(
            _.position == result.flatMap(_.position)
          )
          if numTied > 1 then s"T$pos" else s"$pos"
        }
        val scoreToPar = result.flatMap(_.scoreToPar)
          .map(formatStp)
        val topTenCount =
          if position.exists(_ <= 10) then 1 else 0
        val (seasonEarnings, seasonTopTens) =
          cumulativeByTeamGolfer.getOrElse(
            (teamId, entry.golferId),
            (BigDecimal(0), 0)
          )
        ReportRow(
          round = round,
          golferName = Some(golferName),
          golferId = Some(entry.golferId),
          positionStr = posStr,
          scoreToPar = scoreToPar,
          earnings = earnings,
          topTens = topTenCount,
          ownershipPct = entry.ownershipPct,
          seasonEarnings = seasonEarnings,
          seasonTopTens = seasonTopTens
        )
  }

  private def emptyRow(round: Int): ReportRow =
    ReportRow(
      round = round,
      golferName = None,
      golferId = None,
      positionStr = None,
      scoreToPar = None,
      earnings = BigDecimal(0),
      topTens = 0,
      ownershipPct = BigDecimal(100),
      seasonEarnings = BigDecimal(0),
      seasonTopTens = 0
    )

  /** Build undrafted top-10 list for a single tournament. */
  private def buildUndraftedForTournament(
    results: List[TournamentResult],
    rosteredGolferIds: Set[UUID],
    golferMap: Map[UUID, Golfer],
    multiplier: BigDecimal,
    rules: SeasonRules
  ): List[UndraftedGolfer] =
    results
      .filter(r =>
        r.position.exists(_ <= 10) &&
        !rosteredGolferIds.contains(r.golferId)
      )
      .sortBy(_.position)
      .map { r =>
        val golfer = golferMap.get(r.golferId)
        val name = golfer
          .map(g => s"${g.firstName.head}. ${g.lastName}")
          .getOrElse("?")
        val numTied = results.count(_.position == r.position)
        val payout = PayoutTable.tieSplitPayout(
          r.position.getOrElse(99), numTied,
          multiplier, rules
        )
        val stpStr = r.scoreToPar.map(formatStp)
        UndraftedGolfer(
          name = name,
          position = r.position,
          payout = payout,
          scoreToPar = stpStr
        )
      }

  // --------------------------------------------------
  // getSeasonReport
  // --------------------------------------------------

  /** Season-wide report compiling data from all completed
    * tournaments. When `live=true`, overlays ESPN data from
    * in-progress tournaments.
    */
  def getSeasonReport(
    seasonId: UUID,
    live: Boolean = false
  ): IO[WeeklyReport] =
    val action =
      for
        seasonOpt <- SeasonRepository.findById(seasonId)
        teams <- TeamRepository.findBySeason(seasonId)
        allGolfers <- GolferRepository
          .findAll(activeOnly = false, search = None)
        completed <- TournamentRepository.findAll(
          seasonId = Some(seasonId),
          status = Some("completed")
        )
        allRosters <- TeamRepository
          .getRosterBySeason(seasonId)
        allScores <- completed.flatTraverse(t =>
          ScoreRepository.getScores(seasonId, t.id)
        )
        allResults <- completed.flatTraverse(t =>
          TournamentRepository.findResults(t.id)
        )
        allSeasonTournaments <- TournamentRepository
          .findAll(
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
          .groupBy(s => (s.teamId, s.golferId)).view
          .mapValues(ss =>
            (ss.map(_.points).sum, ss.size)
          ).toMap

        val topTensByTeam = allScores.groupBy(_.teamId)
          .view.mapValues(_.map(_.points).sum).toMap
        val topTenCountByTeam = allScores
          .groupBy(_.teamId).view.mapValues(_.size).toMap
        val totalPot = topTensByTeam.values.sum

        val sideBetPerTeam = rules.sideBetAmount
        val sideBetPerRound = buildSideBetPerRound(
          rules, allRosters, allScores,
          numTeams, sideBetPerTeam
        )
        val sideBetResults =
          aggregateSideBets(sideBetPerRound)

        val teamColumns = teams.map { team =>
          val roster = allRosters
            .filter(_.teamId == team.id)
            .sortBy(_.draftRound)
          val rows = buildSeasonRows(
            roster, golferMap,
            cumulativeByTeamGolfer, team.id
          )
          val teamTopTens = topTensByTeam
            .getOrElse(team.id, BigDecimal(0))
          val weeklyTotal =
            teamTopTens * numTeams - totalPot
          val topTenCount = topTenCountByTeam
            .getOrElse(team.id, 0)
          val sideBetTotal = sideBetResults
            .getOrElse(team.id, BigDecimal(0))

          ReportTeamColumn(
            teamId = team.id,
            teamName = team.teamName,
            ownerName = team.ownerName,
            rows = rows,
            topTens = teamTopTens,
            weeklyTotal = weeklyTotal,
            previous = BigDecimal(0),
            subtotal = weeklyTotal,
            topTenCount = topTenCount,
            topTenMoney = teamTopTens,
            sideBets = sideBetTotal,
            totalCash = weeklyTotal + sideBetTotal
          )
        }

        val rosteredGolferIds =
          allRosters.map(_.golferId).toSet
        val undraftedAgg = buildUndraftedAgg(
          allResults, completed, rosteredGolferIds,
          golferMap, rules
        )

        val sideBetDetail = buildSideBetDetail(
          sideBetPerRound, teams, allRosters, golferMap
        )

        val info = ReportTournamentInfo(
          id = None,
          name = Some("All Tournaments"),
          startDate = None,
          endDate = None,
          status = Some("season"),
          payoutMultiplier = BigDecimal(1),
          week = None
        )

        val report = WeeklyReport(
          tournament = info,
          teams = teamColumns,
          undraftedTopTens = undraftedAgg,
          sideBetDetail = sideBetDetail,
          standingsOrder = buildStandingsOrder(teamColumns)
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
                  espnImportService.previewByDate(
                    seasonId, tournament.startDate
                  ).map { previews =>
                    val matched = matchPreview(
                      previews, tournament
                    ).toList
                    mergeLiveData(
                      acc, matched, rules, additive = true
                    )
                  }.handleErrorWith { e =>
                    logger.warn(e)(
                      s"Live overlay failed for " +
                      s"${tournament.name}"
                    ).as(acc)
                  }
                }
            }
        else if live then
          logger.info(
            "Season report: live requested but " +
            "no non-completed tournaments found"
          ).as(baseReport)
        else IO.pure(baseReport)
    }

  // --------------------------------------------------
  // Season row / undrafted helpers
  // --------------------------------------------------

  private def buildSeasonRows(
    roster: List[RosterEntry],
    golferMap: Map[UUID, Golfer],
    cumulative: Map[(UUID, UUID), (BigDecimal, Int)],
    teamId: UUID
  ): List[ReportRow] = (1 to 8).toList.map { round =>
    roster.find(_.draftRound.contains(round)) match
      case None => emptyRow(round)
      case Some(entry) =>
        val golferName = golferMap.get(entry.golferId)
          .map(_.lastName.toUpperCase).getOrElse("?")
        val (earnings, topTens) = cumulative.getOrElse(
          (teamId, entry.golferId), (BigDecimal(0), 0)
        )
        val posStr =
          if topTens > 0 then Some(s"${topTens}x")
          else None
        ReportRow(
          round = round,
          golferName = Some(golferName),
          golferId = Some(entry.golferId),
          positionStr = posStr,
          scoreToPar = None,
          earnings = earnings,
          topTens = topTens,
          ownershipPct = entry.ownershipPct,
          seasonEarnings = earnings,
          seasonTopTens = topTens
        )
  }

  private def buildSideBetPerRound(
    rules: SeasonRules,
    allRosters: List[RosterEntry],
    allScores: List[FantasyScore],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): List[
    (Int, Map[UUID, BigDecimal], Map[UUID, BigDecimal])
  ] = rules.sideBetRounds.map { round =>
    val roundPicks =
      allRosters.filter(_.draftRound.contains(round))
    val teamTotals = roundPicks.map { entry =>
      val total = allScores
        .filter(s =>
          s.teamId == entry.teamId &&
          s.golferId == entry.golferId
        ).map(_.points).sum
      entry.teamId -> (total * entry.ownershipPct / 100)
    }.toMap
    if teamTotals.isEmpty ||
      teamTotals.values.forall(_ == BigDecimal(0))
    then (round, teamTotals, Map.empty[UUID, BigDecimal])
    else
      val maxEarnings = teamTotals.values.max
      val winners = teamTotals
        .filter(_._2 == maxEarnings).keys.toList
      val numWinners = winners.size
      val winnerCollects =
        sideBetPerTeam * (numTeams - numWinners) / numWinners
      val payouts = teamTotals.map { (tid, _) =>
        if winners.contains(tid) then tid -> winnerCollects
        else tid -> -sideBetPerTeam
      }
      (round, teamTotals, payouts)
  }

  private def aggregateSideBets(
    perRound: List[
      (Int, Map[UUID, BigDecimal], Map[UUID, BigDecimal])
    ]
  ): Map[UUID, BigDecimal] =
    perRound.map(_._3)
      .foldLeft(Map.empty[UUID, BigDecimal]) {
        (acc, roundMap) =>
          roundMap.foldLeft(acc) { (a, entry) =>
            a.updated(
              entry._1,
              a.getOrElse(entry._1, BigDecimal(0)) +
              entry._2
            )
          }
      }

  private def buildSideBetDetail(
    perRound: List[
      (Int, Map[UUID, BigDecimal], Map[UUID, BigDecimal])
    ],
    teams: List[Team],
    allRosters: List[RosterEntry],
    golferMap: Map[UUID, Golfer]
  ): List[ReportSideBetRound] =
    perRound.map { (round, cumEarnings, payouts) =>
      val teamEntries = teams.map { team =>
        val entry = allRosters.find(e =>
          e.teamId == team.id &&
          e.draftRound.contains(round)
        )
        val golferName = entry
          .flatMap(e => golferMap.get(e.golferId))
          .map(_.lastName.toUpperCase)
          .getOrElse("—")
        val earnings = cumEarnings
          .getOrElse(team.id, BigDecimal(0))
        val payout = payouts
          .getOrElse(team.id, BigDecimal(0))
        ReportSideBetTeamEntry(
          teamId = team.id,
          golferName = golferName,
          cumulativeEarnings = earnings,
          payout = payout
        )
      }
      ReportSideBetRound(round = round, teams = teamEntries)
    }

  private def buildUndraftedAgg(
    allResults: List[TournamentResult],
    completed: List[Tournament],
    rosteredGolferIds: Set[UUID],
    golferMap: Map[UUID, Golfer],
    rules: SeasonRules
  ): List[UndraftedGolfer] =
    allResults
      .filter(r =>
        r.position.exists(_ <= 10) &&
        !rosteredGolferIds.contains(r.golferId)
      )
      .groupBy(_.golferId).toList.map { (golferId, results) =>
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
          val numTied =
            tResults.count(_.position == r.position)
          PayoutTable.tieSplitPayout(
            r.position.getOrElse(99), numTied,
            multiplier, rules
          )
        }.sum
        UndraftedGolfer(
          name = name,
          position = None,
          payout = totalPayout
        )
      }.sortBy(_.payout).reverse

  // --------------------------------------------------
  // getGolferHistory
  // --------------------------------------------------

  /** Get a golfer's season top-10 history: tournament,
    * position, earnings.
    */
  def getGolferHistory(
    seasonId: UUID,
    golferId: UUID
  ): IO[GolferHistory] =
    val action =
      for
        golfer <- GolferRepository.findById(golferId)
        scores <- ScoreRepository
          .getGolferSeasonScores(seasonId, golferId)
      yield
        val name = golfer
          .map(g => s"${g.firstName} ${g.lastName}")
          .getOrElse("Unknown")
        val totalEarnings = scores.map(_._3).sum
        val results = scores.map {
          (tournamentName, position, earnings, _) =>
            GolferHistoryEntry(
              tournament = tournamentName,
              position = position,
              earnings = earnings
            )
        }
        GolferHistory(
          golferName = name,
          golferId = golferId,
          totalEarnings = totalEarnings,
          topTens = scores.size,
          results = results
        )
    action.transact(xa)

  // --------------------------------------------------
  // getRankings
  // --------------------------------------------------

  /** Rankings with historical cumulative totals per team
    * through an optional tournament cutoff.
    */
  def getRankings(
    seasonId: UUID,
    live: Boolean = false,
    throughTournamentId: Option[UUID] = None
  ): IO[Rankings] =
    val action =
      for
        seasonOpt <- SeasonRepository.findById(seasonId)
        teams <- TeamRepository.findBySeason(seasonId)
        completedTournaments <- TournamentRepository
          .findAll(seasonId = None, status = Some("completed"))
        allRosters <- TeamRepository
          .getRosterBySeason(seasonId)
        throughTournament <- throughTournamentId
          .traverse(TournamentRepository.findById)
          .map(_.flatten)

        includedTournaments = filterThroughTournament(
          completedTournaments, throughTournament
        )
        allScores <- includedTournaments.flatTraverse(t =>
          ScoreRepository.getScores(seasonId, t.id)
        )

        rules = seasonOpt.map(_.seasonRules)
          .getOrElse(SeasonRules.default)
        includedIds =
          includedTournaments.map(_.id).toSet

        sideBetCumulative <- rules.sideBetRounds
          .traverse { round =>
            val roundPicks = allRosters
              .filter(_.draftRound.contains(round))
            roundPicks.traverse { entry =>
              scopedSideBetTotal(
                seasonId, entry.teamId,
                entry.golferId, includedIds
              ).map(total =>
                (entry.teamId,
                  total * entry.ownershipPct / 100)
              )
            }.map(entries =>
              (round,
                entries.map((tid, amt) =>
                  tid -> amt
                ).toMap)
            )
          }

        allTournamentsForSeason <- TournamentRepository
          .findAll(
            seasonId = Some(seasonId), status = None
          )
      yield
        val nonCompleted = allTournamentsForSeason
          .filter(_.status != "completed")
        val liveCandidates = throughTournament match
          case Some(t) =>
            val priorNonCompleted =
              nonCompleted.filter(tBefore(_, t))
            val selectedIfLive =
              if t.status != "completed" then List(t)
              else Nil
            (priorNonCompleted ++ selectedIfLive)
              .sorted(tournamentOrd)
          case None =>
            nonCompleted.sorted(tournamentOrd)

        val numTeams = teams.size
        val sideBetPerTeam = rules.sideBetAmount
        val sorted =
          includedTournaments.sorted(tournamentOrd)

        val sideBetResults = computeSideBets(
          sideBetCumulative, numTeams, sideBetPerTeam
        )

        val history = buildCumulativeHistory(
          sorted, allScores, teams, numTeams
        )

        val weekLabels = sorted.map { t =>
          t.metadata.hcursor.downField("week")
            .as[String].getOrElse("")
        }
        val tournamentLabels = sorted.map(_.name)
        val currentTotals = history.lastOption
          .getOrElse(
            teams.map(t => t.id -> BigDecimal(0)).toMap
          )

        val teamRankings = teams.map { team =>
          val subtotal = currentTotals
            .getOrElse(team.id, BigDecimal(0))
          val sideBets = sideBetResults
            .getOrElse(team.id, BigDecimal(0))
          val totalCash = subtotal + sideBets
          val seriesData = history.map(h =>
            h.getOrElse(team.id, BigDecimal(0)) + sideBets
          )
          TeamRanking(
            teamId = team.id,
            teamName = team.teamName,
            subtotal = subtotal,
            sideBets = sideBets,
            totalCash = totalCash,
            series = seriesData
          )
        }.sortBy(_.totalCash).reverse

        val ctx = RankingsContext(
          allRosters, rules, sideBetCumulative, numTeams
        )
        val rankings = Rankings(
          teams = teamRankings,
          weeks = weekLabels,
          tournamentNames = tournamentLabels
        )
        (rankings, liveCandidates, ctx)

    action.transact(xa).flatMap {
      (baseRankings, liveCandidates, ctx) =>
        if live && liveCandidates.nonEmpty then
          liveCandidates.foldLeft(
            IO.pure((baseRankings, ctx))
          ) { (accIO, tournament) =>
            accIO.flatMap { (acc, accCtx) =>
              overlayLiveRankings(
                seasonId, acc, tournament, accCtx
              )
            }
          }.map(_._1)
        else IO.pure(baseRankings)
    }

  private[service] def filterThroughTournament(
    completed: List[Tournament],
    through: Option[Tournament]
  ): List[Tournament] = through match
    case None    => completed
    case Some(t) => completed.filter(tOnOrBefore(_, t))

  // --------------------------------------------------
  // DB helpers (unchanged)
  // --------------------------------------------------

  private def scopedSideBetTotal(
    seasonId: UUID,
    teamId: UUID,
    golferId: UUID,
    tournamentIds: Set[UUID]
  ): ConnectionIO[BigDecimal] =
    NonEmptyList.fromList(tournamentIds.toList) match
      case None => BigDecimal(0).pure[ConnectionIO]
      case Some(ids) =>
        ScoreRepository.golferPointTotalScoped(
          seasonId, teamId, golferId, ids
        )

  private def computeSideBets(
    sideBetCumulative: List[(Int, Map[UUID, BigDecimal])],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): Map[UUID, BigDecimal] =
    val perRound = sideBetCumulative.map {
      (_, teamTotals) =>
        if teamTotals.isEmpty ||
          teamTotals.values.forall(_ == BigDecimal(0))
        then Map.empty[UUID, BigDecimal]
        else
          val maxEarnings = teamTotals.values.max
          val winners = teamTotals
            .filter(_._2 == maxEarnings).keys.toSet
          val numWinners = winners.size
          val winnerCollects =
            sideBetPerTeam * (numTeams - numWinners) /
            numWinners
          teamTotals.map { (tid, _) =>
            if winners.contains(tid) then
              tid -> winnerCollects
            else tid -> -sideBetPerTeam
          }
    }
    perRound.foldLeft(Map.empty[UUID, BigDecimal]) {
      (acc, roundMap) =>
        roundMap.foldLeft(acc) { (a, entry) =>
          a.updated(
            entry._1,
            a.getOrElse(entry._1, BigDecimal(0)) + entry._2
          )
        }
    }

  private def buildCumulativeHistory(
    sortedTournaments: List[Tournament],
    allScores: List[FantasyScore],
    teams: List[Team],
    numTeams: Int
  ): List[Map[UUID, BigDecimal]] =
    sortedTournaments
      .scanLeft(
        teams.map(t => t.id -> BigDecimal(0)).toMap
      ) { (cumulative, tournament) =>
        val tScores = allScores
          .filter(_.tournamentId == tournament.id)
        val teamTopTens = tScores.groupBy(_.teamId).view
          .mapValues(_.map(_.points).sum).toMap
        val totalPot = teamTopTens.values.sum
        teams.map { t =>
          val weeklyTotal = teamTopTens.getOrElse(
            t.id, BigDecimal(0)
          ) * numTeams - totalPot
          t.id -> (cumulative.getOrElse(
            t.id, BigDecimal(0)
          ) + weeklyTotal)
        }.toMap
      }.tail

  // --------------------------------------------------
  // Live overlay: rankings
  // --------------------------------------------------

  private def overlayLiveRankings(
    seasonId: UUID,
    baseRankings: Rankings,
    tournament: Tournament,
    ctx: RankingsContext
  ): IO[(Rankings, RankingsContext)] =
    val numTeams = ctx.numTeams
    espnImportService.previewByDate(
      seasonId, tournament.startDate
    ).map { previews =>
      matchPreview(previews, tournament) match
        case None => (baseRankings, ctx)
        case Some(preview) =>
          val totalPot =
            preview.teams.map(_.topTenEarnings).sum
          val liveWeekly = preview.teams.map { t =>
            t.teamId ->
              (t.topTenEarnings * numTeams - totalPot)
          }.toMap
          val weekLabel = tournament.metadata.hcursor
            .downField("week").as[String].getOrElse("")

          val liveByGolfer = preview.teams.flatMap { t =>
            t.golferScores.map(gs =>
              (t.teamId, gs.golferId) -> gs.payout
            )
          }.toMap

          val updatedCumulative =
            ctx.sideBetCumulative.map { (round, teamTotals) =>
              val withLive = ctx.allRosters
                .filter(_.draftRound.contains(round))
                .foldLeft(teamTotals) { (acc, entry) =>
                  val liveEarnings = liveByGolfer.getOrElse(
                    (entry.teamId, entry.golferId),
                    BigDecimal(0)
                  )
                  val adjusted =
                    liveEarnings * entry.ownershipPct / 100
                  if adjusted == BigDecimal(0) then acc
                  else acc.updated(
                    entry.teamId,
                    acc.getOrElse(
                      entry.teamId, BigDecimal(0)
                    ) + adjusted
                  )
                }
              (round, withLive)
            }
          val newSideBets = computeSideBets(
            updatedCumulative, numTeams,
            ctx.rules.sideBetAmount
          )

          val updatedTeams = baseRankings.teams.map { t =>
            val subtotal = t.subtotal
            val sideBets = newSideBets
              .getOrElse(t.teamId, BigDecimal(0))
            val liveWeeklyTotal = liveWeekly
              .getOrElse(t.teamId, BigDecimal(0))
            val newSubtotal = subtotal + liveWeeklyTotal
            val newTotal = newSubtotal + sideBets
            t.copy(
              subtotal = newSubtotal,
              sideBets = sideBets,
              totalCash = newTotal,
              series = t.series :+ newTotal,
              liveWeekly = Some(liveWeeklyTotal)
            )
          }.sortBy(_.totalCash).reverse

          val updatedRankings = baseRankings.copy(
            teams = updatedTeams,
            weeks =
              baseRankings.weeks :+ weekLabel,
            tournamentNames =
              baseRankings.tournamentNames :+
              (preview.espnName + " *"),
            live = Some(true)
          )
          (updatedRankings,
            ctx.copy(
              sideBetCumulative = updatedCumulative
            ))
    }.handleError(_ => (baseRankings, ctx))

  // --------------------------------------------------
  // Live overlay: prior tournament onto report
  // --------------------------------------------------

  /** Overlay a prior non-completed tournament's ESPN data
    * onto a report. Adds the tournament's zero-sum totals
    * to `previous` and live golfer earnings to
    * `seasonEarnings`/`seasonTopTens`. Does NOT modify
    * per-golfer `earnings` (those belong to the selected
    * tournament). Also updates side bet cumulative data.
    */
  private[service] def overlayPriorLivePreview(
    report: WeeklyReport,
    preview: EspnLivePreview,
    rules: SeasonRules
  ): WeeklyReport =
    val numTeams = report.teams.size
    if numTeams == 0 then report
    else
      val totalPot =
        preview.teams.map(_.topTenEarnings).sum
      val zeroSumByTeam: Map[UUID, BigDecimal] =
        preview.teams.map(t =>
          t.teamId ->
            (t.topTenEarnings * numTeams - totalPot)
        ).toMap

      val golferPayouts
        : Map[(UUID, UUID), (BigDecimal, Int)] =
        preview.teams.flatMap { t =>
          t.golferScores.map(gs =>
            (t.teamId, gs.golferId) ->
              (gs.payout, gs.position)
          )
        }.toMap

      val updatedTeams = report.teams.map { team =>
        val priorWeekly = zeroSumByTeam
          .getOrElse(team.teamId, BigDecimal(0))
        val newPrevious = team.previous + priorWeekly
        val newSubtotal = newPrevious + team.weeklyTotal

        // Update golfer season stats via fold
        val (updatedRows, addedTopTens, addedMoney) =
          team.rows.foldLeft(
            (List.empty[ReportRow], 0, BigDecimal(0))
          ) { case ((acc, topTens, money), row) =>
            row.golferId.flatMap(gid =>
              golferPayouts.get((team.teamId, gid))
            ) match
              case Some((payout, _)) =>
                val updated = row.copy(
                  seasonEarnings =
                    row.seasonEarnings + payout,
                  seasonTopTens = row.seasonTopTens + 1
                )
                (acc :+ updated,
                  topTens + 1,
                  money + payout)
              case None =>
                (acc :+ row, topTens, money)
          }

        team.copy(
          previous = newPrevious,
          subtotal = newSubtotal,
          totalCash = newSubtotal + team.sideBets,
          rows = updatedRows,
          topTenCount = team.topTenCount + addedTopTens,
          topTenMoney = team.topTenMoney + addedMoney
        )
      }

      // Update side bet detail with prior tournament data
      val sideBetPerTeam = rules.sideBetAmount
      val updatedSideBetDetail =
        report.sideBetDetail.map { rd =>
          val updatedEntries = rd.teams.map { entry =>
            val gidOpt = updatedTeams
              .find(_.teamId == entry.teamId)
              .flatMap(_.rows
                .find(_.round == rd.round)
                .flatMap(_.golferId))
            val liveEarnings = gidOpt.flatMap { gid =>
              golferPayouts
                .get((entry.teamId, gid))
            }.map(_._1).getOrElse(BigDecimal(0))
            val ownershipPct = updatedTeams
              .find(_.teamId == entry.teamId)
              .flatMap(_.rows
                .find(_.round == rd.round)
                .map(_.ownershipPct))
              .getOrElse(BigDecimal(100))
            val adjusted =
              liveEarnings * ownershipPct / 100
            entry.copy(
              cumulativeEarnings =
                entry.cumulativeEarnings + adjusted
            )
          }
          val finalEntries = recomputeSideBetPayouts(
            updatedEntries, numTeams, sideBetPerTeam
          )
          rd.copy(teams = finalEntries)
        }

      // Recompute side bet totals per team
      val sideBetTotals: Map[UUID, BigDecimal] =
        updatedSideBetDetail.flatMap(_.teams.map(e =>
          e.teamId -> e.payout
        )).groupBy(_._1).view
          .mapValues(_.map(_._2).sum).toMap

      // Apply updated side bets and recompute totalCash
      val finalTeams = updatedTeams.map { t =>
        val newSideBets = sideBetTotals
          .getOrElse(t.teamId, BigDecimal(0))
        t.copy(
          sideBets = newSideBets,
          totalCash = t.subtotal + newSideBets
        )
      }

      report.copy(
        teams = finalTeams,
        sideBetDetail = updatedSideBetDetail,
        standingsOrder = buildStandingsOrder(finalTeams),
        live = Some(true)
      )

  // --------------------------------------------------
  // Live overlay: merge live data onto report
  // --------------------------------------------------

  /** Overlay ESPN live preview data onto the base report.
    * Replaces per-golfer earnings with live projected
    * payouts and recomputes weekly totals, subtotals, and
    * total cash.
    */
  private[service] def mergeLiveData(
    report: WeeklyReport,
    previews: List[EspnLivePreview],
    rules: SeasonRules,
    additive: Boolean = false
  ): WeeklyReport =
    previews.headOption match
      case None => report
      case Some(liveData) =>
        val livePayout
          : Map[(UUID, UUID), (BigDecimal, Int, Option[Int])] =
          liveData.teams.flatMap { team =>
            team.golferScores.map { gs =>
              (team.teamId, gs.golferId) ->
                (gs.payout, gs.position, gs.scoreToPar)
            }
          }.toMap

        val numTeams = report.teams.size

        // Phase 1: update rows and collect weekly earnings
        val phase1Teams = report.teams.map { team =>
          val updatedRows = team.rows.map { row =>
            row.golferId.flatMap(gid =>
              livePayout.get((team.teamId, gid))
            ) match
              case Some((payout, pos, stp)) =>
                val numTied = livePayout.values
                  .count(_._2 == pos)
                val posStr =
                  if numTied > 1 then s"T$pos"
                  else s"$pos"
                val stpStr = stp.map(formatStp)
                val newEarnings =
                  if additive then row.earnings + payout
                  else payout
                val newTopTens =
                  if additive then row.topTens + 1 else 1
                row.copy(
                  earnings = newEarnings,
                  positionStr = Some(posStr),
                  scoreToPar = stpStr,
                  topTens = newTopTens,
                  seasonEarnings =
                    row.seasonEarnings + payout,
                  seasonTopTens = row.seasonTopTens + 1
                )
              case None =>
                if additive then row
                else row.copy(
                  earnings = BigDecimal(0),
                  topTens = 0
                )
          }

          val weeklyTopTens =
            updatedRows.map(_.earnings).sum
          val liveTopTenCount =
            if additive then
              updatedRows.map(_.topTens).sum
            else
              team.topTenCount + updatedRows
                .count(_.topTens > 0)

          // Carry forward for phase 2
          (team.copy(rows = updatedRows,
            topTens = weeklyTopTens,
            topTenCount = liveTopTenCount),
            weeklyTopTens, team.previous, team.sideBets)
        }

        // Recompute zero-sum weekly totals
        val totalPot = phase1Teams.map(_._2).sum

        // Recompute side bets with live data
        val sideBetPerTeam = rules.sideBetAmount
        val liveSideBetDetail =
          report.sideBetDetail.map { rd =>
            val updatedEntries = rd.teams.map { entry =>
              val gidOpt = phase1Teams
                .map(_._1)
                .find(_.teamId == entry.teamId)
                .flatMap(_.rows
                  .find(_.round == rd.round)
                  .flatMap(_.golferId))
              val liveEarnings = gidOpt.flatMap { gid =>
                livePayout.get((entry.teamId, gid))
              }.map(_._1).getOrElse(BigDecimal(0))
              val ownershipPct = phase1Teams
                .map(_._1)
                .find(_.teamId == entry.teamId)
                .flatMap(_.rows
                  .find(_.round == rd.round)
                  .map(_.ownershipPct))
                .getOrElse(BigDecimal(100))
              val adjusted =
                liveEarnings * ownershipPct / 100
              entry.copy(
                cumulativeEarnings =
                  entry.cumulativeEarnings + adjusted
              )
            }
            val finalEntries = recomputeSideBetPayouts(
              updatedEntries, numTeams, sideBetPerTeam
            )
            rd.copy(teams = finalEntries)
          }

        // Aggregate live side bet totals per team
        val liveSideBetTotals: Map[UUID, BigDecimal] =
          liveSideBetDetail.flatMap(_.teams.map(e =>
            e.teamId -> e.payout
          )).groupBy(_._1).view
            .mapValues(_.map(_._2).sum).toMap

        val finalTeams = phase1Teams.map {
          (team, weeklyTopTens, previous, origSideBets) =>
            val sideBets =
              if liveSideBetTotals.nonEmpty then
                liveSideBetTotals.getOrElse(
                  team.teamId, BigDecimal(0)
                )
              else origSideBets
            val weeklyTotal =
              weeklyTopTens * numTeams - totalPot
            val subtotal = previous + weeklyTotal
            val totalCash = subtotal + sideBets
            team.copy(
              weeklyTotal = weeklyTotal,
              subtotal = subtotal,
              sideBets = sideBets,
              totalCash = totalCash
            )
        }

        report.copy(
          teams = finalTeams,
          sideBetDetail = liveSideBetDetail,
          standingsOrder =
            buildStandingsOrder(finalTeams),
          live = Some(true)
        )
