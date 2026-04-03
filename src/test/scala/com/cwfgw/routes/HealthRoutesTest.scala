package com.cwfgw.routes

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.*
import munit.FunSuite
import org.http4s.*
import org.http4s.implicits.*

class HealthRoutesTest extends FunSuite:

  /** Stub transactor — passes null connection, sufficient for route-matching tests that don't hit the DB. */
  private val stubXa: Transactor[IO] = Transactor.fromConnection[IO](null, logHandler = None)

  test("GET /api/v1/nonexistent returns 404") {
    val routes = HealthRoutes.routes(stubXa).orNotFound
    val req = Request[IO](Method.GET, uri"/api/v1/nonexistent")
    val response = routes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }

  test("POST /api/v1/health returns 404 (only GET supported)") {
    val routes = HealthRoutes.routes(stubXa).orNotFound
    val req = Request[IO](Method.POST, uri"/api/v1/health")
    val response = routes.run(req).unsafeRunSync()
    assertEquals(response.status, Status.NotFound)
  }
