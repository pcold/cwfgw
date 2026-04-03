package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.circe.derivation.ConfiguredCodec
import org.typelevel.log4cats.LoggerFactory

import java.text.Normalizer
import java.util.UUID
import java.time.LocalDate

import com.cwfgw.domain.{*, given}
import com.cwfgw.espn.{EspnAthlete, EspnCalendarEntry, EspnClient}
import com.cwfgw.repository.{TournamentRepository, LeagueRepository, TeamRepository, GolferRepository}

/** Admin operations: season upload, ESPN validation, data management. */
class AdminService(espnClient: EspnClient, xa: Transactor[IO])(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger

  /** Parse and validate a season schedule, then persist to DB. Returns a summary of what was created and any ESPN match
    * results.
    */
  def uploadSeason(seasonId: UUID, seasonYear: Int, scheduleText: String): IO[SeasonUploadResult] =
    for
      parsed <- IO
        .fromEither(SeasonParser.parse(scheduleText, seasonYear).left.map(e => new RuntimeException(s"Parse error:\n$e")))
      _ <- logger.info(s"Parsed ${parsed.size} tournaments for $seasonYear season")

      // Fetch ESPN calendar to cross-reference
      espnCalendar <- espnClient.fetchCalendar.handleErrorWith: e =>
        logger.warn(s"ESPN calendar fetch failed: ${e.getMessage}") >> IO.pure(Nil)

      _ <- logger.info(s"ESPN calendar has ${espnCalendar.size} entries")

      // Match parsed tournaments to ESPN entries by name similarity
      matchResults = parsed.map: t =>
        val espnMatch = findEspnMatch(t.name, espnCalendar)
        (t, espnMatch)

      // Persist tournaments
      created <- matchResults.traverse: (parsed, espnMatch) =>
        val req = CreateTournament(
          pgaTournamentId = espnMatch.map(_.id),
          name = parsed.name,
          seasonId = seasonId,
          startDate = parsed.startDate,
          endDate = parsed.endDate,
          courseName = None,
          purseAmount = None,
          payoutMultiplier = Some(parsed.payoutMultiplier),
          week = Some(parsed.week)
        )
        TournamentRepository.create(req).transact(xa).map: tournament =>
          TournamentCreated(
            id = tournament.id,
            name = tournament.name,
            week = parsed.week,
            startDate = parsed.startDate,
            endDate = parsed.endDate,
            payoutMultiplier = parsed.payoutMultiplier,
            espnId = espnMatch.map(_.id),
            espnName = espnMatch.map(_.label)
          )
    yield SeasonUploadResult(
      seasonYear = seasonYear,
      tournamentsCreated = created.size,
      tournaments = created,
      espnMatched = created.count(_.espnId.isDefined),
      espnUnmatched = created.filter(_.espnId.isEmpty).map(_.name)
    )

  /** Step 1: Parse roster text and match each player against ESPN athletes. Returns match results with suggestions for
    * ambiguous/unmatched names.
    */
  def previewRoster(rosterText: String): IO[RosterPreviewResult] =
    for
      parsed <- IO.fromEither(RosterParser.parse(rosterText).left.map(e => new RuntimeException(s"Parse error:\n$e")))
      _ <- logger.info(s"Parsed ${parsed.size} teams, fetching ESPN athletes for matching")
      espnAthletes <- espnClient.fetchActivePlayers
      _ <- logger.info(s"Matching against ${espnAthletes.size} ESPN athletes")
    yield
      val teamPreviews = parsed.map: team =>
        val pickPreviews = team.picks.map: pick =>
          matchEspnPlayer(pick.playerName, espnAthletes) match
            case MatchResult.Exact(athlete) => RosterPickPreview(
                pick.round,
                pick.playerName,
                pick.ownershipPct,
                "exact",
                Some(athlete.espnId),
                Some(athlete.name),
                Nil
              )
            case MatchResult.Ambiguous(candidates) => RosterPickPreview(
                pick.round,
                pick.playerName,
                pick.ownershipPct,
                "ambiguous",
                None,
                None,
                candidates.map(a => EspnSuggestion(a.espnId, a.name))
              )
            case MatchResult.NoMatch =>
              RosterPickPreview(pick.round, pick.playerName, pick.ownershipPct, "no_match", None, None, Nil)
        RosterTeamPreview(team.teamNumber, team.teamName, pickPreviews)
      val allPicks = teamPreviews.flatMap(_.picks)
      RosterPreviewResult(
        teams = teamPreviews,
        totalPicks = allPicks.size,
        exactMatches = allPicks.count(_.matchStatus == "exact"),
        ambiguous = allPicks.count(_.matchStatus == "ambiguous"),
        noMatch = allPicks.count(_.matchStatus == "no_match")
      )

  /** Step 2: Confirm and persist rosters. Each pick includes the resolved ESPN ID (either from the auto-match or user
    * selection).
    */
  def confirmRoster(seasonId: UUID, confirmed: List[ConfirmedTeam]): IO[RosterUploadResult] =
    for results <- confirmed.traverse: team =>
        for
          created <- TeamRepository.create(
            seasonId,
            CreateTeam(ownerName = team.teamName, teamName = team.teamName, teamNumber = Some(team.teamNumber))
          ).transact(xa)
          _ <- logger.info(s"Created team #${team.teamNumber}: ${team.teamName} (${created.id})")

          pickResults <- team.picks.traverse: pick =>
            for
              golfer <- findOrCreateGolfer(pick.playerName, pick.espnId, pick.espnName).transact(xa)
              _ <- TeamRepository.addToRoster(
                created.id,
                AddToRoster(
                  golferId = golfer.id,
                  acquiredVia = Some("draft"),
                  draftRound = Some(pick.round),
                  ownershipPct = Some(BigDecimal(pick.ownershipPct))
                )
              ).transact(xa)
            yield RosterPickResult(
              round = pick.round,
              golferName = s"${golfer.firstName} ${golfer.lastName}",
              golferId = golfer.id,
              ownershipPct = pick.ownershipPct,
              created = true
            )
        yield TeamUploadResult(
          teamId = created.id,
          teamNumber = team.teamNumber,
          teamName = team.teamName,
          picks = pickResults
        )
    yield RosterUploadResult(
      teamsCreated = results.size,
      golfersCreated = results.flatMap(_.picks).count(_.created),
      teams = results
    )

  // Hard-coded aliases for players whose roster names don't match ESPN conventions
  private val playerAliases: Map[String, String] =
    Map("AN" -> "Byeong-Hun An", "DECHAMBEAU" -> "Bryson DeChambeau", "DETRY" -> "Thomas Detry")

  /** Match a roster name against ESPN athletes. Strategy: aliases → exact last name → fuzzy last name (edit distance ≤
    * 2) → no match. Multiple hits → narrow by first hint → ambiguous with suggestions.
    */
  private[service] def matchEspnPlayer(name: String, athletes: List[EspnAthlete]): MatchResult =
    val normalized = stripDiacritics(name.trim.toUpperCase)

    // 0. Check hard-coded aliases first
    val aliasMatch = playerAliases.get(normalized).orElse(playerAliases.get(normalized.split("[\\s,.]+").last))
    aliasMatch match
      case Some(aliasName) =>
        val found = athletes.find(a => stripDiacritics(a.name).equalsIgnoreCase(aliasName))
        MatchResult.Exact(found.getOrElse(EspnAthlete("alias-" + normalized.toLowerCase, aliasName)))
      case None =>
    // Parse name into (lastName, optionalFirstHint)
    // Formats: "SCHEFFLER", "YOUNG, C.", "M.W. LEE", "CAM. YOUNG", "CAM YOUNG"
    val (lastName, firstHint) =
      if normalized.contains(",") then
        (normalized.split(",").head.trim, Some(normalized.split(",", 2)(1).trim.stripSuffix(".").trim))
      else
        val tokens = normalized.split("\\s+")
        if tokens.length >= 2 then
          val last = tokens.last
          val hint = tokens.init.mkString(" ").replaceAll("\\.", "").trim
          (last, if hint.nonEmpty then Some(hint) else None)
        else (normalized.replaceAll("\\.", "").trim, None)

    // 1. Exact last-name match (diacritics-insensitive)
    //    ESPN last name = last space-separated token, preserving hyphens
    val exactMatches = athletes.filter: a =>
      stripDiacritics(espnLastName(a).toUpperCase) == lastName

    narrowByFirstHint(exactMatches, firstHint) match
      case r if r != MatchResult.NoMatch => r
      case _ =>
        // 2. Fuzzy last-name match — max edit distance scales with name length
        //    Short names (≤4 chars): max 1, longer names: max 2
        val maxDist = if lastName.length <= 4 then 1 else 2
        val fuzzyMatches = athletes.map(a => (a, editDistance(stripDiacritics(espnLastName(a).toUpperCase), lastName)))
          .filter(_._2 <= maxDist).sortBy(_._2).map(_._1)

        narrowByFirstHint(fuzzyMatches, firstHint) match
          case r if r != MatchResult.NoMatch => r
          case _ =>
            // 3. Try matching against full ESPN name (handles name reordering)
            val fullNameFuzzy = athletes.map(a => (a, editDistance(stripDiacritics(a.name.toUpperCase), normalized)))
              .filter(_._2 <= 3).sortBy(_._2)

            fullNameFuzzy match
              case (one, d) :: Nil if d <= 2 => MatchResult.Exact(one)
              case list if list.nonEmpty => MatchResult.Ambiguous(list.map(_._1).take(5))
              case _ => MatchResult.NoMatch

  private[service] def espnLastName(a: EspnAthlete): String = a.name.split("\\s+").lastOption.getOrElse(a.name)

  private[service] def narrowByFirstHint(matches: List[EspnAthlete], hint: Option[String]): MatchResult = matches match
    case Nil => MatchResult.NoMatch
    case one :: Nil => MatchResult.Exact(one)
    case multiple => hint.filter(_.nonEmpty) match
        case Some(h) =>
          val hUpper = stripDiacritics(h.toUpperCase)
          val narrowed = multiple.filter(a => firstNameMatches(a, hUpper))
          narrowed match
            case one :: Nil => MatchResult.Exact(one)
            case fewer if fewer.nonEmpty => MatchResult.Ambiguous(fewer)
            case _ => MatchResult.Ambiguous(multiple)
        case None => MatchResult.Ambiguous(multiple)

  /** Check if a hint matches an ESPN player's first name(s). Handles: "C" matches "Cameron", "CAM" matches "Cameron",
    * "MW" matches "Min Woo", "KH" matches "K.H."
    */
  private[service] def firstNameMatches(a: EspnAthlete, hint: String): Boolean =
    val espnParts = stripDiacritics(a.name.toUpperCase).split("\\s+")
    val espnFirstParts = espnParts.init // everything except last name
    if espnFirstParts.isEmpty then return false

    val espnFirst = espnFirstParts.mkString(" ")
    // Direct prefix: "CAM" startsWith check on "CAMERON"
    if espnFirst.startsWith(hint) || hint.startsWith(espnFirst) then return true

    // Multi-initial: "MW" → check M matches Min, W matches Woo
    // Also handles "KH" matching "K.H." (after stripping dots)
    val espnClean = espnFirstParts.map(_.replaceAll("\\.", ""))
    val hintChars = hint.replaceAll("[\\s.]+", "")
    if hintChars.length >= 2 && hintChars.length <= espnClean.length then
      hintChars.zip(espnClean).forall((h, e) => e.startsWith(h.toString))
    else false

  /** Levenshtein edit distance between two strings. */
  private[service] def editDistance(a: String, b: String): Int =
    val m = a.length
    val n = b.length
    val dp = Array.ofDim[Int](m + 1, n + 1)
    for i <- 0 to m do dp(i)(0) = i
    for j <- 0 to n do dp(0)(j) = j
    for
      i <- 1 to m
      j <- 1 to n
    do
      dp(i)(j) =
        if a(i - 1) == b(j - 1) then dp(i - 1)(j - 1)
        else 1 + math.min(dp(i - 1)(j - 1), math.min(dp(i - 1)(j), dp(i)(j - 1)))
    dp(m)(n)

  /** Strip diacritics: ø→o, é→e, ñ→n, ü→u, etc. */
  private[service] def stripDiacritics(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").replace("ø", "o").replace("Ø", "O") // ø doesn't decompose via NFD
    .replace("đ", "d").replace("Đ", "D").replace("ł", "l").replace("Ł", "L")

  private[service] enum MatchResult:
    case Exact(athlete: EspnAthlete)
    case Ambiguous(candidates: List[EspnAthlete])
    case NoMatch

  /** Find or create a golfer using ESPN ID and name. */
  private def findOrCreateGolfer(
    rosterName: String,
    espnId: Option[String],
    espnName: Option[String]
  ): ConnectionIO[Golfer] = espnId match
    case Some(eid) =>
      // Check if golfer with this ESPN ID already exists
      GolferRepository.findByPgaPlayerId(eid).flatMap:
          case Some(g) => g.pure[ConnectionIO]
          case None =>
            val fullName = espnName.getOrElse(rosterName)
            val parts = fullName.split("\\s+", 2)
            val (first, last) = if parts.length >= 2 then (parts(0), parts(1)) else ("", parts(0))
            GolferRepository.create(CreateGolfer(
              pgaPlayerId = Some(eid),
              firstName = first,
              lastName = last,
              country = None,
              worldRanking = None
            ))
    case None =>
      // No ESPN ID — create with roster name only
      val normalized = rosterName.trim
      val (first, last) =
        if normalized.contains(",") then
          val parts = normalized.split(",", 2)
          (parts(1).trim.stripSuffix(".").trim.capitalize, parts(0).trim.toLowerCase.capitalize)
        else ("", normalized.toLowerCase.capitalize)
      GolferRepository
        .create(CreateGolfer(pgaPlayerId = None, firstName = first, lastName = last, country = None, worldRanking = None))

  /** Fetch ESPN calendar and return it for preview before upload. */
  def previewEspnCalendar: IO[List[EspnCalendarEntry]] = espnClient.fetchCalendar

  /** Match a tournament name to an ESPN calendar entry using fuzzy matching. */
  private[service] def findEspnMatch(name: String, calendar: List[EspnCalendarEntry]): Option[EspnCalendarEntry] =
    val normalized = normalize(name)
    // Try exact match first
    calendar.find(e => normalize(e.label) == normalized).orElse:
      // Try contains match
      calendar.find: e =>
        val espnNorm = normalize(e.label)
        espnNorm.contains(normalized) || normalized.contains(espnNorm)
    .orElse:
      // Try matching significant words (3+ chars)
      val words = normalized.split("\\s+").filter(_.length >= 3).toSet
      if words.isEmpty then None
      else
        calendar.map(e => (e, wordOverlap(words, normalize(e.label)))).filter(_._2 > 0.5).sortBy(-_._2).headOption
          .map(_._1)

  private[service] def normalize(s: String): String = s.toLowerCase.replaceAll("[^a-z0-9\\s]", "")
    .replaceAll("\\s+", " ").trim

  private[service] def wordOverlap(words: Set[String], target: String): Double =
    val targetWords = target.split("\\s+").filter(_.length >= 3).toSet
    if words.isEmpty || targetWords.isEmpty then 0.0 else words.intersect(targetWords).size.toDouble / words.size

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

/** API response for ESPN calendar entries. Renames internal `id`/`label` to `espnId`/`name`.
  */
case class CalendarEntryResponse(espnId: String, name: String, startDate: String) derives ConfiguredCodec

object CalendarEntryResponse:
  def from(e: EspnCalendarEntry): CalendarEntryResponse = CalendarEntryResponse(e.id, e.label, e.startDate)
