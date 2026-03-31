package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import java.time.Instant
import com.cwfgw.domain.*

object TeamRepository:

  private val selectCols = fr"id, league_id, owner_name, team_name, team_number, created_at, updated_at"

  def findByLeague(leagueId: UUID): ConnectionIO[List[Team]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM teams WHERE league_id = $leagueId ORDER BY team_number ASC NULLS LAST, team_name")
      .query[Team].to[List]

  def findById(id: UUID): ConnectionIO[Option[Team]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM teams WHERE id = $id")
      .query[Team].option

  def create(leagueId: UUID, req: CreateTeam): ConnectionIO[Team] =
    sql"""INSERT INTO teams (league_id, owner_name, team_name, team_number)
          VALUES ($leagueId, ${req.ownerName}, ${req.teamName}, ${req.teamNumber})
          RETURNING $selectCols"""
      .query[Team].unique

  def update(id: UUID, req: UpdateTeam): ConnectionIO[Option[Team]] =
    val sets = List(
      req.ownerName.map(v => fr"owner_name = $v"),
      req.teamName.map(v => fr"team_name = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE teams SET" ++ setFragment ++ fr", updated_at = now() WHERE id = $id RETURNING" ++ selectCols)
        .query[Team].option

  def getRoster(teamId: UUID): ConnectionIO[List[RosterEntry]] =
    sql"""SELECT id, team_id, golfer_id, acquired_via, draft_round, ownership_pct, acquired_at, dropped_at, is_active
          FROM team_rosters WHERE team_id = $teamId AND dropped_at IS NULL ORDER BY draft_round ASC NULLS LAST, acquired_at"""
      .query[RosterEntry].to[List]

  def addToRoster(teamId: UUID, req: AddToRoster): ConnectionIO[RosterEntry] =
    sql"""INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct)
          VALUES ($teamId, ${req.golferId}, ${req.acquiredVia.getOrElse("free_agent")}, ${req.draftRound}, ${req.ownershipPct.getOrElse(BigDecimal(100))})
          RETURNING id, team_id, golfer_id, acquired_via, draft_round, ownership_pct, acquired_at, dropped_at, is_active"""
      .query[RosterEntry].unique

  def getRosterByLeague(leagueId: UUID): ConnectionIO[List[RosterEntry]] =
    sql"""SELECT r.id, r.team_id, r.golfer_id, r.acquired_via, r.draft_round, r.ownership_pct, r.acquired_at, r.dropped_at, r.is_active
          FROM team_rosters r JOIN teams t ON r.team_id = t.id
          WHERE t.league_id = $leagueId AND r.dropped_at IS NULL
          ORDER BY r.draft_round ASC NULLS LAST"""
      .query[RosterEntry].to[List]

  /** Roster entries joined with golfer names, for a whole league. */
  def getRosterViewByLeague(leagueId: UUID): ConnectionIO[List[(UUID, String, Int, String, String, BigDecimal, UUID)]] =
    sql"""SELECT t.id, t.team_name, r.draft_round, g.first_name, g.last_name, r.ownership_pct, g.id
          FROM team_rosters r
          JOIN teams t ON r.team_id = t.id
          JOIN golfers g ON r.golfer_id = g.id
          WHERE t.league_id = $leagueId AND r.dropped_at IS NULL
          ORDER BY t.created_at, r.draft_round ASC NULLS LAST"""
      .query[(UUID, String, Int, String, String, BigDecimal, UUID)].to[List]

  def dropFromRoster(teamId: UUID, golferId: UUID): ConnectionIO[Boolean] =
    sql"""UPDATE team_rosters SET dropped_at = now(), is_active = false
          WHERE team_id = $teamId AND golfer_id = $golferId AND dropped_at IS NULL"""
      .update.run.map(_ > 0)
