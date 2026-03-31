package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import io.circe.{Json, Decoder, HCursor}
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory
import java.util.UUID
import com.cwfgw.service.{AdminService, ConfirmedTeam, ConfirmedPick}

object AdminRoutes:

  private def errorResponse(e: Throwable)(using LoggerFactory[IO]): IO[Response[IO]] =
    val logger = LoggerFactory[IO].getLogger
    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    logger.error(e)(s"Admin route error: $msg") >>
      BadRequest(Json.obj("error" -> msg.asJson))

  private case class SeasonUploadRequest(leagueId: UUID, seasonYear: Int, schedule: String)
  private given Decoder[SeasonUploadRequest] = Decoder.forProduct3("league_id", "season_year", "schedule")(SeasonUploadRequest.apply)
  private given EntityDecoder[IO, SeasonUploadRequest] = jsonOf[IO, SeasonUploadRequest]

  private case class RosterPreviewRequest(roster: String)
  private given Decoder[RosterPreviewRequest] = Decoder.forProduct1("roster")(RosterPreviewRequest.apply)
  private given EntityDecoder[IO, RosterPreviewRequest] = jsonOf[IO, RosterPreviewRequest]

  private given Decoder[ConfirmedPick] = (c: HCursor) =>
    for
      round <- c.downField("round").as[Int]
      name <- c.downField("player_name").as[String]
      pct <- c.downField("ownership_pct").as[Int]
      espnId <- c.downField("espn_id").as[Option[String]]
      espnName <- c.downField("espn_name").as[Option[String]]
    yield ConfirmedPick(round, name, pct, espnId, espnName)

  private given Decoder[ConfirmedTeam] = (c: HCursor) =>
    for
      num <- c.downField("team_number").as[Int]
      name <- c.downField("team_name").as[String]
      picks <- c.downField("picks").as[List[ConfirmedPick]]
    yield ConfirmedTeam(num, name, picks)

  private case class RosterConfirmRequest(leagueId: UUID, teams: List[ConfirmedTeam])
  private given Decoder[RosterConfirmRequest] = Decoder.forProduct2("league_id", "teams")(RosterConfirmRequest.apply)
  private given EntityDecoder[IO, RosterConfirmRequest] = jsonOf[IO, RosterConfirmRequest]

  def routes(service: AdminService)(using LoggerFactory[IO]): HttpRoutes[IO] = HttpRoutes.of[IO]:

    // Upload a season schedule: POST /api/v1/admin/season
    case req @ POST -> Root / "api" / "v1" / "admin" / "season" =>
      req.as[SeasonUploadRequest].flatMap: body =>
        service.uploadSeason(body.leagueId, body.seasonYear, body.schedule).flatMap: result =>
          Ok(Json.obj(
            "season_year" -> result.seasonYear.asJson,
            "tournaments_created" -> result.tournamentsCreated.asJson,
            "espn_matched" -> result.espnMatched.asJson,
            "espn_unmatched" -> result.espnUnmatched.asJson,
            "tournaments" -> result.tournaments.map: t =>
              Json.obj(
                "id" -> t.id.asJson,
                "name" -> t.name.asJson,
                "week" -> t.week.asJson,
                "start_date" -> t.startDate.asJson,
                "end_date" -> t.endDate.asJson,
                "is_major" -> t.isMajor.asJson,
                "espn_id" -> t.espnId.asJson,
                "espn_name" -> t.espnName.asJson
              )
            .asJson
          ))
      .handleErrorWith(errorResponse)

    // Step 1: Preview roster with ESPN matching: POST /api/v1/admin/roster/preview
    case req @ POST -> Root / "api" / "v1" / "admin" / "roster" / "preview" =>
      req.as[RosterPreviewRequest].flatMap: body =>
        service.previewRoster(body.roster).flatMap: result =>
          Ok(Json.obj(
            "total_picks" -> result.totalPicks.asJson,
            "exact_matches" -> result.exactMatches.asJson,
            "ambiguous" -> result.ambiguous.asJson,
            "no_match" -> result.noMatch.asJson,
            "teams" -> result.teams.map: t =>
              Json.obj(
                "team_number" -> t.teamNumber.asJson,
                "team_name" -> t.teamName.asJson,
                "picks" -> t.picks.map: p =>
                  Json.obj(
                    "round" -> p.round.asJson,
                    "input_name" -> p.inputName.asJson,
                    "ownership_pct" -> p.ownershipPct.asJson,
                    "match_status" -> p.matchStatus.asJson,
                    "espn_id" -> p.espnId.asJson,
                    "espn_name" -> p.espnName.asJson,
                    "suggestions" -> p.suggestions.map(s =>
                      Json.obj("espn_id" -> s.espnId.asJson, "name" -> s.name.asJson)
                    ).asJson
                  )
                .asJson
              )
            .asJson
          ))
      .handleErrorWith(errorResponse)

    // Step 2: Confirm and persist rosters: POST /api/v1/admin/roster/confirm
    case req @ POST -> Root / "api" / "v1" / "admin" / "roster" / "confirm" =>
      req.as[RosterConfirmRequest].flatMap: body =>
        service.confirmRoster(body.leagueId, body.teams).flatMap: result =>
          Ok(Json.obj(
            "teams_created" -> result.teamsCreated.asJson,
            "golfers_created" -> result.golfersCreated.asJson,
            "teams" -> result.teams.map: t =>
              Json.obj(
                "team_id" -> t.teamId.asJson,
                "team_number" -> t.teamNumber.asJson,
                "team_name" -> t.teamName.asJson,
                "picks" -> t.picks.map: p =>
                  Json.obj(
                    "round" -> p.round.asJson,
                    "golfer_name" -> p.golferName.asJson,
                    "golfer_id" -> p.golferId.asJson,
                    "ownership_pct" -> p.ownershipPct.asJson
                  )
                .asJson
              )
            .asJson
          ))
      .handleErrorWith(errorResponse)

    // Preview ESPN calendar: GET /api/v1/admin/espn-calendar
    case GET -> Root / "api" / "v1" / "admin" / "espn-calendar" =>
      service.previewEspnCalendar.flatMap: entries =>
        Ok(Json.arr(entries.map(e => Json.obj(
          "espn_id" -> e.id.asJson,
          "name" -> e.label.asJson,
          "start_date" -> e.startDate.asJson
        ))*))
      .handleErrorWith(errorResponse)
