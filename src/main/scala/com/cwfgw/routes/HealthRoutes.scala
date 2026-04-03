package com.cwfgw.routes

import cats.effect.IO
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*

object HealthRoutes:

  private given Configuration = Configuration.default.withSnakeCaseMemberNames

  private case class HealthResponse(status: String, service: String) derives ConfiguredCodec

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "health" => Ok(HealthResponse("ok", "cwfgw").asJson)
