package com.cwfgw.espn

/** An ESPN tournament parsed from the scoreboard API. */
case class EspnTournament(
    espnId: String,
    name: String,
    completed: Boolean,
    competitors: List[EspnCompetitor]
)

/** A single competitor on the ESPN leaderboard.
  * Status codes from ESPN: "1"=active, "2"=cut, "3"=withdrawn, "4"=disqualified */
case class EspnCompetitor(
    espnId: String,
    name: String,
    order: Int,
    scoreStr: Option[String],
    scoreToPar: Option[Int],
    totalStrokes: Option[Int],
    roundScores: List[Int],
    position: Int,
    status: String = "1"
):
  def madeCut: Boolean = status != "2" && status != "3" && status != "4"

/** A PGA player from ESPN. */
case class EspnAthlete(
    espnId: String,
    name: String
)

/** A calendar entry from the ESPN season schedule. */
case class EspnCalendarEntry(
    id: String,
    label: String,
    startDate: String
)
