package com.cwfgw.domain

import java.util.UUID
import java.time.Instant

final case class User(
    id: UUID,
    username: String,
    passwordHash: String,
    role: String,
    createdAt: Instant
)
