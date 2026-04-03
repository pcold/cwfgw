package com.cwfgw.service

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.GolferRepository

class GolferService(xa: Transactor[IO]):

  def list(activeOnly: Boolean, search: Option[String]): IO[List[Golfer]] = GolferRepository.findAll(activeOnly, search)
    .transact(xa)

  def get(id: UUID): IO[Option[Golfer]] = GolferRepository.findById(id).transact(xa)

  def create(req: CreateGolfer): IO[Golfer] = GolferRepository.create(req).transact(xa)

  def update(id: UUID, req: UpdateGolfer): IO[Option[Golfer]] = GolferRepository.update(id, req).transact(xa)
