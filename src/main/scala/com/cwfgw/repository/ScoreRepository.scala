package com.cwfgw.repository

import cats.data.NonEmptyList
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.*

object ScoreRepository:

  private val selectCols = fr"""id, season_id, team_id, tournament_id, golfer_id, points,
         position, num_tied, base_payout, ownership_pct, payout, multiplier, calculated_at"""

  def getScores(seasonId: UUID, tournamentId: UUID): ConnectionIO[List[FantasyScore]] =
    (fr"SELECT" ++ selectCols ++ fr"""FROM fantasy_scores
          WHERE season_id = $seasonId AND tournament_id = $tournamentId
          ORDER BY points DESC""").query[FantasyScore].to[List]

  def upsertScore(
    seasonId: UUID,
    teamId: UUID,
    tournamentId: UUID,
    golferId: UUID,
    points: BigDecimal,
    bd: ScoreBreakdown
  ): ConnectionIO[FantasyScore] =
    sql"""INSERT INTO fantasy_scores
            (season_id, team_id, tournament_id, golfer_id, points,
             position, num_tied, base_payout, ownership_pct, payout, multiplier)
          VALUES ($seasonId, $teamId, $tournamentId, $golferId, $points,
                  ${bd.position}, ${bd.numTied}, ${bd.basePayout},
                  ${bd.ownershipPct}, ${bd.payout}, ${bd.multiplier})
          ON CONFLICT (season_id, team_id, tournament_id, golfer_id) DO UPDATE SET
            points = EXCLUDED.points,
            position = EXCLUDED.position, num_tied = EXCLUDED.num_tied,
            base_payout = EXCLUDED.base_payout, ownership_pct = EXCLUDED.ownership_pct,
            payout = EXCLUDED.payout, multiplier = EXCLUDED.multiplier,
            calculated_at = now()
          RETURNING $selectCols"""
      .query[FantasyScore].unique

  def getGolferSeasonScores(seasonId: UUID, golferId: UUID): ConnectionIO[List[(String, Int, BigDecimal, BigDecimal)]] =
    sql"""SELECT t.name,
                 COALESCE(MIN(fs.position), 0),
                 SUM(fs.points),
                 COALESCE(MIN(fs.base_payout), 0)
          FROM fantasy_scores fs
          JOIN tournaments t ON fs.tournament_id = t.id
          WHERE fs.season_id = $seasonId AND fs.golfer_id = $golferId
          GROUP BY t.id, t.name, t.start_date
          ORDER BY t.start_date ASC""".query[(String, Int, BigDecimal, BigDecimal)].to[List]

  def getStandings(seasonId: UUID): ConnectionIO[List[SeasonStanding]] =
    sql"""SELECT id, season_id, team_id, total_points, tournaments_played, last_updated
          FROM season_standings WHERE season_id = $seasonId ORDER BY total_points DESC""".query[SeasonStanding].to[List]

  /** Total points for a golfer on a team across all
    * tournaments in a season. */
  def golferPointTotal(
      seasonId: UUID,
      teamId: UUID,
      golferId: UUID
  ): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(points), 0)
          FROM fantasy_scores
          WHERE season_id = $seasonId
            AND team_id = $teamId
            AND golfer_id = $golferId"""
      .query[BigDecimal].unique

  /** Total points and tournament count for a team
    * across all tournaments in a season. */
  def teamSeasonTotals(
      seasonId: UUID,
      teamId: UUID
  ): ConnectionIO[(BigDecimal, Int)] =
    sql"""SELECT COALESCE(SUM(points), 0),
                 COUNT(DISTINCT tournament_id)
          FROM fantasy_scores
          WHERE season_id = $seasonId
            AND team_id = $teamId"""
      .query[(BigDecimal, Int)].unique

  /** Total points for a golfer on a team, scoped to
    * a specific set of tournaments. */
  def golferPointTotalScoped(
      seasonId: UUID,
      teamId: UUID,
      golferId: UUID,
      tournamentIds: NonEmptyList[UUID]
  ): ConnectionIO[BigDecimal] =
    val inClause =
      Fragments.in(fr"tournament_id", tournamentIds)
    (fr"""SELECT COALESCE(SUM(points), 0)
          FROM fantasy_scores
          WHERE season_id = $seasonId
            AND team_id = $teamId
            AND golfer_id = $golferId
            AND""" ++ inClause)
      .query[BigDecimal].unique

  def deleteByTournament(tournamentId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM fantasy_scores WHERE tournament_id = $tournamentId".update.run

  def deleteBySeason(seasonId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM fantasy_scores WHERE season_id = $seasonId".update.run

  def deleteStandingsBySeason(seasonId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM season_standings WHERE season_id = $seasonId".update.run

  def upsertStanding(
    seasonId: UUID,
    teamId: UUID,
    totalPoints: BigDecimal,
    tournamentsPlayed: Int
  ): ConnectionIO[SeasonStanding] =
    sql"""INSERT INTO season_standings (season_id, team_id, total_points, tournaments_played)
          VALUES ($seasonId, $teamId, $totalPoints, $tournamentsPlayed)
          ON CONFLICT (season_id, team_id) DO UPDATE SET
            total_points = EXCLUDED.total_points,
            tournaments_played = EXCLUDED.tournaments_played,
            last_updated = now()
          RETURNING id, season_id, team_id, total_points, tournaments_played, last_updated""".query[SeasonStanding]
      .unique
