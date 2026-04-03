package com.cwfgw.repository

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import com.cwfgw.domain.*

object DraftRepository:

  private val selectDraftCols = fr"id, season_id, status, draft_type, started_at, completed_at, created_at"

  private val selectPickCols = fr"id, draft_id, team_id, golfer_id, round_num, pick_num, picked_at"

  def findBySeason(seasonId: UUID): ConnectionIO[Option[Draft]] =
    (fr"SELECT" ++ selectDraftCols ++ fr"FROM drafts WHERE season_id = $seasonId").query[Draft].option

  def create(seasonId: UUID, req: CreateDraft): ConnectionIO[Draft] = sql"""INSERT INTO drafts (season_id, draft_type)
          VALUES ($seasonId, ${req.draftType.getOrElse("snake")})
          RETURNING $selectDraftCols""".query[Draft].unique

  def updateStatus(draftId: UUID, status: String): ConnectionIO[Option[Draft]] =
    val extra = status match
      case "in_progress" => fr", started_at = now()"
      case "completed" => fr", completed_at = now()"
      case _ => Fragment.empty
    (fr"UPDATE drafts SET status = $status" ++ extra ++ fr"WHERE id = $draftId RETURNING" ++ selectDraftCols)
      .query[Draft].option

  def getPicks(draftId: UUID): ConnectionIO[List[DraftPick]] =
    (fr"SELECT" ++ selectPickCols ++ fr"FROM draft_picks WHERE draft_id = $draftId ORDER BY pick_num").query[DraftPick]
      .to[List]

  def createPick(draftId: UUID, teamId: UUID, roundNum: Int, pickNum: Int): ConnectionIO[DraftPick] =
    sql"""INSERT INTO draft_picks (draft_id, team_id, round_num, pick_num)
          VALUES ($draftId, $teamId, $roundNum, $pickNum)
          RETURNING $selectPickCols""".query[DraftPick].unique

  def makePick(draftId: UUID, pickNum: Int, golferId: UUID): ConnectionIO[Option[DraftPick]] =
    (fr"UPDATE draft_picks SET golfer_id = $golferId, picked_at = now() WHERE draft_id = $draftId AND pick_num = $pickNum AND golfer_id IS NULL RETURNING" ++
      selectPickCols).query[DraftPick].option

  def getAvailableGolfers(draftId: UUID): ConnectionIO[List[Golfer]] =
    sql"""SELECT g.id, g.pga_player_id, g.first_name, g.last_name, g.country, g.world_ranking, g.active, g.updated_at
          FROM golfers g
          WHERE g.active = true
            AND g.id NOT IN (SELECT dp.golfer_id FROM draft_picks dp WHERE dp.draft_id = $draftId AND dp.golfer_id IS NOT NULL)
          ORDER BY g.world_ranking ASC NULLS LAST""".query[Golfer].to[List]
