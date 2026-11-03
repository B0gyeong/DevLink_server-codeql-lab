package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskMessageResponse;
import com.devlink.server.dto.task.TaskQuestionRequest;
import com.devlink.server.service.TaskQuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskQuestionController {
    private final TaskQuestionService questions;

    @PostMapping(path = "/{taskId}/questions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TaskMessageResponse>> create(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal AgentPrincipal principal,
            @Valid @RequestBody TaskQuestionRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        TaskMessageResponse response = questions.create(taskId, principal.agentId(), request);
        return ResponseEntity.status(response.replayed() ? 200 : 201)
                .body(ApiResponse.success(response));
    }
}
