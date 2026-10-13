package com.szsemicon.hr.developmentlog.application;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证真实register-deployment.py脚本生成的JSON可被Java读取
 */
class DevelopmentLogQueryServiceSyntheticTest {

    @Test
    void readsRealSyntheticDeploymentHistoryWithoutIsCurrent() throws Exception {
        // 真实脚本生成的JSON结构（不含isCurrent字段）
        String syntheticJson = """
            {
              "deployments": [
                {
                  "deploymentId": "deploy-20261012-160000-800000",
                  "deployedAt": "2026-10-13T00:00:00+08:00",
                  "verifiedAt": "2026-10-13T00:01:00+08:00",
                  "version": "SYNTHETIC-READER-20261013",
                  "jarSha256": "89947f80192a9702f8778234ce806a83ad7c9eff712e11789032d2d56ab95eb8",
                  "title": "本地合成验证",
                  "description": "仅验证登记脚本与Java读取契约",
                  "details": "- 无生产部署",
                  "_frontendFingerprint": "4c8c5b2cf5cf67c5",
                  "_receiptReleaseName": "SYNTHETIC-READER-20261013"
                }
              ]
            }
            """;

        Path tempFile = Files.createTempFile("synthetic-test", ".json");
        try {
            Files.writeString(tempFile, syntheticJson);

            ObjectMapper objectMapper = new ObjectMapper();
            DevelopmentLogQueryService service = new DevelopmentLogQueryService(
                objectMapper,
                tempFile.toString()
            );

            List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments(null);

            assertThat(entries).hasSize(1);
            DevelopmentLogQueryService.DeploymentEntry entry = entries.get(0);
            assertThat(entry.deploymentId()).isEqualTo("deploy-20261012-160000-800000");
            assertThat(entry.version()).isEqualTo("SYNTHETIC-READER-20261013");
            assertThat(entry.title()).isEqualTo("本地合成验证");
            assertThat(entry.jarSha256()).isEqualTo("89947f80192a9702f8778234ce806a83ad7c9eff712e11789032d2d56ab95eb8");
            // 缺失isCurrent字段时，默认为非当前部署
            assertThat(entry.isCurrentDeployment()).isFalse();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
