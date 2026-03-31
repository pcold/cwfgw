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
import com.cwfgw.service.DraftService

object DraftRoutes:

  private object RoundsParam extends OptionalQueryParamDecoderMatcher[Int]("rounds")

  def routes(service: DraftService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" =>
      service.get(leagueId).flatMap:
        case Some(draft) => Ok(draft)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" =>
      req.as[CreateDraft].flatMap: body =>
        service.create(leagueId, body).flatMap(Created(_))

    case POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" / "start" =>
      service.start(leagueId).flatMap:
        case Right(draft) => Ok(draft)
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))

    case POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" / "initialize" :? RoundsParam(rounds) =>
      service.initializePicks(leagueId, rounds.getOrElse(6)).flatMap:
        case Right(picks) => Ok(picks)
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))

    case req @ POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" / "pick" =>
      req.as[MakePick].flatMap: body =>
        service.makePick(leagueId, body).flatMap:
          case Right(pick) => Ok(pick)
          case Left(err) => BadRequest(Json.obj("error" -> err.asJson))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" / "picks" =>
      service.getPicks(leagueId).flatMap:
        case Right(picks) => Ok(picks)
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "draft" / "available" =>
      service.getAvailableGolfers(leagueId).flatMap:
        case Right(golfers) => Ok(golfers)
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
