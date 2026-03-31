package com.cwfgw.routes

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

object StaticRoutes:

  private val classLoader = getClass.getClassLoader

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    // Serve index.html for root and SPA routes
    case GET -> Root =>
      serveResource("static/index.html", MediaType.text.html)

    // Serve static assets (js, css, images)
    case req @ GET -> "static" /: path =>
      val filePath = s"static/${path.segments.map(_.encoded).mkString("/")}"
      val mediaType = filePath match
        case p if p.endsWith(".js")   => MediaType.application.javascript
        case p if p.endsWith(".css")  => MediaType.text.css
        case p if p.endsWith(".html") => MediaType.text.html
        case p if p.endsWith(".svg")  => MediaType.image.`svg+xml`
        case p if p.endsWith(".png")  => MediaType.image.png
        case _                        => MediaType.application.`octet-stream`
      serveResource(filePath, mediaType)

  private def serveResource(path: String, mediaType: MediaType): IO[Response[IO]] =
    Option(classLoader.getResource(path)) match
      case Some(url) =>
        StaticFile.fromURL(url, None)
          .map(_.withContentType(`Content-Type`(mediaType)))
          .getOrElseF(NotFound())
      case None => NotFound()
