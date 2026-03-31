package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import org.typelevel.log4cats.LoggerFactory
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.TournamentRepository

class TournamentService(
    espnImportService: EspnImportService,
    scoringService: ScoringService,
    xa: Transactor[IO]
)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger
  private val leagueId = UUID.fromString("11111111-1111-1111-1111-111111111111")

  def list(seasonYear: Option[Int], status: Option[String]): IO[List[Tournament]] =
    TournamentRepository.findAll(seasonYear, status).transact(xa)

  def get(id: UUID): IO[Option[Tournament]] =
    TournamentRepository.findById(id).transact(xa)

  def create(req: CreateTournament): IO[Tournament] =
    TournamentRepository.create(req).transact(xa)

  def update(id: UUID, req: UpdateTournament): IO[Option[Tournament]] =
    TournamentRepository.update(id, req).transact(xa)

  def getResults(tournamentId: UUID): IO[List[TournamentResult]] =
    TournamentRepository.findResults(tournamentId).transact(xa)

  def importResults(tournamentId: UUID, results: List[CreateTournamentResult]): IO[List[TournamentResult]] =
    results.traverse(r => TournamentRepository.upsertResult(tournamentId, r)).transact(xa)

  /** Finalize a tournament: import ESPN results, calculate scores, refresh standings.
    * Rejects if any earlier tournament (by start_date) has not been finalized yet. */
  def finalizeTournament(tournamentId: UUID): IO[Either[String, String]] =
    TournamentRepository.findById(tournamentId).transact(xa).flatMap:
      case None => IO.pure(Left("Tournament not found"))
      case Some(tournament) =>
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
