package com.cwfgw.service

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.TeamRepository

class TeamService(xa: Transactor[IO]):

  def listByLeague(leagueId: UUID): IO[List[Team]] =
    TeamRepository.findByLeague(leagueId).transact(xa)

  def get(id: UUID): IO[Option[Team]] =
    TeamRepository.findById(id).transact(xa)

  def create(leagueId: UUID, req: CreateTeam): IO[Team] =
    TeamRepository.create(leagueId, req).transact(xa)

  def update(id: UUID, req: UpdateTeam): IO[Option[Team]] =
    TeamRepository.update(id, req).transact(xa)

  def getRoster(teamId: UUID): IO[List[RosterEntry]] =
    TeamRepository.getRoster(teamId).transact(xa)

  def addToRoster(teamId: UUID, req: AddToRoster): IO[RosterEntry] =
    TeamRepository.addToRoster(teamId, req).transact(xa)

  def dropFromRoster(teamId: UUID, golferId: UUID): IO[Boolean] =
    TeamRepository.dropFromRoster(teamId, golferId).transact(xa)
