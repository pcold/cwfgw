package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*

object ScoreRepository:

  def getScores(leagueId: UUID, tournamentId: UUID): ConnectionIO[List[FantasyScore]] =
    sql"""SELECT id, league_id, team_id, tournament_id, golfer_id, points, breakdown, calculated_at
          FROM fantasy_scores WHERE league_id = $leagueId AND tournament_id = $tournamentId
          ORDER BY points DESC"""
      .query[FantasyScore].to[List]

  def upsertScore(leagueId: UUID, teamId: UUID, tournamentId: UUID, golferId: UUID, points: BigDecimal, breakdown: Json): ConnectionIO[FantasyScore] =
    sql"""INSERT INTO fantasy_scores (league_id, team_id, tournament_id, golfer_id, points, breakdown)
          VALUES ($leagueId, $teamId, $tournamentId, $golferId, $points, $breakdown)
          ON CONFLICT (league_id, team_id, tournament_id, golfer_id) DO UPDATE SET
            points = EXCLUDED.points,
            breakdown = EXCLUDED.breakdown,
            calculated_at = now()
          RETURNING id, league_id, team_id, tournament_id, golfer_id, points, breakdown, calculated_at"""
      .query[FantasyScore].unique

  def getStandings(leagueId: UUID): ConnectionIO[List[LeagueStanding]] =
    sql"""SELECT id, league_id, team_id, total_points, tournaments_played, last_updated
          FROM league_standings WHERE league_id = $leagueId ORDER BY total_points DESC"""
      .query[LeagueStanding].to[List]

  def upsertStanding(leagueId: UUID, teamId: UUID, totalPoints: BigDecimal, tournamentsPlayed: Int): ConnectionIO[LeagueStanding] =
    sql"""INSERT INTO league_standings (league_id, team_id, total_points, tournaments_played)
          VALUES ($leagueId, $teamId, $totalPoints, $tournamentsPlayed)
          ON CONFLICT (league_id, team_id) DO UPDATE SET
            total_points = EXCLUDED.total_points,
            tournaments_played = EXCLUDED.tournaments_played,
            last_updated = now()
          RETURNING id, league_id, team_id, total_points, tournaments_played, last_updated"""
      .query[LeagueStanding].unique
