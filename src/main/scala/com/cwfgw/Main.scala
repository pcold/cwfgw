package com.cwfgw

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.semigroupk.*

import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import com.cwfgw.config.AppConfig
import com.cwfgw.db.{Database, FlywayMigrator}
import com.cwfgw.espn.EspnClient
import com.cwfgw.service.*
import com.cwfgw.routes.*

object Main extends IOApp:

  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def run(args: List[String]): IO[ExitCode] =
    val logger = LoggerFactory[IO].getLogger

    for
      _ <- logger.info("Starting cwfgw - Fantasy Golf League")
      config <- IO.fromEither(
        AppConfig.load.left.map(failures =>
          new RuntimeException(
            s"Failed to load config: ${failures.prettyPrint()}"
          )
        )
      )
      _ <- logger.info("Running database migrations...")
      _ <- FlywayMigrator.migrate(config.database)
      sessions <- Ref.of[IO, Map[String, String]](Map.empty)
      _ <- logger.info(
        s"Server configured for ${config.server.host}:${config.server.port}"
      )
      _ <- Database.transactor(config.database).use: xa =>
        EspnClient.resource.use: espnClient =>
          val leagueService = LeagueService(xa)
          val golferService = GolferService(xa)
          val teamService = TeamService(xa)
          val draftService = DraftService(xa)
          val scoringService = ScoringService(xa)
          val espnImportService =
            EspnImportService(espnClient, xa)
          val tournamentService =
            TournamentService(espnImportService, scoringService, xa)
          val weeklyReportService =
            WeeklyReportService(espnImportService, xa)
          val adminService = AdminService(espnClient, xa)
          val authService = AuthService(xa, sessions)

          val protectedRoutes =
            AuthMiddleware(authService)(
              AdminRoutes.routes(adminService)
                <+> TournamentRoutes.adminRoutes(tournamentService)
                <+> EspnRoutes.adminRoutes(espnImportService)
            )

          val allRoutes =
            StaticRoutes.routes
              <+> HealthRoutes.routes
              <+> AuthRoutes.routes(authService)
              <+> protectedRoutes
              <+> LeagueRoutes.routes(leagueService)
              <+> GolferRoutes.routes(golferService)
              <+> TournamentRoutes.routes(tournamentService)
              <+> TeamRoutes.routes(teamService)
              <+> DraftRoutes.routes(draftService)
              <+> ScoringRoutes.routes(scoringService)
              <+> ReportRoutes.routes(weeklyReportService)
              <+> EspnRoutes.routes(espnImportService)

          authService.seedAdmin("admin", "AlsTheBoss") >>
            EmberServerBuilder
              .default[IO]
              .withHost(config.server.http4sHost)
              .withPort(config.server.http4sPort)
              .withHttpApp(Router("/" -> allRoutes).orNotFound)
              .build
              .useForever
    yield ExitCode.Success
