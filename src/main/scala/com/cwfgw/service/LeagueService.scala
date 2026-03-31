package com.cwfgw.service

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.{LeagueRepository, ScoreRepository}

class LeagueService(xa: Transactor[IO]):

  def list(seasonYear: Option[Int]): IO[List[League]] =
    LeagueRepository.findAll(seasonYear).transact(xa)

  def get(id: UUID): IO[Option[League]] =
    LeagueRepository.findById(id).transact(xa)

  def create(req: CreateLeague): IO[League] =
    LeagueRepository.create(req).transact(xa)

  def update(id: UUID, req: UpdateLeague): IO[Option[League]] =
    LeagueRepository.update(id, req).transact(xa)

  def delete(id: UUID): IO[Boolean] =
    LeagueRepository.delete(id).transact(xa)

  def standings(leagueId: UUID): IO[List[LeagueStanding]] =
    ScoreRepository.getStandings(leagueId).transact(xa)
