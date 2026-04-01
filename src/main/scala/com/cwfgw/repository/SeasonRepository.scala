package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*

object SeasonRepository:

  private val selectCols =
    fr"""id, league_id, name, season_year, season_number,
         status, rules, max_teams, created_at, updated_at"""

  def findAll(
      leagueId: Option[UUID],
      seasonYear: Option[Int]
  ): ConnectionIO[List[Season]] =
    val base = fr"SELECT" ++ selectCols ++ fr"FROM seasons"
    val conditions = List(
      leagueId.map(id => fr"league_id = $id"),
      seasonYear.map(y => fr"season_year = $y")
    ).flatten
    val where =
      if conditions.isEmpty then Fragment.empty
      else
        fr"WHERE" ++ conditions.reduceLeft((a, b) =>
          a ++ fr"AND" ++ b
        )
    (base ++ where ++ fr"ORDER BY season_year DESC, season_number DESC")
      .query[Season].to[List]

  def findById(id: UUID): ConnectionIO[Option[Season]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM seasons WHERE id = $id")
      .query[Season].option

  def create(req: CreateSeason): ConnectionIO[Season] =
    sql"""INSERT INTO seasons (
            league_id, name, season_year, season_number,
            max_teams, rules
          ) VALUES (
            ${req.leagueId}, ${req.name}, ${req.seasonYear},
            ${req.seasonNumber.getOrElse(1)},
            ${req.maxTeams.getOrElse(10)},
            ${req.rules.getOrElse(Json.obj())}
          ) RETURNING $selectCols"""
      .query[Season].unique

  def update(
      id: UUID,
      req: UpdateSeason
  ): ConnectionIO[Option[Season]] =
    val sets = List(
      req.name.map(v => fr"name = $v"),
      req.status.map(v => fr"status = $v"),
      req.rules.map(v => fr"rules = $v"),
      req.maxTeams.map(v => fr"max_teams = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE seasons SET" ++ setFragment ++
        fr", updated_at = now() WHERE id = $id RETURNING" ++ selectCols)
        .query[Season].option
