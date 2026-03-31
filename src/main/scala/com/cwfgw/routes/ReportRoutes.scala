package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import io.circe.Json
import io.circe.syntax.*
import java.util.UUID
import com.cwfgw.service.WeeklyReportService

object ReportRoutes:

  private object LiveParam extends OptionalQueryParamDecoderMatcher[Boolean]("live")

  def routes(service: WeeklyReportService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "report" / UUIDVar(tournamentId) :? LiveParam(live) =>
      service.getReport(leagueId, tournamentId, live.getOrElse(false))
        .flatMap(Ok(_))
        .handleErrorWith(e => BadRequest(Json.obj("error" -> e.getMessage.asJson)))
