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
import com.cwfgw.repository.{ScoreRepository, TeamRepository, TournamentRepository, LeagueRepository}

class ScoringService(xa: Transactor[IO]):

  private val sideBetRounds = List(5, 6, 7, 8)
  private val sideBetPerTeam = BigDecimal(15)

  def getScores(leagueId: UUID, tournamentId: UUID): IO[List[FantasyScore]] =
    ScoreRepository.getScores(leagueId, tournamentId).transact(xa)

  def tieSplitPayout(position: Int, numTied: Int, isMajor: Boolean): BigDecimal =
    PayoutTable.tieSplitPayout(position, numTied, isMajor)

  /** Calculate scores for a tournament. Each golfer's earnings are stored,
    * then the zero-sum weekly totals can be derived.
    */
  def calculateScores(leagueId: UUID, tournamentId: UUID): IO[Either[String, Json]] =
    val action = for
      leagueOpt <- LeagueRepository.findById(leagueId)
      tournamentOpt <- TournamentRepository.findById(tournamentId)
      results <- TournamentRepository.findResults(tournamentId)
      teams <- TeamRepository.findByLeague(leagueId)
      allRosters <- TeamRepository.getRosterByLeague(leagueId)
      outcome <- (leagueOpt, tournamentOpt) match
        case (None, _) => FC.pure(Left("League not found"))
        case (_, None) => FC.pure(Left("Tournament not found"))
        case (Some(league), Some(tournament)) =>
          val isMajor = tournament.isMajor
          val numTeams = teams.size
          val resultsByGolfer = results.map(r => r.golferId -> r).toMap

          // Calculate each team's earnings for this tournament
          val teamEarnings = teams.traverse: team =>
            for
              roster <- TeamRepository.getRoster(team.id)
              golferScores <- roster.traverse: entry =>
                resultsByGolfer.get(entry.golferId) match
                  case None => FC.pure(Option.empty[(UUID, BigDecimal, Json)])
                  case Some(result) =>
                    result.position match
                      case None => FC.pure(Option.empty[(UUID, BigDecimal, Json)])
                      case Some(pos) if pos > 10 => FC.pure(Option.empty[(UUID, BigDecimal, Json)])
                      case Some(pos) =>
                        // Count tied players from results to determine split
                        val numTied = results.count(_.position == result.position)
                        val basePayout = tieSplitPayout(pos, numTied, isMajor)
                        val ownerPayout = basePayout * entry.ownershipPct / BigDecimal(100)
                        val breakdown = Json.obj(
                          "position" -> pos.asJson,
                          "num_tied" -> numTied.asJson,
                          "base_payout" -> basePayout.asJson,
                          "ownership_pct" -> entry.ownershipPct.asJson,
                          "payout" -> ownerPayout.asJson,
                          "is_major" -> isMajor.asJson
                        )
                        ScoreRepository.upsertScore(leagueId, team.id, tournamentId, entry.golferId, ownerPayout, breakdown)
                          .map(s => Some((entry.golferId, ownerPayout, breakdown)))
            yield (team, golferScores.flatten, roster)

          teamEarnings.map: teamsData =>
            val teamTotals = teamsData.map: (team, scores, _) =>
              val topTens = scores.map(_._2).sum
              (team, topTens, scores)

            val totalPot = teamTotals.map(_._2).sum

            val weeklyResults = teamTotals.map: (team, topTens, scores) =>
              val weeklyTotal = topTens * numTeams - totalPot
              Json.obj(
                "team_id" -> team.id.asJson,
                "team_name" -> team.teamName.asJson,
                "top_tens" -> topTens.asJson,
                "weekly_total" -> weeklyTotal.asJson,
                "golfer_scores" -> scores.map: (gid, payout, bd) =>
                  Json.obj("golfer_id" -> gid.asJson, "payout" -> payout.asJson, "breakdown" -> bd)
                .asJson
              )

            Right(Json.obj(
              "tournament_id" -> tournamentId.asJson,
              "is_major" -> isMajor.asJson,
              "num_teams" -> numTeams.asJson,
              "total_pot" -> totalPot.asJson,
              "teams" -> weeklyResults.asJson
            ))
    yield outcome
    action.transact(xa)

  /** Get season-long side bet standings for rounds 5-8 */
  def getSideBetStandings(leagueId: UUID): IO[Either[String, Json]] =
    val action = for
      teams <- TeamRepository.findByLeague(leagueId)
      allRosters <- TeamRepository.getRosterByLeague(leagueId)
      result <-
        if teams.isEmpty then FC.pure(Left("No teams found"))
        else
          val numTeams = teams.size
          val teamMap = teams.map(t => t.id -> t.teamName).toMap
          sideBetRounds.traverse: round =>
            val roundPicks = allRosters.filter(_.draftRound.contains(round))
            roundPicks.traverse: entry =>
              sql"SELECT COALESCE(SUM(points), 0) FROM fantasy_scores WHERE league_id = $leagueId AND team_id = ${entry.teamId} AND golfer_id = ${entry.golferId}"
                .query[BigDecimal].unique.map(total => (entry.teamId, entry.golferId, total))
            .map: entries =>
              val sorted = entries.sortBy(-_._3)
              val winner = sorted.headOption.filter(_._3 > BigDecimal(0))
              val active = winner.isDefined
              Json.obj(
                "round" -> round.asJson,
                "active" -> active.asJson,
                "winner" -> winner.map: (tid, gid, total) =>
                  Json.obj(
                    "team_id" -> tid.asJson,
                    "team_name" -> teamMap.getOrElse(tid, "").asJson,
                    "golfer_id" -> gid.asJson,
                    "cumulative_earnings" -> total.asJson,
                    "net_winnings" -> (sideBetPerTeam * (numTeams - 1)).asJson
                  )
                .asJson,
                "entries" -> sorted.map: (tid, gid, total) =>
                  Json.obj(
                    "team_id" -> tid.asJson,
                    "team_name" -> teamMap.getOrElse(tid, "").asJson,
                    "golfer_id" -> gid.asJson,
                    "cumulative_earnings" -> total.asJson
                  )
                .asJson
              )
          .map: rounds =>
            // Calculate net side bet P&L per team
            val winners = rounds.flatMap: r =>
              r.hcursor.downField("winner").focus.flatMap(_.hcursor.downField("team_id").as[UUID].toOption)
            val teamSideBetPnl = teams.map: team =>
              val wins = winners.count(_ == team.id)
              val activeBets = rounds.count(_.hcursor.downField("active").as[Boolean].getOrElse(false))
              val losses = activeBets - wins
              val net = (sideBetPerTeam * (numTeams - 1) * wins) - (sideBetPerTeam * losses)
              Json.obj(
                "team_id" -> team.id.asJson,
                "team_name" -> team.teamName.asJson,
                "wins" -> wins.asJson,
                "net" -> net.asJson
              )
            Right(Json.obj(
              "rounds" -> rounds.asJson,
              "team_totals" -> teamSideBetPnl.asJson
            ))
    yield result
    action.transact(xa)

  def refreshStandings(leagueId: UUID): IO[List[LeagueStanding]] =
    val action = for
      teams <- TeamRepository.findByLeague(leagueId)
      standings <- teams.traverse: team =>
        for
          scores <- sql"SELECT COALESCE(SUM(points), 0), COUNT(DISTINCT tournament_id) FROM fantasy_scores WHERE league_id = $leagueId AND team_id = ${team.id}"
            .query[(BigDecimal, Int)].unique
          standing <- ScoreRepository.upsertStanding(leagueId, team.id, scores._1, scores._2)
        yield standing
    yield standings
    action.transact(xa)
