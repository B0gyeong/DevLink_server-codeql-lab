package com.devlink.server.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    Optional<TaskEvent> findFirstByTaskIdAndEventTypeInOrderByIdDesc(
            Long taskId, List<TaskEventType> eventTypes);
    boolean existsByTaskIdAndEventType(Long taskId, TaskEventType eventType);

    List<TaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<TaskEvent> findByTaskIdOrderBySequenceAsc(Long taskId);

    Optional<TaskEvent> findByTaskIdAndAuthorAgentIdAndRequestKey(
            Long taskId, Long authorAgentId, String requestKey);

    interface LatestEventTime {
        Long getTaskId();
        TaskEventType getEventType();
        LocalDateTime getLatestAt();
    }

    @Query("""
            select e.taskId as taskId,
                   e.eventType as eventType,
                   max(e.createdAt) as latestAt
            from TaskEvent e
            where e.taskId in :taskIds
              and e.eventType in :eventTypes
            group by e.taskId, e.eventType
            """)
    List<LatestEventTime> findLatestEventTimes(
            @Param("taskIds") List<Long> taskIds,
            @Param("eventTypes") List<TaskEventType> eventTypes);
}
