package com.cwfgw.service

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.TeamRepository

class TeamService(xa: Transactor[IO]):

  def listBySeason(seasonId: UUID): IO[List[Team]] =
    TeamRepository.findBySeason(seasonId).transact(xa)

  def get(id: UUID): IO[Option[Team]] =
    TeamRepository.findById(id).transact(xa)

  def create(seasonId: UUID, req: CreateTeam): IO[Team] =
    TeamRepository.create(seasonId, req).transact(xa)

  def update(id: UUID, req: UpdateTeam): IO[Option[Team]] =
    TeamRepository.update(id, req).transact(xa)

  def getRoster(teamId: UUID): IO[List[RosterEntry]] =
    TeamRepository.getRoster(teamId).transact(xa)

  def addToRoster(teamId: UUID, req: AddToRoster): IO[RosterEntry] =
    TeamRepository.addToRoster(teamId, req).transact(xa)

  def dropFromRoster(teamId: UUID, golferId: UUID): IO[Boolean] =
    TeamRepository.dropFromRoster(teamId, golferId).transact(xa)

  /** Full roster view for a season. */
  def getRosterView(seasonId: UUID): IO[List[RosterViewTeam]] =
    TeamRepository
      .getRosterViewBySeason(seasonId)
      .transact(xa)
      .map: rows =>
        val teamOrder = rows.map(r => r._1).distinct
        rows
          .groupBy(r => (r._1, r._2))
          .toList
          .sortBy((key, _) => teamOrder.indexOf(key._1))
          .map: (key, picks) =>
            RosterViewTeam(
              teamId = key._1,
              teamName = key._2,
              picks = picks.map: r =>
                RosterViewPick(
                  round = r._3,
                  golferName =
                    if r._4.nonEmpty then s"${r._4} ${r._5}"
                    else r._5,
                  ownershipPct = r._6,
                  golferId = r._7
                )
            )

case class RosterViewTeam(
    teamId: UUID,
    teamName: String,
    picks: List[RosterViewPick]
)
case class RosterViewPick(
    round: Int,
    golferName: String,
    ownershipPct: BigDecimal,
    golferId: UUID
)
