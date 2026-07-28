package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendancePolicyMapper {

    AttendancePolicyRows.BindingRow findBinding(@Param("bindingId") String bindingId);

    AttendancePolicyRows.BindingRow findBindingRevision(
            @Param("bindingRevisionId") String bindingRevisionId);

    List<String> findPublishedVersionIdsByKind(
            @Param("legalEntityId") String legalEntityId,
            @Param("policyKind") String policyKind,
            @Param("effectiveFrom") LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo);

    List<AttendancePolicyRows.BindingRow> listBindings(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("groupId") String groupId,
            @Param("asOf") LocalDate asOf,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("at") Instant at);

    long countBindings(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("groupId") String groupId,
            @Param("asOf") LocalDate asOf,
            @Param("at") Instant at);

    List<AttendancePolicyRows.BindingRow> findBindingFamilyHeads(
            @Param("groupId") String groupId,
            @Param("policyKind") String policyKind);

    String lockBindingFamily(@Param("bindingId") String bindingId);

    void insertBindingFamily(@Param("row") AttendancePolicyRows.BindingRow row);

    void insertBindingRevision(@Param("row") AttendancePolicyRows.BindingRow row);

    int updateBinding(
            @Param("row") AttendancePolicyRows.BindingRow row,
            @Param("expectedVersion") long expectedVersion);

    boolean publishedVersionMatchesKind(
            @Param("legalEntityId") String legalEntityId,
            @Param("policyVersionId") String policyVersionId,
            @Param("policyKind") String policyKind,
            @Param("effectiveFrom") LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo);

    String publishedVersionDigest(@Param("policyVersionId") String policyVersionId);

    String publishedVersionParameters(@Param("policyVersionId") String policyVersionId);

    boolean hasOtherFamily(
            @Param("policyKind") String policyKind,
            @Param("groupId") String groupId,
            @Param("excludeBindingId") String excludeBindingId);

    List<AttendancePolicyRows.BindingRow> resolveBindings(
            @Param("groupId") String groupId,
            @Param("groupRevisionId") String groupRevisionId,
            @Param("businessDate") LocalDate businessDate,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);
}
