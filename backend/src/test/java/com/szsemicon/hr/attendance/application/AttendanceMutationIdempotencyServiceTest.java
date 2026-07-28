package com.szsemicon.hr.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AttendanceMutationIdempotencyServiceTest {

    private static final String GROUP_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String GROUP_REVISION_ID =
            "10000000-0000-0000-0000-000000000002";
    private static final String ASSIGNMENT_ID =
            "10000000-0000-0000-0000-000000000003";
    private static final String EMPLOYEE_ID =
            "10000000-0000-0000-0000-000000000004";
    private static final Instant NOW =
            Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void assignment_resource_lock_uses_group_revision_employee_timeline_order() {
        List<String> events = new ArrayList<>();
        AttendanceGroupRepository groups =
                mock(AttendanceGroupRepository.class);
        Assignment assignment = new Assignment(
                ASSIGNMENT_ID,
                GROUP_ID,
                EMPLOYEE_ID,
                LocalDate.parse("2026-08-01"),
                null,
                0,
                "test assignment",
                "actor-1",
                NOW,
                "actor-1",
                NOW);
        AttendanceGroup group = new AttendanceGroup(
                GROUP_ID,
                "legal-entity-1",
                "GROUP_A",
                GROUP_REVISION_ID,
                1,
                "Group A",
                "location-1",
                "location-revision-1",
                "calendar-1",
                "shift-1",
                LifecycleStatus.ACTIVE,
                LocalDate.parse("2026-01-01"),
                null,
                "digest",
                0,
                "test group",
                "actor-1",
                NOW,
                "actor-1",
                NOW);
        when(groups.findAssignment(ASSIGNMENT_ID)).thenAnswer(invocation -> {
            events.add("assignment-read");
            return Optional.of(assignment);
        });
        when(groups.findGroup(GROUP_ID)).thenAnswer(invocation -> {
            events.add("group-read");
            return Optional.of(group);
        });
        doAnswer(invocation -> {
            events.add("group-lock");
            return null;
        }).when(groups).lockGroup(GROUP_ID);
        doAnswer(invocation -> {
            events.add("group-revision-lock");
            return null;
        }).when(groups).lockGroupRevision(GROUP_REVISION_ID);
        doAnswer(invocation -> {
            events.add("employee-lock");
            return null;
        }).when(groups).lockEmployee(EMPLOYEE_ID);
        doAnswer(invocation -> {
            events.add("assignment-timeline-lock");
            return null;
        }).when(groups).lockAssignmentTimeline(ASSIGNMENT_ID);

        CurrentPrincipalProvider principals =
                mock(CurrentPrincipalProvider.class);
        when(principals.currentPrincipalId()).thenReturn("actor-1");
        AttendanceMutationIdempotencyService service =
                new AttendanceMutationIdempotencyService(
                        principals,
                        mock(CurrentCapabilityService.class),
                        mock(PeopleRepository.class),
                        groups,
                        mock(ShiftRepository.class),
                        mock(CalendarRepository.class),
                        new ResourceLockCapturingIdempotencyService(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        String result = service.execute(
                "UPDATE_ASSIGNMENT",
                "ATTENDANCE_GROUP_ASSIGNMENT",
                ASSIGNMENT_ID,
                "idempotency-key-0001",
                "command",
                0L,
                200,
                value -> value,
                String.class,
                () -> {
                    events.add("mutation");
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        assertThat(events).containsExactly(
                "assignment-read",
                "group-read",
                "group-lock",
                "group-read",
                "group-revision-lock",
                "employee-lock",
                "assignment-timeline-lock",
                "mutation");
    }

    private static final class ResourceLockCapturingIdempotencyService
            extends AttendanceSetupIdempotencyService {

        private ResourceLockCapturingIdempotencyService() {
            super(null, null, null, null, null);
        }

        @Override
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
            resourceLock.run();
            return operationBody.get();
        }
    }
}
