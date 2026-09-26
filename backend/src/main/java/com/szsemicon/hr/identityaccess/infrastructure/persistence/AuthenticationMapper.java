package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AuthenticationMapper {

    AccountRow findAccountByNormalizedUsername(
            @Param("normalizedUsername") String normalizedUsername);

    AccountRow lockAccountByNormalizedUsername(
            @Param("normalizedUsername") String normalizedUsername);

    AccountRow findAccountById(@Param("accountId") String accountId);

    AccountRow lockAccountById(@Param("accountId") String accountId);

    AccountRow findAccountByPrincipalId(@Param("principalId") String principalId);

    CredentialRow findCredential(@Param("accountId") String accountId);

    CredentialRow lockCredential(@Param("accountId") String accountId);

    FailureRow findFailure(@Param("accountId") String accountId);

    SessionRow findActiveSessionByDigest(
            @Param("tokenDigest") String tokenDigest,
            @Param("at") Instant at);

    SessionRow findSessionById(@Param("sessionId") String sessionId);

    ResetGrantRow findActiveResetGrant(
            @Param("digest") String digest,
            @Param("at") Instant at);

    ResetGrantRow lockActiveResetGrant(
            @Param("digest") String digest,
            @Param("at") Instant at);
}
