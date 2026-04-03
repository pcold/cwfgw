package com.cwfgw.service

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import com.cwfgw.domain.{*, given}

// ================================================================
// Weekly Report / Season Report
// ================================================================

/** A golfer row in a team's report column. */
case class ReportRow(
  round: Int,
  golferName: Option[String],
  golferId: Option[UUID],
  positionStr: Option[String],
  scoreToPar: Option[String],
  earnings: BigDecimal,
  topTens: Int,
  ownershipPct: BigDecimal,
  seasonEarnings: BigDecimal,
  seasonTopTens: Int
) derives ConfiguredCodec

/** A team column in the report grid. */
case class ReportTeamColumn(
  teamId: UUID,
  teamName: String,
  ownerName: String,
  rows: List[ReportRow],
  topTens: BigDecimal,
  weeklyTotal: BigDecimal,
  previous: BigDecimal,
  subtotal: BigDecimal,
  topTenCount: Int,
  topTenMoney: BigDecimal,
  sideBets: BigDecimal,
  totalCash: BigDecimal
) derives ConfiguredCodec

/** An undrafted golfer who finished in the top 10. */
case class UndraftedGolfer(name: String, position: Option[Int], payout: BigDecimal, scoreToPar: Option[String] = None)
    derives ConfiguredCodec

/** A team's entry in a side bet detail round. */
case class ReportSideBetTeamEntry(teamId: UUID, golferName: String, cumulativeEarnings: BigDecimal, payout: BigDecimal)
    derives ConfiguredCodec

/** One round of side bet detail. */
case class ReportSideBetRound(round: Int, teams: List[ReportSideBetTeamEntry]) derives ConfiguredCodec

/** An entry in the standings order. */
case class StandingsEntry(rank: Int, teamName: String, totalCash: BigDecimal) derives ConfiguredCodec

/** Tournament info block in a report. */
case class ReportTournamentInfo(
  id: Option[UUID],
  name: Option[String],
  startDate: Option[String],
  endDate: Option[String],
  status: Option[String],
  payoutMultiplier: BigDecimal,
  week: Option[String]
) derives ConfiguredCodec

/** The full weekly/season report. */
case class WeeklyReport(
  tournament: ReportTournamentInfo,
  teams: List[ReportTeamColumn],
  undraftedTopTens: List[UndraftedGolfer],
  sideBetDetail: List[ReportSideBetRound],
  standingsOrder: List[StandingsEntry],
  live: Option[Boolean] = None
) derives ConfiguredCodec

// ================================================================
// Rankings
// ================================================================

/** A team's ranking entry with cumulative series data. */
case class TeamRanking(
  teamId: UUID,
  teamName: String,
  subtotal: BigDecimal,
  sideBets: BigDecimal,
  totalCash: BigDecimal,
  series: List[BigDecimal],
  liveWeekly: Option[BigDecimal] = None
) derives ConfiguredCodec

/** Full rankings response. */
case class Rankings(
  teams: List[TeamRanking],
  weeks: List[String],
  tournamentNames: List[String],
  live: Option[Boolean] = None
) derives ConfiguredCodec

// ================================================================
// Golfer History
// ================================================================

/** A single top-10 result in a golfer's history. */
case class GolferHistoryEntry(tournament: String, position: Int, earnings: BigDecimal) derives ConfiguredCodec

/** A golfer's season history. */
case class GolferHistory(
  golferName: String,
  golferId: UUID,
  totalEarnings: BigDecimal,
  topTens: Int,
  results: List[GolferHistoryEntry]
) derives ConfiguredCodec
