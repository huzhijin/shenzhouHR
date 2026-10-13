package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.ExpiryResult;

public interface TimeOffYearEndProcedureGateway {

    ExpiryResult expire(
            String employeeNumber,
            int accountYear,
            String eventId,
            String payloadDigest);
}
