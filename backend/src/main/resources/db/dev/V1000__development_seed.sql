-- 仅供 dev profile 使用的合成数据，不得提升到测试、预发布或生产环境。
INSERT INTO legal_entity (legal_entity_id, code, name, status) VALUES
    ('30000000-0000-0000-0000-000000000001', 'SZSC', '江苏神州半导体科技股份有限公司', 'ACTIVE');

INSERT INTO organization_identity (organization_id, legal_entity_id, identity_status) VALUES
    ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'ACTIVE');

INSERT INTO organization_version (
    organization_version_id,
    organization_id,
    parent_organization_id,
    code,
    name,
    org_type,
    effective_from,
    effective_to,
    formal_sync_batch_id
) VALUES (
    '50000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    NULL,
    'SZSC',
    '江苏神州半导体科技股份有限公司',
    'COMPANY',
    '2026-01-01 00:00:00.000000',
    NULL,
    '60000000-0000-0000-0000-000000000001'
);

INSERT INTO organization_current_projection (
    organization_id,
    current_version_id,
    projection_batch_id,
    projected_at
) VALUES (
    '40000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000001',
    '2026-07-20 00:00:00.000000'
);

INSERT INTO organization_current_closure (
    ancestor_organization_id,
    descendant_organization_id,
    depth,
    projection_batch_id
) VALUES (
    '40000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    0,
    '60000000-0000-0000-0000-000000000001'
);

INSERT INTO organization_source_binding (
    source_binding_id,
    organization_id,
    source_system,
    source_org_id,
    effective_from,
    effective_to,
    confirmation_ref
) VALUES (
    '70000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    'SEEYON',
    '92233720368547758071234567890',
    '2026-01-01 00:00:00.000000',
    NULL,
    'SYNTHETIC-DEVELOPMENT-DATA'
);

INSERT INTO auth_principal (principal_id, employee_id, status) VALUES
    ('80000000-0000-0000-0000-000000000001', NULL, 'ACTIVE');

INSERT INTO auth_data_scope (
    scope_id,
    scope_type,
    legal_entity_id,
    organization_id,
    include_descendants,
    valid_from,
    valid_to
) VALUES (
    '90000000-0000-0000-0000-000000000001',
    'LEGAL_ENTITY',
    '30000000-0000-0000-0000-000000000001',
    NULL,
    TRUE,
    '2026-01-01 00:00:00.000000',
    NULL
);

INSERT INTO auth_principal_role_assignment (
    assignment_id,
    principal_id,
    role_id,
    data_scope_id,
    valid_from,
    valid_to,
    assigned_by,
    reason
) VALUES (
    'a0000000-0000-0000-0000-000000000001',
    '80000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '90000000-0000-0000-0000-000000000001',
    '2026-01-01 00:00:00.000000',
    NULL,
    NULL,
    'SYNTHETIC DEVELOPMENT ACCESS'
);
