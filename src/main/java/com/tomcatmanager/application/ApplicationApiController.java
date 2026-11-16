package com.tomcatmanager.application;

import com.tomcatmanager.release.ReleaseDto;
import com.tomcatmanager.release.ReleaseRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Application registry API. Reading: applications of the user's teams. Changes: administrators of the application's team. */
@RestController
@RequestMapping("/api/applications")
public class ApplicationApiController {
    private final ApplicationService service;
    private final ReleaseRepository releases;
    private final com.tomcatmanager.access.Access access;

    public ApplicationApiController(ApplicationService service, ReleaseRepository releases, com.tomcatmanager.access.Access access) {
        this.service = service; this.releases = releases; this.access = access;
    }

    public record ApplicationDto(Long id, String name, String description, ManagedApplication.ServletApi servletApi,
                                 Integer minJavaVersion, Integer maxJavaVersion, List<String> supportedTomcatVersions,
                                 String otherRequirements, long releaseCount, Instant createdAt, String createdBy,
                                 Instant updatedAt, String updatedBy, long version, Long teamId) { }

    private ApplicationDto dto(ManagedApplication a) {
        return new ApplicationDto(a.getId(), a.getName(), a.getDescription(), a.getServletApi(), a.getMinJavaVersion(), a.getMaxJavaVersion(),
                a.getSupportedTomcatList(), a.getOtherRequirements(), releases.countByApplicationId(a.getId()),
                a.getCreatedAt(), a.getCreatedBy(), a.getUpdatedAt(), a.getUpdatedBy(), a.getVersion(), a.getTeamId());
    }

    @GetMapping
    public List<ApplicationDto> list(@RequestParam(defaultValue = "") String q) {
        return service.searchVisible(q, access.current()).stream().map(this::dto).toList();
    }

    @GetMapping("/{id}")
    public ApplicationDto get(@PathVariable long id) { return dto(service.findVisible(id, access.current())); }

    @GetMapping("/{id}/releases")
    public List<ReleaseDto> releases(@PathVariable long id) {
        service.findVisible(id, access.current());
        return releases.findByApplicationIdOrderByUploadedAtDesc(id).stream().map(ReleaseDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<ApplicationDto> create(@Valid @RequestBody ApplicationForm form, BindingResult bindingResult, Principal principal) {
        form.setId(null);
        ApplicationForm valid = validated(form, bindingResult);
        var s = access.current();
        if (valid.getTeamId() == null) throw new IllegalArgumentException("teamId: choose the team this application belongs to.");
        if (!s.canManageTeam(valid.getTeamId())) throw new com.tomcatmanager.access.NotPermittedException("You cannot register applications in this team.");
        ManagedApplication saved = service.save(valid, principal.getName());
        return ResponseEntity.created(URI.create("/api/applications/" + saved.getId())).body(dto(saved));
    }

    /** Requires the {@code version} from the last read; a concurrent change returns 409. */
    @PutMapping("/{id}")
    public ApplicationDto update(@PathVariable long id, @Valid @RequestBody ApplicationForm form, BindingResult bindingResult, Principal principal) {
        form.setTeamId(service.findManageable(id, access.current()).getTeamId()); // an application stays in its team
        form.setId(id);
        return dto(service.save(validated(form, bindingResult), principal.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Principal principal) {
        service.findManageable(id, access.current());
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }

    private ApplicationForm validated(ApplicationForm form, BindingResult errors) {
        service.validate(form, errors);
        if (errors.hasErrors()) {
            throw new IllegalArgumentException(errors.getAllErrors().stream()
                    .map(e -> e instanceof org.springframework.validation.FieldError f ? f.getField() + ": " + e.getDefaultMessage() : e.getDefaultMessage())
                    .collect(Collectors.joining("; ")));
        }
        return form;
    }
}
