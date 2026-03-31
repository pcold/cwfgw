package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.LeagueService

object LeagueRoutes:

  private object SeasonParam extends OptionalQueryParamDecoderMatcher[Int]("season")

  def routes(service: LeagueService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "leagues" :? SeasonParam(season) =>
      service.list(season).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(id) =>
      service.get(id).flatMap:
        case Some(league) => Ok(league)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "leagues" =>
      req.as[CreateLeague].flatMap: body =>
        service.create(body).flatMap(Created(_))

    case req @ PUT -> Root / "api" / "v1" / "leagues" / UUIDVar(id) =>
      req.as[UpdateLeague].flatMap: body =>
        service.update(id, body).flatMap:
          case Some(league) => Ok(league)
          case None => NotFound()

    case DELETE -> Root / "api" / "v1" / "leagues" / UUIDVar(id) =>
      service.delete(id).flatMap:
        case true => NoContent()
        case false => NotFound()

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(id) / "standings" =>
      service.standings(id).flatMap(Ok(_))
