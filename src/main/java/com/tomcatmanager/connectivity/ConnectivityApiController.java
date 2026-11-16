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
    private final com.tomcatmanager.access.Access access;
    private final com.tomcatmanager.infrastructure.ManagedHostRepository hosts;
    private final com.tomcatmanager.infrastructure.TomcatInstanceRepository instances;

    public ConnectivityApiController(ConnectionTestService service, com.tomcatmanager.audit.AuditService audit, com.tomcatmanager.access.Access access,
                                     com.tomcatmanager.infrastructure.ManagedHostRepository hosts, com.tomcatmanager.infrastructure.TomcatInstanceRepository instances) {
        this.service = service; this.audit = audit; this.access = access; this.hosts = hosts; this.instances = instances;
    }

    // ---- Access: hosts and instances outside the user's access do not exist for them ----

    private com.tomcatmanager.infrastructure.ManagedHost viewableHost(long hostId, com.tomcatmanager.access.AccessScope s) {
        var host = hosts.findById(hostId).orElseThrow(() -> new java.util.NoSuchElementException("Host " + hostId + " not found."));
        if (!s.canViewHost(host.getId(), host.getTeamId())) throw new java.util.NoSuchElementException("Host " + hostId + " not found.");
        return host;
    }

    /** A host is tested by its team's administrators and by users who can deploy to one of its instances. */
    private void requireHostTest(long hostId, com.tomcatmanager.access.AccessScope s) {
        var host = viewableHost(hostId, s);
        boolean allowed = s.canManageHost(host.getTeamId())
                || instances.findByHostIdOrderByNameAsc(hostId).stream().anyMatch(i -> s.canDeployTo(i.getId()));
        if (!allowed) throw new com.tomcatmanager.access.NotPermittedException("You need deploy access to an instance on this host to test it.");
    }

    private void requireViewInstance(long instanceId, com.tomcatmanager.access.AccessScope s) {
        if (!s.canViewInstance(instanceId)) throw new java.util.NoSuchElementException("Tomcat instance " + instanceId + " not found.");
    }

    @PostMapping("/api/connectivity-tests/hosts/{hostId}")
    public ConnectionTestResultDto testHost(@PathVariable long hostId, Principal principal) {
        requireHostTest(hostId, access.current());
        return ConnectionTestResultDto.from(service.testHost(hostId, principal.getName()));
    }

    @PostMapping("/api/connectivity-tests/instances/{instanceId}")
    public ConnectionTestResultDto testInstance(@PathVariable long instanceId, Principal principal) {
        var s = access.current();
        requireViewInstance(instanceId, s);
        if (!s.canDeployTo(instanceId)) throw new com.tomcatmanager.access.NotPermittedException("You need deploy access to this instance to test it.");
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
        var s = access.current();
        ConnectionTestResult result = service.get(id);
        boolean visible = result.getInstanceId() != null ? s.canViewInstance(result.getInstanceId())
                : hosts.findById(result.getHostId()).map(h -> s.canViewHost(h.getId(), h.getTeamId())).orElse(false);
        if (!visible) throw new java.util.NoSuchElementException("Connection test " + id + " not found.");
        return ConnectionTestResultDto.from(result);
    }

    @GetMapping("/api/connectivity-tests/hosts/{hostId}")
    public PageResponse<ConnectionTestResultDto> hostHistory(@PathVariable long hostId, @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        viewableHost(hostId, access.current());
        return PageResponse.of(service.historyForHost(hostId, page, size).map(ConnectionTestResultDto::from));
    }

    @GetMapping("/api/connectivity-tests/instances/{instanceId}")
    public PageResponse<ConnectionTestResultDto> instanceHistory(@PathVariable long instanceId, @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        requireViewInstance(instanceId, access.current());
        return PageResponse.of(service.historyForInstance(instanceId, page, size).map(ConnectionTestResultDto::from));
    }

    public record TrustHostKeyRequest(@NotBlank @Size(max = 100) String fingerprint) { }

    @PostMapping("/api/hosts/{hostId}/host-key/trust")
    public Map<String, String> trustHostKey(@PathVariable long hostId, @Valid @RequestBody TrustHostKeyRequest request, Principal principal) {
        var s = access.current();
        if (!s.canManageHost(viewableHost(hostId, s).getTeamId()))
            throw new com.tomcatmanager.access.NotPermittedException("Only administrators of this host's team can pin its host key.");
        String pinned = service.trustInitialHostKey(hostId, request.fingerprint(), principal.getName()).getSshHostKeyFingerprint();
        audit.success("HOST_KEY_TRUSTED", "HOST", hostId, "Pinned SSH host key " + pinned + " after confirming it from a connection test.");
        return Map.of("hostKeyFingerprint", pinned);
    }
}
