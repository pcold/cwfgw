package com.cwfgw.service

import munit.FunSuite

class RosterParserTest extends FunSuite:

  // ---------- Single team parsing ----------

  test("parse a single team with all ownership explicit") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER 75
        |2 ROSE 100
        |3 LOWRY 100""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isRight)
    val teams = result.toOption.get
    assertEquals(teams.size, 1)
    assertEquals(teams.head.teamNumber, 1)
    assertEquals(teams.head.teamName, "BROWN")
    assertEquals(teams.head.picks.size, 3)
  }

  test("first pick round and ownership parsed correctly") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER 75""".stripMargin
    val result = RosterParser.parse(input)
    val pick = result.toOption.get.head.picks.head
    assertEquals(pick.round, 1)
    assertEquals(pick.playerName, "SCHEFFLER")
    assertEquals(pick.ownershipPct, 75)
  }

  // ---------- Default ownership ----------

  test("ownership defaults to 100 when omitted") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER
        |2 ROSE""".stripMargin
    val result = RosterParser.parse(input)
    val picks = result.toOption.get.head.picks
    assertEquals(picks(0).ownershipPct, 100)
    assertEquals(picks(1).ownershipPct, 100)
  }

  test("mixed explicit and default ownership") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER 75
        |2 ROSE
        |3 LOWRY 50""".stripMargin
    val result = RosterParser.parse(input)
    val picks = result.toOption.get.head.picks
    assertEquals(picks(0).ownershipPct, 75)
    assertEquals(picks(1).ownershipPct, 100)
    assertEquals(picks(2).ownershipPct, 50)
  }

  // ---------- Multi-word player names ----------

  test("multi-word player name") {
    val input =
      """TEAM 1 BROWN
        |1 VAN ROOYEN 50""".stripMargin
    val result = RosterParser.parse(input)
    val pick = result.toOption.get.head.picks.head
    assertEquals(pick.playerName, "VAN ROOYEN")
    assertEquals(pick.ownershipPct, 50)
  }

  test("multi-word player name without ownership") {
    val input =
      """TEAM 1 BROWN
        |1 VAN ROOYEN""".stripMargin
    val result = RosterParser.parse(input)
    val pick = result.toOption.get.head.picks.head
    assertEquals(pick.playerName, "VAN ROOYEN")
    assertEquals(pick.ownershipPct, 100)
  }

  // ---------- Multiple teams ----------

  test("parse multiple teams separated by blank lines") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER 75
        |2 ROSE
        |
        |TEAM 2 SMITH
        |1 SCHEFFLER 25
        |2 MCILROY""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isRight)
    val teams = result.toOption.get
    assertEquals(teams.size, 2)
    assertEquals(teams(0).teamName, "BROWN")
    assertEquals(teams(1).teamName, "SMITH")
    assertEquals(teams(1).picks(0).ownershipPct, 25)
  }

  test("shared player ownership across teams") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER 75
        |
        |TEAM 7 WOMBLE
        |1 SCHEFFLER 25""".stripMargin
    val result = RosterParser.parse(input)
    val teams = result.toOption.get
    val brownPct = teams(0).picks.head.ownershipPct
    val womblePct = teams(1).picks.head.ownershipPct
    assertEquals(brownPct + womblePct, 100)
  }

  // ---------- Full 8-round roster ----------

  test("parse a full 8-round roster") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER 75
        |2 ROSE
        |3 LOWRY
        |4 HOVLAND
        |5 THOMAS
        |6 MORIKAWA
        |7 FLEETWOOD
        |8 HOMA""".stripMargin
    val result = RosterParser.parse(input)
    val picks = result.toOption.get.head.picks
    assertEquals(picks.size, 8)
    assertEquals(picks.last.round, 8)
    assertEquals(picks.last.playerName, "HOMA")
  }

  // ---------- Error cases ----------

  test("invalid team header returns Left") {
    val input =
      """NOT A TEAM HEADER
        |1 SCHEFFLER""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Invalid team header"))
  }

  test("invalid team number returns Left") {
    val input =
      """TEAM abc BROWN
        |1 SCHEFFLER""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isLeft)
  }

  test("missing player name returns Left") {
    val input =
      """TEAM 1 BROWN
        |1""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Missing player name"))
  }

  test("invalid pick line returns Left") {
    val input =
      """TEAM 1 BROWN
        |abc SCHEFFLER""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Invalid pick line"))
  }

  test("empty input returns empty list") {
    val result = RosterParser.parse("")
    // Empty input has no team blocks, so it should succeed with empty list
    assert(result.isRight)
    assertEquals(result.toOption.get.size, 0)
  }

  test("error includes team block number") {
    val input =
      """TEAM 1 BROWN
        |1 SCHEFFLER
        |
        |BAD HEADER
        |1 MCILROY""".stripMargin
    val result = RosterParser.parse(input)
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Team block 2"))
  }
