# V1.9 需求—测试追踪矩阵

## 需求—测试追踪矩阵

> 状态说明：`EXISTING` 表示当前已有相关骨架测试；`PLANNED` 表示后续实现波次必须新增；`EXTERNAL` 表示需要批准环境/样本才能关闭。当前未运行任何测试，本机 MySQL 验证为 `NOT_RUN`。

| 需求域 | 规格来源 | 验收 ID | 建议自动化测试 | 层级 | 波次 | 当前 |
|---|---|---|---|---|---|---|
| 本地账号密码 | PRD V1.9 2.5、本轮 3.1 | AC-AUTH-01～07、AC-AUTH-12 | `login_creates_secure_session`、`login_locks_after_configured_failures`、`password_change_revokes_sessions` | backend integration + browser | 1 | PLANNED |
| capability/数据范围 | PRD V1.9 2.4 | AC-AUTH-08～11 | `payroll_is_default_denied`、`self_endpoint_ignores_client_employee_id` | backend security | 1 | 部分 EXISTING |
| 规则生命周期 | 本轮 3.2 | AC-POL-01～07 | `policy_publish_rejects_scope_conflict`、`frozen_result_keeps_policy_snapshot` | domain + DB + API | 1 | PLANNED |
| 组织员工期初 | PRD V1.9 4、本轮 3.1 | AC-PEOPLE-01～05、AC-PEOPLE-09～11 | `people_import_requires_precheck`、`people_import_publish_is_idempotent`、`published_people_import_with_references_rejects_rollback`、`organization_sync_route_is_absent` | contract + DB + browser | 2 | PLANNED |
| 任职/工龄 | PRD V1.9 4.3 | AC-PEOPLE-06～08、AC-PEOPLE-12 | `rehire_creates_new_employment_period`、`termination_day_is_inside_half_open_employment_period`、`prior_service_change_is_audited` | domain + DB | 2 | PLANNED |
| 班组/班次/日历 | PRD V1.9 5 | AC-ATTSET-01～02 | `shift_versions_cover_december_31`、`new_group_enables_meal_deduction` | domain + API + UI | 3 | PLANNED |
| 迟到宽限 | 本轮 3.3.3 | AC-ATTSET-03～04 | `lateness_grace_boundary_and_cross_group_usage` | property + concurrency | 3/5 | PLANNED |
| 单边缺卡 | 本轮 3.3.4 | AC-ATTSET-05～06 | `single_sided_missing_punch_affects_only_segment` | domain golden case | 3/5 | PLANNED |
| 加班餐扣/跨日/证据优先级 | 本轮 3.3.5～7 | AC-ATTSET-07～09、AC-CALC-03～04 | `temporary_overtime_4759_is_accepted_and_4801_is_zero`、`overnight_punch_cannot_be_reused`、`same_level_evidence_conflict_is_not_last_write_wins` | domain property + golden | 3/5 | PLANNED |
| 离线打卡模板实物 | 本轮 3.6、03 专题 | AC-PUNCH-01、03 | `attendance_template_artifact_contract` | xlsx contract | 4 | artifact EXISTING；当前 NOT_RUN |
| 离线打卡下载/解析/mapping | 本轮 3.6、03 专题 | AC-PUNCH-01～05 | `attendance_template_download_matches_contract`、`mapping_profile_is_versioned` | API + xlsx + DB | 4 | feature PLANNED |
| 员工/任职匹配 | 03 专题 6 | AC-PUNCH-05 | `punch_match_prefers_employee_number_and_effective_employment` | domain + DB | 4 | PLANNED |
| 文件/记录幂等 | 03 专题 7 | AC-PUNCH-06～09 | `renamed_file_hash_does_not_republish`、`exact_api_excel_duplicate_to_one_event`、`near_duplicate_has_zero_active_events_until_review`、`near_duplicate_review_can_resolve_to_one_or_two_events` | DB integration | 4 | PLANNED |
| 批次/错误/部分发布 | 03 专题 4/8 | AC-PUNCH-10、AC-PUNCH-14～15 | `blocking_error_prevents_publish`、`partial_publish_retains_error_rows`、`validation_and_publish_failures_have_distinct_states` | API + DB + browser | 4 | PLANNED |
| 月结保护 | 03 专题 9 | AC-PUNCH-11～13 | `frozen_period_blocks_import_publish`、`void_creates_reversal_without_deleting_raw` | integration | 4/5 | PLANNED |
| 得力内部适配合同 | PRD V1.9 6.1 | AC-CALC-01～02、AC-SOURCE-01 | `deli_contract_fixture_appends_raw_fact_and_advances_watermark`、`deli_retry_resumes_committed_watermark` | synthetic contract + adapter | 4 | PLANNED |
| 得力真实联调 | PRD V1.9 6.1 | AC-CALC-01～02、AC-SOURCE-01 | 脱敏测试租户字段/水位/撤销联调报告 | approved external environment | 4 | EXTERNAL |
| OA 内部适配合同 | PRD V1.9 6.1 | AC-CALC-01～04、AC-SOURCE-01 | `oa_contract_fixture_reversal_creates_new_fact`、`overlap_is_split_deterministically` | synthetic contract + adapter | 4 | PLANNED |
| OA 真实联调 | PRD V1.9 6.1 | AC-CALC-01～04、AC-SOURCE-01 | 脱敏状态/撤销/补录字段与只读接入报告 | approved external environment | 4 | EXTERNAL |
| 证据解释 | PRD V1.9 6/7 | AC-CALC-05～07 | `daily_result_has_complete_evidence_trace`、`recalculation_is_reproducible` | integration + UI | 5 | PLANNED |
| 月结/重开 | PRD V1.9 7.7 | AC-CLOSE-01～04 | `close_blocks_unresolved_errors`、`reopen_keeps_old_snapshot` | DB + API + browser | 5 | PLANNED |
| 假别配置/取消/余额 | PRD V1.9 8.1、本轮 3.4 | AC-LEAVE-01～04 | `leave_policy_defaults_to_workdays`、`leave_cancellation_reverses_only_unconsumed_segments`、`leave_balance_never_becomes_negative` | domain + API + ledger | 6 | PLANNED |
| 年假资格与档位 | PRD V1.9 8.2、本轮 3.4 | AC-ANNUAL-01～06 | `annual_leave_qualification_is_separate_from_tier`、`anniversary_expires_old_grant_before_new_grant` | property + golden cases | 6 | PLANNED |
| 年假闰日/离职/再入职/版本 | PRD V1.9 8.2、12.1 | AC-ANNUAL-07～09 | `feb29_anniversary_defaults_to_feb28`、`termination_expires_balance_without_rewriting_history`、`rehire_does_not_revive_old_grant`、`frozen_anniversary_requires_reopen` | clocked domain + DB | 6 | PLANNED |
| 时间账户 | PRD V1.9 8、本轮 3.5 | AC-TIME-01～04 | `opening_balance_publish_is_idempotent`、`balance_equals_ledger_replay` | DB + property + xlsx | 6 | PLANNED |
| 员工今日/记录/假期解释 | PRD V1.9 9.2 | AC-SELF-01、AC-AUTH-09 | `employee_can_only_view_own_explanation`、`self_leave_balance_matches_ledger` | API security + E2E | 7 | PLANNED |
| 反馈闭环 | PRD V1.9 9.2 | AC-FEEDBACK-01 | `feedback_lifecycle_links_authorized_adjustment`、`feedback_text_is_output_encoded` | API + DB + E2E | 7 | PLANNED |
| 签到排行/留言隐私 | PRD V1.9 9.2～9.3、12.3 | AC-RANK-01～02 | `ranking_contract_excludes_sensitive_fields`、`user_can_delete_only_own_comment`、`reported_comment_is_audited` | contract + security + E2E | 7 | PLANNED |
| 制度中心 | PRD V1.9 9.2 | AC-POLICYCTR-01 | `policy_center_only_returns_published_applicable_versions` | API security + E2E | 7 | PLANNED |
| 位置核验 | PRD V1.9 9.4、12.3 | AC-LOCATION-01 | `location_view_requires_scope_capability_and_reason`、`unknown_coordinate_system_is_not_plotted` | security + audit + E2E | 7 | PLANNED |
| 公司/部门/个人看板 | PRD V1.9 9.3 | AC-DASH-01 | `dashboard_scope_and_freshness_contract`、`company_display_excludes_location_and_payroll` | API + E2E | 7 | PLANNED |
| 报表与导出 | PRD V1.9 9.3 | AC-REPORT-01 | `export_reauthorizes_on_create_and_download`、`export_over_50000_rows_is_async` | security + job + E2E | 6/7 | PLANNED |
| Open Design 接入 | Open Design V1.9、本轮 4 | AC-UI-01～07、AC-NFR-09 | route snapshot、axe、responsive screenshots、token lint、`session_expiry_clears_protected_view` | browser + static | 7 | VISUAL_CAPTURE_NOT_AVAILABLE |
| 薪资隐藏/预留 | 本轮 3.7 | AC-PAY-01～04 | `payroll_frontend_discoverability_is_zero`、`payroll_api_denies_before_query` | static + security + DB | 8 | 前端零入口 EXISTING；域 PLANNED |
| 日志/敏感信息 | 本轮 2.7～9 | AC-NFR-01～02 | secret scan、log capture、download authorization | security | 1～9 | 部分 EXISTING |
| 构建/契约/迁移 | 本轮实施原则 | AC-NFR-03～04 | `npm run check`、`./mvnw test`、OpenAPI diff、Flyway/MySQL matrix | CI | 每波 | 当前 NOT_RUN |
| 本机库、账号与凭据边界 | 本机 MySQL 计划 2～7 | AC-DB-01～03 | `local_mysql_scope_and_charset_contract`、`runtime_account_has_no_ddl_or_cross_schema_access`、`repository_and_runtime_outputs_contain_no_database_secret` | DB security + static | 每波前置 | PLANNED；当前 NOT_RUN |
| Flyway 空库/升级/幂等 | 本机 MySQL 计划 8～9/12 | AC-DB-04～07 | `v1_v2_checksums_are_immutable`、`fresh_test_database_migrates_from_v1`、`v2_snapshot_upgrades_without_data_loss`、`second_migrate_is_noop` | MySQL integration | 每波 | PLANNED；当前 NOT_RUN |
| 本机应用、API、权限与前端真实链路 | 本机 MySQL 计划 11～12 | AC-DB-08～13 | `spring_boot_runs_with_non_root_mysql_account`、`wave_write_then_read_uses_local_mysql`、`mysql_constraints_and_exact_ids_hold`、`unauthorized_request_does_not_mutate_mysql`、`frontend_api_proxy_renders_mysql_states`、`demo_and_real_modes_do_not_cross_write` | MySQL + API + browser | 每波 | PLANNED；当前 NOT_RUN |
| 测试库生命周期与 MySQL 8.4 发布复验 | 本机 MySQL 计划 11/13～14 | AC-DB-14～15 | `test_database_rebuild_is_name_guarded_and_reports_final_state`、MySQL 8.4 migration/lock/index/restore matrix | DB ops + release | 每波/上线 | PLANNED；8.4 EXTERNAL |
| 性能/容量/可用性 | PRD V1.9 11.2 | AC-NFR-05～07 | `50_concurrent_p95_profile`、50k export、source-lag percentiles、monthly recalculation load | performance + observability | 9 | test harness PLANNED；production baseline EXTERNAL |
| 恢复/浏览器兼容 | PRD V1.9 11.2 | AC-NFR-08～09 | isolated restore drill、Chrome/Edge/iOS Safari/Android Chrome matrix | ops + browser | 9 | harness PLANNED；environment EXTERNAL |
| 产品指标/审计事件 | PRD V1.9 11.4～11.5 | AC-METRIC-01～03、AC-AUDIT-01 | `opening_import_publish_rate_is_100_percent`、`freshness_p95_is_within_ten_minutes`、`late_close_emits_observable_alert`、audit schema snapshot、authorization block rate | analytics contract + DB | 1～9 | PLANNED |

