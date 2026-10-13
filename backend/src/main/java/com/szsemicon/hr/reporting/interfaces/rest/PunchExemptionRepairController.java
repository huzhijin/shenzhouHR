package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairIntent;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免打卡日期修复 REST 适配层
 *
 * <p>调用 PunchExemptionRepairService.batchRepair，提供受 Spring Security + CSRF 保护的 HTTP 入口。
 * 仅修复已授权的免打卡记录日期，不自动重算报表。
 */
@RestController
@RequestMapping("/api/v1/attendance/punch-exemptions")
public class PunchExemptionRepairController {

    private final PunchExemptionRepairService service;

    public PunchExemptionRepairController(PunchExemptionRepairService service) {
        this.service = service;
    }

    /**
     * 批量修复免打卡记录的 valid_from 日期。
     *
     * <p>每条修复需提供：
     * <ul>
     *   <li>exemptionId：免打卡记录主键</li>
     *   <li>employeeId / employeeNumber：目标员工身份（用于授权验证）</li>
     *   <li>companyId：预期所属公司</li>
     *   <li>expectedValidFrom：预期当前生效日期（CAS 旧值）</li>
     *   <li>targetValidFrom：目标修复日期</li>
     *   <li>requestId：幂等键（固定 UUID）</li>
     *   <li>approvalEvidenceDigest：批准材料摘要（用于重放验证）</li>
     * </ul>
     *
     * <p>调用者需具备 EMPLOYEE_EDIT 权限（逐员工验证）。
     * 整批在同一事务提交，任一条失败则全部回滚。
     *
     * @param request 批量修复请求（1–50 条）
     * @return 批量修复回执
     */
    @PostMapping("/repair/batch")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.EMPLOYEE_EDIT + "')")
    ResponseEntity<BatchRepairResponse> batchRepair(
            @Valid @RequestBody BatchRepairRequest request) {

        List<RepairIntent> intents = request.repairs().stream()
                .map(this::toIntent)
                .toList();

        List<RepairReceipt> receipts = service.batchRepair(intents);

        List<RepairReceiptDto> dtos = receipts.stream()
                .map(this::toDto)
                .toList();

        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(new BatchRepairResponse(dtos));
    }

    private RepairIntent toIntent(RepairRequestItem item) {
        return new RepairIntent(
                item.employeeId(),
                item.employeeNumber(),
                item.companyId(),
                item.exemptionId(),
                item.expectedValidFrom(),
                item.targetValidFrom(),
                item.requestId(),
                item.approvalEvidenceDigest());
    }

    private RepairReceiptDto toDto(RepairReceipt receipt) {
        return new RepairReceiptDto(
                receipt.receiptId(),
                receipt.requestId(),
                receipt.exemptionId(),
                receipt.companyId(),
                receipt.employeeId(),
                receipt.operation(),
                receipt.originalValidFrom(),
                receipt.repairedValidFrom(),
                receipt.approvalEvidenceDigest(),
                receipt.payloadDigest(),
                receipt.repairedAt(),
                receipt.repairedBy(),
                receipt.beforeDigest(),
                receipt.afterDigest());
    }

    // ---- DTOs ----

    public record BatchRepairRequest(
            @NotEmpty
            @Size(min = 1, max = 50, message = "repairs must contain 1-50 items")
            List<@Valid RepairRequestItem> repairs) {}

    public record RepairRequestItem(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String companyId,
            @NotBlank @Size(max = 64) String employeeId,
            @NotBlank @Size(max = 32) String employeeNumber,
            @NotBlank @Size(max = 64) String exemptionId,
            @NotNull Instant expectedValidFrom,
            @NotNull Instant targetValidFrom,
            @NotBlank @Size(max = 64) String approvalEvidenceDigest) {}

    public record BatchRepairResponse(List<RepairReceiptDto> receipts) {}

    public record RepairReceiptDto(
            String receiptId,
            String requestId,
            String exemptionId,
            String companyId,
            String employeeId,
            String operation,
            Instant originalValidFrom,
            Instant repairedValidFrom,
            String approvalEvidenceDigest,
            String payloadDigest,
            Instant repairedAt,
            String repairedBy,
            String beforeDigest,
            String afterDigest) {}
}
