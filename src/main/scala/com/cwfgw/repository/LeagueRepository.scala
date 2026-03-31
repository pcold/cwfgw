package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*

object LeagueRepository:

  def findAll(seasonYear: Option[Int]): ConnectionIO[List[League]] =
    val base = fr"SELECT id, name, season_year, status, rules, max_teams, created_at, updated_at FROM leagues"
    val where = seasonYear.map(y => fr"WHERE season_year = $y").getOrElse(Fragment.empty)
    (base ++ where ++ fr"ORDER BY created_at DESC")
      .query[League].to[List]

  def findById(id: UUID): ConnectionIO[Option[League]] =
    sql"SELECT id, name, season_year, status, rules, max_teams, created_at, updated_at FROM leagues WHERE id = $id"
      .query[League].option

  def create(req: CreateLeague): ConnectionIO[League] =
    sql"""INSERT INTO leagues (name, season_year, max_teams, rules)
          VALUES (${req.name}, ${req.seasonYear}, ${req.maxTeams.getOrElse(10)}, ${req.rules.getOrElse(Json.obj())})
          RETURNING id, name, season_year, status, rules, max_teams, created_at, updated_at"""
      .query[League].unique

  def update(id: UUID, req: UpdateLeague): ConnectionIO[Option[League]] =
    val sets = List(
      req.name.map(v => fr"name = $v"),
      req.status.map(v => fr"status = $v"),
      req.rules.map(v => fr"rules = $v"),
      req.maxTeams.map(v => fr"max_teams = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE leagues SET" ++ setFragment ++ fr", updated_at = now() WHERE id = $id RETURNING id, name, season_year, status, rules, max_teams, created_at, updated_at")
        .query[League].option

  def delete(id: UUID): ConnectionIO[Boolean] =
    sql"DELETE FROM leagues WHERE id = $id".update.run.map(_ > 0)
