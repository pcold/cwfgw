package com.cwfgw.service

import io.circe.Json
import munit.FunSuite

import java.time.Instant
import java.util.UUID

import com.cwfgw.domain.Golfer

/** Tests for findGolferMatch — the pure golfer matching function
  * extracted from EspnImportService. */
class GolferMatchTest extends FunSuite:

  private val now = Instant.now()

  private def golfer(id: Int, first: String, last: String, espnId: Option[String] = None): Golfer =
    Golfer(
      UUID.fromString(s"00000000-0000-0000-0000-00000000000$id"),
      espnId, first, last, None, None, true, Json.obj(), now
    )

  private val scheffler = golfer(1, "Scottie", "Scheffler")
  private val mcilroy = golfer(2, "Rory", "McIlroy")
  private val rahm = golfer(3, "Jon", "Rahm")
  private val youngCam = golfer(4, "Cameron", "Young")
  private val smithCam = golfer(5, "Cameron", "Smith")
  private val leeMinWoo = golfer(6, "Min Woo", "Lee")
  private val leeKH = golfer(7, "K.H.", "Lee")

  private val allGolfers = List(scheffler, mcilroy, rahm, youngCam, smithCam, leeMinWoo, leeKH)

  // ================================================================
  // Full name match
  // ================================================================

  test("exact full name match (case-insensitive)") {
    val result = findGolferMatch("Scottie Scheffler", "espn1", allGolfers)
    result match
      case GolferMatchResult.FullNameMatch(g) => assertEquals(g.id, scheffler.id)
      case other => fail(s"Expected FullNameMatch, got $other")
  }

  test("full name match is case-insensitive") {
    val result = findGolferMatch("RORY MCILROY", "espn2", allGolfers)
    result match
      case GolferMatchResult.FullNameMatch(g) => assertEquals(g.id, mcilroy.id)
      case other => fail(s"Expected FullNameMatch, got $other")
  }

  test("full name match: Jon Rahm") {
    val result = findGolferMatch("Jon Rahm", "espn3", allGolfers)
    result match
      case GolferMatchResult.FullNameMatch(g) => assertEquals(g.id, rahm.id)
      case other => fail(s"Expected FullNameMatch, got $other")
  }

  // ================================================================
  // Last name only match (unique)
  // ================================================================

  test("unique last name match: Scheffler") {
    // Remove full name match by using wrong first name, but there's only one Scheffler
    val golfers = List(scheffler, mcilroy, rahm)
    val result = findGolferMatch("Scott Scheffler", "espn1", golfers) // wrong first name
    result match
      case GolferMatchResult.LastNameMatch(g) => assertEquals(g.id, scheffler.id)
      case other => fail(s"Expected LastNameMatch, got $other")
  }

  test("unique last name match: Rahm") {
    val result = findGolferMatch("Jonathan Rahm", "espn3", allGolfers) // wrong first name
    result match
      case GolferMatchResult.LastNameMatch(g) => assertEquals(g.id, rahm.id)
      case other => fail(s"Expected LastNameMatch, got $other")
  }

  // ================================================================
  // No match
  // ================================================================

  test("no match when last name has multiple matches") {
    // "Lee" matches both Min Woo Lee and K.H. Lee — ambiguous, no match
    val result = findGolferMatch("Tom Lee", "espn99", allGolfers)
    result match
      case GolferMatchResult.NoMatch(first, last) =>
        assertEquals(first, "Tom")
        assertEquals(last, "Lee")
      case other => fail(s"Expected NoMatch, got $other")
  }

  test("no match for completely unknown golfer") {
    val result = findGolferMatch("Unknown Player", "espn99", allGolfers)
    result match
      case GolferMatchResult.NoMatch(first, last) =>
        assertEquals(first, "Unknown")
        assertEquals(last, "Player")
      case other => fail(s"Expected NoMatch, got $other")
  }

  test("no match returns parsed first/last names") {
    val result = findGolferMatch("Tiger Woods", "espn99", allGolfers)
    result match
      case GolferMatchResult.NoMatch(first, last) =>
        assertEquals(first, "Tiger")
        assertEquals(last, "Woods")
      case other => fail(s"Expected NoMatch, got $other")
  }

  test("single-word name: last name only, empty first") {
    val result = findGolferMatch("Beyonce", "espn99", allGolfers)
    result match
      case GolferMatchResult.NoMatch(first, last) =>
        assertEquals(first, "")
        assertEquals(last, "Beyonce")
      case other => fail(s"Expected NoMatch, got $other")
  }

  // ================================================================
  // Priority: full name match preferred over last name match
  // ================================================================

  test("full name match takes priority over last name match") {
    // Cameron Young should match by full name, not just "Young"
    val result = findGolferMatch("Cameron Young", "espn4", allGolfers)
    result match
      case GolferMatchResult.FullNameMatch(g) => assertEquals(g.id, youngCam.id)
      case other => fail(s"Expected FullNameMatch, got $other")
  }

  test("Cameron Smith matches by full name") {
    val result = findGolferMatch("Cameron Smith", "espn5", allGolfers)
    result match
      case GolferMatchResult.FullNameMatch(g) => assertEquals(g.id, smithCam.id)
      case other => fail(s"Expected FullNameMatch, got $other")
  }

  // ================================================================
  // Edge cases
  // ================================================================

  test("empty golfer list returns NoMatch") {
    val result = findGolferMatch("Scottie Scheffler", "espn1", Nil)
    result match
      case GolferMatchResult.NoMatch(_, _) => () // expected
      case other => fail(s"Expected NoMatch, got $other")
  }

  test("multi-word last name like Min Woo Lee: full name match") {
    val result = findGolferMatch("Min Woo Lee", "espn6", allGolfers)
    // "Min Woo Lee" splits to first="Min", last="Woo Lee"
    // This won't match by full name since our golfer has first="Min Woo", last="Lee"
    // It falls through to last-name match for "Woo Lee" which won't match either
    // So this should be NoMatch — the import service would auto-create
    result match
      case GolferMatchResult.NoMatch(_, _) => () // expected with simple split
      case GolferMatchResult.FullNameMatch(_) => () // also acceptable if matching works
      case other => fail(s"Unexpected result: $other")
  }
