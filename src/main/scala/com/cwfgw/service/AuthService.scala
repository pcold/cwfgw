package com.cwfgw.service

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.mindrot.jbcrypt.BCrypt
import org.typelevel.log4cats.LoggerFactory
import com.cwfgw.repository.UserRepository

class AuthService(xa: Transactor[IO], sessions: Ref[IO, Map[String, String]])(using LoggerFactory[IO]):

  private val logger = LoggerFactory[IO].getLogger

  /** Seed the admin user if no users exist. */
  def seedAdmin(username: String, password: String): IO[Unit] =
    val program =
      for
        n <- UserRepository.count
        _ <-
          if n == 0L then
            val hash = BCrypt.hashpw(password, BCrypt.gensalt())
            UserRepository.insert(username, hash, "admin").void
          else FC.unit
      yield ()
    program.transact(xa) >> logger.info("Admin user seed check complete")

  /** Validate credentials and return a session token. */
  def login(username: String, password: String): IO[Option[String]] = UserRepository.findByUsername(username)
    .transact(xa).flatMap {
      case Some(user) if BCrypt.checkpw(password, user.passwordHash) =>
        val token = java.util.UUID.randomUUID().toString
        sessions.update(_ + (token -> user.username)).as(Some(token))
      case _ => IO.pure(None)
    }

  /** Check if a session token is valid. */
  def validate(token: String): IO[Option[String]] = sessions.get.map(_.get(token))

  /** Remove a session. */
  def logout(token: String): IO[Unit] = sessions.update(_ - token)
