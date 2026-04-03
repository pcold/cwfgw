package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import scala.io.Source

object StaticRoutes:

  private val classLoader = getClass.getClassLoader

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root => serveClasspath("static/index.html", `Content-Type`(MediaType.text.html))

    case GET -> Root / "static" / file =>
      val mediaType = file match
        case f if f.endsWith(".js") => MediaType.application.javascript
        case f if f.endsWith(".css") => MediaType.text.css
        case f if f.endsWith(".html") => MediaType.text.html
        case f if f.endsWith(".svg") => MediaType.image.`svg+xml`
        case f if f.endsWith(".png") => MediaType.image.png
        case _ => MediaType.application.`octet-stream`
      serveClasspath(s"static/$file", `Content-Type`(mediaType))

  private def serveClasspath(path: String, contentType: `Content-Type`): IO[Response[IO]] = IO
    .blocking(Option(classLoader.getResourceAsStream(path))).flatMap:
      case None => NotFound()
      case Some(is) => IO.blocking:
          val bytes = is.readAllBytes()
          is.close()
          bytes
        .flatMap: bytes =>
          Ok(bytes).map(_.withContentType(contentType))
