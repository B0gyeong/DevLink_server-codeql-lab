package com.devlink.server.service;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskQuestionOutboxWriter {
    private static final String TYPE = "TASK_QUESTION_CREATED";
    private static final String DESTINATION = "task-events";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public void enqueue(TaskEvent event, Long receiverAgentId) {
        UUID outboxId = UUID.randomUUID();
        var envelope = objectMapper.createObjectNode()
                .put("event_id", outboxId.toString())
                .put("type", TYPE)
                .put("task_id", event.getTaskId())
                .put("receiver_agent_id", receiverAgentId);
        envelope.putObject("payload")
                .put("task_event_id", event.getId().toString())
                .put("sequence", event.getSequence().toString())
                .put("event_type", event.getEventType().name());

        String taskId = event.getTaskId().toString();
        outbox.save(OutboxEvent.create(outboxId, TYPE, "TASK", taskId, DESTINATION, "TASK:" + taskId, envelope));
    }
}
