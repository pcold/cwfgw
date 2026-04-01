package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import io.circe.Json
import io.circe.syntax.*
import java.time.LocalDate
import java.util.UUID
import com.cwfgw.service.*

object EspnRoutes:

  private def resultToJson(r: EspnImportResult): Json = Json.obj(
    "tournament_id" -> r.tournamentId.asJson,
    "espn_name" -> r.espnName.asJson,
    "espn_id" -> r.espnId.asJson,
    "completed" -> r.completed.asJson,
    "total_competitors" -> r.totalCompetitors.asJson,
    "matched" -> r.matched.asJson,
    "created" -> r.created.asJson,
    "unmatched" -> r.unmatched.asJson
  )

  private def livePreviewToJson(p: EspnLivePreview): Json = Json.obj(
    "espn_name" -> p.espnName.asJson,
    "espn_id" -> p.espnId.asJson,
    "completed" -> p.completed.asJson,
    "is_major" -> p.isMajor.asJson,
    "total_competitors" -> p.totalCompetitors.asJson,
    "teams" -> p.teams.map: t =>
      Json.obj(
        "team_id" -> t.teamId.asJson,
        "team_name" -> t.teamName.asJson,
        "owner_name" -> t.ownerName.asJson,
        "top_ten_earnings" -> t.topTenEarnings.asJson,
        "weekly_total" -> t.weeklyTotal.asJson,
        "golfer_scores" -> t.golferScores.map: gs =>
          Json.obj(
            "golfer_name" -> gs.golferName.asJson,
            "golfer_id" -> gs.golferId.asJson,
            "position" -> gs.position.asJson,
            "num_tied" -> gs.numTied.asJson,
            "score_to_par" -> gs.scoreToPar.asJson,
            "base_payout" -> gs.basePayout.asJson,
            "ownership_pct" -> gs.ownershipPct.asJson,
            "payout" -> gs.payout.asJson
          )
        .asJson
      )
    .asJson,
    "leaderboard" -> p.leaderboard.map: e =>
      Json.obj(
        "name" -> e.name.asJson,
        "position" -> e.position.asJson,
        "score_to_par" -> e.scoreToPar.asJson,
        "thru" -> e.thru.asJson,
        "rostered" -> e.rostered.asJson,
        "team_name" -> e.teamName.asJson
      )
    .asJson
  )

  /** Public read-only routes. */
  def routes(service: EspnImportService): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "api" / "v1" / "espn" / "preview" / UUIDVar(seasonId)
          :? DateParam(date) =>
        service
          .previewByDate(seasonId, date)
          .flatMap(previews =>
            Ok(Json.arr(previews.map(livePreviewToJson)*))
          )
          .handleErrorWith(e =>
            BadRequest(Json.obj("error" -> e.getMessage.asJson))
          )

      case GET -> Root / "api" / "v1" / "espn" / "calendar" =>
        service.fetchCalendar
          .flatMap: entries =>
            Ok(Json.arr(entries.map(e =>
              Json.obj(
                "espn_id" -> e.id.asJson,
                "name" -> e.label.asJson,
                "start_date" -> e.startDate.asJson
              )
            )*))
          .handleErrorWith(e =>
            BadRequest(Json.obj("error" -> e.getMessage.asJson))
          )

  /** Admin-only import routes. */
  def adminRoutes(service: EspnImportService): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case POST -> Root / "api" / "v1" / "espn" / "import"
          :? DateParam(date) =>
        service
          .importByDate(date)
          .flatMap(results =>
            Ok(Json.arr(results.map(resultToJson)*))
          )
          .handleErrorWith(e =>
            BadRequest(Json.obj("error" -> e.getMessage.asJson))
          )

      case POST -> Root / "api" / "v1" / "espn" / "import" / "tournament" / UUIDVar(tournamentId) =>
        service.importForTournament(tournamentId).flatMap:
          case Right(results) =>
            Ok(Json.arr(results.map(resultToJson)*))
          case Left(err) =>
            BadRequest(Json.obj("error" -> err.asJson))

  private object DateParam
      extends QueryParamDecoderMatcher[LocalDate]("date")

  given QueryParamDecoder[LocalDate] =
    QueryParamDecoder[String].map(LocalDate.parse)
