package com.cwfgw.routes

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import com.cwfgw.service.AuthService

object AuthMiddleware:

  private val cookieName = "cwfgw_session"

  def sessionCookie(token: String): ResponseCookie =
    ResponseCookie(cookieName, token, path = Some("/"), httpOnly = true, sameSite = Some(SameSite.Strict))

  def clearCookie: ResponseCookie = ResponseCookie(cookieName, "", path = Some("/"), httpOnly = true, maxAge = Some(0L))

  /** Middleware that gates access to inner routes behind a valid session cookie. Requests without a valid session are
    * skipped (OptionT.none), allowing public routes composed after these to still match.
    */
  def apply(authService: AuthService)(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { req =>
    val tokenOpt = req.cookies.find(_.name == cookieName).map(_.content)
    tokenOpt match
      case Some(token) => OptionT(authService.validate(token).flatMap {
          case Some(_) => routes.run(req).value
          case None => IO.pure(Option.empty[Response[IO]])
        })
      case None => OptionT.none
  }
