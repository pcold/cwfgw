package com.cwfgw.service

import cats.effect.IO
import munit.FunSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import com.cwfgw.espn.{EspnAthlete, EspnCalendarEntry}

class AdminServiceTest extends FunSuite:

  given LoggerFactory[IO] = NoOpFactory[IO]

  // AdminService with null deps — we only test pure methods
  private val admin = new AdminService(espnClient = null, xa = null)

  // ---- Helper: build athlete list ----

  private def athlete(id: String, name: String): EspnAthlete = EspnAthlete(id, name)

  private val sampleAthletes = List(
    athlete("1", "Scottie Scheffler"),
    athlete("2", "Rory McIlroy"),
    athlete("3", "Jon Rahm"),
    athlete("4", "Cameron Young"),
    athlete("5", "Cameron Smith"),
    athlete("6", "Min Woo Lee"),
    athlete("7", "K.H. Lee"),
    athlete("8", "Byeong-Hun An"),
    athlete("9", "Collin Morikawa"),
    athlete("10", "Viktor Hovland"),
    athlete("11", "Tommy Fleetwood"),
    athlete("12", "Thomas Detry"),
    athlete("13", "Erik van Rooyen"),
    athlete("14", "Bryson DeChambeau"),
    athlete("15", "Ludvig Åberg"),
    athlete("16", "Nicolai Højgaard"),
    athlete("17", "Robert Macintyre") // note: lowercase 'i' in ESPN
  )

  // ================================================================
  // editDistance
  // ================================================================

  test("editDistance: identical strings = 0") { assertEquals(admin.editDistance("SCHEFFLER", "SCHEFFLER"), 0) }

  test("editDistance: single substitution = 1") { assertEquals(admin.editDistance("RAHM", "RAHN"), 1) }

  test("editDistance: single insertion = 1") { assertEquals(admin.editDistance("RAHM", "RAAHM"), 1) }

  test("editDistance: single deletion = 1") { assertEquals(admin.editDistance("RAHM", "RAM"), 1) }

  test("editDistance: completely different strings") { assertEquals(admin.editDistance("ABC", "XYZ"), 3) }

  test("editDistance: empty strings") {
    assertEquals(admin.editDistance("", ""), 0)
    assertEquals(admin.editDistance("ABC", ""), 3)
    assertEquals(admin.editDistance("", "XY"), 2)
  }

  // ================================================================
  // stripDiacritics
  // ================================================================

  test("stripDiacritics: plain ASCII unchanged") {
    assertEquals(admin.stripDiacritics("Scottie Scheffler"), "Scottie Scheffler")
  }

  test("stripDiacritics: removes accents") { assertEquals(admin.stripDiacritics("Åberg"), "Aberg") }

  test("stripDiacritics: handles ø → o") { assertEquals(admin.stripDiacritics("Højgaard"), "Hojgaard") }

  test("stripDiacritics: handles đ → d") { assertEquals(admin.stripDiacritics("Đoković"), "Dokovic") }

  test("stripDiacritics: handles ł → l") { assertEquals(admin.stripDiacritics("Łukasz"), "Lukasz") }

  test("stripDiacritics: handles ñ") { assertEquals(admin.stripDiacritics("Muñoz"), "Munoz") }

  // ================================================================
  // firstNameMatches
  // ================================================================

  test("firstNameMatches: single initial matches first name") {
    assert(admin.firstNameMatches(athlete("1", "Cameron Young"), "C"))
  }

  test("firstNameMatches: prefix matches first name") {
    assert(admin.firstNameMatches(athlete("1", "Cameron Young"), "CAM"))
  }

  test("firstNameMatches: full first name matches") {
    assert(admin.firstNameMatches(athlete("1", "Cameron Young"), "CAMERON"))
  }

  test("firstNameMatches: KH does not match K.H. (single ESPN first part)") {
    // K.H. is one token, so multi-initial path requires espnClean.length >= hintChars.length
    // "K.H." is 1 part after split, hint "KH" is 2 chars → doesn't match
    assert(!admin.firstNameMatches(athlete("7", "K.H. Lee"), "KH"))
  }

  test("firstNameMatches: multi-initial MW matches Min Woo") {
    assert(admin.firstNameMatches(athlete("6", "Min Woo Lee"), "MW"))
  }

  test("firstNameMatches: wrong initial does not match") {
    assert(!admin.firstNameMatches(athlete("1", "Cameron Young"), "R"))
  }

  // ================================================================
  // matchEspnPlayer — exact last name
  // ================================================================

  test("matchEspnPlayer: exact last name match → Exact") {
    admin.matchEspnPlayer("SCHEFFLER", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Scottie Scheffler")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: exact last name match case-insensitive") {
    admin.matchEspnPlayer("scheffler", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Scottie Scheffler")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: exact last name with first initial narrows ambiguity") {
    // "C. YOUNG" should narrow to Cameron Young (not Cameron Smith)
    admin.matchEspnPlayer("YOUNG, C.", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Cameron Young")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: multi-initial MW LEE narrows to Min Woo Lee") {
    admin.matchEspnPlayer("M.W. LEE", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Min Woo Lee")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: KH LEE returns Ambiguous (K.H. is single token, can't narrow)") {
    // K.H. Lee's first name is a single token "K.H.", so multi-initial "KH" doesn't narrow
    admin.matchEspnPlayer("KH LEE", sampleAthletes) match
      case admin.MatchResult.Ambiguous(candidates) => assert(candidates.exists(_.name.contains("Lee")))
      case other => fail(s"Expected Ambiguous, got $other")
  }

  // ================================================================
  // matchEspnPlayer — aliases
  // ================================================================

  test("matchEspnPlayer: alias DECHAMBEAU → Bryson DeChambeau") {
    admin.matchEspnPlayer("DECHAMBEAU", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Bryson DeChambeau")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: alias AN → Byeong-Hun An") {
    admin.matchEspnPlayer("AN", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Byeong-Hun An")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: alias DETRY → Thomas Detry") {
    admin.matchEspnPlayer("DETRY", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Thomas Detry")
      case other => fail(s"Expected Exact, got $other")
  }

  // ================================================================
  // matchEspnPlayer — diacritics
  // ================================================================

  test("matchEspnPlayer: diacritics-insensitive match for Åberg") {
    admin.matchEspnPlayer("ABERG", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Ludvig Åberg")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: diacritics-insensitive match for Højgaard") {
    admin.matchEspnPlayer("HOJGAARD", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Nicolai Højgaard")
      case other => fail(s"Expected Exact, got $other")
  }

  // ================================================================
  // matchEspnPlayer — fuzzy
  // ================================================================

  test("matchEspnPlayer: fuzzy match with small typo → Exact") {
    // MORIKAWA vs MORIKAWA — exact, but test MORIKWA (missing A)
    admin.matchEspnPlayer("MORIKWA", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Collin Morikawa")
      case other => fail(s"Expected Exact, got $other")
  }

  test("matchEspnPlayer: fuzzy match HOVLUND → Hovland (1 char diff)") {
    admin.matchEspnPlayer("HOVLUND", sampleAthletes) match
      case admin.MatchResult.Exact(a) => assertEquals(a.name, "Viktor Hovland")
      case other => fail(s"Expected Exact, got $other")
  }

  // ================================================================
  // matchEspnPlayer — ambiguous / no match
  // ================================================================

  test("matchEspnPlayer: ambiguous when multiple last-name matches without hint") {
    // "LEE" matches both Min Woo Lee and K.H. Lee
    admin.matchEspnPlayer("LEE", sampleAthletes) match
      case admin.MatchResult.Ambiguous(candidates) =>
        assert(candidates.size >= 2)
        assert(candidates.exists(_.name.contains("Min Woo")))
        assert(candidates.exists(_.name.contains("K.H.")))
      case other => fail(s"Expected Ambiguous, got $other")
  }

  test("matchEspnPlayer: no match for completely unknown name") {
    admin.matchEspnPlayer("ZZZZXXX", sampleAthletes) match
      case admin.MatchResult.NoMatch => () // expected
      case other => fail(s"Expected NoMatch, got $other")
  }

  // ================================================================
  // findEspnMatch — tournament name matching
  // ================================================================

  private val sampleCalendar = List(
    EspnCalendarEntry("401", "The Masters", "2026-04-09"),
    EspnCalendarEntry("402", "PGA Championship", "2026-05-14"),
    EspnCalendarEntry("403", "Arnold Palmer Invitational presented by Mastercard", "2026-03-05"),
    EspnCalendarEntry("404", "Sony Open in Hawaii", "2026-01-15"),
    EspnCalendarEntry("405", "AT&T Pebble Beach Pro-Am", "2026-02-12")
  )

  test("findEspnMatch: exact name match") {
    val result = admin.findEspnMatch("The Masters", sampleCalendar)
    assertEquals(result.map(_.id), Some("401"))
  }

  test("findEspnMatch: substring match — short name contained in long ESPN name") {
    val result = admin.findEspnMatch("Arnold Palmer Invitational", sampleCalendar)
    assertEquals(result.map(_.id), Some("403"))
  }

  test("findEspnMatch: substring match — ESPN name contained in input") {
    val result = admin.findEspnMatch("Sony Open in Hawaii Special", sampleCalendar)
    assertEquals(result.map(_.id), Some("404"))
  }

  test("findEspnMatch: word overlap match") {
    // "Pebble Beach Pro-Am" should match "AT&T Pebble Beach Pro-Am"
    val result = admin.findEspnMatch("Pebble Beach Pro-Am", sampleCalendar)
    assertEquals(result.map(_.id), Some("405"))
  }

  test("findEspnMatch: no match for unknown tournament") {
    val result = admin.findEspnMatch("Totally Fake Open", sampleCalendar)
    assertEquals(result, None)
  }
