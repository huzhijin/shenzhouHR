-- V76: 免打卡修复回执表
-- request_id: 调用方提供的幂等键（同一物理请求的重试携带相同ID）
-- payload_digest: 规范化完整请求摘要（公司/员工/工号/免卡ID/source/expectedFrom/targetFrom/operation/批准依据）
-- approval_evidence_digest: 修复依据的稳定摘要（确认单据/批准材料的不可变内容）
-- company_id/employee_id: 申请时绑定，防止后续修改主体
-- before_digest/after_digest: 修复前后免卡行整行稳定哈希（含身份/公司/工号/source/from/to/created_at）
CREATE TABLE punch_exemption_repair_receipt (
    receipt_id                VARCHAR(64)   NOT NULL,
    request_id                VARCHAR(64)   NOT NULL COMMENT '调用方幂等键，固定UUID',
    exemption_id              VARCHAR(64)   NOT NULL,
    company_id                VARCHAR(36)   NOT NULL COMMENT '执行时绑定公司，不可变',
    employee_id               VARCHAR(36)   NOT NULL COMMENT '执行时绑定员工，不可变',
    operation                 VARCHAR(32)   NOT NULL,
    original_valid_from       DATETIME(6)   NOT NULL,
    repaired_valid_from       DATETIME(6)   NOT NULL,
    approval_evidence_digest  VARCHAR(128)  NOT NULL COMMENT '修复依据的规范化摘要',
    payload_digest            VARCHAR(128)  NOT NULL COMMENT '规范化完整请求摘要，防止同requestId不同payload重放',
    repaired_at               DATETIME(6)   NOT NULL,
    repaired_by               VARCHAR(64)   NOT NULL,
    before_digest             VARCHAR(128)  NOT NULL COMMENT '修复前免卡整行哈希',
    after_digest              VARCHAR(128)  NOT NULL COMMENT '修复后免卡整行哈希',
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_request_id (request_id),
    UNIQUE KEY uk_exemption_operation (exemption_id, operation),
    KEY idx_repaired_at (repaired_at),
    KEY idx_employee_id (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
