package com.cwfgw.espn

import cats.effect.IO
import io.circe.Json
import io.circe.parser.*
import munit.FunSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.net.http.{HttpClient as JHttpClient}

class EspnClientTest extends FunSuite:

  // We only test pure parsing methods, so we pass a dummy HttpClient
  given LoggerFactory[IO] = NoOpFactory[IO]
  private val client = EspnClient(JHttpClient.newHttpClient())

  // ---- Helper to build ESPN-shaped JSON ----

  private def competitorJson(
    id: String,
    name: String,
    order: Int,
    score: String,
    statusId: String = "1",
    roundScores: List[Int] = List(70, 70, 70, 70)
  ): Json =
    val linescores = Json.arr(roundScores.map(r => Json.obj("value" -> Json.fromDouble(r.toDouble).get)): _*)
    Json.obj(
      "id" -> Json.fromString(id),
      "order" -> Json.fromInt(order),
      "score" -> Json.fromString(score),
      "athlete" -> Json.obj("displayName" -> Json.fromString(name)),
      "linescores" -> linescores,
      "status" -> Json.obj("type" -> Json.obj("id" -> Json.fromString(statusId)))
    )

  private def tournamentJson(
    eventId: String = "401580123",
    eventName: String = "The Players Championship",
    completed: Boolean = true,
    competitors: List[Json] = Nil
  ): Json = Json.obj(
    "events" -> Json.arr(Json.obj(
      "id" -> Json.fromString(eventId),
      "name" -> Json.fromString(eventName),
      "status" -> Json.obj("type" -> Json.obj("completed" -> Json.fromBoolean(completed))),
      "competitions" -> Json.arr(Json.obj("competitors" -> Json.arr(competitors: _*)))
    ))
  )

  // ---- parseLeaderboard basic ----

  test("parseLeaderboard parses tournament name and id") {
    val json = tournamentJson(
      eventId = "401580999",
      eventName = "Masters Tournament",
      competitors = List(competitorJson("1", "Tiger Woods", 1, "-10"))
    )
    val result = client.parseLeaderboard(json)
    assert(result.isRight, result)
    val t = result.toOption.get
    assertEquals(t.espnId, "401580999")
    assertEquals(t.name, "Masters Tournament")
    assertEquals(t.completed, true)
  }

  test("parseLeaderboard parses competitor fields") {
    val json = tournamentJson(competitors =
      List(competitorJson("42", "Scottie Scheffler", 1, "-15", roundScores = List(65, 67, 68, 66)))
    )
    val t = client.parseLeaderboard(json).toOption.get
    assertEquals(t.competitors.size, 1)
    val c = t.competitors.head
    assertEquals(c.espnId, "42")
    assertEquals(c.name, "Scottie Scheffler")
    assertEquals(c.scoreStr, Some("-15"))
    assertEquals(c.scoreToPar, Some(-15))
    assertEquals(c.totalStrokes, Some(266))
    assertEquals(c.roundScores, List(65, 67, 68, 66))
  }

  // ---- assignPositions (tested via parseLeaderboard) ----

  test("unique scores get sequential positions") {
    val json = tournamentJson(competitors =
      List(
        competitorJson("1", "Player A", 1, "-10"),
        competitorJson("2", "Player B", 2, "-8"),
        competitorJson("3", "Player C", 3, "-6")
      )
    )
    val competitors = client.parseLeaderboard(json).toOption.get.competitors
    assertEquals(competitors.map(_.position), List(1, 2, 3))
  }

  test("tied scores share position") {
    val json = tournamentJson(competitors =
      List(
        competitorJson("1", "Player A", 1, "-10"),
        competitorJson("2", "Player B", 2, "-8"),
        competitorJson("3", "Player C", 3, "-8"),
        competitorJson("4", "Player D", 4, "-6")
      )
    )
    val competitors = client.parseLeaderboard(json).toOption.get.competitors
    // Players B and C are tied at -8, both get position 2
    assertEquals(
      competitors.map(c => (c.name, c.position)),
      List(
        ("Player A", 1),
        ("Player B", 2),
        ("Player C", 2),
        ("Player D", 4) // skips 3 because 2 players shared position 2
      )
    )
  }

  test("three-way tie shares position") {
    val json = tournamentJson(competitors =
      List(
        competitorJson("1", "Player A", 1, "-10"),
        competitorJson("2", "Player B", 2, "-7"),
        competitorJson("3", "Player C", 3, "-7"),
        competitorJson("4", "Player D", 4, "-7"),
        competitorJson("5", "Player E", 5, "-5")
      )
    )
    val competitors = client.parseLeaderboard(json).toOption.get.competitors
    assertEquals(
      competitors.map(c => (c.name, c.position)),
      List(("Player A", 1), ("Player B", 2), ("Player C", 2), ("Player D", 2), ("Player E", 5))
    )
  }

  test("all players tied at same score share position 1") {
    val json = tournamentJson(competitors =
      List(
        competitorJson("1", "Player A", 1, "E"),
        competitorJson("2", "Player B", 2, "E"),
        competitorJson("3", "Player C", 3, "E")
      )
    )
    val competitors = client.parseLeaderboard(json).toOption.get.competitors
    assertEquals(competitors.map(_.position), List(1, 1, 1))
  }

  // ---- madeCut logic in parseCompetitor ----

  test("active player with 4 rounds is parsed with madeCut=true") {
    val json = tournamentJson(competitors =
      List(competitorJson("1", "Active Player", 1, "-5", statusId = "1", roundScores = List(68, 69, 70, 68)))
    )
    val c = client.parseLeaderboard(json).toOption.get.competitors.head
    assertEquals(c.status, "1")
    assert(c.madeCut)
  }

  test("cut player (status 2) is parsed") {
    val json = tournamentJson(competitors =
      List(competitorJson("1", "Cut Player", 1, "+5", statusId = "2", roundScores = List(75, 76)))
    )
    val c = client.parseLeaderboard(json).toOption.get.competitors.head
    assertEquals(c.status, "2")
    assert(!c.madeCut)
  }

  test("withdrawn player (status 3) is parsed") {
    val json =
      tournamentJson(competitors = List(competitorJson("1", "WD Player", 1, "+2", statusId = "3", roundScores = List(72))))
    val c = client.parseLeaderboard(json).toOption.get.competitors.head
    assertEquals(c.status, "3")
    assert(!c.madeCut)
  }

  test("disqualified player (status 4) is parsed") {
    val json = tournamentJson(competitors =
      List(competitorJson("1", "DQ Player", 1, "+1", statusId = "4", roundScores = List(71, 72)))
    )
    val c = client.parseLeaderboard(json).toOption.get.competitors.head
    assertEquals(c.status, "4")
    assert(!c.madeCut)
  }

  // ---- parseAllLeaderboards ----

  test("parseAllLeaderboards parses multiple events") {
    val json = Json.obj(
      "events" -> Json.arr(
        Json.obj(
          "id" -> Json.fromString("100"),
          "name" -> Json.fromString("Event One"),
          "status" -> Json.obj("type" -> Json.obj("completed" -> Json.fromBoolean(true))),
          "competitions" -> Json.arr(Json.obj("competitors" -> Json.arr(competitorJson("1", "Player A", 1, "-5"))))
        ),
        Json.obj(
          "id" -> Json.fromString("200"),
          "name" -> Json.fromString("Event Two"),
          "status" -> Json.obj("type" -> Json.obj("completed" -> Json.fromBoolean(false))),
          "competitions" -> Json.arr(Json.obj("competitors" -> Json.arr(competitorJson("2", "Player B", 1, "-3"))))
        )
      )
    )
    val result = client.parseAllLeaderboards(json)
    assert(result.isRight, result)
    val tournaments = result.toOption.get
    assertEquals(tournaments.size, 2)
    assertEquals(tournaments(0).name, "Event One")
    assertEquals(tournaments(1).name, "Event Two")
    assertEquals(tournaments(1).completed, false)
  }

  // ---- Score parsing edge cases ----

  test("even par 'E' is parsed as scoreToPar=0") {
    val json = tournamentJson(competitors = List(competitorJson("1", "Even Player", 1, "E")))
    val c = client.parseLeaderboard(json).toOption.get.competitors.head
    assertEquals(c.scoreToPar, Some(0))
  }

  test("positive score '+3' is parsed correctly") {
    val json = tournamentJson(competitors = List(competitorJson("1", "Over Par", 1, "+3")))
    val c = client.parseLeaderboard(json).toOption.get.competitors.head
    assertEquals(c.scoreToPar, Some(3))
  }

  // ---- Error handling ----

  test("parseLeaderboard returns Left for empty events") {
    val json = Json.obj("events" -> Json.arr())
    val result = client.parseLeaderboard(json)
    assert(result.isLeft)
  }

  test("parseLeaderboard returns Left for missing events field") {
    val json = Json.obj("foo" -> Json.fromString("bar"))
    val result = client.parseLeaderboard(json)
    assert(result.isLeft)
  }

  // ---- Missing order field fallback ----

  test("competitor without order field uses list index as fallback") {
    val noOrderJson = Json.obj(
      "id" -> Json.fromString("99"),
      "score" -> Json.fromString("-3"),
      "athlete" -> Json.obj("displayName" -> Json.fromString("Adam Svensson")),
      "linescores" -> Json.arr(
        Json.obj("value" -> Json.fromDouble(70.0).get),
        Json.obj("value" -> Json.fromDouble(69.0).get),
        Json.obj("value" -> Json.fromDouble(71.0).get),
        Json.obj("value" -> Json.fromDouble(68.0).get)
      ),
      "status" -> Json.obj("type" -> Json.obj("id" -> Json.fromString("1")))
    )
    val json = tournamentJson(competitors = List(competitorJson("1", "Player A", 1, "-10"), noOrderJson))
    val t = client.parseLeaderboard(json).toOption.get
    assertEquals(t.competitors.size, 2)
    val svensson = t.competitors.find(_.name == "Adam Svensson").get
    assertEquals(svensson.espnId, "99")
    assertEquals(svensson.scoreToPar, Some(-3))
  }
