package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import java.util.UUID
import com.cwfgw.service.WeeklyReportService

object ReportRoutes:

  private object LiveParam extends OptionalQueryParamDecoderMatcher[Boolean]("live")

  private object ThroughTournamentParam extends OptionalQueryParamDecoderMatcher[String]("through")

  def routes(service: WeeklyReportService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "report" :? LiveParam(live) => service
        .getSeasonReport(seasonId, live.getOrElse(false)).flatMap(Ok(_))
        .handleErrorWith(RouteHelpers.badRequestFromError)

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "report" / UUIDVar(tournamentId) :?
        LiveParam(live) => service.getReport(seasonId, tournamentId, live.getOrElse(false)).flatMap(Ok(_))
        .handleErrorWith(RouteHelpers.badRequestFromError)

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "rankings" :?
        LiveParam(live) +& ThroughTournamentParam(through) =>
      val throughId = through.map(UUID.fromString)
      service.getRankings(seasonId, live.getOrElse(false), throughId).flatMap(Ok(_))
        .handleErrorWith(RouteHelpers.badRequestFromError)

    case GET -> Root / "api" / "v1" / "seasons" / UUIDVar(seasonId) / "golfer" / UUIDVar(golferId) / "history" =>
      service.getGolferHistory(seasonId, golferId).flatMap(Ok(_)).handleErrorWith(RouteHelpers.badRequestFromError)
