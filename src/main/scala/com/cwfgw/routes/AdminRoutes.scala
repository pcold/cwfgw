package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.{Json, Decoder}
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.*

object AdminRoutes:

  private def errorResponse(e: Throwable)(using LoggerFactory[IO]): IO[Response[IO]] =
    val logger = LoggerFactory[IO].getLogger
    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    logger.error(e)(s"Admin route error: $msg") >> BadRequest(Json.obj("error" -> msg.asJson))

  private case class SeasonUploadRequest(seasonId: UUID, seasonYear: Int, schedule: String)
  private given Decoder[SeasonUploadRequest] = Decoder
    .forProduct3("season_id", "season_year", "schedule")(SeasonUploadRequest.apply)
  private given EntityDecoder[IO, SeasonUploadRequest] = jsonOf[IO, SeasonUploadRequest]

  private case class RosterPreviewRequest(roster: String)
  private given Decoder[RosterPreviewRequest] = Decoder.forProduct1("roster")(RosterPreviewRequest.apply)
  private given EntityDecoder[IO, RosterPreviewRequest] = jsonOf[IO, RosterPreviewRequest]

  private case class RosterConfirmRequest(seasonId: UUID, teams: List[ConfirmedTeam])
  private given Decoder[RosterConfirmRequest] = Decoder.forProduct2("season_id", "teams")(RosterConfirmRequest.apply)
  private given EntityDecoder[IO, RosterConfirmRequest] = jsonOf[IO, RosterConfirmRequest]

  def routes(service: AdminService)(using LoggerFactory[IO]): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case req @ POST -> Root / "api" / "v1" / "admin" / "season" => req.as[SeasonUploadRequest].flatMap { body =>
        service.uploadSeason(body.seasonId, body.seasonYear, body.schedule).flatMap(result => Ok(result.asJson))
      }.handleErrorWith(errorResponse)

    case req @ POST -> Root / "api" / "v1" / "admin" / "roster" / "preview" => req.as[RosterPreviewRequest]
        .flatMap { body => service.previewRoster(body.roster).flatMap(result => Ok(result.asJson)) }
        .handleErrorWith(errorResponse)

    case req @ POST -> Root / "api" / "v1" / "admin" / "roster" / "confirm" => req.as[RosterConfirmRequest]
        .flatMap { body => service.confirmRoster(body.seasonId, body.teams).flatMap(result => Ok(result.asJson)) }
        .handleErrorWith(errorResponse)

    case GET -> Root / "api" / "v1" / "admin" / "espn-calendar" => service.previewEspnCalendar.flatMap { entries =>
        val response = entries.map(CalendarEntryResponse.from)
        Ok(response.asJson)
      }.handleErrorWith(errorResponse)
