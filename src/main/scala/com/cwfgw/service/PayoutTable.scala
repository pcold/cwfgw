package com.cwfgw.service

import java.util.UUID
import scala.math.BigDecimal.RoundingMode
import com.cwfgw.domain.SeasonRules

/** Payout calculation for top-N fantasy scoring.
  * Used by ScoringService (persisted results)
  * and EspnImportService (live preview). */
object PayoutTable:

  /** Calculate tie-split payout for a tournament position.
    *
    * E.g., T4 with 3 tied and default payouts:
    * average of positions 4,5,6 = ($8+$7+$6)/3 = $7.
    * Floor of $1 per player for any tie that overlaps
    * the payout zone.
    *
    * @param multiplier tournament-level multiplier
    *   (1.0 = normal, 2.0 = majors)
    */
  def tieSplitPayout(
      position: Int,
      numTied: Int,
      multiplier: BigDecimal,
      rules: SeasonRules
  ): BigDecimal =
    val payouts = rules.payouts
    val numPlaces = payouts.size
    if position > numPlaces then BigDecimal(0)
    else if numTied <= 1 then
      payouts.lift(position - 1)
        .getOrElse(BigDecimal(0)) * multiplier
    else
      val positions = (position until position + numTied).toList
      val totalPayout = positions
        .flatMap(p => payouts.lift(p - 1))
        .sum
      val averaged = totalPayout / numTied
      (averaged max rules.tieFloor) * multiplier

  /** Split a base payout across owners so rounded values
    * sum exactly to the base. Largest-ownership share is
    * rounded first; the last owner gets the remainder.
    */
  def splitOwnership(
      basePayout: BigDecimal,
      owners: List[(UUID, BigDecimal)]
  ): Map[UUID, BigDecimal] =
    if owners.size <= 1 then
      owners.map((id, _) => id -> basePayout).toMap
    else
      val sorted = owners.sortBy(-_._2)
      val rounded = sorted.init.map { (id, pct) =>
        id -> (basePayout * pct / 100)
          .setScale(2, RoundingMode.HALF_UP)
      }
      val remainder = basePayout - rounded.map(_._2).sum
      (rounded :+ (sorted.last._1 -> remainder)).toMap
