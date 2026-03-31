package com.cwfgw.scheduler

import cats.effect.IO
import org.typelevel.log4cats.LoggerFactory

import java.time.{DayOfWeek, Duration as JDuration, LocalDate, LocalDateTime, LocalTime, ZoneId}
import scala.concurrent.duration.*

import com.cwfgw.service.WeeklyJobService

/** Schedules the weekly tournament processing pipeline.
  *
  * Behavior:
  *   - Runs every Monday at 6:00 AM ET
  *   - If any tournament is still in progress, retries every 12 hours
  *   - Stops retrying once all pending tournaments are processed (or the next Monday arrives)
  */
class TournamentScheduler(jobService: WeeklyJobService)(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger
  private val timezone = ZoneId.of("America/New_York")
  private val mondayRunTime = LocalTime.of(6, 0)
  private val retryInterval = 12.hours

  /** Start the scheduler as a background fiber. Never returns (loops forever). */
  def start: IO[Unit] =
    logger.info("Tournament scheduler started") >> loop

  private def loop: IO[Unit] =
    for
      delay <- timeUntilNextMonday
      _ <- logger.info(s"Next scheduled run in ${delay.toHours}h ${delay.toMinutes % 60}m")
      _ <- IO.sleep(delay)
      _ <- runWithRetries
      _ <- loop
    yield ()

  private def runWithRetries: IO[Unit] =
    for
      _ <- logger.info("=== Weekly tournament job starting ===")
      result <- jobService.processAll.attempt
      counts <- result match
        case Right(c) => IO.pure(c)
        case Left(err) =>
          logger.error(err)(s"Weekly job failed: ${err.getMessage}") >>
            IO.pure((0, 1)) // treat errors as pending so we retry
      _ <- if counts._2 > 0 then retryLoop(counts._2)
           else logger.info("=== All tournaments processed, done until next Monday ===")
    yield ()

  private def retryLoop(remaining: Int): IO[Unit] =
    for
      _ <- logger.info(s"$remaining tournament(s) still pending, retrying in ${retryInterval.toHours}h")
      _ <- IO.sleep(retryInterval)
      // Don't retry past the next Monday — a new cycle will handle it
      pastNextMonday <- isPastNextMonday
      _ <-
        if pastNextMonday then
          logger.info("Next Monday reached, ending retry cycle")
        else
          jobService.processAll.attempt.flatMap:
            case Right((_, 0)) =>
              logger.info("=== All tournaments now processed ===")
            case Right((_, stillPending)) =>
              retryLoop(stillPending)
            case Left(err) =>
              logger.error(err)(s"Retry failed: ${err.getMessage}") >>
                retryLoop(remaining)
    yield ()

  private def timeUntilNextMonday: IO[FiniteDuration] = IO:
    val now = LocalDateTime.now(timezone)
    val nextMonday =
      if now.getDayOfWeek == DayOfWeek.MONDAY && now.toLocalTime.isBefore(mondayRunTime) then
        now.toLocalDate.atTime(mondayRunTime)
      else
        val daysUntilMonday = (DayOfWeek.MONDAY.getValue - now.getDayOfWeek.getValue + 7) % 7
        val days = if daysUntilMonday == 0 then 7 else daysUntilMonday
        now.toLocalDate.plusDays(days).atTime(mondayRunTime)
    val millis = JDuration.between(now, nextMonday).toMillis
    millis.millis

  private def isPastNextMonday: IO[Boolean] = IO:
    val now = LocalDateTime.now(timezone)
    now.getDayOfWeek == DayOfWeek.MONDAY && now.toLocalTime.isAfter(mondayRunTime)
