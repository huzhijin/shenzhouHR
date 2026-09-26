package com.szsemicon.hr.evidenceingestion.adapter.web;

import com.szsemicon.hr.evidenceingestion.application.OaDocumentSyncApplicationService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary debug endpoint for triggering OA sync without authentication.
 *
 * <p><strong>WARNING:</strong> This controller is ONLY enabled when
 * {@code shenzhouhr.debug.oa-sync-endpoint=true}. It bypasses all
 * authentication and authorization. DO NOT enable in production.</p>
 *
 * <p>To use: {@code curl -X POST http://127.0.0.1:9090/api/debug/oa-sync}</p>
 */
@RestController
@RequestMapping("/api/debug")
@Profile("!prod & (dev | test)")
@ConditionalOnProperty(name = "shenzhouhr.debug.oa-sync-endpoint", havingValue = "true")
public class OaDebugSyncController {

    private final OaDocumentSyncApplicationService syncService;

    public OaDebugSyncController(OaDocumentSyncApplicationService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/oa-sync")
    public Map<String, String> triggerOaSync() {
        syncService.runScheduled();
        return Map.of(
                "status", "triggered",
                "message", "OA sync started for all active sources. Check logs for results."
        );
    }
}
