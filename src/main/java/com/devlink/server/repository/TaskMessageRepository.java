package com.devlink.server.repository;

import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskMessageItem;

/** PostgreSQL 스냅샷을 이용해 늦은 커밋도 복구하는 읽기 전용 원본 이벤트 조회다. */
@Repository
public class TaskMessageRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public TaskMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public String currentSnapshot() {
        return jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
    }

    public byte[] cursorSigningKey() {
        return jdbc.queryForObject("SELECT cursor_signing_key FROM task_message_sync_settings WHERE id = 1",
                (row, index) -> java.util.HexFormat.of().parseHex(row.getString(1)));
    }

    public List<TaskMessageItem> findReceived(AgentPrincipal agent, String previousSnapshot,
            String windowSnapshot, long afterTaskId, long afterSequence, int limit) {
        // 이전 완료 창에는 안 보였지만 이번 고정 창에는 보이는 커밋만 가져온다.
        // 페이지 중 새 커밋은 다음 창으로 넘기므로 작은 ID가 뒤늦게 나타나도 누락되지 않는다.
        String sql = """
                SELECT event.id, event.task_id, event.sequence, event.event_type, event.created_at
                FROM task_events event JOIN tasks task ON task.id = event.task_id
                WHERE task.team_id = :teamId
                  AND task.requester_agent_id <> task.receiver_agent_id
                  AND ((task.requester_agent_id = :agentId AND event.author_agent_id = task.receiver_agent_id)
                    OR (task.receiver_agent_id = :agentId AND event.author_agent_id = task.requester_agent_id))
                  AND event.event_type IN ('QUESTION', 'ANSWER')
                  AND pg_visible_in_snapshot(event.sync_xid, CAST(:window AS pg_snapshot))
                  AND (CAST(:previous AS pg_snapshot) IS NULL
                    OR (event.sync_xid >= pg_snapshot_xmin(CAST(:previous AS pg_snapshot))
                      AND NOT pg_visible_in_snapshot(event.sync_xid, CAST(:previous AS pg_snapshot))))
                  AND (event.task_id, event.sequence) > (:afterTaskId, :afterSequence)
                ORDER BY event.task_id, event.sequence
                LIMIT :limit
                """;
        var parameters = new MapSqlParameterSource()
                .addValue("teamId", agent.teamId()).addValue("agentId", agent.agentId())
                .addValue("previous", previousSnapshot, java.sql.Types.VARCHAR)
                .addValue("window", windowSnapshot).addValue("afterTaskId", afterTaskId)
                .addValue("afterSequence", afterSequence).addValue("limit", limit);
        // outbox나 Task 상태를 조건으로 사용하지 않아 알림 실패·작업 종료 후에도 복구한다.
        return named.query(sql, parameters, (row, index) -> new TaskMessageItem(
                Long.toString(row.getLong("id")), row.getLong("task_id"),
                Long.toString(row.getLong("sequence")), row.getString("event_type"),
                row.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)));
    }
}
