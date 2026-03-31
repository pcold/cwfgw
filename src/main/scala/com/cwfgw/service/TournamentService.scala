package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.TournamentRepository

class TournamentService(xa: Transactor[IO]):

  def list(seasonYear: Option[Int], status: Option[String]): IO[List[Tournament]] =
    TournamentRepository.findAll(seasonYear, status).transact(xa)

  def get(id: UUID): IO[Option[Tournament]] =
    TournamentRepository.findById(id).transact(xa)

  def create(req: CreateTournament): IO[Tournament] =
    TournamentRepository.create(req).transact(xa)

  def update(id: UUID, req: UpdateTournament): IO[Option[Tournament]] =
    TournamentRepository.update(id, req).transact(xa)

  def getResults(tournamentId: UUID): IO[List[TournamentResult]] =
    TournamentRepository.findResults(tournamentId).transact(xa)

  def importResults(tournamentId: UUID, results: List[CreateTournamentResult]): IO[List[TournamentResult]] =
    results.traverse(r => TournamentRepository.upsertResult(tournamentId, r)).transact(xa)
