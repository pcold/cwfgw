package com.cwfgw.domain

import io.circe.Json
import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import java.time.Instant

case class Draft(
    id: UUID,
    leagueId: UUID,
    status: String,
    draftType: String,
    settings: Json,
    startedAt: Option[Instant],
    completedAt: Option[Instant],
    createdAt: Instant
) derives ConfiguredCodec

case class CreateDraft(
    draftType: Option[String],
    settings: Option[Json]
) derives ConfiguredCodec

case class DraftPick(
    id: UUID,
    draftId: UUID,
    teamId: UUID,
    golferId: Option[UUID],
    roundNum: Int,
    pickNum: Int,
    pickedAt: Option[Instant]
) derives ConfiguredCodec

case class MakePick(
    teamId: UUID,
    golferId: UUID
) derives ConfiguredCodec
