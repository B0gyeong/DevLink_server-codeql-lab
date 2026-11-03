package com.devlink.server.domain.taskevent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: task.
@Entity
@Table(name = "task_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "task_id", length = 255, nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 30, nullable = false)
    private TaskEventType eventType;

    @Column(name = "content", length = 255, nullable = true, columnDefinition = "TEXT")
    private String content;

    // 질문·답변 작성자는 POST에서 검증한 Agent 신원으로 지정한다.
    @Column(name = "author_agent_id")
    private Long authorAgentId;

    // 같은 Task의 삽입 순서는 DB가 Task 행 잠금 안에서 부여한다.
    @Column(name = "sequence", nullable = false, insertable = false, updatable = false)
    private Long sequence;

    @Column(name = "created_at", length = 255, nullable = false, columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime createdAt;

    public static TaskEvent of(Long taskId, TaskEventType type, String content) {
        requireValue(taskId, "taskId");
        requireValue(type, "eventType");
        if (type == TaskEventType.QUESTION || type == TaskEventType.ANSWER) {
            throw new IllegalArgumentException("질문·답변에는 작성 Agent가 필요합니다.");
        }
        TaskEvent entity = new TaskEvent();
        entity.taskId = taskId;
        entity.eventType = type;
        entity.content = content;
        return entity;
    }

    /** POST 담당자가 참여 권한·상태·질문 참조를 검증한 뒤 사용하는 저장 진입점이다. */
    public static TaskEvent message(Long taskId, TaskEventType type, Long authorAgentId, String content) {
        requireValue(taskId, "taskId");
        requireValue(authorAgentId, "authorAgentId");
        requireValue(content, "content");
        if (taskId <= 0 || authorAgentId <= 0
                || (type != TaskEventType.QUESTION && type != TaskEventType.ANSWER)) {
            throw new IllegalArgumentException("질문·답변의 Task·작성자·유형이 올바르지 않습니다.");
        }
        TaskEvent entity = new TaskEvent();
        entity.taskId = taskId;
        entity.eventType = type;
        entity.authorAgentId = authorAgentId;
        entity.content = content;
        entity.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        return entity;
    }

    @PrePersist
    private void initializeTimestamp() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
