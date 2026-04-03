package com.cwfgw.service

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

class AuthServiceTest extends FunSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def mkService(tokens: Map[String, String] = Map.empty): AuthService =
    val ref = Ref.unsafe[IO, Map[String, String]](tokens)
    AuthService(xa = null, sessions = ref)

  test("validate returns username for existing token") {
    val service = mkService(Map("tok-1" -> "alice"))
    val result = service.validate("tok-1").unsafeRunSync()
    assertEquals(result, Some("alice"))
  }

  test("validate returns None for unknown token") {
    val service = mkService(Map("tok-1" -> "alice"))
    val result = service.validate("tok-unknown").unsafeRunSync()
    assertEquals(result, None)
  }

  test("validate returns None after logout") {
    val service = mkService(Map("tok-1" -> "alice"))
    service.logout("tok-1").unsafeRunSync()
    val result = service.validate("tok-1").unsafeRunSync()
    assertEquals(result, None)
  }

  test("logout of non-existent token does not error") {
    val service = mkService(Map("tok-1" -> "alice"))
    service.logout("tok-nonexistent").unsafeRunSync()
    // original token still valid
    assertEquals(service.validate("tok-1").unsafeRunSync(), Some("alice"))
  }

  test("multiple sessions validate independently") {
    val service = mkService(Map("tok-1" -> "alice", "tok-2" -> "bob", "tok-3" -> "carol"))

    assertEquals(service.validate("tok-1").unsafeRunSync(), Some("alice"))
    assertEquals(service.validate("tok-2").unsafeRunSync(), Some("bob"))
    assertEquals(service.validate("tok-3").unsafeRunSync(), Some("carol"))

    // logout bob, others unaffected
    service.logout("tok-2").unsafeRunSync()
    assertEquals(service.validate("tok-1").unsafeRunSync(), Some("alice"))
    assertEquals(service.validate("tok-2").unsafeRunSync(), None)
    assertEquals(service.validate("tok-3").unsafeRunSync(), Some("carol"))
  }