## 2. 测试数据与时钟

- 全部员工、地点、设备、工资和假因使用合成标识。
- 所有时间边界测试注入可控 `Clock` 和显式 `ZoneId`；默认 `Asia/Shanghai`。
- 年假测试分别命名并断言普通日、月末、2 月 29 日默认映射 2 月 28 日、周年前日/当日、离职日/次日、再入职、规则切换和冻结重开；不得只用一个泛化用例覆盖全部边界。
- 外部契约测试只保存脱敏样本与字段摘要，不保存凭据或真实个人数据。
- 本机 MySQL 测试只使用 `shenzhou_hr_test` 合成数据；测试可重建该库，但必须用精确库名保护并报告结束状态。
- `shenzhou_hr_dev` 默认保留；任何删除、清空或重建必须先记录影响、备份校验和恢复步骤。

## 3. QA 先行规则

每个实现波次由 QA 先提交该波合同、领域和权限红测；实现提交必须证明同一命名测试在前态失败、实现后通过。测试作者和业务代码作者分离。

## 4. 完成声明规则

- 单元测试通过不能替代 MySQL、浏览器或真实外部联调。
- H2、demo/mock、旧 `target/` 和旧数据库报告均不能替代本轮本机 MySQL 证据。
- 本机 MySQL 通过不能替代 MySQL 8.4 LTS 上线复验；两类结果必须分别报告。
- 未执行的本机条目保持 `NOT_RUN`，缺少批准环境的生产/外部条目保持 `NOT_VERIFIED` 或 `EXTERNAL`。
- 契约桩通过只能写“合同测试通过”，不能写“致远/得力联调通过”。
- 静态响应式检查不能替代 `preview_confirm` 截图。
- 历史 `dist/`、`target/` 或旧测试报告不能作为本次通过证据。
