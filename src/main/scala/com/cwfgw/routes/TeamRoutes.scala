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
import com.cwfgw.service.{TeamService, RosterViewTeam}

object TeamRoutes:

  def routes(service: TeamService): HttpRoutes[IO] = HttpRoutes.of[IO]:
    // Full roster view with golfer names: GET /api/v1/leagues/:id/rosters
    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "rosters" =>
      service.getRosterView(leagueId).flatMap: teams =>
        Ok(Json.arr(teams.map: t =>
          Json.obj(
            "team_id" -> t.teamId.asJson,
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
        *))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "teams" =>
      service.listByLeague(leagueId).flatMap(Ok(_))

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(_) / "teams" / UUIDVar(teamId) =>
      service.get(teamId).flatMap:
        case Some(team) => Ok(team)
        case None => NotFound()

    case req @ POST -> Root / "api" / "v1" / "leagues" / UUIDVar(leagueId) / "teams" =>
      req.as[CreateTeam].flatMap: body =>
        service.create(leagueId, body).flatMap(Created(_))

    case req @ PUT -> Root / "api" / "v1" / "leagues" / UUIDVar(_) / "teams" / UUIDVar(teamId) =>
      req.as[UpdateTeam].flatMap: body =>
        service.update(teamId, body).flatMap:
          case Some(team) => Ok(team)
          case None => NotFound()

    case GET -> Root / "api" / "v1" / "leagues" / UUIDVar(_) / "teams" / UUIDVar(teamId) / "roster" =>
      service.getRoster(teamId).flatMap(Ok(_))

    case req @ POST -> Root / "api" / "v1" / "leagues" / UUIDVar(_) / "teams" / UUIDVar(teamId) / "roster" =>
      req.as[AddToRoster].flatMap: body =>
        service.addToRoster(teamId, body).flatMap(Created(_))

    case DELETE -> Root / "api" / "v1" / "leagues" / UUIDVar(_) / "teams" / UUIDVar(teamId) / "roster" / UUIDVar(golferId) =>
      service.dropFromRoster(teamId, golferId).flatMap:
        case true => NoContent()
        case false => NotFound()
