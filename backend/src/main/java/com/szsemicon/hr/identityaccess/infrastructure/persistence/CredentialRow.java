package com.szsemicon.hr.identityaccess.infrastructure.persistence;

record CredentialRow(String accountId, String passwordHash, long rowVersion) {
}
