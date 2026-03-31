package com.cwfgw.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.http4s.*
import org.http4s.implicits.*
import io.circe.Json
import org.http4s.circe.*

class HealthRoutesTest extends FunSuite:

  private val routes = HealthRoutes.routes.orNotFound

  test("GET /api/v1/health returns 200 OK") {
    val req = Request[IO](Method.GET, uri"/api/v1/health")
    val response = routes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
  }

  test("GET /api/v1/health returns JSON with status ok") {
    val req = Request[IO](Method.GET, uri"/api/v1/health")
    val response = routes.run(req).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.downField("status").as[String], Right("ok"))
    assertEquals(body.hcursor.downField("service").as[String], Right("cwfgw"))
  }

  test("GET /api/v1/health returns application/json content type") {
    val req = Request[IO](Method.GET, uri"/api/v1/health")
    val response = routes.run(req).unsafeRunSync()
    assert(response.contentType.exists(_.mediaType == MediaType.application.json))
  }

  test("GET /api/v1/nonexistent returns 404") {
    val req = Request[IO](Method.GET, uri"/api/v1/nonexistent")
    val response = routes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("POST /api/v1/health returns 404 (only GET supported)") {
    val req = Request[IO](Method.POST, uri"/api/v1/health")
    val response = routes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }
