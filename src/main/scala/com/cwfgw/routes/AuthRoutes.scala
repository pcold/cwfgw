package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import io.circe.{Json, Decoder}
import io.circe.syntax.*
import com.cwfgw.service.AuthService

object AuthRoutes:

  private final case class LoginRequest(username: String, password: String)

  private given Decoder[LoginRequest] = Decoder.forProduct2("username", "password")(LoginRequest.apply)

  private given EntityDecoder[IO, LoginRequest] = jsonOf[IO, LoginRequest]

  def routes(authService: AuthService): HttpRoutes[IO] = HttpRoutes.of[IO]:

    case req @ POST -> Root / "api" / "v1" / "auth" / "login" => req.as[LoginRequest].flatMap { body =>
        authService.login(body.username, body.password).flatMap {
          case Some(token) => Ok(Json.obj("ok" -> true.asJson)).map(_.addCookie(AuthMiddleware.sessionCookie(token)))
          case None => Forbidden(Json.obj("error" -> "Invalid credentials".asJson))
        }
      }

    case req @ POST -> Root / "api" / "v1" / "auth" / "logout" =>
      val tokenOpt = req.cookies.find(_.name == "cwfgw_session").map(_.content)
      tokenOpt.fold(IO.unit)(authService.logout) >> Ok(Json.obj("ok" -> true.asJson))
        .map(_.addCookie(AuthMiddleware.clearCookie))

    case req @ GET -> Root / "api" / "v1" / "auth" / "me" =>
      val tokenOpt = req.cookies.find(_.name == "cwfgw_session").map(_.content)
      tokenOpt match
        case None => Ok(Json.obj("authenticated" -> false.asJson))
        case Some(token) => authService.validate(token).flatMap {
            case Some(username) => Ok(Json.obj("authenticated" -> true.asJson, "username" -> username.asJson))
            case None => Ok(Json.obj("authenticated" -> false.asJson))
          }
