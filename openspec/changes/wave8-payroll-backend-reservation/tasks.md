## 1. Reservation Domain and Port

- [x] 1.1 Add validated payroll period, item-definition, employee-profile-reference, frozen-snapshot-reference, and calculation-result-reference domain types without monetary/statutory/payment fields.
- [x] 1.2 Add the application-owned read-only `FrozenAttendanceSnapshotPort` and immutable W5 snapshot contract with no adapter implementation.
- [x] 1.3 Add domain boundary tests for precise IDs, periods, versions, states, unique item codes, digests, and forbidden scope.

## 2. Default-Off and Default-Deny Access Boundary

- [ ] 2.1 Add the default-false `shenzhouhr.payroll-reservation.enabled` configuration and prove missing/false configuration stays disabled.
- [ ] 2.2 Add `PAYROLL:RESERVATION_READ` without grants and keep every `PAYROLL:*` code out of ordinary capability, role, account, and menu responses.
- [ ] 2.3 Implement the ordered feature → capability → real-adapter → snapshot validation gate and stable fail-closed errors.
- [ ] 2.4 Add the narrow payroll denial audit port and production adapter using stable, payload-free audit fields.

## 3. Security, Audit, and Isolation Verification

- [ ] 3.1 Add negative tests for feature-off, missing capability, missing W5 adapter, and invalid snapshot state/version/digest/period, including no-later-dependency assertions.
- [ ] 3.2 Add audit negative tests that prove exactly one allowlisted denial event and no payload, capability-list, exception, secret, or sensitive-value leakage.
- [ ] 3.3 Add architecture tests for payroll layer isolation, no inward dependency from other business packages, and zero W5 snapshot-port implementations.
- [ ] 3.4 Add a dependency-free Unicode NFKC/case-fold verifier and unit tests for frontend source/dist zero discoverability, backend REST/OpenAPI absence, and prohibited scope.

## 4. Independent Phase Verification and Documentation

- [ ] 4.1 Document the feature flag, fail-closed behavior, W5 integration block, rollback, and explicit non-goals without adding any frontend or public API artifact.
- [ ] 4.2 Run focused W8 tests, the Unicode discoverability verifier, the full backend test suite, and repository diff scans proving frontend/OpenAPI/public-route scope is unchanged.

## 5. W5 FINAL Integration (Blocked)

- [ ] 5.1 After W5 FINAL is synchronized, reconcile its actual close-snapshot ID/version/reopen/digest contract with the reserved read-only port.
- [ ] 5.2 Implement the real W5 close-snapshot adapter and integration tests in a separate commit; do not add any fallback adapter or block W9 while this remains incomplete.
