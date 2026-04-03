package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.SeasonService

/** Season CRUD and standings routes. */
object SeasonRoutes:

  private given QueryParamDecoder[UUID] = QueryParamDecoder[String].map(UUID.fromString)

  private object LeagueIdParam extends OptionalQueryParamDecoderMatcher[UUID]("league_id")
  private object YearParam extends OptionalQueryParamDecoderMatcher[Int]("year")

  def routes(service: SeasonService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "seasons" :? LeagueIdParam(leagueId) +& YearParam(year) => service
        .list(leagueId, year).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(id) => service.get(id).flatMap:
        case Some(season) => Ok(season)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "seasons" => req.as[CreateSeason].flatMap: body =>
        service.create(body).flatMap(Created(_))

    case req @ PUT -> Root / "api" / "v1" / "seasons" / UUIDVar(id) => req.as[UpdateSeason].flatMap: body =>
        service.update(id, body).flatMap:
          case Some(season) => Ok(season)
          case None => NotFound()

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(id) / "standings" => service.standings(id).flatMap(Ok(_))
