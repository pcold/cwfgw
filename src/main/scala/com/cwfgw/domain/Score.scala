package com.cwfgw.domain

import io.circe.Json
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
    breakdown: Json,
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
