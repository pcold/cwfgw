package com.cwfgw.domain

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import java.time.Instant

case class Team(
    id: UUID,
    seasonId: UUID,
    ownerName: String,
    teamName: String,
    teamNumber: Option[Int],
    createdAt: Instant,
    updatedAt: Instant
) derives ConfiguredCodec

case class CreateTeam(
    ownerName: String,
    teamName: String,
    teamNumber: Option[Int] = None
) derives ConfiguredCodec

case class UpdateTeam(
    ownerName: Option[String],
    teamName: Option[String]
) derives ConfiguredCodec

case class RosterEntry(
    id: UUID,
    teamId: UUID,
    golferId: UUID,
    acquiredVia: String,
    draftRound: Option[Int],
    ownershipPct: BigDecimal,
    acquiredAt: Instant,
    droppedAt: Option[Instant],
    isActive: Boolean
) derives ConfiguredCodec

case class AddToRoster(
    golferId: UUID,
    acquiredVia: Option[String],
    draftRound: Option[Int],
    ownershipPct: Option[BigDecimal]
) derives ConfiguredCodec
