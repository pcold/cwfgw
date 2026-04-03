package com.cwfgw.repository

import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.*

object SeasonRepository:

  private val selectCols = fr"""id, league_id, name, season_year, season_number,
         status, tie_floor, side_bet_amount, max_teams, created_at, updated_at"""

  def findAll(leagueId: Option[UUID], seasonYear: Option[Int]): ConnectionIO[List[Season]] =
    val base = fr"SELECT" ++ selectCols ++ fr"FROM seasons"
    val conditions = List(leagueId.map(id => fr"league_id = $id"), seasonYear.map(y => fr"season_year = $y")).flatten
    val where =
      if conditions.isEmpty then Fragment.empty else fr"WHERE" ++ conditions.reduceLeft((a, b) => a ++ fr"AND" ++ b)
    (base ++ where ++ fr"ORDER BY season_year DESC, season_number DESC").query[Season].to[List]

  def findById(id: UUID): ConnectionIO[Option[Season]] =
    (fr"SELECT" ++ selectCols ++ fr"FROM seasons WHERE id = $id").query[Season].option

  /** Load the full SeasonRules for a season by joining the payouts and side bet rounds tables. */
  def getSeasonRules(seasonId: UUID): ConnectionIO[Option[SeasonRules]] =
    for
      seasonOpt <- findById(seasonId)
      result <- seasonOpt.traverse { season =>
        for
          payouts <- sql"""SELECT amount FROM season_rule_payouts
                           WHERE season_id = $seasonId ORDER BY position ASC"""
            .query[BigDecimal].to[List]
          sideBetRounds <- sql"""SELECT round FROM season_rule_side_bet_rounds
                                WHERE season_id = $seasonId ORDER BY round ASC"""
            .query[Int].to[List]
        yield SeasonRules(
          payouts = if payouts.isEmpty then SeasonRules.default.payouts else payouts,
          tieFloor = season.tieFloor,
          sideBetRounds = if sideBetRounds.isEmpty then SeasonRules.default.sideBetRounds else sideBetRounds,
          sideBetAmount = season.sideBetAmount
        )
      }
    yield result

  def create(req: CreateSeason): ConnectionIO[Season] = sql"""INSERT INTO seasons (
            league_id, name, season_year, season_number,
            max_teams, tie_floor, side_bet_amount
          ) VALUES (
            ${req.leagueId}, ${req.name}, ${req.seasonYear},
            ${req.seasonNumber.getOrElse(1)},
            ${req.maxTeams.getOrElse(10)},
            ${req.tieFloor.getOrElse(SeasonRules.default.tieFloor)},
            ${req.sideBetAmount.getOrElse(SeasonRules.default.sideBetAmount)}
          ) RETURNING $selectCols""".query[Season].unique

  def update(id: UUID, req: UpdateSeason): ConnectionIO[Option[Season]] =
    val sets = List(
      req.name.map(v => fr"name = $v"),
      req.status.map(v => fr"status = $v"),
      req.maxTeams.map(v => fr"max_teams = $v"),
      req.tieFloor.map(v => fr"tie_floor = $v"),
      req.sideBetAmount.map(v => fr"side_bet_amount = $v")
    ).flatten
    if sets.isEmpty then findById(id)
    else
      val setFragment = sets.reduceLeft((a, b) => a ++ fr"," ++ b)
      (fr"UPDATE seasons SET" ++ setFragment ++ fr", updated_at = now() WHERE id = $id RETURNING" ++ selectCols)
        .query[Season].option
