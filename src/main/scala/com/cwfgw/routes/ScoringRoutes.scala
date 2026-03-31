package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import io.circe.Json
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.ScoringService

object ScoringRoutes:

  def routes(service: ScoringService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "scoring" / UUIDVar(tournamentId) =>
      service.getScores(leagueId, tournamentId).flatMap(Ok(_))

    case POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "scoring" / "calculate" / UUIDVar(tournamentId) =>
      service.calculateScores(leagueId, tournamentId).flatMap:
        case Right(scores) => Ok(scores)
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))

    case POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "scoring" / "refresh-standings" =>
      service.refreshStandings(leagueId).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "scoring" / "side-bets" =>
      service.getSideBetStandings(leagueId).flatMap:
        case Right(data) => Ok(data)
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
