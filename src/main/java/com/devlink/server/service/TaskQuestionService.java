package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.task.TaskMessageResponse;
import com.devlink.server.dto.task.TaskQuestionRequest;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskQuestionService {
    private static final int MAX_MESSAGE_CODE_POINTS = 8_000;

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final AgentRepository agents;
    private final TeamAuthorizationService authorization;
    private final TaskQuestionOutboxWriter outbox;

    @Transactional
    public TaskMessageResponse create(Long taskId, long authorId, TaskQuestionRequest request) {

        // 1) 입력 검증 agentId와 본문 길이 확인
        if (authorId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        String message = request.message();
        if (message.codePointCount(0, message.length()) > MAX_MESSAGE_CODE_POINTS || message.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        // 2) Task를 잠근 뒤 Agent를 조회
        // 2-1) 같은 팀의 멤버이자 Task 참여자인지 확인
        var task = tasks.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var author = agents.findById(authorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        boolean requester = task.getRequesterAgentId().equals(authorId);
        if (!task.getTeamId().equals(author.getTeamId())
                || (!requester && !task.getReceiverAgentId().equals(authorId))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        // 2-2) 자기 배정 Task는 409로 거부
        authorization.requireTeamMember(author.getOwnerUserId(), task.getTeamId());
        if (task.getRequesterAgentId().equals(task.getReceiverAgentId())) {
            throw new BusinessException(ErrorCode.TASK_CONVERSATION_UNAVAILABLE);
        }

        // 3) 멱등 키 확인
        // 같은 키로 같은 질문을 보낸 경우 Task가 종료됐어도 기존 이벤트 반환
        var existing = events.findByTaskIdAndAuthorAgentIdAndRequestKey(taskId, authorId, request.requestKey());
        if (existing.isPresent()) {
            TaskEvent event = existing.get();
            // 키는 같은데 유형, 본문이 다르면 409
            if (event.getEventType() != TaskEventType.QUESTION || !message.equals(event.getContent())) {
                throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
            }
            return TaskMessageResponse.from(event, author.getName(), true);
        }

        // 4) 신규 질문 저장
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        TaskEvent event = events.saveAndFlush(
                TaskEvent.of(taskId, TaskEventType.QUESTION, message, authorId, null, request.requestKey()));
        Long receiverId = requester ? task.getReceiverAgentId() : task.getRequesterAgentId();
        outbox.enqueue(event, receiverId);
        return TaskMessageResponse.from(event, author.getName(), false);
    }
}
