package com.cwfgw.service

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import com.cwfgw.domain.given

case class EspnImportResult(
  tournamentId: UUID,
  espnName: String,
  espnId: String,
  completed: Boolean,
  totalCompetitors: Int,
  matched: Int,
  unmatched: List[String],
  created: Int,
  collisions: List[String] = Nil
) derives ConfiguredCodec

case class ImportedPlayer(name: String, position: Int, matched: Boolean, created: Boolean)

case class EspnLivePreview(
  espnName: String,
  espnId: String,
  completed: Boolean,
  payoutMultiplier: BigDecimal,
  totalCompetitors: Int,
  teams: List[PreviewTeamScore],
  leaderboard: List[PreviewLeaderboardEntry]
) derives ConfiguredCodec

case class PreviewTeamScore(
  teamId: UUID,
  teamName: String,
  ownerName: String,
  topTenEarnings: BigDecimal,
  golferScores: List[PreviewGolferScore],
  weeklyTotal: BigDecimal = BigDecimal(0)
) derives ConfiguredCodec

case class PreviewGolferScore(
  golferName: String,
  golferId: UUID,
  position: Int,
  numTied: Int,
  scoreToPar: Option[Int],
  basePayout: BigDecimal,
  ownershipPct: BigDecimal,
  payout: BigDecimal
) derives ConfiguredCodec

case class PreviewLeaderboardEntry(
  name: String,
  position: Int,
  scoreToPar: Option[Int],
  thru: Option[String],
  rostered: Boolean,
  teamName: Option[String]
) derives ConfiguredCodec
