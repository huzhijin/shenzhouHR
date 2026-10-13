package com.szsemicon.hr.developmentlog.application;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentLogQueryServiceStrictMetadataTest {

    private DevelopmentLogQueryService service;
    private Path historyFile;
    private ObjectMapper strictMapper;

    @BeforeEach
    void setUp() throws Exception {
        historyFile = Files.createTempFile("deployment-history", ".json");
        strictMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        service = new DevelopmentLogQueryService(strictMapper, historyFile.toString());
    }

    @Test
    void readsActualRegisterProofGeneratedHistoryWithStrictMapper() throws Exception {
        Path fixture = new ClassPathResource(
                "fixtures/deployment-history/register-proof-generated.json").getFile().toPath();
        Files.copy(fixture, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments(null);

        assertThat(entries).hasSize(1);
        DevelopmentLogQueryService.DeploymentEntry entry = entries.get(0);
        assertThat(entry.deploymentId()).isEqualTo("deploy-20261013-001246-366000");
        assertThat(entry.version()).isEqualTo("shenzhouhr-release-20261012234213");
        assertThat(entry.title()).isEqualTo("开发日志严格读取集成回放");
        assertThat(entry.description()).contains("本地测试真实登记脚本输出");
        assertThat(entry.details()).contains("员工号筛选补齐历史身份关联");
        assertThat(entry.jarSha256()).isEqualTo("db63faee78b953592eda8aebd168cf7991987cb4db3ca0fb1d748dcead9edf9c");
        assertThat(entry.deployedAt()).isNotNull();
    }

    @Test
    void readsProductionEvidenceDesensitizedHistoryWithStrictMapper() throws Exception {
        Path fixture = new ClassPathResource(
                "fixtures/deployment-history/production-evidence-desensitized.json").getFile().toPath();
        Files.copy(fixture, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments(null);

        assertThat(entries).hasSize(1);
        DevelopmentLogQueryService.DeploymentEntry entry = entries.get(0);
        assertThat(entry.deploymentId()).isEqualTo("deploy-20261013-001246-366862");
        assertThat(entry.version()).isEqualTo("shenzhouhr-release-20261012234213");
        assertThat(entry.title()).isEqualTo("工龄精度、离职历史、部门汇总与开发日志修复");
        assertThat(entry.description()).contains("公司外工龄按365天/年");
        assertThat(entry.details()).contains("系统管理新增开发日志");
        assertThat(entry.jarSha256()).isEqualTo("db63faee78b953592eda8aebd168cf7991987cb4db3ca0fb1d748dcead9edf9c");
        assertThat(entry.deployedAt()).isNotNull();
    }

    @Test
    void metadataFieldsNotExposedInSerializedResponse() throws Exception {
        Path fixture = new ClassPathResource(
                "fixtures/deployment-history/register-proof-generated.json").getFile().toPath();
        Files.copy(fixture, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        List<DevelopmentLogQueryService.DeploymentEntry> entries = service.listDeployments(null);
        assertThat(entries).hasSize(1);

        String serialized = strictMapper.writeValueAsString(entries.get(0));

        assertThat(serialized).doesNotContain("verifiedAt");
        assertThat(serialized).doesNotContain("_frontendFingerprint");
        assertThat(serialized).doesNotContain("_receiptReleaseName");
        assertThat(serialized).doesNotContain("39f34f8c5145419e");
        assertThat(serialized).doesNotContain("00:13:57");

        assertThat(serialized).contains("deploymentId");
        assertThat(serialized).contains("version");
        assertThat(serialized).contains("title");
    }
}
