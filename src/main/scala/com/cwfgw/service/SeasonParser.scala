package com.cwfgw.service

import java.time.{LocalDate, Month}
import scala.util.Try

/** Parses a season schedule text file into tournament entries.
  *
  * Expected format (one tournament per line):
  * {{{
  * 1 1 Jan 15-18 Sony Open
  * 8a 8 March 5-8 Arnold Palmer Invitational 1.5x
  * 9 10 March 12-15 The Players Championship 2x
  * }}}
  *
  * Fields: week event_number date_range tournament_name [Nx]
  *   - Trailing "Nx" (e.g. "2x", "1.5x") sets the payout multiplier for that tournament. No suffix = 1x.
  *   - "***" in name marks a signature event (metadata only)
  *   - Week can be "8a", "8b" for multi-event weeks
  */
object SeasonParser:

  final case class ParsedTournament(
    week: String,
    eventNumber: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    name: String,
    payoutMultiplier: BigDecimal,
    isSignature: Boolean,
    notes: Option[String]
  )

  private val multiplierPattern = """(\d+\.?\d*)[xX]$""".r

  private val months = Map(
    "jan" -> Month.JANUARY,
    "january" -> Month.JANUARY,
    "feb" -> Month.FEBRUARY,
    "february" -> Month.FEBRUARY,
    "mar" -> Month.MARCH,
    "march" -> Month.MARCH,
    "apr" -> Month.APRIL,
    "april" -> Month.APRIL,
    "may" -> Month.MAY,
    "jun" -> Month.JUNE,
    "june" -> Month.JUNE,
    "jul" -> Month.JULY,
    "july" -> Month.JULY,
    "aug" -> Month.AUGUST,
    "august" -> Month.AUGUST,
    "sep" -> Month.SEPTEMBER,
    "september" -> Month.SEPTEMBER,
    "oct" -> Month.OCTOBER,
    "october" -> Month.OCTOBER,
    "nov" -> Month.NOVEMBER,
    "november" -> Month.NOVEMBER,
    "dec" -> Month.DECEMBER,
    "december" -> Month.DECEMBER
  )

  def parse(text: String, seasonYear: Int): Either[String, List[ParsedTournament]] =
    val lines = text.trim.linesIterator.filter(_.trim.nonEmpty).toList
    val results = lines.zipWithIndex.map { (line, idx) =>
      parseLine(line.trim, seasonYear).left.map(err => s"Line ${idx + 1}: $err")
    }
    val errors = results.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors.mkString("\n")) else Right(results.collect { case Right(t) => t })

  private def parseLine(line: String, year: Int): Either[String, ParsedTournament] =
    val tokens = line.split("\\s+").toList
    tokens match
      case week :: eventStr :: rest if eventStr.toIntOption.isDefined =>
        val eventNum = eventStr.toInt
        parseDateAndName(rest, year).map { (start, end, name, mult, isSig, notes) =>
          ParsedTournament(week, eventNum, start, end, name, mult, isSig, notes)
        }
      case _ => Left(s"Could not parse: '$line' — " + "expected: WEEK EVENT_NUM DATE_RANGE NAME")

  private def parseDateAndName(
    tokens: List[String],
    year: Int
  ): Either[String, (LocalDate, LocalDate, String, BigDecimal, Boolean, Option[String])] = tokens match
    case monthStr :: dateRange :: rest => parseMonth(monthStr) match
        case None => Left(s"Unknown month: '$monthStr'")
        case Some(startMonth) => parseDateRange(dateRange, startMonth, year).flatMap { (startDate, endDate) =>
            val rawName = rest.mkString(" ")
            val isSignature = rawName.contains("***")

            // Extract optional multiplier from last token
            val (nameWithoutMult, multiplier) = extractMultiplier(rawName)

            // Also support legacy "Double $$" for
            // backward compatibility
            val (cleanName, finalMult) =
              if nameWithoutMult.contains("Double $$") then
                val n = nameWithoutMult.replace("Double $$", "").trim
                (n, BigDecimal(2))
              else (nameWithoutMult, multiplier)

            val name = cleanName.replace("***", "").replaceAll("\\s*(2 event week|BOTH count)\\s*", "").trim

            val notes = List(
              if finalMult > 1 then Some(s"${finalMult}x") else None,
              if rawName.contains("2 event week") then Some("2 event week") else None,
              if rawName.contains("BOTH count") then Some("BOTH count") else None
            ).flatten match
              case Nil => None
              case ns => Some(ns.mkString(", "))

            Right((startDate, endDate, name, finalMult, isSignature, notes))
          }
    case _ => Left("Missing date range")

  /** Extract a trailing multiplier like "2x" or "1.5x" from the raw name string. Returns the name without the
    * multiplier token and the parsed value.
    */
  private def extractMultiplier(raw: String): (String, BigDecimal) =
    val tokens = raw.split("\\s+").toList
    tokens.lastOption match
      case Some(last) => multiplierPattern.findFirstMatchIn(last) match
          case Some(m) =>
            val mult = BigDecimal(m.group(1))
            val remaining = tokens.init.mkString(" ")
            (remaining, mult)
          case None => (raw, BigDecimal(1))
      case None => (raw, BigDecimal(1))

  /** Parse a date range like "15-18" (same month), "29-Feb1" (cross-month), "5-8"
    */
  private def parseDateRange(range: String, startMonth: Month, year: Int): Either[String, (LocalDate, LocalDate)] =
    range.split("-", 2) match
      case Array(startStr, endStr) => startStr.toIntOption match
          case None => Left(s"Invalid start day: '$startStr'")
          case Some(startDay) => endStr.toIntOption match
              case Some(endDay) =>
                val start = LocalDate.of(year, startMonth, startDay)
                val endMonth = if endDay < startDay then startMonth.plus(1) else startMonth
                val end = LocalDate.of(year, endMonth, endDay)
                Right((start, end))
              case None =>
                val monthEnd = months.keys.find(m => endStr.toLowerCase.startsWith(m))
                monthEnd match
                  case Some(mStr) =>
                    val dayStr = endStr.substring(mStr.length)
                    dayStr.toIntOption match
                      case Some(endDay) =>
                        val start = LocalDate.of(year, startMonth, startDay)
                        val end = LocalDate.of(year, months(mStr), endDay)
                        Right((start, end))
                      case None => Left(s"Invalid end date: '$endStr'")
                  case None => Left(s"Invalid end date: '$endStr'")
      case _ => Left(s"Invalid date range: '$range'")

  private def parseMonth(s: String): Option[Month] = months.get(s.toLowerCase)
