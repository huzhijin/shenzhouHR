package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.application.AttendancePolicyCommands.BindingCommand;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class AttendancePolicyImpactTokenService {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);

    private final Map<String, Preview> previews = new ConcurrentHashMap<>();
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttendancePolicyImpactTokenService(
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IssuedToken issue(
            String actorId,
            BindingCommand command,
            int groupCount,
            int assignmentCount) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TOKEN_LIFETIME);
        String token = tokenService.newOpaqueToken();
        previews.put(
                token,
                new Preview(
                        actorId,
                        requestDigest(command),
                        groupCount,
                        assignmentCount,
                        expiresAt));
        removeExpired(now);
        return new IssuedToken(token, expiresAt);
    }

    public void requireCurrent(
            String token,
            String actorId,
            BindingCommand command,
            int groupCount,
            int assignmentCount) {
        Preview preview = token == null ? null : previews.get(token);
        Instant now = clock.instant();
        if (preview == null
                || !now.isBefore(preview.expiresAt())
                || !tokenService.constantTimeEquals(
                        preview.actorId(), actorId)
                || !tokenService.constantTimeEquals(
                        preview.requestDigest(), requestDigest(command))
                || preview.groupCount() != groupCount
                || preview.assignmentCount() != assignmentCount) {
            throw AttendanceSetupRules.conflict(
                    "ATTENDANCE_POLICY_IMPACT_TOKEN_INVALID",
                    "影响预览已过期、范围已变化或与当前请求不一致");
        }
    }

    private String requestDigest(BindingCommand command) {
        try {
            return tokenService.digest(
                    objectMapper.writeValueAsString(command));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "attendance impact request serialization failed",
                    exception);
        }
    }

    private void removeExpired(Instant now) {
        previews.entrySet().removeIf(
                entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private record Preview(
            String actorId,
            String requestDigest,
            int groupCount,
            int assignmentCount,
            Instant expiresAt) {
    }
}
