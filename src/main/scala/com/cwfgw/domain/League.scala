package com.cwfgw.domain

import io.circe.Json
import io.circe.derivation.{Configuration, ConfiguredCodec}
import java.util.UUID
import java.time.Instant

given Configuration = Configuration.default.withSnakeCaseMemberNames

case class League(
    id: UUID,
    name: String,
    seasonYear: Int,
    status: String,
    rules: Json,
    maxTeams: Int,
    createdAt: Instant,
    updatedAt: Instant
) derives ConfiguredCodec

case class CreateLeague(
    name: String,
    seasonYear: Int,
    maxTeams: Option[Int],
    rules: Option[Json]
) derives ConfiguredCodec

case class UpdateLeague(
    name: Option[String],
    status: Option[String],
    rules: Option[Json],
    maxTeams: Option[Int]
) derives ConfiguredCodec
