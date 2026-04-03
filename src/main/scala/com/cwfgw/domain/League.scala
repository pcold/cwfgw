package com.cwfgw.domain

import io.circe.{Json, Decoder, Encoder}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import java.util.UUID
import java.time.Instant

given Configuration = Configuration.default.withSnakeCaseMemberNames

/** Top-level league (e.g., "Castlewood Fantasy Golf"). Has many seasons.
  */
case class League(id: UUID, name: String, createdAt: Instant) derives ConfiguredCodec

case class CreateLeague(name: String) derives ConfiguredCodec

/** Configurable scoring rules for a season. Stored as JSONB in the seasons table.
  */
final case class SeasonRules(
  payouts: List[BigDecimal],
  tieFloor: BigDecimal,
  sideBetRounds: List[Int],
  sideBetAmount: BigDecimal
) derives ConfiguredCodec

object SeasonRules:
  val default: SeasonRules = SeasonRules(
    payouts = List(18, 12, 10, 8, 7, 6, 5, 4, 3, 2).map(BigDecimal(_)),
    tieFloor = BigDecimal(1),
    sideBetRounds = List(5, 6, 7, 8),
    sideBetAmount = BigDecimal(15)
  )

  def fromJson(json: Json): SeasonRules = json.as[SeasonRules].getOrElse(default)

  def toJson(rules: SeasonRules): Json = rules.asJson

/** A season within a league. Has its own schedule, draft, teams, rosters, and scoring.
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
) derives ConfiguredCodec:

  def seasonRules: SeasonRules = SeasonRules.fromJson(rules)

case class CreateSeason(
  leagueId: UUID,
  name: String,
  seasonYear: Int,
  seasonNumber: Option[Int],
  maxTeams: Option[Int],
  rules: Option[Json]
) derives ConfiguredCodec

case class UpdateSeason(name: Option[String], status: Option[String], rules: Option[Json], maxTeams: Option[Int])
    derives ConfiguredCodec
