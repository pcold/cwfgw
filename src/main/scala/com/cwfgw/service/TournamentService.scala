package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.LoggerFactory
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.{ScoreRepository, SeasonRepository, TournamentRepository}

class TournamentService(espnImportService: EspnImportService, scoringService: ScoringService, xa: Transactor[IO])(using
  LoggerFactory[IO]
):

  private val logger = LoggerFactory[IO].getLogger

  def list(seasonId: Option[UUID], status: Option[String]): IO[List[Tournament]] = TournamentRepository
    .findAll(seasonId, status).transact(xa)

  def get(id: UUID): IO[Option[Tournament]] = TournamentRepository.findById(id).transact(xa)

  def create(req: CreateTournament): IO[Tournament] = TournamentRepository.create(req).transact(xa)

  def update(id: UUID, req: UpdateTournament): IO[Option[Tournament]] = TournamentRepository.update(id, req).transact(xa)

  def getResults(tournamentId: UUID): IO[List[TournamentResult]] = TournamentRepository.findResults(tournamentId)
    .transact(xa)

  def importResults(tournamentId: UUID, results: List[CreateTournamentResult]): IO[List[TournamentResult]] = results
    .traverse(r => TournamentRepository.upsertResult(tournamentId, r)).transact(xa)

  /** Finalize a tournament: import ESPN results, calculate scores, refresh standings. Rejects if any earlier tournament
    * in the same season has not been finalized yet.
    */
  def finalizeTournament(tournamentId: UUID): IO[Either[String, String]] = TournamentRepository.findById(tournamentId)
    .transact(xa).flatMap:
      case None => IO.pure(Left("Tournament not found"))
      case Some(tournament) =>
        val seasonId = tournament.seasonId
        val checkOrder =
          for
            upcoming <- TournamentRepository.findAll(Some(seasonId), Some("upcoming")).transact(xa)
            inProgress <- TournamentRepository.findAll(Some(seasonId), Some("in_progress")).transact(xa)
          yield (upcoming ++ inProgress).filter(t => t.id != tournamentId && t.startDate.isBefore(tournament.startDate))
            .sortBy(_.startDate)

        checkOrder.flatMap: unfinalized =>
          checkBlockingTournaments(unfinalized, "finalize") match
            case Some(msg) => IO.pure(Left(msg))
            case None =>
              val pipeline =
                for
                  _ <- logger.info(s"Finalizing '${tournament.name}'...")
                  importResults <- tournament.pgaTournamentId match
                    case Some(espnId) => espnImportService.importSingleEvent(tournament.startDate, espnId).map(List(_))
                    case None => espnImportService.importByDate(tournament.startDate)
                  _ <- logger.info(s"Imported ${importResults.size} event(s), calculating scores...")
                  _ <- scoringService.calculateScores(seasonId, tournamentId)
                  _ <- scoringService.refreshStandings(seasonId)
                  _ <- logger.info(s"Finalized '${tournament.name}'")
                yield Right(s"Tournament '${tournament.name}' finalized successfully")
              pipeline.handleErrorWith: err =>
                logger.error(err)(s"Failed to finalize '${tournament.name}'") >>
                  IO.pure(Left(Option(err.getMessage).getOrElse(err.getClass.getSimpleName)))

  /** Reset a finalized tournament back to 'upcoming'. */
  def resetTournament(tournamentId: UUID): IO[Either[String, String]] = TournamentRepository.findById(tournamentId)
    .transact(xa).flatMap:
      case None => IO.pure(Left("Tournament not found"))
      case Some(tournament) =>
        val seasonId = tournament.seasonId
        TournamentRepository.findAll(Some(seasonId), Some("completed")).transact(xa).flatMap: completed =>
          val later = completed.filter(t => t.id != tournamentId && t.startDate.isAfter(tournament.startDate))
          checkBlockingTournaments(later, "reset") match
            case Some(msg) => IO.pure(Left(msg))
            case None =>
              val deleteAndReset =
                for
                  _ <- logger.info(s"Resetting '${tournament.name}'...")
                  scoresDeleted <- ScoreRepository.deleteByTournament(tournamentId).transact(xa)
                  resultsDeleted <- TournamentRepository.deleteResultsByTournament(tournamentId).transact(xa)
                  _ <- TournamentRepository.update(
                    tournamentId,
                    UpdateTournament(
                      name = None,
                      startDate = None,
                      endDate = None,
                      courseName = None,
                      status = Some("upcoming"),
                      purseAmount = None,
                      payoutMultiplier = None
                    )
                  ).transact(xa)
                  _ <- scoringService.refreshStandings(seasonId)
                  _ <- logger.info(s"Reset complete for '${tournament.name}'")
                yield Right(s"Tournament '${tournament
                    .name}' reset ($scoresDeleted scores, $resultsDeleted results deleted)")
              deleteAndReset.handleErrorWith: err =>
                logger.error(err)(s"Failed to reset '${tournament.name}'") >>
                  IO.pure(Left(Option(err.getMessage).getOrElse(err.getClass.getSimpleName)))

  /** Delete all results, scores, and standings for a season, resetting every tournament back to 'upcoming'.
    */
  def cleanSeasonResults(seasonId: UUID): IO[Either[String, String]] = SeasonRepository.findById(seasonId).transact(xa)
    .flatMap:
      case None => IO.pure(Left("Season not found"))
      case Some(season) =>
        val clean =
          for
            _ <- logger.info(s"Cleaning all results for season '${season.name}'...")
            scores <- ScoreRepository.deleteBySeason(seasonId).transact(xa)
            results <- TournamentRepository.deleteResultsBySeason(seasonId).transact(xa)
            standings <- ScoreRepository.deleteStandingsBySeason(seasonId).transact(xa)
            tournaments <- TournamentRepository.resetSeasonTournaments(seasonId).transact(xa)
            _ <- logger.info(
              s"Cleaned season '${season.name}': " + s"$scores scores, $results results, " +
                s"$standings standings, $tournaments tournaments reset"
            )
          yield Right(
            s"Season '${season.name}' cleaned: " + s"$scores scores, $results results, " +
              s"$standings standings deleted; " + s"$tournaments tournaments reset to upcoming"
          )
        clean.handleErrorWith: err =>
          logger.error(err)(s"Failed to clean season '${season.name}'") >>
            IO.pure(Left(Option(err.getMessage).getOrElse(err.getClass.getSimpleName)))

  /** Finalize a season: all tournaments must be completed. */
  def finalizeSeason(seasonId: UUID): IO[Either[String, String]] =
    for
      seasonOpt <- SeasonRepository.findById(seasonId).transact(xa)
      result <- seasonOpt match
        case None => IO.pure(Left("Season not found"))
        case Some(season) => TournamentRepository.findAll(Some(seasonId), None).transact(xa).flatMap: tournaments =>
            checkSeasonComplete(tournaments) match
              case Some(msg) => IO.pure(Left(msg))
              case None => SeasonRepository.update(
                  seasonId,
                  UpdateSeason(
                    name = None,
                    status = Some("completed"),
                    maxTeams = None,
                    tieFloor = None,
                    sideBetAmount = None
                  )
                ).transact(xa).map(_ => Right(s"Season '${season.name}' finalized (${tournaments.size} tournaments)"))
    yield result

  /** Pure: check if any tournaments block an operation (finalize or reset). Returns an error message if blocked. */
  private[service] def checkBlockingTournaments(blocking: List[Tournament], action: String): Option[String] =
    if blocking.isEmpty then None
    else
      val names = blocking.sortBy(_.startDate).map(t => s"'${t.name}' (${t.startDate})").mkString(", ")
      val verb = if action == "finalize" then "Finalize" else "Reset"
      Some(s"Cannot $action out of order. $verb these first: $names")

  /** Pure: check if all tournaments in a season are completed. Returns an error message if not ready. */
  private[service] def checkSeasonComplete(tournaments: List[Tournament]): Option[String] =
    if tournaments.isEmpty then Some("No tournaments in this season")
    else
      val incomplete = tournaments.filter(_.status != "completed")
      if incomplete.isEmpty then None
      else
        val names = incomplete.sortBy(_.startDate).map(t => s"'${t.name}' (${t.status})").mkString(", ")
        Some(s"Cannot finalize — incomplete tournaments: $names")
