package com.cwfgw.service

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.{SeasonRepository, ScoreRepository}

class SeasonService(xa: Transactor[IO]):

  def list(
      leagueId: Option[UUID],
      seasonYear: Option[Int]
  ): IO[List[Season]] =
    SeasonRepository.findAll(leagueId, seasonYear).transact(xa)

  def get(id: UUID): IO[Option[Season]] =
    SeasonRepository.findById(id).transact(xa)

  def create(req: CreateSeason): IO[Season] =
    SeasonRepository.create(req).transact(xa)

  def update(id: UUID, req: UpdateSeason): IO[Option[Season]] =
    SeasonRepository.update(id, req).transact(xa)

  def standings(seasonId: UUID): IO[List[SeasonStanding]] =
    ScoreRepository.getStandings(seasonId).transact(xa)
