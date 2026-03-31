package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
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
