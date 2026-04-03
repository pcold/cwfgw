package com.cwfgw.service

/** Parses a team roster text file.
  *
  * Expected format:
  * {{{
  * TEAM 1 BROWN
  * 1 SCHEFFLER 75
  * 2 ROSE
  * 3 LOWRY
  * ...
  *
  * TEAM 7 WOMBLE
  * 1 SCHEFFLER 25
  * ...
  * }}}
  *
  *   - `TEAM {number} {name}` starts each team block
  *   - `{round} {player_name} [{ownership_pct}]` — ownership % only when not 100
  *   - Blank line between teams
  */
object RosterParser:

  case class ParsedTeam(teamNumber: Int, teamName: String, picks: List[ParsedPick])

  case class ParsedPick(round: Int, playerName: String, ownershipPct: Int)

  def parse(text: String): Either[String, List[ParsedTeam]] =
    val lines = text.trim.linesIterator.toList
    val teams = splitIntoTeamBlocks(lines)
    val results = teams.zipWithIndex.map: (block, idx) =>
      parseTeamBlock(block).left.map(err => s"Team block ${idx + 1}: $err")
    val errors = results.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors.mkString("\n")) else Right(results.collect { case Right(t) => t })

  private def splitIntoTeamBlocks(lines: List[String]): List[List[String]] =
    val (blocks, current) = lines
      .foldLeft((List.empty[List[String]], List.empty[String])) { case ((blocks, current), line) =>
        val trimmed = line.trim
        if trimmed.isEmpty then if current.nonEmpty then (blocks :+ current.reverse, Nil) else (blocks, Nil)
        else (blocks, trimmed :: current)
      }
    if current.nonEmpty then blocks :+ current.reverse else blocks

  private def parseTeamBlock(lines: List[String]): Either[String, ParsedTeam] = lines match
    case Nil => Left("Empty team block")
    case header :: picks => parseTeamHeader(header).flatMap: (teamNum, teamName) =>
        val pickResults = picks.map(parsePick)
        val errors = pickResults.collect { case Left(e) => e }
        if errors.nonEmpty then Left(s"$teamName: ${errors.mkString("; ")}")
        else Right(ParsedTeam(teamNum, teamName, pickResults.collect { case Right(p) => p }))

  private def parseTeamHeader(line: String): Either[String, (Int, String)] =
    // TEAM 1 BROWN
    val tokens = line.split("\\s+", 3).toList
    tokens match
      case "TEAM" :: numStr :: name :: Nil => numStr.toIntOption match
          case Some(num) => Right((num, name.trim))
          case None => Left(s"Invalid team number: '$numStr'")
      case _ => Left(s"Invalid team header: '$line' — expected: TEAM {number} {name}")

  private def parsePick(line: String): Either[String, ParsedPick] =
    val tokens = line.split("\\s+").toList
    tokens match
      case roundStr :: rest if roundStr.toIntOption.isDefined =>
        val round = roundStr.toInt
        // Check if last token is an ownership percentage (a number)
        val (nameParts, pct) = rest.lastOption.flatMap(_.toIntOption) match
          case Some(p) if p <= 100 && p > 0 => (rest.dropRight(1), p)
          case _ => (rest, 100)
        if nameParts.isEmpty then Left(s"Missing player name on line: '$line'")
        else Right(ParsedPick(round, nameParts.mkString(" "), pct))
      case _ => Left(s"Invalid pick line: '$line' — expected: {round} {player_name} [{ownership_pct}]")
