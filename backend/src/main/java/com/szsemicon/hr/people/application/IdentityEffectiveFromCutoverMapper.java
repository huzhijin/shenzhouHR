package com.szsemicon.hr.people.application;

import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Blocked;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Candidate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityEffectiveFromCutoverMapper {

    List<Candidate> listMovable(@Param("cutoff") LocalDate cutoff);

    List<Candidate> listAlreadyApplied(@Param("cutoff") LocalDate cutoff);

    List<Blocked> listBlocked(@Param("cutoff") LocalDate cutoff);

    int moveEmployeeVersions(@Param("cutoff") LocalDate cutoff);

    int moveAssignments(@Param("cutoff") LocalDate cutoff);

    int moveOrganizationVersions(@Param("cutoff") LocalDate cutoff);

    int moveGroupAssignments(@Param("cutoff") LocalDate cutoff);

    int moveGroupRevisions(@Param("cutoff") LocalDate cutoff);

    int moveCalendarVersions(@Param("cutoff") LocalDate cutoff);

    int movePolicyBindings(@Param("cutoff") LocalDate cutoff);

    void insertRun(
            @Param("runId") String runId,
            @Param("requestId") String requestId,
            @Param("actorId") String actorId,
            @Param("reason") String reason,
            @Param("cutoff") LocalDate cutoff,
            @Param("status") String status,
            @Param("updatedCount") int updatedCount,
            @Param("refusedCount") int refusedCount,
            @Param("createdAt") Instant createdAt);

    void insertItem(
            @Param("itemId") String itemId,
            @Param("runId") String runId,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("ownerId") String ownerId,
            @Param("oldFrom") LocalDate oldFrom,
            @Param("newFrom") LocalDate newFrom);

    String findRunStatusByRequest(
            @Param("actorId") String actorId,
            @Param("requestId") String requestId);
}
