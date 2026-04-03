package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.LeagueService

/** Top-level league routes. */
object LeagueRoutes:

  def routes(service: LeagueService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "leagues" => service.list.flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(id) => service.get(id).flatMap:
        case Some(league) => Ok(league)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "leagues" => req.as[CreateLeague].flatMap: body =>
        service.create(body).flatMap(Created(_))
