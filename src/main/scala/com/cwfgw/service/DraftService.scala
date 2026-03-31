package com.cwfgw.service

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.repository.{DraftRepository, TeamRepository}

class DraftService(xa: Transactor[IO]):

  def get(leagueId: UUID): IO[Option[Draft]] =
    DraftRepository.findByLeague(leagueId).transact(xa)

  def create(leagueId: UUID, req: CreateDraft): IO[Draft] =
    DraftRepository.create(leagueId, req).transact(xa)

  def start(leagueId: UUID): IO[Either[String, Draft]] =
    val action = for
      draftOpt <- DraftRepository.findByLeague(leagueId)
      result <- draftOpt match
        case None => FC.pure(Left("No draft found for this league"))
        case Some(draft) if draft.status != "pending" =>
          FC.pure(Left(s"Draft is already ${draft.status}"))
        case Some(draft) =>
          DraftRepository.updateStatus(draft.id, "in_progress").map {
            case Some(d) => Right(d)
            case None => Left("Failed to start draft")
          }
    yield result
    action.transact(xa)

  def getPicks(leagueId: UUID): IO[Either[String, List[DraftPick]]] =
    val action = for
      draftOpt <- DraftRepository.findByLeague(leagueId)
      result <- draftOpt match
        case None => FC.pure(Left("No draft found for this league"))
        case Some(draft) => DraftRepository.getPicks(draft.id).map(Right(_))
    yield result
    action.transact(xa)

  def makePick(leagueId: UUID, req: MakePick): IO[Either[String, DraftPick]] =
    val action = for
      draftOpt <- DraftRepository.findByLeague(leagueId)
      result <- draftOpt match
        case None => FC.pure(Left("No draft found for this league"))
        case Some(draft) if draft.status != "in_progress" =>
          FC.pure(Left("Draft is not in progress"))
        case Some(draft) =>
          for
            picks <- DraftRepository.getPicks(draft.id)
            nextPick = picks.find(_.golferId.isEmpty)
            result <- nextPick match
              case None => FC.pure(Left("All picks have been made"))
              case Some(pick) if pick.teamId != req.teamId =>
                FC.pure(Left(s"It is not this team's turn to pick"))
              case Some(pick) =>
                DraftRepository.makePick(draft.id, pick.pickNum, req.golferId).map {
                  case Some(p) => Right(p)
                  case None => Left("Failed to make pick")
                }
          yield result
    yield result
    action.transact(xa)

  def getAvailableGolfers(leagueId: UUID): IO[Either[String, List[Golfer]]] =
    val action = for
      draftOpt <- DraftRepository.findByLeague(leagueId)
      result <- draftOpt match
        case None => FC.pure(Left("No draft found for this league"))
        case Some(draft) => DraftRepository.getAvailableGolfers(draft.id).map(Right(_))
    yield result
    action.transact(xa)

  def initializePicks(leagueId: UUID, rounds: Int): IO[Either[String, List[DraftPick]]] =
    val action = for
      draftOpt <- DraftRepository.findByLeague(leagueId)
      teams <- TeamRepository.findByLeague(leagueId)
      result <- (draftOpt, teams) match
        case (None, _) => FC.pure(Left("No draft found for this league"))
        case (_, Nil) => FC.pure(Left("No teams in this league"))
        case (Some(draft), teams) if draft.status != "pending" =>
          FC.pure(Left("Draft picks can only be initialized when draft is pending"))
        case (Some(draft), teams) =>
          val picks = snakeDraftOrder(teams.map(_.id), rounds, draft.id)
          picks.traverse { case (draftId, teamId, roundNum, pickNum) =>
            DraftRepository.createPick(draftId, teamId, roundNum, pickNum)
          }.map(Right(_))
    yield result
    action.transact(xa)

  /** Generate snake draft pick order: odd rounds go in team order,
    * even rounds reverse. Returns (draftId, teamId, roundNum, pickNum). */
  private[service] def snakeDraftOrder(
      teamIds: List[UUID], rounds: Int, draftId: UUID
  ): List[(UUID, UUID, Int, Int)] =
    for
      round <- (1 to rounds).toList
      (teamId, idx) <- (if round % 2 == 0 then teamIds.reverse else teamIds).zipWithIndex
    yield (draftId, teamId, round, (round - 1) * teamIds.size + idx + 1)
