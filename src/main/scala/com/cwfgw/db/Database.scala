package com.cwfgw.db

import cats.effect.{IO, Resource}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import com.cwfgw.config.DatabaseConfig

object Database:

  def transactor(config: DatabaseConfig): Resource[IO, HikariTransactor[IO]] =
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](config.poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = config.driver,
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = ec
      )
    yield xa
