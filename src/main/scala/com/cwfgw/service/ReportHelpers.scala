package com.cwfgw.service

import com.cwfgw.domain.*

import java.util.UUID

/** Pure shared utilities for report building and live overlay. */
object ReportHelpers:

  /** Orders tournaments by (startDate, name) to handle same-date multi-events like Week 8A / 8B. */
  val tournamentOrd: Ordering[Tournament] =
    Ordering.by(t => (t.startDate, t.name))

  /** True when `a` precedes `b` in tournament order. */
  def tBefore(a: Tournament, b: Tournament): Boolean =
    tournamentOrd.lt(a, b)

  /** True when `a` precedes or equals `b` in tournament order. */
  def tOnOrBefore(a: Tournament, b: Tournament): Boolean =
    tournamentOrd.lteq(a, b)

  /** Finds the preview matching a tournament by ESPN ID, falling back to the first preview. */
  def matchPreview(previews: List[EspnLivePreview], tournament: Tournament): Option[EspnLivePreview] =
    tournament.pgaTournamentId match
      case Some(pgaId) =>
        previews.find(_.espnId == pgaId).orElse(previews.headOption)
      case None => previews.headOption

  /** Build standings from a list of team columns sorted by totalCash descending. */
  def buildStandingsOrder(teams: List[ReportTeamColumn]): List[StandingsEntry] =
    teams.sortBy(_.totalCash).reverse.zipWithIndex.map { (t, i) =>
      StandingsEntry(i + 1, t.teamName, t.totalCash)
    }

  /** Compute side bet round payouts from a map of teamId -> cumulative earnings.
    * Returns the updated entries list with recomputed payouts.
    */
  def recomputeSideBetPayouts(
    entries: List[ReportSideBetTeamEntry],
    numTeams: Int,
    sideBetPerTeam: BigDecimal
  ): List[ReportSideBetTeamEntry] =
    val teamEarnings = entries.map(e => e.teamId -> e.cumulativeEarnings).toMap
    val allZero = teamEarnings.values.forall(_ == BigDecimal(0))
    if allZero then entries.map(_.copy(payout = BigDecimal(0)))
    else
      val maxE = teamEarnings.values.max
      val winners = teamEarnings.filter(_._2 == maxE).keys.toSet
      val nw = winners.size
      val winnerCollects = sideBetPerTeam * (numTeams - nw) / nw
      entries.map { e =>
        val payout =
          if winners.contains(e.teamId) then winnerCollects
          else -sideBetPerTeam
        e.copy(payout = payout)
      }

  /** Filter completed tournaments through an optional cutoff tournament. */
  def filterThroughTournament(
    completed: List[Tournament],
    through: Option[Tournament]
  ): List[Tournament] = through match
    case None    => completed
    case Some(t) => completed.filter(tOnOrBefore(_, t))

  /** Format a score-to-par integer as a display string (E for even, +5, -3). */
  def formatStp(s: Int): String =
    if s == 0 then "E"
    else if s > 0 then s"+$s"
    else s.toString
