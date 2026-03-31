package com.cwfgw.routes

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import io.circe.Json
import org.http4s.circe.*

object HealthRoutes:

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "health" =>
      Ok(Json.obj(
        "status" -> Json.fromString("ok"),
        "service" -> Json.fromString("cwfgw")
      ))
