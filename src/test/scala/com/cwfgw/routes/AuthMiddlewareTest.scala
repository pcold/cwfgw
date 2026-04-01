package com.cwfgw.routes

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import com.cwfgw.service.AuthService

class AuthMiddlewareTest extends FunSuite:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  private val validToken = "good-token"
  private val validUser = "admin"

  private def mkAuthService: AuthService =
    val sessions = Ref.unsafe[IO, Map[String, String]](
      Map(validToken -> validUser)
    )
    new AuthService(null, sessions):
      override def login(
          username: String,
          password: String
      ): IO[Option[String]] = IO.pure(None)

  /** A simple protected route that returns 200 with a body. */
  private val innerRoutes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "v1" / "admin" / "secret" =>
      Ok(Json.obj("data" -> "classified".asJson))

  private def sessionCookie(token: String): RequestCookie =
    RequestCookie("cwfgw_session", token)

  test("request without cookie skips protected routes (404)") {
    val protected_ =
      AuthMiddleware(mkAuthService)(innerRoutes).orNotFound
    val req =
      Request[IO](Method.GET, uri"/api/v1/admin/secret")
    val resp = protected_.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("request with invalid token skips protected routes (404)") {
    val protected_ =
      AuthMiddleware(mkAuthService)(innerRoutes).orNotFound
    val req =
      Request[IO](Method.GET, uri"/api/v1/admin/secret")
        .addCookie(sessionCookie("bad-token"))
    val resp = protected_.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("request with valid token passes through to inner route") {
    val protected_ =
      AuthMiddleware(mkAuthService)(innerRoutes).orNotFound
    val req =
      Request[IO](Method.GET, uri"/api/v1/admin/secret")
        .addCookie(sessionCookie(validToken))
    val resp = protected_.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = resp.as[Json].unsafeRunSync()
    assertEquals(
      body.hcursor.downField("data").as[String],
      Right("classified")
    )
  }

  test("valid token but unmatched path returns 404") {
    val protected_ =
      AuthMiddleware(mkAuthService)(innerRoutes).orNotFound
    val req =
      Request[IO](Method.GET, uri"/api/v1/admin/nonexistent")
        .addCookie(sessionCookie(validToken))
    val resp = protected_.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.NotFound)
  }

  test("protected routes compose with public routes") {
    val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO]:
      case GET -> Root / "api" / "v1" / "public" =>
        Ok(Json.obj("public" -> true.asJson))

    val combined =
      (AuthMiddleware(mkAuthService)(innerRoutes)
        <+> publicRoutes).orNotFound

    // Public route works without auth
    val pubReq =
      Request[IO](Method.GET, uri"/api/v1/public")
    val pubResp = combined.run(pubReq).unsafeRunSync()
    assertEquals(pubResp.status, Status.Ok)

    // Protected route without auth falls through (404)
    val secReq =
      Request[IO](Method.GET, uri"/api/v1/admin/secret")
    val secResp = combined.run(secReq).unsafeRunSync()
    assertEquals(secResp.status, Status.NotFound)

    // Protected route works with auth
    val authReq =
      Request[IO](Method.GET, uri"/api/v1/admin/secret")
        .addCookie(sessionCookie(validToken))
    val authResp = combined.run(authReq).unsafeRunSync()
    assertEquals(authResp.status, Status.Ok)
  }
