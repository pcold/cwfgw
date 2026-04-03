package com.cwfgw.routes

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*

object HealthRoutes:

  private given Configuration = Configuration.default.withSnakeCaseMemberNames

  private case class HealthResponse(status: String, service: String, database: String) derives ConfiguredCodec

  def routes(xa: Transactor[IO]): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "health" =>
      val dbCheck = sql"SELECT 1".query[Int].unique.transact(xa).attempt
      dbCheck.flatMap {
        case Right(_) => Ok(HealthResponse("ok", "cwfgw", "connected").asJson)
        case Left(_) => InternalServerError(HealthResponse("degraded", "cwfgw", "unreachable").asJson)
      }
