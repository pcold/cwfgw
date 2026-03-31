package com.cwfgw.service

/** Shared payout table for top-10 fantasy scoring.
  * Used by both ScoringService (persisted results) and EspnImportService (live preview). */
object PayoutTable:

  val payouts: Map[Int, BigDecimal] = Map(
    1 -> BigDecimal(18), 2 -> BigDecimal(12), 3 -> BigDecimal(10),
    4 -> BigDecimal(8),  5 -> BigDecimal(7),  6 -> BigDecimal(6),
    7 -> BigDecimal(5),  8 -> BigDecimal(4),  9 -> BigDecimal(3),
    10 -> BigDecimal(2)
  )

  private val minTiePayout = BigDecimal(1)

  /** Calculate tie-split payout for a tournament position.
    * E.g., T4 with 3 players tied: average of positions 4,5,6 payouts = ($8+$7+$6)/3 = $7.
    * Floor of $1 per player for any tie that overlaps the payout zone. */
  def tieSplitPayout(position: Int, numTied: Int, isMajor: Boolean): BigDecimal =
    val multiplier = if isMajor then BigDecimal(2) else BigDecimal(1)
    if position > 10 then BigDecimal(0)
    else if numTied <= 1 then
      payouts.getOrElse(position, BigDecimal(0)) * multiplier
    else
      val positions = (position until position + numTied).toList
      val totalPayout = positions.flatMap(p => payouts.get(p)).sum
      val averaged = totalPayout / numTied
      (averaged max minTiePayout) * multiplier
