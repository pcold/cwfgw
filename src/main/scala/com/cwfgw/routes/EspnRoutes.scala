package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.syntax.*
import java.time.LocalDate
import java.util.UUID
import com.cwfgw.domain.*
import com.cwfgw.service.*

object EspnRoutes:

  /** Public read-only routes. */
  def routes(service: EspnImportService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "espn" / "preview" / UUIDVar(seasonId) :? DateParam(date) => service
        .previewByDate(seasonId, date).flatMap(previews => Ok(previews.asJson))
        .handleErrorWith(RouteHelpers.badRequestFromError)

    case GET -> Root / "api" / "v1" / "espn" / "calendar" => service.fetchCalendar.flatMap { entries =>
        val response = entries.map(CalendarEntryResponse.from)
        Ok(response.asJson)
      }.handleErrorWith(RouteHelpers.badRequestFromError)

  /** Admin-only import routes. */
  def adminRoutes(service: EspnImportService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case POST -> Root / "api" / "v1" / "espn" / "import" :? DateParam(date) => service.importByDate(date)
        .flatMap(results => Ok(results.asJson)).handleErrorWith(RouteHelpers.badRequestFromError)

    case POST -> Root / "api" / "v1" / "espn" / "import" / "tournament" / UUIDVar(tournamentId) => service
        .importForTournament(tournamentId).flatMap {
          case Right(results) => Ok(results.asJson)
          case Left(err) => RouteHelpers.badRequest(err)
        }

  private object DateParam extends QueryParamDecoderMatcher[LocalDate]("date")

  given QueryParamDecoder[LocalDate] = QueryParamDecoder[String].map(LocalDate.parse)
