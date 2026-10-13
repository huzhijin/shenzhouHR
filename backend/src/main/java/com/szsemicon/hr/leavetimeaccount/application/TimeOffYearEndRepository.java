package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.AccountCandidate;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunItemRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunRecord;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface TimeOffYearEndRepository {

    boolean tryAcquireLock(
            int accountYear,
            String lockToken,
            String lockOwner,
            Duration lease);

    boolean renewLock(
            int accountYear,
            String lockToken,
            Duration lease);

    void releaseLock(int accountYear, String lockToken);

    List<AccountCandidate> findCandidates(int accountYear);

    Optional<String> findExpiryEventDigest(
            String sourceRequestId,
            String sourceEventId);

    void insertRun(RunRecord run);

    void insertRunItem(RunItemRecord item);

    void completeRun(RunRecord run);
}
