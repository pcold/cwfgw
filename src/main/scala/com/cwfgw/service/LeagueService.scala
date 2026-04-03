package com.cwfgw.service

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.LeagueRepository

class LeagueService(xa: Transactor[IO]):

  def list: IO[List[League]] = LeagueRepository.findAll.transact(xa)

  def get(id: UUID): IO[Option[League]] = LeagueRepository.findById(id).transact(xa)

  def create(req: CreateLeague): IO[League] = LeagueRepository.create(req).transact(xa)
