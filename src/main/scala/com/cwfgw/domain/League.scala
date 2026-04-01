package com.cwfgw.domain

import io.circe.Json
import io.circe.derivation.{Configuration, ConfiguredCodec}
import java.util.UUID
import java.time.Instant

given Configuration = Configuration.default.withSnakeCaseMemberNames

/** Top-level league (e.g., "Castlewood Fantasy Golf").
  * Has many seasons.
  */
case class League(
    id: UUID,
    name: String,
    createdAt: Instant
) derives ConfiguredCodec

case class CreateLeague(
    name: String
) derives ConfiguredCodec

/** A season within a league. Has its own schedule, draft,
  * teams, rosters, and scoring.
  */
case class Season(
    id: UUID,
    leagueId: UUID,
    name: String,
    seasonYear: Int,
    seasonNumber: Int,
    status: String,
    rules: Json,
    maxTeams: Int,
    createdAt: Instant,
    updatedAt: Instant
) derives ConfiguredCodec

case class CreateSeason(
    leagueId: UUID,
    name: String,
    seasonYear: Int,
    seasonNumber: Option[Int],
    maxTeams: Option[Int],
    rules: Option[Json]
) derives ConfiguredCodec

case class UpdateSeason(
    name: Option[String],
    status: Option[String],
    rules: Option[Json],
    maxTeams: Option[Int]
) derives ConfiguredCodec
