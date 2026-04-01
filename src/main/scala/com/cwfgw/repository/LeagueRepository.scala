package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.*

object LeagueRepository:

  def findAll: ConnectionIO[List[League]] =
    sql"SELECT id, name, created_at FROM leagues ORDER BY name"
      .query[League].to[List]

  def findById(id: UUID): ConnectionIO[Option[League]] =
    sql"SELECT id, name, created_at FROM leagues WHERE id = $id"
      .query[League].option

  def create(req: CreateLeague): ConnectionIO[League] =
    sql"""INSERT INTO leagues (name)
          VALUES (${req.name})
          RETURNING id, name, created_at"""
      .query[League].unique
