package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*

object TournamentRepository:

  private val selectCols =
    fr"id, pga_tournament_id, name, season_year, start_date, end_date, course_name, status, purse_amount, is_major, metadata, created_at"

  def findAll(seasonYear: Option[Int], status: Option[String]): ConnectionIO[List[Tournament]] =
    val base = fr"SELECT" ++ selectCols ++ fr"FROM tournaments"
    val conditions = List(
      seasonYear.map(y => fr"season_year = $y"),
      status.map(s => fr"status = $s")
    ).flatten
    val where = if conditions.isEmpty then Fragment.empty
                else fr"WHERE" ++ conditions.reduceLeft((a, b) => a ++ fr"AND" ++ b)
    (base ++ where ++ fr"ORDER BY start_date DESC")
      .query[Tournament].to[List]

  def findById(id: UUID): ConnectionIO[Option[Tournament]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM tournaments WHERE id = $id")
      .query[Tournament].option

  def create(req: CreateTournament): ConnectionIO[Tournament] =
    sql"""INSERT INTO tournaments (pga_tournament_id, name, season_year, start_date, end_date, course_name, purse_amount, is_major, metadata)
          VALUES (${req.pgaTournamentId}, ${req.name}, ${req.seasonYear}, ${req.startDate}, ${req.endDate}, ${req.courseName}, ${req.purseAmount}, ${req.isMajor.getOrElse(false)}, ${req.metadata.getOrElse(Json.obj())})
          RETURNING $selectCols"""
      .query[Tournament].unique

  def update(id: UUID, req: UpdateTournament): ConnectionIO[Option[Tournament]] =
    val sets = List(
      req.name.map(v => fr"name = $v"),
      req.startDate.map(v => fr"start_date = $v"),
      req.endDate.map(v => fr"end_date = $v"),
      req.courseName.map(v => fr"course_name = $v"),
      req.status.map(v => fr"status = $v"),
      req.purseAmount.map(v => fr"purse_amount = $v"),
      req.isMajor.map(v => fr"is_major = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE tournaments SET" ++ setFragment ++ fr"WHERE id = $id RETURNING" ++ selectCols)
        .query[Tournament].option

  def findResults(tournamentId: UUID): ConnectionIO[List[TournamentResult]] =
    sql"""SELECT id, tournament_id, golfer_id, position, score_to_par, total_strokes, earnings, round_scores, made_cut, metadata
          FROM tournament_results WHERE tournament_id = $tournamentId ORDER BY position ASC NULLS LAST"""
      .query[TournamentResult].to[List]

  def upsertResult(tournamentId: UUID, req: CreateTournamentResult): ConnectionIO[TournamentResult] =
    sql"""INSERT INTO tournament_results (tournament_id, golfer_id, position, score_to_par, total_strokes, earnings, round_scores, made_cut)
          VALUES ($tournamentId, ${req.golferId}, ${req.position}, ${req.scoreToPar}, ${req.totalStrokes}, ${req.earnings}, ${req.roundScores.getOrElse(Json.Null)}, ${req.madeCut})
          ON CONFLICT (tournament_id, golfer_id) DO UPDATE SET
            position = EXCLUDED.position,
            score_to_par = EXCLUDED.score_to_par,
            total_strokes = EXCLUDED.total_strokes,
            earnings = EXCLUDED.earnings,
            round_scores = EXCLUDED.round_scores,
            made_cut = EXCLUDED.made_cut
          RETURNING id, tournament_id, golfer_id, position, score_to_par, total_strokes, earnings, round_scores, made_cut, metadata"""
      .query[TournamentResult].unique
