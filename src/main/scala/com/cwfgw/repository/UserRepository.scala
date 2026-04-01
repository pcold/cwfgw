package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.User

object UserRepository:

  def findByUsername(username: String): ConnectionIO[Option[User]] =
    sql"""SELECT id, username, password_hash, role, created_at
          FROM users WHERE username = $username"""
      .query[User].option

  def insert(
      username: String,
      passwordHash: String,
      role: String
  ): ConnectionIO[User] =
    sql"""INSERT INTO users (username, password_hash, role)
          VALUES ($username, $passwordHash, $role)
          RETURNING id, username, password_hash, role, created_at"""
      .query[User].unique

  def count: ConnectionIO[Long] =
    sql"SELECT count(*) FROM users".query[Long].unique
