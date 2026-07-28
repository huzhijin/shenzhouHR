package com.szsemicon.hr.shared.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * Read-side authorization failures intentionally share the same response as a
 * missing object so callers cannot use the API as a resource oracle.
 */
public final class ResourceNotAvailableAccessDeniedException
        extends AccessDeniedException {

    public ResourceNotAvailableAccessDeniedException() {
        super("resource is not available to the current principal");
    }
}
