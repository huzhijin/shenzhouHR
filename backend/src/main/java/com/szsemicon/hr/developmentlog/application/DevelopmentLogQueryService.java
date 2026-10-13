package com.szsemicon.hr.developmentlog.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import com.szsemicon.hr.shared.web.ApiProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class DevelopmentLogQueryService {

    private static final Logger logger = LoggerFactory.getLogger(DevelopmentLogQueryService.class);
    private static final ZoneId ASIA_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper objectMapper;
    private final Path historyFile;

    public DevelopmentLogQueryService(
            ObjectMapper objectMapper,
            @Value("${shenzhouhr.development-log.history-file}") String historyFilePath
    ) {
        this.objectMapper = objectMapper;
        this.historyFile = Path.of(historyFilePath);
    }

    public List<DeploymentEntry> listDeployments(String searchQuery) {
        if (!Files.exists(historyFile)) {
            return Collections.emptyList();
        }

        DeploymentHistory history;
        try (var reader = Files.newBufferedReader(historyFile)) {
            history = objectMapper.readValue(reader, DeploymentHistory.class);
        } catch (Exception e) {
            logger.error("Failed to read deployment history file", e);
            throw new ApiProblemException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "deployment.history.read.failed",
                    "读取部署历史失败",
                    true
            );
        }

        if (history == null || history.deployments == null) {
            throw new ApiProblemException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "deployment.history.invalid",
                    "部署历史文件格式错误",
                    true
            );
        }

        List<DeploymentEntry> entries = new ArrayList<>(history.deployments);

        validateEntries(entries);

        entries = entries.stream()
                .map(entry -> new DeploymentEntry(
                        entry.deploymentId,
                        entry.deployedAt,
                        entry.version,
                        entry.jarSha256,
                        entry.title,
                        entry.description,
                        entry.details,
                        false,
                        null,
                        null,
                        null
                ))
                .toList();

        entries = new ArrayList<>(entries);
        entries.sort(Comparator.comparing(DeploymentEntry::deployedAt).reversed());

        if (searchQuery != null && !searchQuery.isBlank()) {
            String query = searchQuery.toLowerCase().trim();
            entries = entries.stream()
                    .filter(entry -> matchesSearch(entry, query))
                    .toList();
        }

        return entries;
    }

    private void validateEntries(List<DeploymentEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            DeploymentEntry entry = entries.get(i);
            if (entry == null) {
                throw new ApiProblemException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "deployment.history.invalid",
                        "部署历史包含空记录"
                );
            }
            if (entry.deploymentId == null || entry.deploymentId.isBlank()) {
                throw new ApiProblemException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "deployment.history.invalid",
                        "部署记录缺少部署ID"
                );
            }
            if (entry.deployedAt == null) {
                throw new ApiProblemException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "deployment.history.invalid",
                        "部署记录缺少部署时间"
                );
            }
            if (entry.version == null || entry.version.isBlank()) {
                throw new ApiProblemException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "deployment.history.invalid",
                        "部署记录缺少版本号"
                );
            }
            if (entry.title == null || entry.title.isBlank()) {
                throw new ApiProblemException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "deployment.history.invalid",
                        "部署记录缺少标题"
                );
            }

            // Check for duplicate deploymentId
            for (int j = i + 1; j < entries.size(); j++) {
                if (entries.get(j) != null && entry.deploymentId.equals(entries.get(j).deploymentId)) {
                    throw new ApiProblemException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "deployment.history.invalid",
                            "部署历史包含重复ID"
                    );
                }
            }
        }
    }

    private boolean matchesSearch(DeploymentEntry entry, String query) {
        if (entry.version != null && entry.version.toLowerCase().contains(query)) {
            return true;
        }
        if (entry.title != null && entry.title.toLowerCase().contains(query)) {
            return true;
        }
        if (entry.description != null && entry.description.toLowerCase().contains(query)) {
            return true;
        }
        if (entry.details != null && entry.details.toLowerCase().contains(query)) {
            return true;
        }

        try {
            LocalDate searchDate = LocalDate.parse(query, DATE_FORMATTER);
            LocalDate entryDate = entry.deployedAt.atZone(ASIA_SHANGHAI).toLocalDate();
            return entryDate.equals(searchDate);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public record DeploymentEntry(
            @JsonProperty("deploymentId") String deploymentId,
            @JsonProperty("deployedAt") Instant deployedAt,
            @JsonProperty("version") String version,
            @JsonProperty("jarSha256") String jarSha256,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("details") String details,
            @JsonProperty("isCurrent") Boolean isCurrent,
            @JsonProperty(value = "verifiedAt", access = JsonProperty.Access.WRITE_ONLY) Instant verifiedAt,
            @JsonProperty(value = "_frontendFingerprint", access = JsonProperty.Access.WRITE_ONLY) String frontendFingerprint,
            @JsonProperty(value = "_receiptReleaseName", access = JsonProperty.Access.WRITE_ONLY) String receiptReleaseName
    ) {
        public boolean isCurrentDeployment() {
            return isCurrent != null && isCurrent;
        }
    }

    public record DeploymentHistory(
            @JsonProperty("deployments") List<DeploymentEntry> deployments
    ) {}
}
