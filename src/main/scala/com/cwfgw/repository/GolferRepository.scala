package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*

object GolferRepository:

  private val selectCols =
    fr"id, pga_player_id, first_name, last_name, country, world_ranking, active, metadata, updated_at"

  def findAll(activeOnly: Boolean, search: Option[String]): ConnectionIO[List[Golfer]] =
    val base = fr"SELECT" ++ selectCols ++ fr"FROM golfers"
    val conditions = List(
      if activeOnly then Some(fr"active = true") else None,
      search.map(s => fr"(first_name ILIKE ${"%" + s + "%"} OR last_name ILIKE ${"%" + s + "%"})")
    ).flatten
    val where =
      if conditions.isEmpty then Fragment.empty else fr"WHERE" ++ conditions.reduceLeft((a, b) => a ++ fr"AND" ++ b)
    (base ++ where ++ fr"ORDER BY world_ranking ASC NULLS LAST, last_name ASC").query[Golfer].to[List]

  def findById(id: UUID): ConnectionIO[Option[Golfer]] = (fr"SELECT" ++ selectCols ++ fr"FROM golfers WHERE id = $id")
    .query[Golfer].option

  def create(req: CreateGolfer): ConnectionIO[Golfer] =
    sql"""INSERT INTO golfers (pga_player_id, first_name, last_name, country, world_ranking)
          VALUES (${req.pgaPlayerId}, ${req.firstName}, ${req.lastName}, ${req.country}, ${req.worldRanking})
          RETURNING $selectCols""".query[Golfer].unique

  def update(id: UUID, req: UpdateGolfer): ConnectionIO[Option[Golfer]] =
    val sets = List(
      req.pgaPlayerId.map(v => fr"pga_player_id = $v"),
      req.firstName.map(v => fr"first_name = $v"),
      req.lastName.map(v => fr"last_name = $v"),
      req.country.map(v => fr"country = $v"),
      req.worldRanking.map(v => fr"world_ranking = $v"),
      req.active.map(v => fr"active = $v"),
      req.metadata.map(v => fr"metadata = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE golfers SET" ++ setFragment ++ fr", updated_at = now() WHERE id = $id RETURNING" ++ selectCols)
        .query[Golfer].option
