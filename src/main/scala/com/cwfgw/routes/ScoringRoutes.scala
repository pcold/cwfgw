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
    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "scoring" / UUIDVar(tournamentId) => service
        .getScores(seasonId, tournamentId).flatMap(Ok(_))

    case POST ->
        Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "scoring" / "calculate" / UUIDVar(tournamentId) => service
        .calculateScores(seasonId, tournamentId).flatMap:
          case Right(result) => Ok(result.asJson)
          case Left(err) => BadRequest(Json.obj("error" -> err.asJson))

    case POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "scoring" / "refresh-standings" => service
        .refreshStandings(seasonId).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "scoring" / "side-bets" => service
        .getSideBetStandings(seasonId).flatMap:
          case Right(result) => Ok(result.asJson)
          case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
