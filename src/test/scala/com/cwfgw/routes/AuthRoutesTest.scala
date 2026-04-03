package com.cwfgw.routes

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import com.cwfgw.service.AuthService

class AuthRoutesTest extends FunSuite:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  private val validToken = "test-token-123"
  private val validUser = "admin"

  private def mkAuthService(loginResult: Option[String] = None): AuthService =
    val sessions = Ref.unsafe[IO, Map[String, String]](Map(validToken -> validUser))
    new AuthService(null, sessions):
      override def login(username: String, password: String): IO[Option[String]] = IO.pure(loginResult)

  private def sessionCookie(token: String): RequestCookie = RequestCookie("cwfgw_session", token)

  // ================================================================
  // GET /api/v1/auth/me
  // ================================================================

  test("GET /auth/me without cookie returns authenticated: false") {
    val routes = AuthRoutes.routes(mkAuthService()).orNotFound
    val req = Request[IO](Method.GET, uri"/api/v1/auth/me")
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = resp.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("authenticated").as[Boolean], Right(false))
  }

  test("GET /auth/me with valid cookie returns authenticated: true") {
    val routes = AuthRoutes.routes(mkAuthService()).orNotFound
    val req = Request[IO](Method.GET, uri"/api/v1/auth/me").addCookie(sessionCookie(validToken))
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = resp.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("authenticated").as[Boolean], Right(true))
    assertEquals(body.hcursor.downField("username").as[String], Right(validUser))
  }

  test("GET /auth/me with invalid cookie returns authenticated: false") {
    val routes = AuthRoutes.routes(mkAuthService()).orNotFound
    val req = Request[IO](Method.GET, uri"/api/v1/auth/me").addCookie(sessionCookie("bogus-token"))
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val body = resp.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("authenticated").as[Boolean], Right(false))
  }

  // ================================================================
  // POST /api/v1/auth/login
  // ================================================================

  test("POST /auth/login with valid creds returns 200 and sets cookie") {
    val token = "new-session-token"
    val routes = AuthRoutes.routes(mkAuthService(Some(token))).orNotFound
    val req = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(Json.obj("username" -> "admin".asJson, "password" -> "AlsTheBoss".asJson))
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
    val cookie = resp.cookies.find(_.name == "cwfgw_session")
    assert(cookie.isDefined, "Should set session cookie")
    assertEquals(cookie.get.content, token)
  }

  test("POST /auth/login with bad creds returns 403") {
    val routes = AuthRoutes.routes(mkAuthService(None)).orNotFound
    val req = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(Json.obj("username" -> "admin".asJson, "password" -> "wrong".asJson))
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Forbidden)
    val body = resp.as[Json].unsafeRunSync()
    assert(body.hcursor.downField("error").as[String].isRight)
  }

  // ================================================================
  // POST /api/v1/auth/logout
  // ================================================================

  test("POST /auth/logout clears session and sets empty cookie") {
    val authService = mkAuthService()
    val routes = AuthRoutes.routes(authService).orNotFound

    // Logout with valid token
    val req = Request[IO](Method.POST, uri"/api/v1/auth/logout").addCookie(sessionCookie(validToken))
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)

    val cookie = resp.cookies.find(_.name == "cwfgw_session")
    assert(cookie.isDefined, "Should set clear cookie")
    assertEquals(cookie.get.content, "")

    // Verify session was actually removed
    val validated = authService.validate(validToken).unsafeRunSync()
    assertEquals(validated, None)
  }

  test("POST /auth/logout without cookie returns 200") {
    val routes = AuthRoutes.routes(mkAuthService()).orNotFound
    val req = Request[IO](Method.POST, uri"/api/v1/auth/logout")
    val resp = routes.run(req).unsafeRunSync()
    assertEquals(resp.status, Status.Ok)
  }
