package com.devlink.server.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.repository.TaskMessageRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 서버 상태 대신 서명된 cursor에 조회 창을 담으며, 토큰·서명키는 응답에 포함하지 않는다. */
@Component
public class TaskMessageCursorCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper json;
    private final TaskMessageRepository repository;

    public TaskMessageCursorCodec(ObjectMapper json, TaskMessageRepository repository) {
        this.json = json;
        this.repository = repository;
    }

    public Cursor decode(String value, AgentPrincipal agent) {
        if (value == null) {
            return new Cursor(1, agent.agentId(), agent.ownerUserId(), agent.teamId(), null, null, 0, 0);
        }
        try {
            if (value.length() > 65536) throw new IllegalArgumentException();
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2 || parts[0].isEmpty()) throw new IllegalArgumentException();
            byte[] signature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(signature, sign(parts[0]))) throw new IllegalArgumentException();
            Cursor cursor = json.readerFor(Cursor.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(DECODER.decode(parts[0]));
            validate(cursor);
            // 현재 Agent의 권한으로만 조회하며 다른 Agent의 정상 cursor도 재사용할 수 없다.
            if (cursor.agentId() != agent.agentId() || cursor.ownerUserId() != agent.ownerUserId()
                    || cursor.teamId() != agent.teamId()) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
            return cursor;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    public String encode(Cursor cursor) {
        validate(cursor);
        try {
            String payload = ENCODER.encodeToString(json.writeValueAsBytes(cursor));
            return payload + "." + ENCODER.encodeToString(sign(payload));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("동기화 cursor를 만들 수 없습니다.", exception);
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(repository.cursorSigningKey(), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("동기화 cursor 서명을 만들 수 없습니다.", exception);
        }
    }

    private void validate(Cursor cursor) {
        if (cursor == null || cursor.version() != 1 || cursor.agentId() <= 0
                || cursor.ownerUserId() <= 0 || cursor.teamId() <= 0
                || cursor.afterTaskId() < 0 || cursor.afterSequence() < 0
                || (cursor.afterTaskId() == 0) != (cursor.afterSequence() == 0)
                || (cursor.windowSnapshot() == null && cursor.afterTaskId() != 0)
                || !validSnapshot(cursor.previousSnapshot()) || !validSnapshot(cursor.windowSnapshot())) {
            throw new IllegalArgumentException("올바르지 않은 동기화 cursor입니다.");
        }
    }

    private boolean validSnapshot(String snapshot) {
        return snapshot == null || snapshot.matches("[0-9]+:[0-9]+:([0-9]+(,[0-9]+)*)?");
    }

    /** 이전 완료 창과 현재 페이지 창을 분리해 여러 HTTP 요청 사이에도 조회 대상을 고정한다. */
    public record Cursor(int version, long agentId, long ownerUserId, long teamId,
            String previousSnapshot, String windowSnapshot, long afterTaskId, long afterSequence) {
        public Cursor page(String window, long taskId, long sequence) {
            return new Cursor(version, agentId, ownerUserId, teamId, previousSnapshot, window, taskId, sequence);
        }

        public Cursor complete(String window) {
            return new Cursor(version, agentId, ownerUserId, teamId, window, null, 0, 0);
        }
    }
}
