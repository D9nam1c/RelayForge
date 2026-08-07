package io.relayforge.control;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
class TenantIdentity {
    UUID requireTenant(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) throw new AccessDeniedException("JWT is required");
        String claim = jwt.getClaimAsString("tenant_id");
        try {
            return UUID.fromString(claim);
        } catch (RuntimeException exception) {
            throw new AccessDeniedException("Valid tenant_id claim is required");
        }
    }
}
