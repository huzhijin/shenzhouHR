package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface OaReadOnlyConnectionProvider {

    Connection openConnection() throws SQLException;
}
