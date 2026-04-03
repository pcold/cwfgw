package com.cwfgw.domain

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import java.time.{Instant, LocalDate}

case class Tournament(
  id: UUID,
  pgaTournamentId: Option[String],
  name: String,
  seasonId: UUID,
  startDate: LocalDate,
  endDate: LocalDate,
  courseName: Option[String],
  status: String,
  purseAmount: Option[Long],
  payoutMultiplier: BigDecimal,
  week: Option[String],
  createdAt: Instant
) derives ConfiguredCodec

case class CreateTournament(
  pgaTournamentId: Option[String],
  name: String,
  seasonId: UUID,
  startDate: LocalDate,
  endDate: LocalDate,
  courseName: Option[String],
  purseAmount: Option[Long],
  payoutMultiplier: Option[BigDecimal],
  week: Option[String] = None
) derives ConfiguredCodec

case class UpdateTournament(
  name: Option[String],
  startDate: Option[LocalDate],
  endDate: Option[LocalDate],
  courseName: Option[String],
  status: Option[String],
  purseAmount: Option[Long],
  payoutMultiplier: Option[BigDecimal]
) derives ConfiguredCodec

case class TournamentResult(
  id: UUID,
  tournamentId: UUID,
  golferId: UUID,
  position: Option[Int],
  scoreToPar: Option[Int],
  totalStrokes: Option[Int],
  earnings: Option[Long],
  round1: Option[Int],
  round2: Option[Int],
  round3: Option[Int],
  round4: Option[Int],
  madeCut: Boolean
) derives ConfiguredCodec

case class CreateTournamentResult(
  golferId: UUID,
  position: Option[Int],
  scoreToPar: Option[Int],
  totalStrokes: Option[Int],
  earnings: Option[Long],
  round1: Option[Int],
  round2: Option[Int],
  round3: Option[Int],
  round4: Option[Int],
  madeCut: Boolean
) derives ConfiguredCodec
