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
import com.cwfgw.service.TournamentService

object TournamentRoutes:

  private given QueryParamDecoder[UUID] =
    QueryParamDecoder[String].map(UUID.fromString)

  private object SeasonIdParam
      extends OptionalQueryParamDecoderMatcher[UUID]("season_id")
  private object StatusParam
      extends OptionalQueryParamDecoderMatcher[String]("status")

  def routes(service: TournamentService): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "api" / "v1" / "tournaments"
          :? SeasonIdParam(seasonId) +& StatusParam(status) =>
        service.list(seasonId, status).flatMap(Ok(_))

      case GET -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) =>
        service.get(id).flatMap:
          case Some(t) => Ok(t)
          case None    => NotFound()

      case req @ POST -> Root / "api" / "v1" / "tournaments" =>
        req.as[CreateTournament].flatMap: body =>
          service.create(body).flatMap(Created(_))

      case req @ PUT -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) =>
        req.as[UpdateTournament].flatMap: body =>
          service.update(id, body).flatMap:
            case Some(t) => Ok(t)
            case None    => NotFound()

      case GET -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) / "results" =>
        service.getResults(id).flatMap(Ok(_))

      case req @ POST -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) / "results" =>
        req.as[List[CreateTournamentResult]].flatMap: body =>
          service.importResults(id, body).flatMap(Ok(_))

  /** Routes that require admin authentication. */
  def adminRoutes(service: TournamentService): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case POST -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) / "finalize" =>
        service.finalizeTournament(id).flatMap:
          case Right(msg) =>
            Ok(Json.obj("message" -> msg.asJson))
          case Left(err) =>
            BadRequest(Json.obj("error" -> err.asJson))

      case POST -> Root / "api" / "v1" / "tournaments" / UUIDVar(id) / "reset" =>
        service.resetTournament(id).flatMap:
          case Right(msg) =>
            Ok(Json.obj("message" -> msg.asJson))
          case Left(err) =>
            BadRequest(Json.obj("error" -> err.asJson))

      case POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "finalize" =>
        service.finalizeSeason(seasonId).flatMap:
          case Right(msg) =>
            Ok(Json.obj("message" -> msg.asJson))
          case Left(err) =>
            BadRequest(Json.obj("error" -> err.asJson))

      case POST -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "clean-results" =>
        service.cleanSeasonResults(seasonId).flatMap:
          case Right(msg) =>
            Ok(Json.obj("message" -> msg.asJson))
          case Left(err) =>
            BadRequest(Json.obj("error" -> err.asJson))
