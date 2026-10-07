package com.szsemicon.hr.identityaccess.interfaces.rest;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.identityaccess.application.AuthenticationService;
import com.szsemicon.hr.identityaccess.application.AuthenticationService.LoginResult;
import com.szsemicon.hr.identityaccess.application.AuthenticationService.SessionIdentity;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.security.SessionAuthenticationFilter;
import com.szsemicon.hr.shared.security.SessionAuthenticationFilter.SessionSecurityDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final SecurityTokenService tokenService;
    private final AuditService auditService;
    private final boolean secureCookie;
    private final Duration absoluteTimeout;

    public AuthenticationController(
            AuthenticationService authenticationService,
            SecurityTokenService tokenService,
            AuditService auditService,
            @Value("${shenzhouhr.security.session.cookie-secure:true}") boolean secureCookie,
            @Value("${shenzhouhr.security.session.absolute-timeout:PT12H}") Duration absoluteTimeout) {
        this.authenticationService = authenticationService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.secureCookie = secureCookie;
        this.absoluteTimeout = absoluteTimeout;
    }

    @PostMapping("/login")
    ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authenticationService.login(request.username(), request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.rawToken()).toString())
                .header("X-CSRF-TOKEN", tokenService.csrfToken(result.rawToken()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toView(result));
    }

    @GetMapping("/session")
    ResponseEntity<SessionView> session(HttpServletRequest request) {
        Object authenticatedIdentity =
                request.getAttribute(SessionAuthenticationFilter.SESSION_IDENTITY_ATTRIBUTE);
        SessionIdentity identity;
        if (authenticatedIdentity instanceof SessionIdentity sessionIdentity) {
            identity = sessionIdentity;
        } else {
            String rawToken = SessionAuthenticationFilter.findCookie(
                    request,
                    SessionAuthenticationFilter.COOKIE_NAME);
            identity = authenticationService.currentSession(rawToken == null ? "" : rawToken);
        }
        String rawToken = SessionAuthenticationFilter.findCookie(
                request,
                SessionAuthenticationFilter.COOKIE_NAME);
        return ResponseEntity.ok()
                .header("X-CSRF-TOKEN", tokenService.csrfToken(rawToken))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toView(identity));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(Authentication authentication) {
        SessionSecurityDetails details = sessionDetails(authentication);
        authenticationService.logout(details.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
                .build();
    }

    @PostMapping("/password/change")
    ResponseEntity<Void> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            Authentication authentication) {
        SessionSecurityDetails details = sessionDetails(authentication);
        authenticationService.changePassword(
                request.currentPassword(),
                request.newPassword(),
                false,
                details.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
                .build();
    }

    @PostMapping("/password/first-change")
    ResponseEntity<Void> firstPasswordChange(
            @Valid @RequestBody PasswordChangeRequest request,
            Authentication authentication) {
        SessionSecurityDetails details = sessionDetails(authentication);
        authenticationService.changePassword(
                request.currentPassword(),
                request.newPassword(),
                true,
                details.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
                .build();
    }

    @PostMapping("/password-reset-requests")
    ResponseEntity<AcceptedOperation> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        authenticationService.requestPasswordReset(request.username());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AcceptedOperation(true, auditService.currentCorrelationId()));
    }

    @PostMapping("/password-resets")
    ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetCompletionRequest request) {
        authenticationService.resetPassword(request.grant(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    ResponseEntity<Void> revokeSession(
            @org.springframework.web.bind.annotation.PathVariable String sessionId,
            @Valid @RequestBody ReasonRequest request) {
        authenticationService.revokeCurrentAccountSession(sessionId, request.reason());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie sessionCookie(String rawToken) {
        return ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(absoluteTimeout)
                .build();
    }

    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private static SessionSecurityDetails sessionDetails(Authentication authentication) {
        if (authentication == null
                || !(authentication.getDetails() instanceof SessionSecurityDetails details)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "session-backed authentication is required");
        }
        return details;
    }

    private static SessionView toView(LoginResult result) {
        return toView(new SessionIdentity(result.session(), result.account(), result.capabilities()));
    }

    private static SessionView toView(SessionIdentity identity) {
        List<String> availableCapabilities = identity.account().firstPasswordChangeRequired()
                ? List.of()
                : identity.capabilities();
        return new SessionView(
                identity.session().sessionId(),
                identity.account().accountId(),
                identity.account().username(),
                identity.account().displayName(),
                identity.session().status(),
                identity.account().firstPasswordChangeRequired(),
                identity.session().createdAt(),
                identity.session().lastSeenAt(),
                identity.session().idleExpiresAt(),
                identity.session().absoluteExpiresAt(),
                availableCapabilities,
                menu(availableCapabilities));
    }

    public static List<MenuItem> menu(List<String> capabilities) {
        List<MenuItem> menu = new ArrayList<>();
        boolean canReadOrganizationDashboard = capabilities.contains(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ);
        boolean canReadSelfAttendance = capabilities.contains(
                CapabilityCodes.ATTENDANCE_SELF_READ);
        if (canReadOrganizationDashboard) {
            menu.add(new MenuItem(
                    "workbench", "考勤工作台", "/workbench"));
        } else if (canReadSelfAttendance) {
            /*
             * /workbench is capability-sensitive: personal accounts receive a
             * self-scoped projection, while administrators keep the organization
             * dashboard. Keeping this item first makes login land on the correct
             * home without widening either data scope.
             */
            menu.add(new MenuItem(
                    "personal-workbench", "我的考勤工作台", "/workbench"));
        }
        if (canReadSelfAttendance) {
            menu.add(new MenuItem(
                    "my-attendance", "我的考勤", "/me/today"));
        }
        if (capabilities.contains(
                CapabilityCodes.LEAVE_SELF_READ)) {
            menu.add(new MenuItem(
                    "my-leave", "我的假期", "/me/leave"));
        }
        if (capabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_READ)) {
            menu.add(new MenuItem(
                    "attendance-reports",
                    "考勤报表",
                    "/attendance/reports"));
        }
        if (capabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ)) {
            menu.add(new MenuItem(
                    "attendance-query-exceptions",
                    "异常总览",
                    "/attendance/queries/exceptions"));
            menu.add(new MenuItem(
                    "attendance-query-leave",
                    "请假统计",
                    "/attendance/queries/leave"));
            menu.add(new MenuItem(
                    "attendance-query-leave-summary",
                    "请假汇总",
                    "/attendance/queries/leave-summary"));
            menu.add(new MenuItem(
                    "attendance-query-overtime",
                    "加班统计",
                    "/attendance/queries/overtime"));
            menu.add(new MenuItem(
                    "attendance-query-overtime-daily",
                    "加班日报",
                    "/attendance/queries/overtime-daily"));
            menu.add(new MenuItem(
                    "attendance-query-finance-overtime",
                    "每日加班查询",
                    "/attendance/queries/finance-overtime"));
            menu.add(new MenuItem(
                    "attendance-query-overtime-fee-daily",
                    "每日加班费查询",
                    "/attendance/queries/overtime-fee-daily"));
            menu.add(new MenuItem(
                    "attendance-query-overtime-voluntary-daily",
                    "每日义务加班查询",
                    "/attendance/queries/overtime-voluntary-daily"));
            menu.add(new MenuItem(
                    "attendance-query-overtime-comp-daily",
                    "每日调休查询",
                    "/attendance/queries/overtime-comp-daily"));
            menu.add(new MenuItem(
                    "attendance-query-absence-stat",
                    "旷工统计表",
                    "/attendance/queries/absence-stat"));
            menu.add(new MenuItem(
                    "attendance-query-leave-stat",
                    "请假统计表",
                    "/attendance/queries/leave-stat"));
            menu.add(new MenuItem(
                    "attendance-query-daily-journal",
                    "考勤日报",
                    "/attendance/queries/daily-journal"));
            menu.add(new MenuItem(
                    "attendance-query-makeup",
                    "补签",
                    "/attendance/queries/makeup"));
            menu.add(new MenuItem(
                    "attendance-query-work-hours",
                    "月度工时统计表",
                    "/attendance/queries/work-hours"));
            menu.add(new MenuItem(
                    "attendance-query-late",
                    "迟到统计",
                    "/attendance/queries/late"));
            menu.add(new MenuItem(
                    "attendance-query-missed-punch",
                    "忘打卡",
                    "/attendance/queries/missed-punch"));
            menu.add(new MenuItem(
                    "attendance-query-missed-punch-stat",
                    "忘打卡统计表",
                    "/attendance/queries/missed-punch-stat"));
            menu.add(new MenuItem(
                    "attendance-query-attendance-rate",
                    "出勤率",
                    "/attendance/queries/attendance-rate"));
            menu.add(new MenuItem(
                    "attendance-query-annual-leave",
                    "年休假",
                    "/attendance/queries/annual-leave"));
            menu.add(new MenuItem(
                    "attendance-query-annual-leave-stat",
                    "年假统计表",
                    "/attendance/queries/annual-leave-stat"));
            menu.add(new MenuItem(
                    "attendance-query-time-off",
                    "调休额度",
                    "/attendance/queries/time-off"));
            menu.add(new MenuItem(
                    "attendance-query-time-off-stat",
                    "调休统计表",
                    "/attendance/queries/time-off-stat"));
            menu.add(new MenuItem(
                    "attendance-query-time-off-daily",
                    "调休日报",
                    "/attendance/queries/time-off-daily"));
            menu.add(new MenuItem(
                    "attendance-query-matrix",
                    "考勤明细",
                    "/attendance/queries/matrix"));
        }
        if (capabilities.contains("POLICY:READ")) {
            menu.add(new MenuItem("rules", "规则设置", "/rules"));
        }
        if (capabilities.contains("ACCOUNT:READ")) {
            menu.add(new MenuItem("accounts", "账号管理", "/access/accounts"));
        }
        if (capabilities.contains("ROLE:READ")) {
            menu.add(new MenuItem("roles", "角色权限", "/access/roles"));
        }
        if (capabilities.contains("AUDIT:READ")) {
            menu.add(new MenuItem("audit", "操作记录", "/access/audit"));
        }
        if (capabilities.contains("PEOPLE_IMPORT:READ")) {
            menu.add(new MenuItem("people-import", "导入人员", "/people/import"));
        }
        if (capabilities.contains("ORGANIZATION:READ")
                || capabilities.contains("MASTER_DATA:READ")) {
            menu.add(new MenuItem(
                    "people-organization", "部门与组织", "/people/organization"));
        }
        if (capabilities.contains("EMPLOYEE:READ")
                || capabilities.contains("MASTER_DATA:READ")) {
            menu.add(new MenuItem("people-employees", "员工", "/people/employees"));
        }
        if (capabilities.contains("ATTENDANCE_SETUP:READ")) {
            menu.add(new MenuItem(
                    "attendance-groups", "考勤组", "/rules/attendance-groups"));
            menu.add(new MenuItem("attendance-shifts", "班次", "/rules/shifts"));
            menu.add(new MenuItem("attendance-calendars", "工作日历", "/rules/calendars"));
            menu.add(new MenuItem(
                    "attendance-policies", "考勤规则", "/rules/attendance-policy"));
        }
        if (capabilities.contains("ATTENDANCE_SOURCE:READ")) {
            menu.add(new MenuItem(
                    "attendance-sources-online", "考勤机数据", "/sources/online"));
            menu.add(new MenuItem(
                    "attendance-sources-oa", "OA 单据", "/sources/oa"));
            menu.add(new MenuItem(
                    "attendance-source-jobs", "同步记录", "/sources/jobs"));
        }
        if (capabilities.contains("ATTENDANCE_PUNCH_IMPORT:READ")) {
            menu.add(new MenuItem(
                    "attendance-punch-imports",
                    "导入打卡文件",
                    "/sources/attendance-excel"));
        }
        return List.copyOf(menu);
    }

    public record LoginRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 256) String password) {
    }

    public record PasswordChangeRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(min = 12, max = 256) String newPassword) {
    }

    public record PasswordResetRequest(@NotBlank @Size(max = 128) String username) {
    }

    public record PasswordResetCompletionRequest(
            @NotBlank @Size(min = 32, max = 512) String grant,
            @NotBlank @Size(min = 12, max = 256) String newPassword) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record AcceptedOperation(boolean accepted, String correlationId) {
    }

    public record SessionView(
            String sessionId,
            String accountId,
            String username,
            String displayName,
            String status,
            boolean firstPasswordChangeRequired,
            Instant issuedAt,
            Instant lastSeenAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            List<String> capabilities,
            List<MenuItem> menu) {
    }

    public record MenuItem(String key, String label, String path) {
    }
}
