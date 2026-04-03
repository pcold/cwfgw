package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import io.circe.Json
import io.circe.syntax.*

/** Shared error response builder for route handlers. */
object RouteHelpers:

  def badRequest(message: String): IO[Response[IO]] = BadRequest(Json.obj("error" -> message.asJson))

  def badRequestFromError(e: Throwable): IO[Response[IO]] =
    badRequest(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  def okMessage(message: String): IO[Response[IO]] = Ok(Json.obj("message" -> message.asJson))
