package com.tomcatmanager.connectivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.tomcatmanager.common.PageResponse;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * Connection-test endpoints. Authorization is enforced in SecurityConfig: running tests needs
 * ADMIN or DEPLOYMENT_OPERATOR, trusting a host key needs ADMIN, reading needs any signed-in user.
 * Tests are bounded by per-target timeouts, so they run synchronously and return the persisted result.
 */
@RestController
public class ConnectivityApiController {
    private final ConnectionTestService service;
    private final com.tomcatmanager.audit.AuditService audit;

    public ConnectivityApiController(ConnectionTestService service, com.tomcatmanager.audit.AuditService audit) { this.service = service; this.audit = audit; }

    @PostMapping("/api/connectivity-tests/hosts/{hostId}")
    public ConnectionTestResultDto testHost(@PathVariable long hostId, Principal principal) {
        return ConnectionTestResultDto.from(service.testHost(hostId, principal.getName()));
    }

    @PostMapping("/api/connectivity-tests/instances/{instanceId}")
    public ConnectionTestResultDto testInstance(@PathVariable long instanceId, Principal principal) {
        return ConnectionTestResultDto.from(service.testInstance(instanceId, principal.getName()));
    }

    /** Test SSH settings from the host form before saving (ADMIN). Not persisted. */
    @PostMapping("/api/connectivity-tests/drafts/host")
    public ConnectionTestResultDto testDraftHost(@Valid @RequestBody DraftConnectionRequests.DraftHost request, Principal principal) {
        return service.testDraftHost(request, principal.getName());
    }

    /** Test Tomcat Manager settings from the instance form before saving (ADMIN). Not persisted. */
    @PostMapping("/api/connectivity-tests/drafts/manager")
    public ConnectionTestResultDto testDraftManager(@Valid @RequestBody DraftConnectionRequests.DraftManager request, Principal principal) {
        return service.testDraftManager(request, principal.getName());
    }

    @GetMapping("/api/connectivity-tests/{id}")
    public ConnectionTestResultDto get(@PathVariable long id) {
        return ConnectionTestResultDto.from(service.get(id));
    }

    @GetMapping("/api/connectivity-tests/hosts/{hostId}")
    public PageResponse<ConnectionTestResultDto> hostHistory(@PathVariable long hostId, @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return PageResponse.of(service.historyForHost(hostId, page, size).map(ConnectionTestResultDto::from));
    }

    @GetMapping("/api/connectivity-tests/instances/{instanceId}")
    public PageResponse<ConnectionTestResultDto> instanceHistory(@PathVariable long instanceId, @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        return PageResponse.of(service.historyForInstance(instanceId, page, size).map(ConnectionTestResultDto::from));
    }

    public record TrustHostKeyRequest(@NotBlank @Size(max = 100) String fingerprint) { }

    @PostMapping("/api/hosts/{hostId}/host-key/trust")
    public Map<String, String> trustHostKey(@PathVariable long hostId, @Valid @RequestBody TrustHostKeyRequest request, Principal principal) {
        String pinned = service.trustInitialHostKey(hostId, request.fingerprint(), principal.getName()).getSshHostKeyFingerprint();
        audit.success("HOST_KEY_TRUSTED", "HOST", hostId, "Pinned SSH host key " + pinned + " after confirming it from a connection test.");
        return Map.of("hostKeyFingerprint", pinned);
    }
}
