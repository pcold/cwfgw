package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.GolferService

object GolferRoutes:

  private object ActiveParam extends OptionalQueryParamDecoderMatcher[Boolean]("active")
  private object SearchParam extends OptionalQueryParamDecoderMatcher[String]("search")

  def routes(service: GolferService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "golfers" :? ActiveParam(active) +& SearchParam(search) => service
        .list(active.getOrElse(true), search).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "golfers" / UUIDVar(id) => service.get(id).flatMap:
        case Some(golfer) => Ok(golfer)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "golfers" => req.as[CreateGolfer].flatMap: body =>
        service.create(body).flatMap(Created(_))

    case req @ PUT -> Root / "api" / "v1" / "golfers" / UUIDVar(id) => req.as[UpdateGolfer].flatMap: body =>
        service.update(id, body).flatMap:
          case Some(golfer) => Ok(golfer)
          case None => NotFound()
