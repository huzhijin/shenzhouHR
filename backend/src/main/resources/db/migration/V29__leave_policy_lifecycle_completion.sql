-- Complete the annual-leave and eight leave-type policy lifecycles without
-- rewriting any published snapshot or historical leave/account fact.

ALTER TABLE annual_leave_policy_lifecycle_event
    DROP CHECK ck_annual_leave_policy_lifecycle_action,
    ADD CONSTRAINT ck_annual_leave_policy_lifecycle_action CHECK (
        action IN (
            'DRAFT_CREATED', 'VALIDATED', 'DRAFT_DISCARDED', 'PUBLISHED',
            'DEACTIVATE_SCHEDULED'
        )
    );

ALTER TABLE annual_leave_policy_idempotency_record
    DROP CHECK ck_annual_leave_policy_idempotency_operation,
    ADD CONSTRAINT ck_annual_leave_policy_idempotency_operation CHECK (
        operation IN (
            'CREATE_DRAFT', 'VALIDATE', 'PUBLISH', 'DISCARD_DRAFT',
            'SCHEDULE_DEACTIVATION'
        )
    );

ALTER TABLE leave_policy_revision
    MODIFY COLUMN published_at DATETIME(6) NULL,
    DROP CHECK ck_leave_policy_revision_status,
    ADD CONSTRAINT ck_leave_policy_revision_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'INACTIVE')
    );

ALTER TABLE leave_policy_lifecycle_event
    DROP CHECK ck_leave_policy_lifecycle_action,
    ADD CONSTRAINT ck_leave_policy_lifecycle_action CHECK (
        action IN (
            'DRAFT_CREATED', 'VALIDATED', 'DRAFT_DISCARDED', 'PUBLISHED',
            'DEACTIVATE_SCHEDULED'
        )
    );

ALTER TABLE leave_policy_idempotency_record
    DROP CHECK ck_leave_policy_idempotency_operation,
    ADD CONSTRAINT ck_leave_policy_idempotency_operation CHECK (
        operation IN (
            'CREATE_PUBLISHED_VERSION',
            'CREATE_DRAFT', 'VALIDATE', 'PUBLISH', 'DISCARD_DRAFT',
            'SCHEDULE_DEACTIVATION'
        )
    );
