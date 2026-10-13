-- Expand the deployable Seeyon OA metadata/runtime contract for the
-- screenshot-declared leave-revocation formmain_0370 table. This migration
-- only widens local fail-closed allowlists; it does not mark OA live metadata
-- or business semantics as verified.

ALTER TABLE oa_metadata_probe_run
    DROP CHECK ck_oa_probe_counts,
    ADD CONSTRAINT ck_oa_probe_counts CHECK (
        expected_table_count = 11
        AND present_table_count <= expected_table_count
        AND compatible_column_count <= expected_column_count
    );

ALTER TABLE oa_metadata_probe_table_result
    DROP CHECK ck_oa_probe_table_name,
    ADD CONSTRAINT ck_oa_probe_table_name CHECK (
        table_name IN (
            'formmain_0265', 'formmain_0170', 'formmain_0370',
            'formmain_0171', 'formson_0172', 'formmain_0251',
            'formson_0252', 'formmain_0201', 'formson_0202',
            'formmain_0203', 'formson_0204'
        )
    );

ALTER TABLE oa_runtime_table_contract
    DROP CHECK ck_oa_contract_form_kind,
    ADD CONSTRAINT ck_oa_contract_form_kind CHECK (
        form_kind IN (
            'TRIP', 'LEAVE', 'LEAVE_REVOCATION', 'OVERTIME',
            'OUTING', 'EXEMPT_PUNCH', 'PUNCH_CORRECTION'
        )
    );
