package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.*

object TournamentRepository:

  private val selectCols = fr"""id, pga_tournament_id, name, season_id, start_date,
         end_date, course_name, status, purse_amount,
         payout_multiplier, week, created_at"""

  def findAll(seasonId: Option[UUID], status: Option[String]): ConnectionIO[List[Tournament]] =
    val base = fr"SELECT" ++ selectCols ++ fr"FROM tournaments"
    val conditions = List(seasonId.map(id => fr"season_id = $id"), status.map(s => fr"status = $s")).flatten
    val where =
      if conditions.isEmpty then Fragment.empty else fr"WHERE" ++ conditions.reduceLeft((a, b) => a ++ fr"AND" ++ b)
    (base ++ where ++ fr"ORDER BY start_date DESC").query[Tournament].to[List]

  def findById(id: UUID): ConnectionIO[Option[Tournament]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM tournaments WHERE id = $id").query[Tournament].option

  def create(req: CreateTournament): ConnectionIO[Tournament] = sql"""INSERT INTO tournaments (
            pga_tournament_id, name, season_id, start_date,
            end_date, course_name, purse_amount,
            payout_multiplier, week
          ) VALUES (
            ${req.pgaTournamentId}, ${req.name}, ${req.seasonId},
            ${req.startDate}, ${req.endDate}, ${req.courseName},
            ${req.purseAmount},
            ${req.payoutMultiplier.getOrElse(BigDecimal(1))},
            ${req.week}
          ) RETURNING $selectCols""".query[Tournament].unique

  def update(id: UUID, req: UpdateTournament): ConnectionIO[Option[Tournament]] =
    val sets = List(
      req.name.map(v => fr"name = $v"),
      req.startDate.map(v => fr"start_date = $v"),
      req.endDate.map(v => fr"end_date = $v"),
      req.courseName.map(v => fr"course_name = $v"),
      req.status.map(v => fr"status = $v"),
      req.purseAmount.map(v => fr"purse_amount = $v"),
      req.payoutMultiplier.map(v => fr"payout_multiplier = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE tournaments SET" ++ setFragment ++ fr"WHERE id = $id RETURNING" ++ selectCols).query[Tournament].option

  def findResults(tournamentId: UUID): ConnectionIO[List[TournamentResult]] =
    sql"""SELECT id, tournament_id, golfer_id, position,
            score_to_par, total_strokes, earnings,
            round1, round2, round3, round4, made_cut
          FROM tournament_results
          WHERE tournament_id = $tournamentId
          ORDER BY position ASC NULLS LAST""".query[TournamentResult].to[List]

  /** Look up a tournament's internal id and payout multiplier by its ESPN/PGA id. */
  def findIdAndMultiplier(pgaTournamentId: String): ConnectionIO[Option[(UUID, BigDecimal)]] =
    sql"""SELECT id, payout_multiplier FROM tournaments
          WHERE pga_tournament_id = $pgaTournamentId""".query[(UUID, BigDecimal)].option

  /** Find a tournament id by its ESPN/PGA tournament id. */
  def findIdByPgaId(pgaTournamentId: String): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM tournaments WHERE pga_tournament_id = $pgaTournamentId".query[UUID].option

  /** Find the earliest tournament with no linked ESPN id. */
  def findFirstUnlinked: ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM tournaments WHERE pga_tournament_id IS NULL ORDER BY start_date ASC LIMIT 1".query[UUID].option

  /** Link an ESPN/PGA tournament id to a tournament record. */
  def linkPgaTournamentId(id: UUID, pgaTournamentId: String): ConnectionIO[Int] =
    sql"UPDATE tournaments SET pga_tournament_id = $pgaTournamentId WHERE id = $id".update.run

  /** Mark a tournament as completed. */
  def markCompleted(id: UUID): ConnectionIO[Int] =
    sql"UPDATE tournaments SET status = 'completed' WHERE id = $id".update.run

  def deleteResultsByTournament(tournamentId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM tournament_results WHERE tournament_id = $tournamentId".update.run

  def deleteResultsBySeason(seasonId: UUID): ConnectionIO[Int] = sql"""DELETE FROM tournament_results
          WHERE tournament_id IN (SELECT id FROM tournaments WHERE season_id = $seasonId)""".update.run

  def resetSeasonTournaments(seasonId: UUID): ConnectionIO[Int] =
    sql"UPDATE tournaments SET status = 'upcoming' WHERE season_id = $seasonId AND status != 'upcoming'".update.run

  def upsertResult(tournamentId: UUID, req: CreateTournamentResult): ConnectionIO[TournamentResult] =
    sql"""INSERT INTO tournament_results (
            tournament_id, golfer_id, position, score_to_par,
            total_strokes, earnings,
            round1, round2, round3, round4, made_cut
          ) VALUES (
            $tournamentId, ${req.golferId}, ${req.position},
            ${req.scoreToPar}, ${req.totalStrokes}, ${req.earnings},
            ${req.round1}, ${req.round2}, ${req.round3}, ${req.round4},
            ${req.madeCut}
          ) ON CONFLICT (tournament_id, golfer_id) DO UPDATE SET
            position = EXCLUDED.position,
            score_to_par = EXCLUDED.score_to_par,
            total_strokes = EXCLUDED.total_strokes,
            earnings = EXCLUDED.earnings,
            round1 = EXCLUDED.round1, round2 = EXCLUDED.round2,
            round3 = EXCLUDED.round3, round4 = EXCLUDED.round4,
            made_cut = EXCLUDED.made_cut
          RETURNING id, tournament_id, golfer_id, position,
            score_to_par, total_strokes, earnings,
            round1, round2, round3, round4, made_cut""".query[TournamentResult].unique
