package com.cwfgw.service

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import com.cwfgw.domain.{*, given}

/** A single golfer's score entry within a team's weekly result. */
case class GolferScoreEntry(golferId: UUID, payout: BigDecimal, breakdown: ScoreBreakdown) derives ConfiguredCodec

/** One team's weekly scoring result. */
case class TeamWeeklyResult(
  teamId: UUID,
  teamName: String,
  topTens: BigDecimal,
  weeklyTotal: BigDecimal,
  golferScores: List[GolferScoreEntry]
) derives ConfiguredCodec

/** Full result of scoring a tournament. */
case class WeeklyScoreResult(
  tournamentId: UUID,
  multiplier: BigDecimal,
  numTeams: Int,
  totalPot: BigDecimal,
  teams: List[TeamWeeklyResult]
) derives ConfiguredCodec

/** A team's entry in a side bet round. */
case class SideBetEntry(teamId: UUID, teamName: String, golferId: UUID, cumulativeEarnings: BigDecimal)
    derives ConfiguredCodec

/** The winner of a side bet round. Extends SideBetEntry with net winnings.
  */
case class SideBetWinner(
  teamId: UUID,
  teamName: String,
  golferId: UUID,
  cumulativeEarnings: BigDecimal,
  netWinnings: BigDecimal
) derives ConfiguredCodec

/** One round's side bet standings. */
case class SideBetRound(round: Int, active: Boolean, winner: Option[SideBetWinner], entries: List[SideBetEntry])
    derives ConfiguredCodec

/** A team's aggregate side bet P&L. */
case class SideBetTeamTotal(teamId: UUID, teamName: String, wins: Int, net: BigDecimal) derives ConfiguredCodec

/** Full side bet standings result. */
case class SideBetStandings(rounds: List[SideBetRound], teamTotals: List[SideBetTeamTotal]) derives ConfiguredCodec
