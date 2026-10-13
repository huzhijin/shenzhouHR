package com.szsemicon.hr.developmentlog.application;

import tools.jackson.databind.ObjectMapper;
import com.szsemicon.hr.shared.web.ApiProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevelopmentLogQueryServiceTest {

    @TempDir
    Path tempDir;

    private DevelopmentLogQueryService service;
    private Path historyFile;

    @BeforeEach
    void setUp() {
        historyFile = tempDir.resolve("deployment-history.json");
        ObjectMapper objectMapper = new ObjectMapper();
        service = new DevelopmentLogQueryService(objectMapper, historyFile.toString());
    }

    @Test
    void returnsEmptyListWhenFileDoesNotExist() {
        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments(null);
        assertThat(entries).isEmpty();
    }

    @Test
    void readsAndSortsDeploymentsDescendingByTime() throws Exception {
        String json = "{\"deployments\":[" +
                "{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\"," +
                "\"jarSha256\":\"" + "a".repeat(64) + "\",\"title\":\"First\",\"description\":\"First deployment\"," +
                "\"details\":\"- Feature A\",\"isCurrent\":false}," +
                "{\"deploymentId\":\"deploy-2\",\"deployedAt\":\"2026-10-12T15:30:00Z\",\"version\":\"v1.1.0\"," +
                "\"jarSha256\":\"" + "b".repeat(64) + "\",\"title\":\"Second\",\"description\":\"Second deployment\"," +
                "\"details\":\"- Feature B\",\"isCurrent\":true}," +
                "{\"deploymentId\":\"deploy-3\",\"deployedAt\":\"2026-10-11T08:00:00Z\",\"version\":\"v1.0.1\"," +
                "\"jarSha256\":\"" + "c".repeat(64) + "\",\"title\":\"Patch\",\"description\":\"Bug fix\"," +
                "\"details\":\"- Fix bug\",\"isCurrent\":false}" +
                "]}";
        Files.writeString(historyFile, json);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments(null);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).version()).isEqualTo("v1.1.0");
        assertThat(entries.get(1).version()).isEqualTo("v1.0.1");
        assertThat(entries.get(2).version()).isEqualTo("v1.0.0");
    }

    @Test
    void filtersEntriesByVersionSearch() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\",\"jarSha256\":\"" + "a".repeat(64) + "\",\"title\":\"First\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false},{\"deploymentId\":\"deploy-2\",\"deployedAt\":\"2026-10-12T15:30:00Z\",\"version\":\"v1.1.0\",\"jarSha256\":\"" + "b".repeat(64) + "\",\"title\":\"Second\",\"description\":\"\",\"details\":\"\",\"isCurrent\":true}]}";
        Files.writeString(historyFile, json);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments("v1.1");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).version()).isEqualTo("v1.1.0");
    }

    @Test
    void filtersEntriesByTitleSearch() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\",\"jarSha256\":\"" + "a".repeat(64) + "\",\"title\":\"Bug Fix Release\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false},{\"deploymentId\":\"deploy-2\",\"deployedAt\":\"2026-10-12T15:30:00Z\",\"version\":\"v1.1.0\",\"jarSha256\":\"" + "b".repeat(64) + "\",\"title\":\"Feature Release\",\"description\":\"\",\"details\":\"\",\"isCurrent\":true}]}";
        Files.writeString(historyFile, json);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments("feature");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).title()).isEqualTo("Feature Release");
    }

    @Test
    void filtersEntriesByDateSearch() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\",\"jarSha256\":\"" + "a".repeat(64) + "\",\"title\":\"First\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false},{\"deploymentId\":\"deploy-2\",\"deployedAt\":\"2026-10-12T15:30:00Z\",\"version\":\"v1.1.0\",\"jarSha256\":\"" + "b".repeat(64) + "\",\"title\":\"Second\",\"description\":\"\",\"details\":\"\",\"isCurrent\":true}]}";
        Files.writeString(historyFile, json);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments("2026-10-12");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).version()).isEqualTo("v1.1.0");
    }

    @Test
    void throwsApiProblemExceptionWhenRootIsNull() throws Exception {
        Files.writeString(historyFile, "null");

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("部署历史文件格式错误");
    }

    @Test
    void throwsApiProblemExceptionWhenDeploymentsIsNull() throws Exception {
        String json = "{\"deployments\":null}";
        Files.writeString(historyFile, json);

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("部署历史文件格式错误");
    }

    @Test
    void throwsApiProblemExceptionWhenFileIsCorrupted() throws Exception {
        Files.writeString(historyFile, "{invalid json");

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("读取部署历史失败");
    }

    @Test
    void throwsApiProblemExceptionWhenDeploymentIdMissing() throws Exception {
        String json = "{\"deployments\":[{\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\",\"title\":\"First\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false}]}";
        Files.writeString(historyFile, json);

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("缺少部署ID");
    }

    @Test
    void throwsApiProblemExceptionWhenDeployedAtMissing() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"version\":\"v1.0.0\",\"title\":\"First\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false}]}";
        Files.writeString(historyFile, json);

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("缺少部署时间");
    }

    @Test
    void throwsApiProblemExceptionWhenVersionMissing() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"title\":\"First\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false}]}";
        Files.writeString(historyFile, json);

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("缺少版本号");
    }

    @Test
    void throwsApiProblemExceptionWhenTitleMissing() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false}]}";
        Files.writeString(historyFile, json);

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("缺少标题");
    }

    @Test
    void throwsApiProblemExceptionWhenDuplicateDeploymentId() throws Exception {
        String json = "{\"deployments\":[{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-10T10:00:00Z\",\"version\":\"v1.0.0\",\"title\":\"First\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false},{\"deploymentId\":\"deploy-1\",\"deployedAt\":\"2026-10-11T10:00:00Z\",\"version\":\"v1.0.1\",\"title\":\"Second\",\"description\":\"\",\"details\":\"\",\"isCurrent\":false}]}";
        Files.writeString(historyFile, json);

        assertThatThrownBy(() -> service.listDeployments(null))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("重复ID");
    }
}
