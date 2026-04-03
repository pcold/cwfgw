package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.*

object GolferRepository:

  private val selectCols = fr"id, pga_player_id, first_name, last_name, country, world_ranking, active, updated_at"

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

  /** Find a golfer by their ESPN/PGA athlete ID. */
  def findIdByPgaPlayerId(pgaPlayerId: String): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM golfers WHERE pga_player_id = $pgaPlayerId".query[UUID].option

  /** Find a full golfer record by their ESPN/PGA athlete ID. */
  def findByPgaPlayerId(pgaPlayerId: String): ConnectionIO[Option[Golfer]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM golfers WHERE pga_player_id = $pgaPlayerId").query[Golfer].option

  /** Link an ESPN/PGA athlete ID to a golfer (only if not already linked). */
  def linkPgaPlayerId(id: UUID, pgaPlayerId: String): ConnectionIO[Int] =
    sql"UPDATE golfers SET pga_player_id = $pgaPlayerId WHERE id = $id AND pga_player_id IS NULL".update.run

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
      req.active.map(v => fr"active = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE golfers SET" ++ setFragment ++ fr", updated_at = now() WHERE id = $id RETURNING" ++ selectCols)
        .query[Golfer].option
