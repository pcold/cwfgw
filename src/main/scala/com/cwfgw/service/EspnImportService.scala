package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory

import java.util.UUID
import java.time.LocalDate

import com.cwfgw.domain.*
import com.cwfgw.espn.{EspnCalendarEntry, EspnClient, EspnCompetitor, EspnTournament}
import com.cwfgw.repository.{GolferRepository, TeamRepository, TournamentRepository}

/** Imports ESPN tournament results into the database.
  *
  * Flow:
  *   1. Fetch leaderboard from ESPN by tournament date
  *   2. Match ESPN competitors to golfers in our DB (by name or ESPN ID)
  *   3. Upsert tournament_results with positions and scores
  *   4. Optionally auto-create golfers not in our DB
  */
class EspnImportService(espnClient: EspnClient, xa: Transactor[IO])(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger

  /** Import results for completed tournament(s) by date. Rejects in-progress events. */
  def importByDate(date: LocalDate): IO[List[EspnImportResult]] =
    for
      json <- espnClient.fetchScoreboard(date)
      tournaments <- IO.fromEither(
        espnClient.parseAllLeaderboards(json).left.map(e => new RuntimeException(e))
      )
      _ <- logger.info(s"ESPN: ${tournaments.size} event(s) found for $date")
      completed = tournaments.filter(_.completed)
      _ <- IO.raiseWhen(completed.isEmpty && tournaments.nonEmpty)(
        new RuntimeException(s"No completed events for $date. ${tournaments.map(t => s"'${t.name}' is in progress").mkString(", ")}. Use GET /espn/preview to see live scores.")
      )
      results <- completed.traverse: parsed =>
        logger.info(s"ESPN: importing ${parsed.name} (${parsed.espnId}), ${parsed.competitors.size} competitors") >>
          importTournament(parsed)
    yield results

  /** Import results for a specific tournament ID in our DB.
    * Only imports the single ESPN event matching this tournament's pga_tournament_id. */
  def importForTournament(tournamentId: UUID): IO[Either[String, List[EspnImportResult]]] =
    TournamentRepository.findById(tournamentId).transact(xa).flatMap:
      case None => IO.pure(Left("Tournament not found"))
      case Some(tournament) =>
        tournament.pgaTournamentId match
          case Some(espnId) =>
            importSingleEvent(tournament.startDate, espnId).map(r => Right(List(r)))
          case None =>
            // No ESPN ID linked — fall back to importing all events for the date
            importByDate(tournament.startDate).map(Right(_))

  /** Import results for a single ESPN event by its ESPN ID.
    * Fetches the scoreboard for the date, filters to the matching event, and imports only that one. */
  def importSingleEvent(date: LocalDate, espnEventId: String): IO[EspnImportResult] =
    for
      json <- espnClient.fetchScoreboard(date)
      tournaments <- IO.fromEither(
        espnClient.parseAllLeaderboards(json).left.map(e => new RuntimeException(e))
      )
      target <- IO.fromOption(tournaments.find(_.espnId == espnEventId))(
        new RuntimeException(s"ESPN event $espnEventId not found on scoreboard for $date")
      )
      _ <- IO.raiseUnless(target.completed)(
        new RuntimeException(s"ESPN event '${target.name}' ($espnEventId) is not yet completed")
      )
      _ <- logger.info(s"ESPN: importing single event ${target.name} ($espnEventId), ${target.competitors.size} competitors")
      result <- importTournament(target)
    yield result

  /** Preview live fantasy scoring from current ESPN leaderboard, without writing to DB.
    * Shows per-team earnings based on which rostered golfers are in the top 10. */
  def previewByDate(seasonId: UUID, date: LocalDate): IO[List[EspnLivePreview]] =
    for
      json <- espnClient.fetchScoreboard(date)
      tournaments <- IO.fromEither(
        espnClient.parseAllLeaderboards(json).left.map(e => new RuntimeException(e))
      )
      dbState <- (for
        allGolfers <- GolferRepository.findAll(activeOnly = false, search = None)
        teams <- TeamRepository.findBySeason(seasonId)
        rosters <- TeamRepository.getRosterBySeason(seasonId)
        // Find tournament records to check is_major
        tournamentRecords <- tournaments.traverse: espn =>
          sql"SELECT id, is_major FROM tournaments WHERE pga_tournament_id = ${espn.espnId}"
            .query[(UUID, Boolean)].option
      yield (allGolfers, teams, rosters, tournamentRecords)).transact(xa)
      (allGolfers, teams, rosters, tournamentRecords) = dbState
      golferByEspnId = allGolfers.flatMap(g => g.pgaPlayerId.map(_ -> g)).toMap
      golferByName = allGolfers.map(g => (g.firstName.toLowerCase, g.lastName.toLowerCase) -> g).toMap
      golferByLastName = allGolfers.groupBy(_.lastName.toLowerCase)
      rostersByTeam = rosters.groupBy(_.teamId)
      previews = tournaments.zip(tournamentRecords).map: (espn, tournamentRecord) =>
        val isMajor = tournamentRecord.exists(_._2)

        // Match ESPN competitors to our golfers (read-only, no auto-create)
        val matchedCompetitors: List[(EspnCompetitor, Option[Golfer])] = espn.competitors.map: c =>
          val golfer = golferByEspnId.get(c.espnId).orElse:
            val parts = c.name.split("\\s+", 2)
            val (first, last) = if parts.length >= 2 then (parts(0), parts(1)) else ("", parts(0))
            golferByName.get((first.toLowerCase, last.toLowerCase)).orElse:
              golferByLastName.get(last.toLowerCase).flatMap:
                case g :: Nil => Some(g)
                case _ => None
          (c, golfer)

        // Build position -> tied count map
        val tiedCounts: Map[Int, Int] = espn.competitors
          .groupBy(_.position).view.mapValues(_.size).toMap

        // Calculate per-team fantasy scores from live leaderboard
        val teamPreviews = teams.map: team =>
          val roster = rostersByTeam.getOrElse(team.id, Nil)
          val rosteredGolferIds = roster.map(r => r.golferId -> r).toMap
          val golferEarnings = matchedCompetitors.flatMap: (competitor, golferOpt) =>
            for
              golfer <- golferOpt
              entry <- rosteredGolferIds.get(golfer.id)
              if competitor.position <= 10
            yield
              val numTied = tiedCounts.getOrElse(competitor.position, 1)
              val basePayout = PayoutTable.tieSplitPayout(competitor.position, numTied, isMajor)
              val ownerPayout = basePayout * entry.ownershipPct / BigDecimal(100)
              PreviewGolferScore(
                golferName = s"${golfer.firstName} ${golfer.lastName}",
                golferId = golfer.id,
                position = competitor.position,
                numTied = numTied,
                scoreToPar = competitor.scoreToPar,
                basePayout = basePayout,
                ownershipPct = entry.ownershipPct,
                payout = ownerPayout
              )
          val topTens = golferEarnings.map(_.payout).sum
          PreviewTeamScore(
            teamId = team.id,
            teamName = team.teamName,
            ownerName = team.ownerName,
            topTenEarnings = topTens,
            golferScores = golferEarnings
          )

        // Compute zero-sum weekly totals
        val numTeams = teams.size
        val totalPot = teamPreviews.map(_.topTenEarnings).sum
        val teamsWithWeekly = teamPreviews.map: tp =>
          tp.copy(weeklyTotal = tp.topTenEarnings * numTeams - totalPot)

        EspnLivePreview(
          espnName = espn.name,
          espnId = espn.espnId,
          completed = espn.completed,
          isMajor = isMajor,
          totalCompetitors = espn.competitors.size,
          teams = teamsWithWeekly.sortBy(-_.weeklyTotal),
          leaderboard = matchedCompetitors.sortBy(_._1.position).take(20).map: (c, gOpt) =>
            PreviewLeaderboardEntry(
              name = c.name,
              position = c.position,
              scoreToPar = c.scoreToPar,
              thru = if c.roundScores.nonEmpty then Some(s"${c.roundScores.size} rounds") else None,
              rostered = gOpt.exists(g => rosters.exists(_.golferId == g.id)),
              teamName = gOpt.flatMap(g => rosters.find(_.golferId == g.id).flatMap(r => teams.find(_.id == r.teamId).map(_.teamName)))
            )
        )
    yield previews

  /** Fetch the ESPN season calendar. */
  def fetchCalendar: IO[List[EspnCalendarEntry]] =
    espnClient.fetchCalendar

  /** Import results by ESPN event ID, matching to our tournament by pga_tournament_id. */
  def importByEspnId(espnEventId: String, date: LocalDate): IO[EspnImportResult] =
    for
      json <- espnClient.fetchScoreboard(date)
      parsed <- IO.fromEither(
        espnClient.parseLeaderboard(json).left.map(e => new RuntimeException(e))
      )
      result <- importTournament(parsed)
    yield result

  private def importTournament(espn: EspnTournament): IO[EspnImportResult] =
    val action = for
      // Find our tournament by ESPN ID (stored in pga_tournament_id)
      tournamentOpt <- sql"SELECT id FROM tournaments WHERE pga_tournament_id = ${espn.espnId}"
        .query[UUID].option
      tournamentId <- tournamentOpt match
        case Some(id) => FC.pure(id)
        case None =>
          // Try to find by name similarity (fallback)
          sql"SELECT id FROM tournaments WHERE pga_tournament_id IS NULL ORDER BY start_date ASC LIMIT 1"
            .query[UUID].option.flatMap:
              case Some(id) =>
                // Link this tournament to the ESPN event
                sql"UPDATE tournaments SET pga_tournament_id = ${espn.espnId} WHERE id = $id".update.run.as(id)
              case None =>
                FC.raiseError[UUID](new RuntimeException(
                  s"No tournament found for ESPN event '${espn.name}' (${espn.espnId}). Create the tournament first."))

      // Update tournament status if completed
      _ <- if espn.completed
           then sql"UPDATE tournaments SET status = 'completed' WHERE id = $tournamentId".update.run
           else FC.pure(0)

      // Load all golfers for name matching
      allGolfers <- GolferRepository.findAll(activeOnly = false, search = None)

      // Process each competitor
      results <- espn.competitors.traverse: competitor =>
        matchGolfer(competitor, allGolfers).flatMap:
          case None =>
            FC.pure(ImportedPlayer(competitor.name, competitor.position, matched = false, created = false))
          case Some((golferId, created)) =>
            val roundScoresJson = Json.arr(competitor.roundScores.map(s => Json.fromInt(s))*)
            TournamentRepository.upsertResult(tournamentId, CreateTournamentResult(
              golferId = golferId,
              position = Some(competitor.position),
              scoreToPar = competitor.scoreToPar,
              totalStrokes = competitor.totalStrokes,
              earnings = None,
              roundScores = Some(roundScoresJson),
              madeCut = competitor.madeCut
            )).map: _ =>
              ImportedPlayer(competitor.name, competitor.position, matched = true, created = created)
    yield EspnImportResult(
      tournamentId = tournamentId,
      espnName = espn.name,
      espnId = espn.espnId,
      completed = espn.completed,
      totalCompetitors = espn.competitors.size,
      matched = results.count(_.matched),
      unmatched = results.filterNot(_.matched).map(_.name),
      created = results.count(_.created)
    )
    action.transact(xa)

  /** Try to match an ESPN competitor to a golfer in our DB.
    * Strategy:
    *   1. Match by ESPN athlete ID (stored in pga_player_id) — check DB directly
    *   2. Match by exact full name (first + last)
    *   3. Match by last name only (if unique)
    *   4. Auto-create if not found
    */
  private def matchGolfer(
      competitor: EspnCompetitor,
      allGolfers: List[Golfer]
  ): ConnectionIO[Option[(UUID, Boolean)]] =
    // 1. Check DB for existing ESPN ID mapping
    sql"SELECT id FROM golfers WHERE pga_player_id = ${competitor.espnId}"
      .query[UUID].option.flatMap:
        case Some(id) => FC.pure(Some((id, false)))
        case None =>
          findGolferMatch(competitor.name, competitor.espnId, allGolfers) match
            case GolferMatchResult.FullNameMatch(g) =>
              sql"UPDATE golfers SET pga_player_id = ${competitor.espnId} WHERE id = ${g.id} AND pga_player_id IS NULL"
                .update.run.as(Some((g.id, false)))
            case GolferMatchResult.LastNameMatch(g) =>
              sql"UPDATE golfers SET pga_player_id = ${competitor.espnId} WHERE id = ${g.id} AND pga_player_id IS NULL"
                .update.run.as(Some((g.id, false)))
            case GolferMatchResult.NoMatch(first, last) =>
              GolferRepository.create(CreateGolfer(
                pgaPlayerId = Some(competitor.espnId),
                firstName = first,
                lastName = last,
                country = None,
                worldRanking = None
              )).map(g => Some((g.id, true)))

/** Pure golfer matching result — no DB side effects. */
private[service] enum GolferMatchResult:
  case FullNameMatch(golfer: Golfer)
  case LastNameMatch(golfer: Golfer)
  case NoMatch(firstName: String, lastName: String)

/** Pure: match a competitor name against a list of golfers.
  * Strategy: exact full name → unique last name → no match. */
private[service] def findGolferMatch(
    competitorName: String, espnId: String, allGolfers: List[Golfer]
): GolferMatchResult =
  val nameParts = competitorName.split("\\s+", 2)
  val (first, last) = if nameParts.length >= 2 then (nameParts(0), nameParts(1)) else ("", nameParts(0))

  // 1. Exact full name match
  val byFullName = allGolfers.find(g =>
    g.firstName.equalsIgnoreCase(first) && g.lastName.equalsIgnoreCase(last))
  byFullName match
    case Some(g) => GolferMatchResult.FullNameMatch(g)
    case None =>
      // 2. Unique last name match
      val byLastName = allGolfers.filter(_.lastName.equalsIgnoreCase(last))
      byLastName match
        case g :: Nil => GolferMatchResult.LastNameMatch(g)
        case _ => GolferMatchResult.NoMatch(first, last)

case class EspnImportResult(
    tournamentId: UUID,
    espnName: String,
    espnId: String,
    completed: Boolean,
    totalCompetitors: Int,
    matched: Int,
    unmatched: List[String],
    created: Int
)

case class ImportedPlayer(
    name: String,
    position: Int,
    matched: Boolean,
    created: Boolean
)

case class EspnLivePreview(
    espnName: String,
    espnId: String,
    completed: Boolean,
    isMajor: Boolean,
    totalCompetitors: Int,
    teams: List[PreviewTeamScore],
    leaderboard: List[PreviewLeaderboardEntry]
)

case class PreviewTeamScore(
    teamId: UUID,
    teamName: String,
    ownerName: String,
    topTenEarnings: BigDecimal,
    golferScores: List[PreviewGolferScore],
    weeklyTotal: BigDecimal = BigDecimal(0)
)

case class PreviewGolferScore(
    golferName: String,
    golferId: UUID,
    position: Int,
    numTied: Int,
    scoreToPar: Option[Int],
    basePayout: BigDecimal,
    ownershipPct: BigDecimal,
    payout: BigDecimal
)

case class PreviewLeaderboardEntry(
    name: String,
    position: Int,
    scoreToPar: Option[Int],
    thru: Option[String],
    rostered: Boolean,
    teamName: Option[String]
)
