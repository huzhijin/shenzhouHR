package com.szsemicon.hr.punchimport.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public final class PunchImportStateMachine {

    private static final EnumMap<BatchState, Set<BatchState>> TRANSITIONS =
            new EnumMap<>(BatchState.class);

    static {
        TRANSITIONS.put(BatchState.DRAFT, EnumSet.of(BatchState.VALIDATING));
        TRANSITIONS.put(
                BatchState.VALIDATING,
                EnumSet.of(
                        BatchState.VALIDATION_FAILED,
                        BatchState.AWAITING_CONFIRMATION,
                        BatchState.BLOCKED_BY_FROZEN_PERIOD));
        TRANSITIONS.put(
                BatchState.VALIDATION_FAILED,
                EnumSet.of(BatchState.VALIDATING));
        TRANSITIONS.put(
                BatchState.BLOCKED_BY_FROZEN_PERIOD,
                EnumSet.of(BatchState.VALIDATING));
        TRANSITIONS.put(
                BatchState.AWAITING_CONFIRMATION,
                EnumSet.of(BatchState.VALIDATING, BatchState.PUBLISHING));
        TRANSITIONS.put(
                BatchState.PUBLISHING,
                EnumSet.of(
                        BatchState.PUBLISHED,
                        BatchState.PARTIALLY_PUBLISHED,
                        BatchState.PUBLISH_FAILED));
        TRANSITIONS.put(
                BatchState.PUBLISH_FAILED,
                EnumSet.of(BatchState.VALIDATING, BatchState.PUBLISHING));
        TRANSITIONS.put(BatchState.PUBLISHED, EnumSet.of(BatchState.VOIDED));
        TRANSITIONS.put(
                BatchState.PARTIALLY_PUBLISHED,
                EnumSet.of(BatchState.VOIDED));
        TRANSITIONS.put(BatchState.VOIDED, EnumSet.noneOf(BatchState.class));
    }

    private PunchImportStateMachine() {
    }

    public enum BatchState {
        DRAFT,
        VALIDATING,
        VALIDATION_FAILED,
        AWAITING_CONFIRMATION,
        BLOCKED_BY_FROZEN_PERIOD,
        PUBLISHING,
        PUBLISHED,
        PARTIALLY_PUBLISHED,
        PUBLISH_FAILED,
        VOIDED
    }

    public record RetryPrerequisites(
            boolean correctedInput,
            boolean periodReopened,
            boolean newPrecheckCompleted,
            boolean publicationRollbackConfirmed,
            boolean identicalIdempotentRequest) {

        public static RetryPrerequisites none() {
            return new RetryPrerequisites(false, false, false, false, false);
        }
    }

    public static Set<BatchState> allowedTargets(BatchState state) {
        return Set.copyOf(TRANSITIONS.get(state));
    }

    public static void requireTransition(BatchState from, BatchState to) {
        requireTransition(from, to, RetryPrerequisites.none());
    }

    public static void requireTransition(
            BatchState from,
            BatchState to,
            RetryPrerequisites prerequisites) {
        if (!TRANSITIONS.get(from).contains(to)) {
            throw new IllegalStateException(
                    "unsupported punch import transition: " + from + " -> " + to);
        }
        if (from == BatchState.VALIDATION_FAILED
                && (!prerequisites.correctedInput()
                || !prerequisites.newPrecheckCompleted())) {
            throw new IllegalStateException(
                    "validation retry requires corrected input and a new precheck");
        }
        if (from == BatchState.BLOCKED_BY_FROZEN_PERIOD
                && (!prerequisites.periodReopened()
                || !prerequisites.newPrecheckCompleted())) {
            throw new IllegalStateException(
                    "frozen-period retry requires reopen and a new precheck");
        }
        if (from == BatchState.PUBLISH_FAILED) {
            if (to == BatchState.VALIDATING
                    && !prerequisites.newPrecheckCompleted()) {
                throw new IllegalStateException(
                        "publish failure revalidation requires a new precheck");
            }
            if (to == BatchState.PUBLISHING
                    && (!prerequisites.publicationRollbackConfirmed()
                    || !prerequisites.identicalIdempotentRequest())) {
                throw new IllegalStateException(
                        "publish retry requires confirmed rollback and the identical idempotent request");
            }
        }
    }
}
