package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import org.typelevel.log4cats.LoggerFactory

import java.util.UUID
import java.time.LocalDate

import com.cwfgw.domain.*
import com.cwfgw.repository.TournamentRepository

/** Orchestrates the weekly pipeline: ESPN import → score calculation → standings refresh.
  * Called by the Scheduler when a tournament's end_date has passed. */
class WeeklyJobService(
    espnImportService: EspnImportService,
    scoringService: ScoringService,
    xa: Transactor[IO]
)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger
  private val leagueId = UUID.fromString("11111111-1111-1111-1111-111111111111")

  /** Find tournaments that ended before `today` but haven't been processed yet. */
  def findPendingTournaments(today: LocalDate): IO[List[Tournament]] =
    for
      upcoming <- TournamentRepository.findAll(seasonYear = None, status = Some("upcoming")).transact(xa)
      inProgress <- TournamentRepository.findAll(seasonYear = None, status = Some("in_progress")).transact(xa)
    yield (upcoming ++ inProgress).filter(_.endDate.isBefore(today)).sortBy(_.endDate)

  /** Run the full pipeline for a single tournament. Returns true if completed, false if still in progress. */
  def processTournament(tournament: Tournament): IO[Boolean] =
    for
      _ <- logger.info(s"Processing '${tournament.name}' (end_date=${tournament.endDate})...")
      importResults <- espnImportService.importByDate(tournament.startDate).attempt
      completed <- importResults match
        case Left(err) =>
          logger.warn(s"Import not ready for '${tournament.name}': ${err.getMessage}") >>
            IO.pure(false)
        case Right(results) =>
          val anyCompleted = results.exists(_.completed)
          if anyCompleted then
            for
              _ <- logger.info(s"Imported ${results.size} event(s) for '${tournament.name}', calculating scores...")
              _ <- results.traverse_ : result =>
                scoringService.calculateScores(leagueId, result.tournamentId).flatMap:
                  case Left(err) => logger.warn(s"Score calc issue for ${result.espnName}: $err")
                  case Right(_) => logger.info(s"Scores calculated for ${result.espnName}")
              _ <- logger.info("Refreshing league standings...")
              _ <- scoringService.refreshStandings(leagueId)
              _ <- logger.info(s"Pipeline complete for '${tournament.name}'")
            yield true
          else
            logger.info(s"Tournament '${tournament.name}' not yet completed on ESPN, will retry") >>
              IO.pure(false)
    yield completed

  /** Finalize a specific tournament by ID: import ESPN results, calculate scores, refresh standings.
    * Rejects if any earlier tournament (by start_date) has not been finalized yet. */
  def finalizeTournament(tournamentId: UUID): IO[Either[String, String]] =
    TournamentRepository.findById(tournamentId).transact(xa).flatMap:
      case None => IO.pure(Left("Tournament not found"))
      case Some(tournament) =>
        // Check for unfinalized earlier tournaments
        val checkOrder = for
          upcoming <- TournamentRepository.findAll(seasonYear = Some(tournament.seasonYear), status = Some("upcoming")).transact(xa)
          inProgress <- TournamentRepository.findAll(seasonYear = Some(tournament.seasonYear), status = Some("in_progress")).transact(xa)
        yield
          val earlier = (upcoming ++ inProgress).filter(t => t.id != tournamentId && t.startDate.isBefore(tournament.startDate))
          earlier.sortBy(_.startDate)

        checkOrder.flatMap: unfinalized =>
          if unfinalized.nonEmpty then
            val names = unfinalized.map(t => s"'${t.name}' (${t.startDate})").mkString(", ")
            IO.pure(Left(s"Cannot finalize out of order. Finalize these first: $names"))
          else
            val pipeline = for
              _ <- logger.info(s"Finalizing '${tournament.name}'...")
              importResults <- tournament.pgaTournamentId match
                case Some(espnId) => espnImportService.importSingleEvent(tournament.startDate, espnId).map(List(_))
                case None => espnImportService.importByDate(tournament.startDate)
              _ <- logger.info(s"Imported ${importResults.size} event(s), calculating scores...")
              _ <- scoringService.calculateScores(leagueId, tournamentId)
              _ <- scoringService.refreshStandings(leagueId)
              _ <- logger.info(s"Finalized '${tournament.name}'")
            yield Right(s"Tournament '${tournament.name}' finalized successfully")
            pipeline.handleErrorWith: err =>
              logger.error(err)(s"Failed to finalize '${tournament.name}'") >>
                IO.pure(Left(Option(err.getMessage).getOrElse(err.getClass.getSimpleName)))

  /** Reset a finalized tournament: delete scores + results, set status back to 'upcoming', refresh standings.
    * Rejects if any later tournament (by start_date) is already finalized. */
  def resetTournament(tournamentId: UUID): IO[Either[String, String]] =
    TournamentRepository.findById(tournamentId).transact(xa).flatMap:
      case None => IO.pure(Left("Tournament not found"))
      case Some(tournament) =>
        // Check for finalized later tournaments
        TournamentRepository.findAll(seasonYear = Some(tournament.seasonYear), status = Some("completed")).transact(xa).flatMap: completed =>
          val later = completed.filter(t => t.id != tournamentId && t.startDate.isAfter(tournament.startDate))
          if later.nonEmpty then
            val names = later.sortBy(_.startDate).map(t => s"'${t.name}' (${t.startDate})").mkString(", ")
            IO.pure(Left(s"Cannot reset out of order. Reset these first: $names"))
          else
            val deleteAndReset = for
              _ <- logger.info(s"Resetting '${tournament.name}' (${tournament.id})...")
              scoresDeleted <- sql"DELETE FROM fantasy_scores WHERE tournament_id = $tournamentId".update.run.transact(xa)
              _ <- logger.info(s"Deleted $scoresDeleted fantasy_scores rows")
              resultsDeleted <- sql"DELETE FROM tournament_results WHERE tournament_id = $tournamentId".update.run.transact(xa)
              _ <- logger.info(s"Deleted $resultsDeleted tournament_results rows")
              _ <- TournamentRepository.update(tournamentId, UpdateTournament(
                name = None, startDate = None, endDate = None, courseName = None,
                status = Some("upcoming"), purseAmount = None, isMajor = None
              )).transact(xa)
              _ <- logger.info("Set status back to 'upcoming', refreshing standings...")
              _ <- scoringService.refreshStandings(leagueId)
              _ <- logger.info(s"Reset complete for '${tournament.name}'")
            yield Right(s"Tournament '${tournament.name}' reset successfully ($scoresDeleted scores, $resultsDeleted results deleted)")
            deleteAndReset.handleErrorWith: err =>
              logger.error(err)(s"Failed to reset '${tournament.name}'") >>
                IO.pure(Left(Option(err.getMessage).getOrElse(err.getClass.getSimpleName)))

  /** Run the pipeline for all pending tournaments. Returns count of (completed, still_pending). */
  def processAll: IO[(Int, Int)] =
    for
      today <- IO(LocalDate.now())
      pending <- findPendingTournaments(today)
      _ <- if pending.isEmpty then logger.info("No pending tournaments to process")
           else logger.info(s"Found ${pending.size} pending tournament(s)")
      results <- pending.traverse(processTournament)
      completed = results.count(identity)
      stillPending = results.count(!_)
    yield (completed, stillPending)
