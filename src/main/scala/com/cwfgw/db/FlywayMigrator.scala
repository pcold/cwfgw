package com.cwfgw.db

import cats.effect.IO
import org.flywaydb.core.Flyway
import org.typelevel.log4cats.LoggerFactory
import com.cwfgw.config.DatabaseConfig

object FlywayMigrator:

  def migrate(config: DatabaseConfig)(using LoggerFactory[IO]): IO[Int] =
    val logger = LoggerFactory[IO].getLogger
    IO.blocking:
      val flyway = Flyway.configure()
        .dataSource(config.url, config.user, config.password)
        .locations("classpath:db/migration")
        .load()
      flyway.repair()
      flyway.migrate().migrationsExecuted
    .flatTap(count => logger.info(s"Flyway applied $count migration(s)"))
