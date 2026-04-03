package com.cwfgw.domain

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import java.time.Instant

case class FantasyScore(
  id: UUID,
  seasonId: UUID,
  teamId: UUID,
  tournamentId: UUID,
  golferId: UUID,
  points: BigDecimal,
  position: Int,
  numTied: Int,
  basePayout: BigDecimal,
  ownershipPct: BigDecimal,
  payout: BigDecimal,
  multiplier: BigDecimal,
  calculatedAt: Instant
) derives ConfiguredCodec

case class SeasonStanding(
  id: UUID,
  seasonId: UUID,
  teamId: UUID,
  totalPoints: BigDecimal,
  tournamentsPlayed: Int,
  lastUpdated: Instant
) derives ConfiguredCodec
