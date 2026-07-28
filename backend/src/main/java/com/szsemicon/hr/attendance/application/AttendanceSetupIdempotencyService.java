package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AttendanceSetupIdempotencyService {

    private static final Duration STARTED_TAKEOVER_AFTER = Duration.ofMinutes(5);

    private final AttendanceSetupIdempotencyRepository repository;
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AttendanceIdempotencyReplayIndicator replayIndicator;

    public AttendanceSetupIdempotencyService(
            AttendanceSetupIdempotencyRepository repository,
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock,
            AttendanceIdempotencyReplayIndicator replayIndicator) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.replayIndicator = replayIndicator;
    }

    public <T> T execute(
            String actorId,
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            Object canonicalRequest,
            Runnable currentAccessCheck,
            Runnable resourceLock,
            int responseStatus,
            Function<T, String> responseResourceId,
            Class<T> responseType,
            Supplier<T> operationBody) {
        return execute(
                actorId,
                operation,
                resourceType,
                resourceId,
                idempotencyKey,
                canonicalRequest,
                currentAccessCheck,
                resourceLock,
                responseStatus,
                responseResourceId,
                ignored -> Map.of(),
                responseType,
                operationBody);
    }

    public <T> T execute(
            String actorId,
            String operation,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            Object canonicalRequest,
            Runnable currentAccessCheck,
            Runnable resourceLock,
            int responseStatus,
            Function<T, String> responseResourceId,
            Function<T, Map<String, String>> responseBusinessHeaders,
            Class<T> responseType,
            Supplier<T> operationBody) {
        String key = AttendanceSetupRules.idempotencyKey(idempotencyKey);
        String requestDigest = tokenService.digest(canonicalJson(canonicalRequest));
        if (currentAccessCheck == null || resourceLock == null) {
            throw new IllegalArgumentException(
                    "attendance idempotency access check and resource lock are required");
        }
        currentAccessCheck.run();
        repository.find(actorId, operation, resourceType, resourceId, key);
        resourceLock.run();
        currentAccessCheck.run();
        var existing = repository.find(
                actorId, operation, resourceType, resourceId, key).orElse(null);
        String recordId = UUID.randomUUID().toString();
        var now = clock.instant();
        if (existing != null) {
            T replay = completedReplay(existing, requestDigest, responseType);
            if (replay != null) {
                return replay;
            }
            if (!takeOver(existing, recordId, requestDigest, now)) {
                var latest = repository.find(
                        actorId, operation, resourceType, resourceId, key)
                        .orElse(existing);
                replay = completedReplay(latest, requestDigest, responseType);
                if (replay != null) {
                    return replay;
                }
                throw inProgress();
            }
        } else {
            repository.insertStarted(
                    recordId,
                    actorId,
                    operation,
                    resourceType,
                    resourceId,
                    key,
                    requestDigest,
                    now);
            var acquired = repository.find(
                            actorId, operation, resourceType, resourceId, key)
                    .orElseThrow(() -> new IllegalStateException(
                            "attendance idempotency acquisition disappeared"));
            if (!recordId.equals(acquired.recordId())) {
                T replay = completedReplay(
                        acquired, requestDigest, responseType);
                if (replay != null) {
                    return replay;
                }
                if (!takeOver(acquired, recordId, requestDigest, now)) {
                    throw inProgress();
                }
            }
        }
        T response = operationBody.get();
        String bodyJson = json(response);
        String responseId = responseResourceId.apply(response);
        if (responseId == null || responseId.isBlank()) {
            throw new IllegalStateException(
                    "attendance idempotency response resource id is blank");
        }
        Map<String, String> responseHeaders = responseHeaders(
                key, responseBusinessHeaders.apply(response));
        if (!repository.complete(
                recordId,
                requestDigest,
                responseStatus,
                json(responseHeaders),
                bodyJson,
                clock.instant())) {
            throw AttendanceSetupRules.conflict(
                    "IDEMPOTENCY_COMPLETION_CONFLICT",
                    "幂等响应持久化失败");
        }
        replayIndicator.mark(false, responseHeaders);
        return response;
    }

    private boolean takeOver(
            AttendanceSetupIdempotencyRepository.StoredResponse existing,
            String nextRecordId,
            String requestDigest,
            Instant now) {
        requireSameDigest(existing, requestDigest);
        if (!"STARTED".equals(existing.state())
                || existing.createdAt() == null
                || existing.createdAt().isAfter(
                        now.minus(STARTED_TAKEOVER_AFTER))) {
            return false;
        }
        return repository.takeOverStarted(
                existing.recordId(),
                nextRecordId,
                requestDigest,
                now.minus(STARTED_TAKEOVER_AFTER),
                now);
    }

    private <T> T completedReplay(
            AttendanceSetupIdempotencyRepository.StoredResponse existing,
            String requestDigest,
            Class<T> responseType) {
        requireSameDigest(existing, requestDigest);
        if (!"COMPLETED_SUCCESS".equals(existing.state())) {
            return null;
        }
        if (existing.responseBodyJson() == null
                || existing.responseStatus() == null
                || existing.responseHeadersJson() == null) {
            throw new IllegalStateException(
                    "completed attendance idempotency response is incomplete");
        }
        T response = read(existing.responseBodyJson(), responseType);
        replayIndicator.mark(
                true, storedResponseHeaders(existing.responseHeadersJson()));
        return response;
    }

    private Map<String, String> responseHeaders(
            String idempotencyKey,
            Map<String, String> businessHeaders) {
        if (businessHeaders == null) {
            throw new IllegalStateException(
                    "attendance idempotency response headers are null");
        }
        Map<String, String> headers =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Idempotency-Key", idempotencyKey);
        for (Map.Entry<String, String> entry : businessHeaders.entrySet()) {
            addResponseHeader(headers, entry.getKey(), entry.getValue());
        }
        return headers;
    }

    private Map<String, String> storedResponseHeaders(String json) {
        Object value = read(json, Object.class);
        if (!(value instanceof Map<?, ?> stored)) {
            throw new IllegalStateException(
                    "stored attendance idempotency response headers are invalid");
        }
        Map<String, String> headers =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<?, ?> entry : stored.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof String headerValue)) {
                throw new IllegalStateException(
                        "stored attendance idempotency response headers are invalid");
            }
            addResponseHeader(headers, name, headerValue);
        }
        return headers;
    }

    private void addResponseHeader(
            Map<String, String> headers, String name, String value) {
        if (name == null
                || name.isBlank()
                || value == null
                || name.indexOf('\r') >= 0
                || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
                || "Idempotency-Replayed".equalsIgnoreCase(name)) {
            throw new IllegalStateException(
                    "attendance idempotency response header is invalid");
        }
        String previous = headers.putIfAbsent(name, value);
        if (previous != null && !previous.equals(value)) {
            throw new IllegalStateException(
                    "attendance idempotency response header conflicts");
        }
    }

    private void requireSameDigest(
            AttendanceSetupIdempotencyRepository.StoredResponse existing,
            String requestDigest) {
        if (!existing.requestDigest().equals(requestDigest)) {
            throw AttendanceSetupRules.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于不同的请求");
        }
    }

    private RuntimeException inProgress() {
        return AttendanceSetupRules.conflict(
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "相同 Idempotency-Key 的请求仍在处理中");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "attendance idempotency serialization failed", exception);
        }
    }

    private String canonicalJson(Object value) {
        Object generic = objectMapper.convertValue(value, Object.class);
        return json(canonicalize(generic));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()),
                        canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> ordered = new ArrayList<>(collection.size());
            for (Object item : collection) {
                ordered.add(canonicalize(item));
            }
            return ordered;
        }
        return value;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "stored attendance idempotency response is invalid", exception);
        }
    }
}
