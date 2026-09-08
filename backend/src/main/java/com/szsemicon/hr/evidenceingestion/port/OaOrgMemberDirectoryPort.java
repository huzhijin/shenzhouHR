package com.szsemicon.hr.evidenceingestion.port;

import java.math.BigInteger;
import java.util.List;

/**
 * Read-only boundary for resolving a Seeyon OA subject picker value.
 *
 * <p>The production adapter is limited to this confirmed member-directory
 * lookup while the OA form approval/FK contracts remain unsigned.
 * Implementations must query {@code org_member.id} exactly and return the
 * stored {@code code} without trimming, case folding or numeric coercion.</p>
 */
public interface OaOrgMemberDirectoryPort {

    List<OrgMemberRecord> findById(BigInteger orgMemberId);

    record OrgMemberRecord(BigInteger id, String code, String name) {

        public OrgMemberRecord(BigInteger id, String code) {
            this(id, code, null);
        }
    }
}
