package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskMessageSyncResponse;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskMessageRepository;
import com.devlink.server.repository.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** WebSocket 신호·재접속·주기 Pull이 공유하는 읽기 전용 동기화 진입점이다. */
@Service
public class TaskMessageSyncService {
    private final TaskMessageRepository messages;
    private final TaskMessageCursorCodec cursors;
    private final AgentRepository agents;
    private final TeamMemberRepository members;

    public TaskMessageSyncService(TaskMessageRepository messages, TaskMessageCursorCodec cursors,
            AgentRepository agents, TeamMemberRepository members) {
        this.messages = messages;
        this.cursors = cursors;
        this.agents = agents;
        this.members = members;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskMessageSyncResponse synchronize(AgentPrincipal agent, String cursor, int limit) {
        if (agent == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (limit < 1 || limit > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        requireCurrentAgent(agent);
        var position = cursors.decode(cursor, agent);
        // 한 요청 안의 권한 검사·현재 창·조회는 같은 DB 스냅샷을 사용한다.
        String window = position.windowSnapshot() == null ? messages.currentSnapshot() : position.windowSnapshot();
        var candidates = messages.findReceived(agent, position.previousSnapshot(), window,
                position.afterTaskId(), position.afterSequence(), limit + 1);
        boolean hasNext = candidates.size() > limit;
        var items = candidates.subList(0, Math.min(limit, candidates.size()));
        TaskMessageCursorCodec.Cursor next;
        if (hasNext) {
            var last = items.getLast();
            next = position.page(window, last.taskId(), Long.parseLong(last.sequence()));
        } else {
            // 빈 결과여도 완료 창을 반환한다. 당시 미커밋이던 이벤트는 다음 창에서 다시 검사한다.
            next = position.complete(window);
        }
        return new TaskMessageSyncResponse(items, cursors.encode(next), hasNext);
    }

    private void requireCurrentAgent(AgentPrincipal principal) {
        var agent = agents.findById(principal.agentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        if (agent.getTeamId() != principal.teamId() || agent.getOwnerUserId() != principal.ownerUserId()
                || !members.existsByTeamIdAndUserId(principal.teamId(), principal.ownerUserId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }
}
