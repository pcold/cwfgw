package com.cwfgw.service

import io.circe.derivation.ConfiguredCodec
import java.util.UUID
import java.time.LocalDate
import com.cwfgw.domain.given
import com.cwfgw.espn.EspnCalendarEntry

case class SeasonUploadResult(
  seasonYear: Int,
  tournamentsCreated: Int,
  tournaments: List[TournamentCreated],
  espnMatched: Int,
  espnUnmatched: List[String]
) derives ConfiguredCodec

case class TournamentCreated(
  id: UUID,
  name: String,
  week: String,
  startDate: LocalDate,
  endDate: LocalDate,
  payoutMultiplier: BigDecimal,
  espnId: Option[String],
  espnName: Option[String]
) derives ConfiguredCodec

// -- Step 1: Preview result models --

case class RosterPreviewResult(
  teams: List[RosterTeamPreview],
  totalPicks: Int,
  exactMatches: Int,
  ambiguous: Int,
  noMatch: Int
) derives ConfiguredCodec

case class RosterTeamPreview(teamNumber: Int, teamName: String, picks: List[RosterPickPreview]) derives ConfiguredCodec

case class RosterPickPreview(
  round: Int,
  inputName: String,
  ownershipPct: Int,
  matchStatus: String, // "exact", "ambiguous", "no_match"
  espnId: Option[String],
  espnName: Option[String],
  suggestions: List[EspnSuggestion]
) derives ConfiguredCodec

case class EspnSuggestion(espnId: String, name: String) derives ConfiguredCodec

// -- Step 2: Confirm models --

case class ConfirmedTeam(teamNumber: Int, teamName: String, picks: List[ConfirmedPick]) derives ConfiguredCodec

case class ConfirmedPick(
  round: Int,
  playerName: String,
  ownershipPct: Int,
  espnId: Option[String],
  espnName: Option[String]
) derives ConfiguredCodec

// -- Upload result models --

case class RosterUploadResult(teamsCreated: Int, golfersCreated: Int, teams: List[TeamUploadResult])
    derives ConfiguredCodec

case class TeamUploadResult(teamId: UUID, teamNumber: Int, teamName: String, picks: List[RosterPickResult])
    derives ConfiguredCodec

case class RosterPickResult(round: Int, golferName: String, golferId: UUID, ownershipPct: Int, created: Boolean)
    derives ConfiguredCodec

/** API response for ESPN calendar entries. Renames internal `id`/`label` to `espnId`/`name`. */
case class CalendarEntryResponse(espnId: String, name: String, startDate: String) derives ConfiguredCodec

object CalendarEntryResponse:
  def from(e: EspnCalendarEntry): CalendarEntryResponse = CalendarEntryResponse(e.id, e.label, e.startDate)
