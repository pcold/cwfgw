package com.cwfgw.routes

import cats.effect.IO
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import com.cwfgw.service.AuthService

object AuthRoutes:

  private given Configuration = Configuration.default.withSnakeCaseMemberNames

  private case class LoginRequest(username: String, password: String)
  private given Decoder[LoginRequest] = Decoder.forProduct2("username", "password")(LoginRequest.apply)
  private given EntityDecoder[IO, LoginRequest] = jsonOf[IO, LoginRequest]

  private case class OkResponse(ok: Boolean) derives ConfiguredCodec

  private case class ErrorResponse(error: String) derives ConfiguredCodec

  private case class AuthStatus(authenticated: Boolean, username: Option[String] = None) derives ConfiguredCodec

  def routes(authService: AuthService): HttpRoutes[IO] = HttpRoutes.of[IO]:

    case req @ POST -> Root / "api" / "v1" / "auth" / "login" => req.as[LoginRequest].flatMap { body =>
        authService.login(body.username, body.password).flatMap {
          case Some(token) => Ok(OkResponse(true).asJson).map(_.addCookie(AuthMiddleware.sessionCookie(token)))
          case None => Forbidden(ErrorResponse("Invalid credentials").asJson)
        }
      }

    case req @ POST -> Root / "api" / "v1" / "auth" / "logout" =>
      val tokenOpt = req.cookies.find(_.name == "cwfgw_session").map(_.content)
      tokenOpt.fold(IO.unit)(authService.logout) >> Ok(OkResponse(true).asJson)
        .map(_.addCookie(AuthMiddleware.clearCookie))

    case req @ GET -> Root / "api" / "v1" / "auth" / "me" =>
      val tokenOpt = req.cookies.find(_.name == "cwfgw_session").map(_.content)
      tokenOpt match
        case None => Ok(AuthStatus(authenticated = false).asJson)
        case Some(token) => authService.validate(token).flatMap {
            case Some(username) => Ok(AuthStatus(true, Some(username)).asJson)
            case None => Ok(AuthStatus(authenticated = false).asJson)
          }
