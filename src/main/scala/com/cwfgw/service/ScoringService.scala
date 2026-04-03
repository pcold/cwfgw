package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import com.cwfgw.domain.{*, given}
import com.cwfgw.repository.{ScoreRepository, SeasonRepository, TeamRepository, TournamentRepository}
import doobie.*
import doobie.implicits.*
import java.util.UUID

class ScoringService(xa: Transactor[IO]):

  def getScores(seasonId: UUID, tournamentId: UUID): IO[List[FantasyScore]] = ScoreRepository
    .getScores(seasonId, tournamentId).transact(xa)

  def tieSplitPayout(position: Int, numTied: Int, multiplier: BigDecimal, rules: SeasonRules): BigDecimal = PayoutTable
    .tieSplitPayout(position, numTied, multiplier, rules)

  /** Calculate scores for a tournament. Each golfer's earnings are stored, then the zero-sum weekly totals can be
    * derived.
    */
  def calculateScores(seasonId: UUID, tournamentId: UUID): IO[Either[String, WeeklyScoreResult]] =
    val action =
      for
        seasonOpt <- SeasonRepository.findById(seasonId)
        rulesOpt <- SeasonRepository.getSeasonRules(seasonId)
        tournamentOpt <- TournamentRepository.findById(tournamentId)
        results <- TournamentRepository.findResults(tournamentId)
        teams <- TeamRepository.findBySeason(seasonId)
        allRosters <- TeamRepository.getRosterBySeason(seasonId)
        outcome <- (seasonOpt, tournamentOpt) match
          case (None, _) => FC.pure(Left("Season not found"))
          case (_, None) => FC.pure(Left("Tournament not found"))
          case (Some(season), Some(tournament)) =>
            val rules = rulesOpt.getOrElse(SeasonRules.default)
            val multiplier = tournament.payoutMultiplier
            val numTeams = teams.size
            val resultsByGolfer = results.map(r => r.golferId -> r).toMap
            val golferOwners = allRosters.groupBy(_.golferId).view.mapValues(_.map(e => (e.teamId, e.ownershipPct)))
              .toMap

            val teamEarnings = teams.traverse { team =>
              for
                roster <- TeamRepository.getRoster(team.id)
                golferScores <- roster.traverse { entry =>
                  resultsByGolfer.get(entry.golferId) match
                    case None => FC.pure(Option.empty[GolferScoreEntry])
                    case Some(result) => result.position match
                        case None => FC.pure(Option.empty[GolferScoreEntry])
                        case Some(pos) if pos > rules.payouts.size => FC.pure(Option.empty[GolferScoreEntry])
                        case Some(pos) =>
                          val numTied = results.count(_.position == result.position)
                          val basePayout = tieSplitPayout(pos, numTied, multiplier, rules)
                          val owners = golferOwners.getOrElse(entry.golferId, Nil)
                          val splits = PayoutTable.splitOwnership(basePayout, owners)
                          val ownerPayout = splits.getOrElse(team.id, basePayout)
                          val bd = ScoreBreakdown(pos, numTied, basePayout, entry.ownershipPct, ownerPayout, multiplier)
                          ScoreRepository
                            .upsertScore(seasonId, team.id, tournamentId, entry.golferId, ownerPayout, bd)
                            .map(_ => Some(GolferScoreEntry(entry.golferId, ownerPayout, bd)))
                }
              yield (team, golferScores.flatten, roster)
            }

            teamEarnings.map { teamsData =>
              val teamTotals = teamsData.map { (team, scores, _) =>
                val topTens = scores.map(_.payout).sum
                (team, topTens, scores)
              }
              val totalPot = teamTotals.map(_._2).sum

              val weeklyResults = teamTotals.map { (team, topTens, scores) =>
                val weeklyTotal = topTens * numTeams - totalPot
                TeamWeeklyResult(team.id, team.teamName, topTens, weeklyTotal, scores)
              }

              Right(WeeklyScoreResult(tournamentId, multiplier, numTeams, totalPot, weeklyResults))
            }
      yield outcome
    action.transact(xa)

  /** Get season-long side bet standings. */
  def getSideBetStandings(seasonId: UUID): IO[Either[String, SideBetStandings]] =
    val action =
      for
        seasonOpt <- SeasonRepository.findById(seasonId)
        rulesOpt <- SeasonRepository.getSeasonRules(seasonId)
        teams <- TeamRepository.findBySeason(seasonId)
        allRosters <- TeamRepository.getRosterBySeason(seasonId)
        result <-
          if teams.isEmpty then FC.pure(Left("No teams found"))
          else
            seasonOpt match
              case None => FC.pure(Left("Season not found"))
              case Some(season) =>
                val rules = rulesOpt.getOrElse(SeasonRules.default)
                val numTeams = teams.size
                val teamMap = teams.map(t => t.id -> t.teamName).toMap
                rules.sideBetRounds.traverse { round =>
                  val roundPicks = allRosters.filter(_.draftRound.contains(round))
                  roundPicks.traverse { entry =>
                    ScoreRepository.golferPointTotal(seasonId, entry.teamId, entry.golferId)
                      .map(total => (entry.teamId, entry.golferId, total))
                  }.map { entries =>
                    val sorted = entries.sortBy(-_._3)
                    val winner = sorted.headOption.filter(_._3 > BigDecimal(0))
                    val active = winner.isDefined
                    val winnerResult = winner.map { (tid, gid, total) =>
                      SideBetWinner(tid, teamMap.getOrElse(tid, ""), gid, total, rules.sideBetAmount * (numTeams - 1))
                    }
                    val entryList = sorted.map { (tid, gid, total) =>
                      SideBetEntry(tid, teamMap.getOrElse(tid, ""), gid, total)
                    }
                    SideBetRound(round, active, winnerResult, entryList)
                  }
                }.map { rounds =>
                  val winners = rounds.flatMap(_.winner.map(_.teamId))
                  val teamSideBetPnl = teams.map { team =>
                    val wins = winners.count(_ == team.id)
                    val activeBets = rounds.count(_.active)
                    val losses = activeBets - wins
                    val net = (rules.sideBetAmount * (numTeams - 1) * wins) - (rules.sideBetAmount * losses)
                    SideBetTeamTotal(team.id, team.teamName, wins, net)
                  }
                  Right(SideBetStandings(rounds, teamSideBetPnl))
                }
      yield result
    action.transact(xa)

  /** Pure: calculate a golfer's payout for a team given tournament results. Returns None if the golfer has no finish in
    * the payout zone.
    * @param owners
    *   all (teamId, ownershipPct) pairs for this golfer, used for remainder-based rounding
    * @param teamId
    *   the team to compute the payout for
    */
  private[service] def calculateGolferPayout(
    position: Option[Int],
    allResults: List[TournamentResult],
    ownershipPct: BigDecimal,
    multiplier: BigDecimal,
    rules: SeasonRules,
    owners: List[(UUID, BigDecimal)] = Nil,
    teamId: Option[UUID] = None
  ): Option[(BigDecimal, BigDecimal, ScoreBreakdown)] = position match
    case None => None
    case Some(pos) if pos > rules.payouts.size => None
    case Some(pos) =>
      val numTied = allResults.count(_.position == Some(pos))
      val basePayout = PayoutTable.tieSplitPayout(pos, numTied, multiplier, rules)
      val ownerPayout = teamId match
        case Some(tid) if owners.size > 1 =>
          PayoutTable.splitOwnership(basePayout, owners).getOrElse(tid, basePayout)
        case _ => basePayout * ownershipPct / BigDecimal(100)
      val breakdown = ScoreBreakdown(pos, numTied, basePayout, ownershipPct, ownerPayout, multiplier)
      Some((basePayout, ownerPayout, breakdown))

  /** Pure: compute zero-sum weekly totals from team earnings. Each team's weekly = (team_top_tens * num_teams) -
    * total_pot. Sum across all teams is always 0.
    */
  private[service] def zeroSumWeekly(teamEarnings: List[(UUID, BigDecimal)]): List[(UUID, BigDecimal)] =
    val numTeams = teamEarnings.size
    val totalPot = teamEarnings.map(_._2).sum
    teamEarnings.map((teamId, topTens) => (teamId, topTens * numTeams - totalPot))

  /** Pure: compute side bet P&L per team given a map of teamId -> cumulative earnings. Winner gets +amount*(N-1),
    * losers get -amount. Ties split the winnings.
    */
  private[service] def sideBetPnl(
    teamEarnings: Map[UUID, BigDecimal],
    sideBetAmount: BigDecimal = BigDecimal(15)
  ): Map[UUID, BigDecimal] =
    if teamEarnings.isEmpty || teamEarnings.values.forall(_ == BigDecimal(0)) then
      teamEarnings.view.mapValues(_ => BigDecimal(0)).toMap
    else
      val maxEarnings = teamEarnings.values.max
      val winners = teamEarnings.filter(_._2 == maxEarnings).keys.toSet
      val numTeams = teamEarnings.size
      val numWinners = winners.size
      val winnerCollects = sideBetAmount * (numTeams - numWinners) / numWinners
      teamEarnings.map((tid, _) => if winners.contains(tid) then tid -> winnerCollects else tid -> -sideBetAmount)

  def refreshStandings(seasonId: UUID): IO[List[SeasonStanding]] =
    val action =
      for
        teams <- TeamRepository.findBySeason(seasonId)
        standings <- teams.traverse { team =>
          for
            scores <- ScoreRepository.teamSeasonTotals(seasonId, team.id)
            standing <- ScoreRepository.upsertStanding(seasonId, team.id, scores._1, scores._2)
          yield standing
        }
      yield standings
    action.transact(xa)
