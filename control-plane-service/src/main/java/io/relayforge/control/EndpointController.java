package io.relayforge.control;

import io.relayforge.contracts.SubscriptionMatch;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EndpointController {
    private final EndpointService service;
    private final TenantIdentity tenants;

    EndpointController(EndpointService service, TenantIdentity tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @PostMapping("/v1/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PLATFORM_ADMIN')")
    EndpointService.CreatedEndpoint create(@RequestBody EndpointService.CreateEndpoint request,
                                           Authentication authentication) {
        return service.create(tenants.requireTenant(authentication), authentication.getName(), request);
    }

    @GetMapping("/v1/endpoints")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','TENANT_OPERATOR','PLATFORM_ADMIN')")
    List<EndpointService.EndpointView> list(Authentication authentication) {
        return service.list(tenants.requireTenant(authentication));
    }

    @PostMapping("/v1/endpoints/{endpointId}/rotate-secret")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PLATFORM_ADMIN')")
    EndpointService.RotatedSecret rotate(@PathVariable UUID endpointId, Authentication authentication) {
        return service.rotate(tenants.requireTenant(authentication), endpointId, authentication.getName());
    }

    @GetMapping("/internal/v1/subscriptions/matches")
    List<SubscriptionMatch> matching(@RequestParam UUID tenantId, @RequestParam String eventType,
                                     @RequestHeader("X-RelayForge-Internal-Token") String ignored) {
        return service.matching(tenantId, eventType);
    }
}
