package com.cwfgw.domain

import io.circe.Json
import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import java.time.Instant

case class Golfer(
    id: UUID,
    pgaPlayerId: Option[String],
    firstName: String,
    lastName: String,
    country: Option[String],
    worldRanking: Option[Int],
    active: Boolean,
    metadata: Json,
    updatedAt: Instant
) derives ConfiguredCodec

case class CreateGolfer(
    pgaPlayerId: Option[String],
    firstName: String,
    lastName: String,
    country: Option[String],
    worldRanking: Option[Int]
) derives ConfiguredCodec

case class UpdateGolfer(
    pgaPlayerId: Option[String],
    firstName: Option[String],
    lastName: Option[String],
    country: Option[String],
    worldRanking: Option[Int],
    active: Option[Boolean],
    metadata: Option[Json]
) derives ConfiguredCodec
