package io.relayforge.dispatch;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/v1/deliveries")
class DeliveryController {
    private final DispatchService service;

    DeliveryController(DispatchService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','TENANT_OPERATOR','PLATFORM_ADMIN')")
    List<DispatchService.DeliveryView> list(@RequestParam(required = false) UUID eventId,
                                           Authentication authentication) {
        return service.list(tenant(authentication), eventId);
    }

    @GetMapping("/{deliveryId}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','TENANT_OPERATOR','PLATFORM_ADMIN')")
    DispatchService.DeliveryDetail get(@PathVariable UUID deliveryId, Authentication authentication) {
        return service.get(tenant(authentication), deliveryId);
    }

    @PostMapping("/{deliveryId}/replay")
    @PreAuthorize("hasAnyRole('TENANT_OPERATOR','PLATFORM_ADMIN')")
    void replay(@PathVariable UUID deliveryId, Authentication authentication) {
        service.replay(tenant(authentication), deliveryId, authentication.getName());
    }

    @PostMapping("/{deliveryId}/cancel")
    @PreAuthorize("hasAnyRole('TENANT_OPERATOR','PLATFORM_ADMIN')")
    void cancel(@PathVariable UUID deliveryId, Authentication authentication) {
        service.cancel(tenant(authentication), deliveryId, authentication.getName());
    }

    private static UUID tenant(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            try { return UUID.fromString(jwt.getClaimAsString("tenant_id")); }
            catch (RuntimeException ignored) { }
        }
        throw new AccessDeniedException("Valid tenant_id claim is required");
    }
}

@RestControllerAdvice
class DispatchProblemHandler {
    @ExceptionHandler(DispatchNotFoundException.class)
    ProblemDetail notFound() { return problem(HttpStatus.NOT_FOUND, "Delivery not found", "No delivery was found for this tenant", "not-found"); }
    @ExceptionHandler(InvalidDeliveryOperationException.class)
    ProblemDetail conflict(InvalidDeliveryOperationException exception) {
        return problem(HttpStatus.CONFLICT, "Invalid delivery state", exception.getMessage(), "invalid-delivery-state");
    }
    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setTitle(title); result.setType(URI.create("https://relayforge.local/problems/" + type)); return result;
    }
}
