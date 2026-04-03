package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.DraftService

object DraftRoutes:

  private object RoundsParam extends OptionalQueryParamDecoderMatcher[Int]("rounds")

  def routes(service: DraftService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" => service.get(seasonId).flatMap:
        case Some(draft) => Ok(draft)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" => req.as[CreateDraft].flatMap:
        body => service.create(seasonId, body).flatMap(Created(_))

    case POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" / "start" => service.start(seasonId)
        .flatMap:
          case Right(draft) => Ok(draft)
          case Left(err) => RouteHelpers.badRequest(err)

    case POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" / "initialize" :? RoundsParam(rounds) =>
      service.initializePicks(seasonId, rounds.getOrElse(6)).flatMap:
        case Right(picks) => Ok(picks)
        case Left(err) => RouteHelpers.badRequest(err)

    case req @ POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" / "pick" => req.as[MakePick]
        .flatMap: body =>
          service.makePick(seasonId, body).flatMap:
            case Right(pick) => Ok(pick)
            case Left(err) => RouteHelpers.badRequest(err)

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" / "picks" => service.getPicks(seasonId)
        .flatMap:
          case Right(picks) => Ok(picks)
          case Left(err) => RouteHelpers.badRequest(err)

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "draft" / "available" => service
        .getAvailableGolfers(seasonId).flatMap:
          case Right(golfers) => Ok(golfers)
          case Left(err) => RouteHelpers.badRequest(err)
