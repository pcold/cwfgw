package com.cwfgw.service

import munit.FunSuite
import java.util.UUID
import java.time.{Instant, LocalDate}
import com.cwfgw.domain.Tournament
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global

class TournamentServiceTest extends FunSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val service = new TournamentService(null, null, null, Semaphore[IO](1).unsafeRunSync())
  private val now = Instant.now()
  private val seasonId = UUID.randomUUID()

  private def mkTournament(name: String, date: String, status: String = "completed"): Tournament = Tournament(
    id = UUID.randomUUID(),
    pgaTournamentId = None,
    name = name,
    seasonId = seasonId,
    startDate = LocalDate.parse(date),
    endDate = LocalDate.parse(date),
    courseName = None,
    status = status,
    purseAmount = None,
    payoutMultiplier = BigDecimal(1),
    week = None,
    createdAt = now
  )

  // ================================================================
  // checkBlockingTournaments
  // ================================================================

  test("checkBlockingTournaments: empty list returns None") {
    assertEquals(service.checkBlockingTournaments(Nil, "finalize"), None)
  }

  test("checkBlockingTournaments: single blocker returns finalize message") {
    val blocker = mkTournament("The Masters", "2026-04-09")
    val result = service.checkBlockingTournaments(List(blocker), "finalize")
    assert(result.isDefined)
    assert(result.get.contains("Cannot finalize out of order"))
    assert(result.get.contains("Finalize these first"))
    assert(result.get.contains("The Masters"))
  }

  test("checkBlockingTournaments: single blocker returns reset message") {
    val blocker = mkTournament("PGA Championship", "2026-05-14")
    val result = service.checkBlockingTournaments(List(blocker), "reset")
    assert(result.isDefined)
    assert(result.get.contains("Cannot reset out of order"))
    assert(result.get.contains("Reset these first"))
    assert(result.get.contains("PGA Championship"))
  }

  test("checkBlockingTournaments: multiple blockers sorted by date") {
    val t1 = mkTournament("First", "2026-01-15")
    val t2 = mkTournament("Second", "2026-03-20")
    val t3 = mkTournament("Third", "2026-02-10")
    val result = service.checkBlockingTournaments(List(t1, t2, t3), "finalize")
    assert(result.isDefined)
    val msg = result.get
    // Should be sorted by date: First, Third, Second
    val firstIdx = msg.indexOf("First")
    val thirdIdx = msg.indexOf("Third")
    val secondIdx = msg.indexOf("Second")
    assert(firstIdx < thirdIdx, s"First ($firstIdx) should come before Third ($thirdIdx)")
    assert(thirdIdx < secondIdx, s"Third ($thirdIdx) should come before Second ($secondIdx)")
  }

  test("checkBlockingTournaments: message includes dates") {
    val blocker = mkTournament("US Open", "2026-06-18")
    val result = service.checkBlockingTournaments(List(blocker), "finalize")
    assert(result.get.contains("2026-06-18"))
  }

  // ================================================================
  // checkSeasonComplete
  // ================================================================

  test("checkSeasonComplete: empty tournament list") {
    val result = service.checkSeasonComplete(Nil)
    assertEquals(result, Some("No tournaments in this season"))
  }

  test("checkSeasonComplete: all completed returns None") {
    val tournaments = List(
      mkTournament("Week 1", "2026-01-08", "completed"),
      mkTournament("Week 2", "2026-01-15", "completed"),
      mkTournament("Week 3", "2026-01-22", "completed")
    )
    assertEquals(service.checkSeasonComplete(tournaments), None)
  }

  test("checkSeasonComplete: one incomplete") {
    val tournaments = List(
      mkTournament("Week 1", "2026-01-08", "completed"),
      mkTournament("Week 2", "2026-01-15", "in_progress"),
      mkTournament("Week 3", "2026-01-22", "completed")
    )
    val result = service.checkSeasonComplete(tournaments)
    assert(result.isDefined)
    assert(result.get.contains("Cannot finalize"))
    assert(result.get.contains("Week 2"))
    assert(result.get.contains("in_progress"))
  }

  test("checkSeasonComplete: multiple incomplete sorted by date") {
    val tournaments = List(
      mkTournament("Late", "2026-03-01", "upcoming"),
      mkTournament("Early", "2026-01-01", "in_progress"),
      mkTournament("Done", "2026-02-01", "completed")
    )
    val result = service.checkSeasonComplete(tournaments)
    assert(result.isDefined)
    val msg = result.get
    val earlyIdx = msg.indexOf("Early")
    val lateIdx = msg.indexOf("Late")
    assert(earlyIdx < lateIdx, s"Early ($earlyIdx) should come before Late ($lateIdx)")
    assert(!msg.contains("Done"))
  }

  test("checkSeasonComplete: includes status in message") {
    val tournaments = List(mkTournament("Week 1", "2026-01-08", "upcoming"))
    val result = service.checkSeasonComplete(tournaments)
    assert(result.get.contains("upcoming"))
  }
