package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.application.AttendanceIdempotencyReplayIndicator;
import com.szsemicon.hr.attendance.application.AttendanceSetupIdempotencyRepository;
import com.szsemicon.hr.attendance.application.AttendanceSetupIdempotencyService;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AttendanceSetupIdempotencyServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-27T12:00:00Z");

    private final SecurityTokenService tokens = new SecurityTokenService();
    private final FakeRepository repository = new FakeRepository();
    private final AttendanceSetupIdempotencyService service =
            new AttendanceSetupIdempotencyService(
                    repository,
                    tokens,
                    JsonMapper.builder().findAndAddModules().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new AttendanceIdempotencyReplayIndicator());

    @Test
    void replays_the_exact_committed_body_and_rejects_changed_digest() {
        AtomicInteger invocations = new AtomicInteger();

        TestResponse first = execute(
                Map.of("value", "same"),
                () -> new TestResponse(
                        "resource-1", invocations.incrementAndGet()));
        TestResponse replay = execute(
                Map.of("value", "same"),
                () -> new TestResponse(
                        "resource-2", invocations.incrementAndGet()));

        assertThat(first).isEqualTo(new TestResponse("resource-1", 1));
        assertThat(replay).isEqualTo(first);
        assertThat(invocations).hasValue(1);
        assertThat(repository.stored.state()).isEqualTo("COMPLETED_SUCCESS");
        assertThat(repository.stored.responseStatus()).isEqualTo(200);
        assertThat(repository.stored.responseHeadersJson())
                .contains(
                        "\"ETag\":\"\\\"1\\\"\"",
                        "\"Idempotency-Key\":\"idempotency-key-0001\"");

        assertThatThrownBy(() -> execute(
                Map.of("value", "changed"),
                () -> new TestResponse("must-not-run", 99)))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void takes_over_only_a_stale_started_record_with_the_same_digest() {
        String requestDigest = tokens.digest("{\"value\":\"same\"}");
        repository.stored = new AttendanceSetupIdempotencyRepository.StoredResponse(
                "stale-record",
                requestDigest,
                "STARTED",
                null,
                null,
                null,
                NOW.minusSeconds(301));

        TestResponse response = execute(
                Map.of("value", "same"),
                () -> new TestResponse("successor", 1));

        assertThat(response.resourceId()).isEqualTo("successor");
        assertThat(repository.takeovers).isEqualTo(1);
        assertThat(repository.stored.state()).isEqualTo("COMPLETED_SUCCESS");
        assertThat(repository.stored.recordId()).isNotEqualTo("stale-record");
    }

    @Test
    void a_fresh_started_record_is_not_executed_twice() {
        String requestDigest = tokens.digest("{\"value\":\"same\"}");
        repository.stored = new AttendanceSetupIdempotencyRepository.StoredResponse(
                "active-record",
                requestDigest,
                "STARTED",
                null,
                null,
                null,
                NOW.minusSeconds(299));

        assertThatThrownBy(() -> execute(
                Map.of("value", "same"),
                () -> new TestResponse("must-not-run", 99)))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        assertThat(repository.takeovers).isZero();
    }

    @Test
    void rechecks_current_access_after_the_resource_lock_before_replay() {
        execute(
                Map.of("value", "same"),
                () -> new TestResponse("resource-1", 1));
        repository.events.clear();

        TestResponse replay = execute(
                Map.of("value", "same"),
                () -> new TestResponse("must-not-run", 99));

        assertThat(replay).isEqualTo(new TestResponse("resource-1", 1));
        assertThat(repository.events).containsExactly(
                "access-check",
                "ledger-find",
                "resource-lock",
                "access-check",
                "ledger-find");
    }

    private TestResponse execute(
            Object request,
            java.util.function.Supplier<TestResponse> body) {
        return service.execute(
                "actor-1",
                "UPDATE_RESOURCE",
                "RESOURCE",
                "resource-1",
                "idempotency-key-0001",
                request,
                () -> repository.events.add("access-check"),
                () -> repository.events.add("resource-lock"),
                200,
                TestResponse::resourceId,
                response -> Map.of(
                        "ETag", "\"" + response.value() + "\""),
                TestResponse.class,
                body);
    }

    public record TestResponse(String resourceId, int value) {
    }

    private static final class FakeRepository
            implements AttendanceSetupIdempotencyRepository {

        private StoredResponse stored;
        private int takeovers;
        private final List<String> events = new ArrayList<>();

        @Override
        public Optional<StoredResponse> find(
                String actorId,
                String operation,
                String resourceType,
                String resourceId,
                String idempotencyKey) {
            events.add("ledger-find");
            return Optional.ofNullable(stored);
        }

        @Override
        public void insertStarted(
                String recordId,
                String actorId,
                String operation,
                String resourceType,
                String resourceId,
                String idempotencyKey,
                String requestDigest,
                Instant createdAt) {
            if (stored == null) {
                stored = new StoredResponse(
                        recordId,
                        requestDigest,
                        "STARTED",
                        null,
                        null,
                        null,
                        createdAt);
            }
        }

        @Override
        public boolean takeOverStarted(
                String currentRecordId,
                String nextRecordId,
                String requestDigest,
                Instant staleBefore,
                Instant createdAt) {
            if (stored == null
                    || !stored.recordId().equals(currentRecordId)
                    || !stored.requestDigest().equals(requestDigest)
                    || !"STARTED".equals(stored.state())
                    || stored.createdAt().isAfter(staleBefore)) {
                return false;
            }
            stored = new StoredResponse(
                    nextRecordId,
                    requestDigest,
                    "STARTED",
                    null,
                    null,
                    null,
                    createdAt);
            takeovers += 1;
            return true;
        }

        @Override
        public boolean complete(
                String recordId,
                String requestDigest,
                int responseStatus,
                String responseHeadersJson,
                String responseBodyJson,
                Instant completedAt) {
            if (stored == null
                    || !stored.recordId().equals(recordId)
                    || !stored.requestDigest().equals(requestDigest)
                    || !"STARTED".equals(stored.state())) {
                return false;
            }
            stored = new StoredResponse(
                    recordId,
                    requestDigest,
                    "COMPLETED_SUCCESS",
                    responseStatus,
                    responseHeadersJson,
                    responseBodyJson,
                    stored.createdAt());
            return true;
        }
    }
}
