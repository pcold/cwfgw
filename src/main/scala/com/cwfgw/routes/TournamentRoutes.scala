package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.TournamentService

object TournamentRoutes:

  private object SeasonParam extends OptionalQueryParamDecoderMatcher[Int]("season")
  private object StatusParam extends OptionalQueryParamDecoderMatcher[String]("status")

  def routes(service: TournamentService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "tournaments" :? SeasonParam(season) +& StatusParam(status) =>
      service.list(season, status).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) =>
      service.get(id).flatMap:
        case Some(t) => Ok(t)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "tournaments" =>
      req.as[CreateTournament].flatMap: body =>
        service.create(body).flatMap(Created(_))

    case req @ PUT -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) =>
      req.as[UpdateTournament].flatMap: body =>
        service.update(id, body).flatMap:
          case Some(t) => Ok(t)
          case None => NotFound()

    case GET -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) / "results" =>
      service.getResults(id).flatMap(Ok(_))

    case req @ POST -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) / "results" =>
      req.as[List[CreateTournamentResult]].flatMap: body =>
        service.importResults(id, body).flatMap(Ok(_))
