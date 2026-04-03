package com.cwfgw.service

import cats.effect.IO
import com.cwfgw.domain.*
import org.typelevel.log4cats.LoggerFactory

import java.time.LocalDate
import java.util.UUID

import ReportHelpers.*

/** Context needed to recompute side bets during live overlay. */
final case class RankingsContext(
  allRosters: List[RosterEntry],
  rules: SeasonRules,
  sideBetCumulative: List[(Int, Map[UUID, BigDecimal])],
  numTeams: Int
)

/** Merges ESPN live leaderboard data onto base reports and rankings.
  * All pure transformation methods are package-private for testability.
  */
class LiveOverlayService(
  espnImportService: EspnImportService
)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger

  // --------------------------------------------------
  // Orchestration: called by WeeklyReportService
  // --------------------------------------------------

  /** Overlay live ESPN data onto a single-tournament report. Handles both prior non-completed tournaments
    * and the selected tournament itself.
    */
  def overlayReport(
    seasonId: UUID,
    baseReport: WeeklyReport,
    rules: SeasonRules,
    priorNonCompleted: List[Tournament],
    selTournament: Option[Tournament],
    tournamentId: UUID
  ): IO[WeeklyReport] =
    val withPrior = priorNonCompleted
      .sorted(tournamentOrd)
      .foldLeft(IO.pure(baseReport)) { (accIO, t) =>
        accIO.flatMap { acc =>
          espnImportService
            .previewByDate(seasonId, t.startDate)
            .map { previews =>
              matchPreview(previews, t)
                .map(overlayPriorLivePreview(acc, _, rules))
                .getOrElse(acc)
            }
            .handleErrorWith { e =>
              logger.warn(e)(s"Prior live overlay failed: ${t.name}").as(acc)
            }
        }
      }
    val status = baseReport.tournament.status.getOrElse("")
    if status != "completed" then
      val startDate = baseReport.tournament.startDate.getOrElse("")
      if startDate.isEmpty then withPrior
      else
        withPrior.flatMap { rpt =>
          espnImportService
            .previewByDate(seasonId, LocalDate.parse(startDate))
            .map { previews =>
              val matched = selTournament.flatMap(matchPreview(previews, _)).toList
              mergeLiveData(rpt, matched, rules)
            }
            .handleErrorWith { e =>
              logger.warn(e)(s"Live overlay failed for $tournamentId").as(rpt)
            }
        }
    else withPrior

  /** Overlay live ESPN data onto a season-wide report across all non-completed tournaments. */
  def overlaySeasonReport(
    seasonId: UUID,
    baseReport: WeeklyReport,
    rules: SeasonRules,
    nonCompleted: List[Tournament]
  ): IO[WeeklyReport] =
    if nonCompleted.nonEmpty then
      val sorted = nonCompleted.sorted(tournamentOrd)
      logger.info(s"Season report live overlay: ${sorted.size} non-completed tournaments") >>
        sorted.foldLeft(IO.pure(baseReport)) { (accIO, tournament) =>
          accIO.flatMap { acc =>
            espnImportService
              .previewByDate(seasonId, tournament.startDate)
              .map { previews =>
                val matched = matchPreview(previews, tournament).toList
                mergeLiveData(acc, matched, rules, additive = true)
              }
              .handleErrorWith { e =>
                logger.warn(e)(s"Live overlay failed for ${tournament.name}").as(acc)
              }
          }
        }
    else
      logger.info("Season report: live requested but no non-completed tournaments found")
        .as(baseReport)

  /** Overlay live ESPN data onto rankings for all live candidate tournaments. */
  def overlayRankings(
    seasonId: UUID,
    baseRankings: Rankings,
    liveCandidates: List[Tournament],
    ctx: RankingsContext
  ): IO[Rankings] =
    liveCandidates
      .foldLeft(IO.pure((baseRankings, ctx))) { (accIO, tournament) =>
        accIO.flatMap { (acc, accCtx) =>
          overlayLiveRankings(seasonId, acc, tournament, accCtx)
        }
      }
      .map(_._1)

  // --------------------------------------------------
  // Core overlay methods
  // --------------------------------------------------

  /** Overlay a single live tournament onto rankings, updating cumulative side bets. */
  private def overlayLiveRankings(
    seasonId: UUID,
    baseRankings: Rankings,
    tournament: Tournament,
    ctx: RankingsContext
  ): IO[(Rankings, RankingsContext)] =
    val numTeams = ctx.numTeams
    espnImportService
      .previewByDate(seasonId, tournament.startDate)
      .map { previews =>
        matchPreview(previews, tournament) match
          case None => (baseRankings, ctx)
          case Some(preview) =>
            val totalPot = preview.teams.map(_.topTenEarnings).sum
            val liveWeekly = preview.teams.map { t =>
              t.teamId -> (t.topTenEarnings * numTeams - totalPot)
            }.toMap
            val weekLabel = tournament.week.getOrElse("")

            val liveByGolfer = preview.teams.flatMap { t =>
              t.golferScores.map(gs => (t.teamId, gs.golferId) -> gs.payout)
            }.toMap

            val updatedCumulative = ctx.sideBetCumulative.map { (round, teamTotals) =>
              val withLive = ctx.allRosters
                .filter(_.draftRound.contains(round))
                .foldLeft(teamTotals) { (acc, entry) =>
                  val liveEarnings = liveByGolfer.getOrElse(
                    (entry.teamId, entry.golferId), BigDecimal(0)
                  )
                  val adjusted = liveEarnings * entry.ownershipPct / 100
                  if adjusted == BigDecimal(0) then acc
                  else acc.updated(
                    entry.teamId,
                    acc.getOrElse(entry.teamId, BigDecimal(0)) + adjusted
                  )
                }
              (round, withLive)
            }
            val newSideBets = computeSideBets(updatedCumulative, numTeams, ctx.rules.sideBetAmount)

            val updatedTeams = baseRankings.teams.map { t =>
              val sideBets = newSideBets.getOrElse(t.teamId, BigDecimal(0))
              val liveWeeklyTotal = liveWeekly.getOrElse(t.teamId, BigDecimal(0))
              val newSubtotal = t.subtotal + liveWeeklyTotal
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
              weeks = baseRankings.weeks :+ weekLabel,
              tournamentNames = baseRankings.tournamentNames :+ (preview.espnName + " *"),
              live = Some(true)
            )
            (updatedRankings, ctx.copy(sideBetCumulative = updatedCumulative))
      }
      .handleError(_ => (baseRankings, ctx))

  /** Overlay a prior non-completed tournament's ESPN data onto a report. Adds the tournament's zero-sum
    * totals to `previous` and live golfer earnings to `seasonEarnings`/`seasonTopTens`. Does NOT modify
    * per-golfer `earnings` (those belong to the selected tournament). Also updates side bet cumulative data.
    */
  private[service] def overlayPriorLivePreview(
    report: WeeklyReport,
    preview: EspnLivePreview,
    rules: SeasonRules
  ): WeeklyReport =
    val numTeams = report.teams.size
    if numTeams == 0 then report
    else
      val totalPot = preview.teams.map(_.topTenEarnings).sum
      val zeroSumByTeam: Map[UUID, BigDecimal] =
        preview.teams.map(t => t.teamId -> (t.topTenEarnings * numTeams - totalPot)).toMap

      val golferPayouts: Map[(UUID, UUID), (BigDecimal, Int)] =
        preview.teams.flatMap { t =>
          t.golferScores.map(gs => (t.teamId, gs.golferId) -> (gs.payout, gs.position))
        }.toMap

      val updatedTeams = report.teams.map { team =>
        val priorWeekly = zeroSumByTeam.getOrElse(team.teamId, BigDecimal(0))
        val newPrevious = team.previous + priorWeekly
        val newSubtotal = newPrevious + team.weeklyTotal

        val (updatedRows, addedTopTens, addedMoney) =
          team.rows.foldLeft((List.empty[ReportRow], 0, BigDecimal(0))) {
            case ((acc, topTens, money), row) =>
              row.golferId.flatMap(gid => golferPayouts.get((team.teamId, gid))) match
                case Some((payout, _)) =>
                  val updated = row.copy(
                    seasonEarnings = row.seasonEarnings + payout,
                    seasonTopTens = row.seasonTopTens + 1
                  )
                  (acc :+ updated, topTens + 1, money + payout)
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

      val sideBetPerTeam = rules.sideBetAmount
      val updatedSideBetDetail = updateSideBetDetail(
        report.sideBetDetail, updatedTeams, golferPayouts, numTeams, sideBetPerTeam
      )

      val sideBetTotals = aggregateSideBetTotals(updatedSideBetDetail)
      val finalTeams = updatedTeams.map { t =>
        val newSideBets = sideBetTotals.getOrElse(t.teamId, BigDecimal(0))
        t.copy(sideBets = newSideBets, totalCash = t.subtotal + newSideBets)
      }

      report.copy(
        teams = finalTeams,
        sideBetDetail = updatedSideBetDetail,
        standingsOrder = buildStandingsOrder(finalTeams),
        live = Some(true)
      )

  /** Overlay ESPN live preview data onto the base report. Replaces per-golfer earnings with live
    * projected payouts and recomputes weekly totals, subtotals, and total cash.
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
        val livePayout: Map[(UUID, UUID), (BigDecimal, Int, Option[Int])] =
          liveData.teams.flatMap { team =>
            team.golferScores.map { gs =>
              (team.teamId, gs.golferId) -> (gs.payout, gs.position, gs.scoreToPar)
            }
          }.toMap

        val numTeams = report.teams.size

        // Phase 1: update rows and collect weekly earnings
        val phase1Teams = report.teams.map { team =>
          val updatedRows = team.rows.map { row =>
            row.golferId.flatMap(gid => livePayout.get((team.teamId, gid))) match
              case Some((payout, pos, stp)) =>
                val numTied = livePayout.values.count(_._2 == pos)
                val posStr = if numTied > 1 then s"T$pos" else s"$pos"
                val stpStr = stp.map(formatStp)
                val newEarnings = if additive then row.earnings + payout else payout
                val newTopTens = if additive then row.topTens + 1 else 1
                row.copy(
                  earnings = newEarnings,
                  positionStr = Some(posStr),
                  scoreToPar = stpStr,
                  topTens = newTopTens,
                  seasonEarnings = row.seasonEarnings + payout,
                  seasonTopTens = row.seasonTopTens + 1
                )
              case None =>
                if additive then row
                else row.copy(earnings = BigDecimal(0), topTens = 0)
          }

          val weeklyTopTens = updatedRows.map(_.earnings).sum
          val liveTopTenCount =
            if additive then updatedRows.map(_.topTens).sum
            else team.topTenCount + updatedRows.count(_.topTens > 0)

          (team.copy(rows = updatedRows, topTens = weeklyTopTens, topTenCount = liveTopTenCount),
            weeklyTopTens, team.previous, team.sideBets)
        }

        // Recompute zero-sum weekly totals
        val totalPot = phase1Teams.map(_._2).sum

        // Recompute side bets with live data
        val sideBetPerTeam = rules.sideBetAmount
        val liveSideBetDetail = updateSideBetDetailFromPhase1(
          report.sideBetDetail, phase1Teams.map(_._1), livePayout, numTeams, sideBetPerTeam
        )

        val liveSideBetTotals = aggregateSideBetTotals(liveSideBetDetail)

        val finalTeams = phase1Teams.map { (team, weeklyTopTens, previous, origSideBets) =>
          val sideBets =
            if liveSideBetTotals.nonEmpty then liveSideBetTotals.getOrElse(team.teamId, BigDecimal(0))
            else origSideBets
          val weeklyTotal = weeklyTopTens * numTeams - totalPot
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
          standingsOrder = buildStandingsOrder(finalTeams),
          live = Some(true)
        )

  // --------------------------------------------------
  // Side bet helpers shared by overlay methods
  // --------------------------------------------------

  /** Compute zero-sum side bet winners/losers across multiple rounds. */
  private[service] def computeSideBets(
    sideBetCumulative: List[(Int, Map[UUID, BigDecimal])],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): Map[UUID, BigDecimal] =
    val perRound = sideBetCumulative.map { (_, teamTotals) =>
      if teamTotals.isEmpty || teamTotals.values.forall(_ == BigDecimal(0))
      then Map.empty[UUID, BigDecimal]
      else
        val maxEarnings = teamTotals.values.max
        val winners = teamTotals.filter(_._2 == maxEarnings).keys.toSet
        val numWinners = winners.size
        val winnerCollects = sideBetPerTeam * (numTeams - numWinners) / numWinners
        teamTotals.map { (tid, _) =>
          if winners.contains(tid) then tid -> winnerCollects
          else tid -> -sideBetPerTeam
        }
    }
    perRound.foldLeft(Map.empty[UUID, BigDecimal]) { (acc, roundMap) =>
      roundMap.foldLeft(acc) { (a, entry) =>
        a.updated(entry._1, a.getOrElse(entry._1, BigDecimal(0)) + entry._2)
      }
    }

  // --------------------------------------------------
  // Internal helpers to reduce duplication
  // --------------------------------------------------

  /** Update side bet detail entries with live golfer earnings from a golfer payouts map. */
  private def updateSideBetDetail(
    sideBetDetail: List[ReportSideBetRound],
    teams: List[ReportTeamColumn],
    golferPayouts: Map[(UUID, UUID), (BigDecimal, Int)],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): List[ReportSideBetRound] =
    sideBetDetail.map { rd =>
      val updatedEntries = rd.teams.map { entry =>
        val gidOpt = teams
          .find(_.teamId == entry.teamId)
          .flatMap(_.rows.find(_.round == rd.round).flatMap(_.golferId))
        val liveEarnings = gidOpt
          .flatMap(gid => golferPayouts.get((entry.teamId, gid)))
          .map(_._1).getOrElse(BigDecimal(0))
        val ownershipPct = teams
          .find(_.teamId == entry.teamId)
          .flatMap(_.rows.find(_.round == rd.round).map(_.ownershipPct))
          .getOrElse(BigDecimal(100))
        val adjusted = liveEarnings * ownershipPct / 100
        entry.copy(cumulativeEarnings = entry.cumulativeEarnings + adjusted)
      }
      rd.copy(teams = recomputeSideBetPayouts(updatedEntries, numTeams, sideBetPerTeam))
    }

  /** Update side bet detail from phase1 teams using a 3-tuple livePayout map. */
  private def updateSideBetDetailFromPhase1(
    sideBetDetail: List[ReportSideBetRound],
    teams: List[ReportTeamColumn],
    livePayout: Map[(UUID, UUID), (BigDecimal, Int, Option[Int])],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): List[ReportSideBetRound] =
    sideBetDetail.map { rd =>
      val updatedEntries = rd.teams.map { entry =>
        val gidOpt = teams
          .find(_.teamId == entry.teamId)
          .flatMap(_.rows.find(_.round == rd.round).flatMap(_.golferId))
        val liveEarnings = gidOpt
          .flatMap(gid => livePayout.get((entry.teamId, gid)))
          .map(_._1).getOrElse(BigDecimal(0))
        val ownershipPct = teams
          .find(_.teamId == entry.teamId)
          .flatMap(_.rows.find(_.round == rd.round).map(_.ownershipPct))
          .getOrElse(BigDecimal(100))
        val adjusted = liveEarnings * ownershipPct / 100
        entry.copy(cumulativeEarnings = entry.cumulativeEarnings + adjusted)
      }
      rd.copy(teams = recomputeSideBetPayouts(updatedEntries, numTeams, sideBetPerTeam))
    }

  /** Aggregate side bet totals per team from side bet detail rounds. */
  private def aggregateSideBetTotals(
    sideBetDetail: List[ReportSideBetRound]
  ): Map[UUID, BigDecimal] =
    sideBetDetail
      .flatMap(_.teams.map(e => e.teamId -> e.payout))
      .groupBy(_._1).view
      .mapValues(_.map(_._2).sum).toMap
