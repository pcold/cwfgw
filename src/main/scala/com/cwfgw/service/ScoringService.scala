package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.{
  ScoreRepository, TeamRepository,
  TournamentRepository, SeasonRepository
}

class ScoringService(xa: Transactor[IO]):

  def getScores(
      seasonId: UUID,
      tournamentId: UUID
  ): IO[List[FantasyScore]] =
    ScoreRepository.getScores(seasonId, tournamentId)
      .transact(xa)

  def tieSplitPayout(
      position: Int,
      numTied: Int,
      multiplier: BigDecimal,
      rules: SeasonRules
  ): BigDecimal =
    PayoutTable.tieSplitPayout(
      position, numTied, multiplier, rules
    )

  /** Calculate scores for a tournament. Each golfer's
    * earnings are stored, then the zero-sum weekly totals
    * can be derived. */
  def calculateScores(
      seasonId: UUID,
      tournamentId: UUID
  ): IO[Either[String, Json]] =
    val action = for
      seasonOpt <- SeasonRepository.findById(seasonId)
      tournamentOpt <- TournamentRepository
        .findById(tournamentId)
      results <- TournamentRepository
        .findResults(tournamentId)
      teams <- TeamRepository.findBySeason(seasonId)
      allRosters <- TeamRepository
        .getRosterBySeason(seasonId)
      outcome <- (seasonOpt, tournamentOpt) match
        case (None, _) =>
          FC.pure(Left("Season not found"))
        case (_, None) =>
          FC.pure(Left("Tournament not found"))
        case (Some(season), Some(tournament)) =>
          val rules = season.seasonRules
          val multiplier = tournament.payoutMultiplier
          val numTeams = teams.size
          val resultsByGolfer =
            results.map(r => r.golferId -> r).toMap

          val teamEarnings = teams.traverse { team =>
            for
              roster <- TeamRepository.getRoster(team.id)
              golferScores <- roster.traverse { entry =>
                resultsByGolfer.get(entry.golferId) match
                  case None =>
                    FC.pure(
                      Option.empty[(UUID, BigDecimal, Json)]
                    )
                  case Some(result) =>
                    result.position match
                      case None =>
                        FC.pure(
                          Option.empty[
                            (UUID, BigDecimal, Json)
                          ]
                        )
                      case Some(pos)
                          if pos > rules.payouts.size =>
                        FC.pure(
                          Option.empty[
                            (UUID, BigDecimal, Json)
                          ]
                        )
                      case Some(pos) =>
                        val numTied = results.count(
                          _.position == result.position
                        )
                        val basePayout =
                          tieSplitPayout(
                            pos, numTied,
                            multiplier, rules
                          )
                        val ownerPayout =
                          basePayout *
                            entry.ownershipPct /
                            BigDecimal(100)
                        val breakdown = Json.obj(
                          "position" -> pos.asJson,
                          "num_tied" -> numTied.asJson,
                          "base_payout" ->
                            basePayout.asJson,
                          "ownership_pct" ->
                            entry.ownershipPct.asJson,
                          "payout" ->
                            ownerPayout.asJson,
                          "multiplier" ->
                            multiplier.asJson
                        )
                        ScoreRepository.upsertScore(
                          seasonId, team.id, tournamentId,
                          entry.golferId, ownerPayout,
                          breakdown
                        ).map(s =>
                          Some((
                            entry.golferId,
                            ownerPayout,
                            breakdown
                          ))
                        )
              }
            yield (team, golferScores.flatten, roster)
          }

          teamEarnings.map { teamsData =>
            val teamTotals = teamsData.map {
              (team, scores, _) =>
                val topTens = scores.map(_._2).sum
                (team, topTens, scores)
            }
            val totalPot = teamTotals.map(_._2).sum

            val weeklyResults = teamTotals.map {
              (team, topTens, scores) =>
                val weeklyTotal =
                  topTens * numTeams - totalPot
                Json.obj(
                  "team_id" -> team.id.asJson,
                  "team_name" -> team.teamName.asJson,
                  "top_tens" -> topTens.asJson,
                  "weekly_total" -> weeklyTotal.asJson,
                  "golfer_scores" -> scores.map {
                    (gid, payout, bd) =>
                      Json.obj(
                        "golfer_id" -> gid.asJson,
                        "payout" -> payout.asJson,
                        "breakdown" -> bd
                      )
                  }.asJson
                )
            }

            Right(Json.obj(
              "tournament_id" -> tournamentId.asJson,
              "multiplier" -> multiplier.asJson,
              "num_teams" -> numTeams.asJson,
              "total_pot" -> totalPot.asJson,
              "teams" -> weeklyResults.asJson
            ))
          }
    yield outcome
    action.transact(xa)

  /** Get season-long side bet standings. */
  def getSideBetStandings(
      seasonId: UUID
  ): IO[Either[String, Json]] =
    val action = for
      seasonOpt <- SeasonRepository.findById(seasonId)
      teams <- TeamRepository.findBySeason(seasonId)
      allRosters <- TeamRepository
        .getRosterBySeason(seasonId)
      result <-
        if teams.isEmpty then
          FC.pure(Left("No teams found"))
        else seasonOpt match
          case None =>
            FC.pure(Left("Season not found"))
          case Some(season) =>
            val rules = season.seasonRules
            val numTeams = teams.size
            val teamMap =
              teams.map(t => t.id -> t.teamName).toMap
            rules.sideBetRounds.traverse { round =>
              val roundPicks = allRosters
                .filter(_.draftRound.contains(round))
              roundPicks.traverse { entry =>
                sql"""SELECT COALESCE(SUM(points), 0)
                      FROM fantasy_scores
                      WHERE season_id = $seasonId
                        AND team_id = ${entry.teamId}
                        AND golfer_id = ${entry.golferId}"""
                  .query[BigDecimal].unique
                  .map(total =>
                    (entry.teamId, entry.golferId, total)
                  )
              }.map { entries =>
                val sorted = entries.sortBy(-_._3)
                val winner = sorted.headOption
                  .filter(_._3 > BigDecimal(0))
                val active = winner.isDefined
                Json.obj(
                  "round" -> round.asJson,
                  "active" -> active.asJson,
                  "winner" -> winner.map {
                    (tid, gid, total) =>
                      Json.obj(
                        "team_id" -> tid.asJson,
                        "team_name" -> teamMap
                          .getOrElse(tid, "").asJson,
                        "golfer_id" -> gid.asJson,
                        "cumulative_earnings" ->
                          total.asJson,
                        "net_winnings" ->
                          (rules.sideBetAmount *
                            (numTeams - 1)).asJson
                      )
                  }.asJson,
                  "entries" -> sorted.map {
                    (tid, gid, total) =>
                      Json.obj(
                        "team_id" -> tid.asJson,
                        "team_name" -> teamMap
                          .getOrElse(tid, "").asJson,
                        "golfer_id" -> gid.asJson,
                        "cumulative_earnings" ->
                          total.asJson
                      )
                  }.asJson
                )
              }
            }.map { rounds =>
              val winners = rounds.flatMap { r =>
                r.hcursor.downField("winner").focus
                  .flatMap(_.hcursor.downField("team_id")
                    .as[UUID].toOption)
              }
              val teamSideBetPnl = teams.map { team =>
                val wins = winners.count(_ == team.id)
                val activeBets = rounds.count(
                  _.hcursor.downField("active")
                    .as[Boolean].getOrElse(false)
                )
                val losses = activeBets - wins
                val net =
                  (rules.sideBetAmount *
                    (numTeams - 1) * wins) -
                    (rules.sideBetAmount * losses)
                Json.obj(
                  "team_id" -> team.id.asJson,
                  "team_name" -> team.teamName.asJson,
                  "wins" -> wins.asJson,
                  "net" -> net.asJson
                )
              }
              Right(Json.obj(
                "rounds" -> rounds.asJson,
                "team_totals" -> teamSideBetPnl.asJson
              ))
            }
    yield result
    action.transact(xa)

  /** Pure: calculate a golfer's payout for a team given
    * tournament results. Returns None if the golfer has
    * no finish in the payout zone. */
  private[service] def calculateGolferPayout(
      position: Option[Int],
      allResults: List[TournamentResult],
      ownershipPct: BigDecimal,
      multiplier: BigDecimal,
      rules: SeasonRules
  ): Option[(BigDecimal, BigDecimal, Json)] =
    position match
      case None => None
      case Some(pos) if pos > rules.payouts.size => None
      case Some(pos) =>
        val numTied =
          allResults.count(_.position == Some(pos))
        val basePayout = PayoutTable.tieSplitPayout(
          pos, numTied, multiplier, rules
        )
        val ownerPayout =
          basePayout * ownershipPct / BigDecimal(100)
        val breakdown = Json.obj(
          "position" -> pos.asJson,
          "num_tied" -> numTied.asJson,
          "base_payout" -> basePayout.asJson,
          "ownership_pct" -> ownershipPct.asJson,
          "payout" -> ownerPayout.asJson,
          "multiplier" -> multiplier.asJson
        )
        Some((basePayout, ownerPayout, breakdown))

  /** Pure: compute zero-sum weekly totals from team
    * earnings. Each team's weekly =
    * (team_top_tens * num_teams) - total_pot.
    * Sum across all teams is always 0. */
  private[service] def zeroSumWeekly(
      teamEarnings: List[(UUID, BigDecimal)]
  ): List[(UUID, BigDecimal)] =
    val numTeams = teamEarnings.size
    val totalPot = teamEarnings.map(_._2).sum
    teamEarnings.map((teamId, topTens) =>
      (teamId, topTens * numTeams - totalPot)
    )

  /** Pure: compute side bet P&L per team given a map
    * of teamId -> cumulative earnings.
    * Winner gets +amount*(N-1), losers get -amount.
    * Ties split the winnings. */
  private[service] def sideBetPnl(
      teamEarnings: Map[UUID, BigDecimal],
      sideBetAmount: BigDecimal = BigDecimal(15)
  ): Map[UUID, BigDecimal] =
    if teamEarnings.isEmpty ||
        teamEarnings.values.forall(_ == BigDecimal(0))
    then teamEarnings.view
      .mapValues(_ => BigDecimal(0)).toMap
    else
      val maxEarnings = teamEarnings.values.max
      val winners = teamEarnings
        .filter(_._2 == maxEarnings).keys.toSet
      val numTeams = teamEarnings.size
      val numWinners = winners.size
      val winnerCollects =
        sideBetAmount * (numTeams - numWinners) /
          numWinners
      teamEarnings.map((tid, _) =>
        if winners.contains(tid) then
          tid -> winnerCollects
        else tid -> -sideBetAmount
      )

  def refreshStandings(
      seasonId: UUID
  ): IO[List[SeasonStanding]] =
    val action = for
      teams <- TeamRepository.findBySeason(seasonId)
      standings <- teams.traverse { team =>
        for
          scores <- sql"""SELECT COALESCE(SUM(points), 0),
                    COUNT(DISTINCT tournament_id)
                    FROM fantasy_scores
                    WHERE season_id = $seasonId
                      AND team_id = ${team.id}"""
            .query[(BigDecimal, Int)].unique
          standing <- ScoreRepository.upsertStanding(
            seasonId, team.id, scores._1, scores._2
          )
        yield standing
      }
    yield standings
    action.transact(xa)
