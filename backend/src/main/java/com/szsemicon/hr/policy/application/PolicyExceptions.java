package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import java.util.List;

public final class PolicyExceptions {

    private PolicyExceptions() {
    }

    public static final class NotFound extends RuntimeException {

        public NotFound() {
            super("policy resource is not available");
        }
    }

    public static final class ValidationFailed extends RuntimeException {

        private final List<ValidationIssue> issues;

        public ValidationFailed(List<ValidationIssue> issues) {
            super("policy validation failed");
            this.issues = List.copyOf(issues);
        }

        public List<ValidationIssue> issues() {
            return issues;
        }
    }

    public static final class StaleVersion extends RuntimeException {

        public StaleVersion() {
            super("policy row version is stale");
        }
    }

    public static final class Conflict extends RuntimeException {

        private final String code;

        public Conflict(String code) {
            super("policy operation conflicts with current state");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
