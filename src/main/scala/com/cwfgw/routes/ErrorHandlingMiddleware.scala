package com.cwfgw.routes

import cats.data.Kleisli
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.LoggerFactory

/** Global error handler that catches unhandled exceptions and returns a JSON 500 response. */
object ErrorHandlingMiddleware:

  def apply(app: HttpApp[IO])(using LoggerFactory[IO]): HttpApp[IO] =
    val logger = LoggerFactory[IO].getLogger
    Kleisli { req =>
      app.run(req).handleErrorWith { err =>
        logger.error(err)(s"Unhandled error on ${req.method} ${req.uri}") >>
          InternalServerError(Json.obj("error" -> "Internal server error".asJson))
      }
    }
