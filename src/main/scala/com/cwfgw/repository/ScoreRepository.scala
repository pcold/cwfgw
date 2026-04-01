package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*

object ScoreRepository:

  def getScores(seasonId: UUID, tournamentId: UUID): ConnectionIO[List[FantasyScore]] =
    sql"""SELECT id, season_id, team_id, tournament_id, golfer_id, points, breakdown, calculated_at
          FROM fantasy_scores WHERE season_id = $seasonId AND tournament_id = $tournamentId
          ORDER BY points DESC"""
      .query[FantasyScore].to[List]

  def upsertScore(seasonId: UUID, teamId: UUID, tournamentId: UUID, golferId: UUID, points: BigDecimal, breakdown: Json): ConnectionIO[FantasyScore] =
    sql"""INSERT INTO fantasy_scores (season_id, team_id, tournament_id, golfer_id, points, breakdown)
          VALUES ($seasonId, $teamId, $tournamentId, $golferId, $points, $breakdown)
          ON CONFLICT (season_id, team_id, tournament_id, golfer_id) DO UPDATE SET
            points = EXCLUDED.points,
            breakdown = EXCLUDED.breakdown,
            calculated_at = now()
          RETURNING id, season_id, team_id, tournament_id, golfer_id, points, breakdown, calculated_at"""
      .query[FantasyScore].unique

  def getGolferSeasonScores(seasonId: UUID, golferId: UUID): ConnectionIO[List[(String, Int, BigDecimal, BigDecimal)]] =
    sql"""SELECT t.name,
                 COALESCE((MIN(fs.breakdown->>'position'))::int, 0),
                 MIN(fs.points),
                 COALESCE(MIN((fs.breakdown->>'base_payout')::numeric), 0)
          FROM fantasy_scores fs
          JOIN tournaments t ON fs.tournament_id = t.id
          WHERE fs.season_id = $seasonId AND fs.golfer_id = $golferId
          GROUP BY t.id, t.name, t.start_date
          ORDER BY t.start_date ASC"""
      .query[(String, Int, BigDecimal, BigDecimal)].to[List]

  def getStandings(seasonId: UUID): ConnectionIO[List[SeasonStanding]] =
    sql"""SELECT id, season_id, team_id, total_points, tournaments_played, last_updated
          FROM season_standings WHERE season_id = $seasonId ORDER BY total_points DESC"""
      .query[SeasonStanding].to[List]

  def upsertStanding(seasonId: UUID, teamId: UUID, totalPoints: BigDecimal, tournamentsPlayed: Int): ConnectionIO[SeasonStanding] =
    sql"""INSERT INTO season_standings (season_id, team_id, total_points, tournaments_played)
          VALUES ($seasonId, $teamId, $totalPoints, $tournamentsPlayed)
          ON CONFLICT (season_id, team_id) DO UPDATE SET
            total_points = EXCLUDED.total_points,
            tournaments_played = EXCLUDED.tournaments_played,
            last_updated = now()
          RETURNING id, season_id, team_id, total_points, tournaments_played, last_updated"""
      .query[SeasonStanding].unique
