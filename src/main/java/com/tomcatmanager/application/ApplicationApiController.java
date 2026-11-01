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

/** Application registry API. Reading: any signed-in user. Changes: ADMIN (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/applications")
public class ApplicationApiController {
    private final ApplicationService service;
    private final ReleaseRepository releases;

    public ApplicationApiController(ApplicationService service, ReleaseRepository releases) {
        this.service = service; this.releases = releases;
    }

    public record ApplicationDto(Long id, String name, String description, ManagedApplication.ServletApi servletApi,
                                 Integer minJavaVersion, Integer maxJavaVersion, List<String> supportedTomcatVersions,
                                 String otherRequirements, long releaseCount, Instant createdAt, String createdBy,
                                 Instant updatedAt, String updatedBy, long version) { }

    private ApplicationDto dto(ManagedApplication a) {
        return new ApplicationDto(a.getId(), a.getName(), a.getDescription(), a.getServletApi(), a.getMinJavaVersion(), a.getMaxJavaVersion(),
                a.getSupportedTomcatList(), a.getOtherRequirements(), releases.countByApplicationId(a.getId()),
                a.getCreatedAt(), a.getCreatedBy(), a.getUpdatedAt(), a.getUpdatedBy(), a.getVersion());
    }

    @GetMapping
    public List<ApplicationDto> list(@RequestParam(defaultValue = "") String q) {
        return service.search(q).stream().map(this::dto).toList();
    }

    @GetMapping("/{id}")
    public ApplicationDto get(@PathVariable long id) { return dto(service.find(id)); }

    @GetMapping("/{id}/releases")
    public List<ReleaseDto> releases(@PathVariable long id) {
        service.find(id);
        return releases.findByApplicationIdOrderByUploadedAtDesc(id).stream().map(ReleaseDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<ApplicationDto> create(@Valid @RequestBody ApplicationForm form, BindingResult bindingResult, Principal principal) {
        form.setId(null);
        ManagedApplication saved = service.save(validated(form, bindingResult), principal.getName());
        return ResponseEntity.created(URI.create("/api/applications/" + saved.getId())).body(dto(saved));
    }

    /** Requires the {@code version} from the last read; a concurrent change returns 409. */
    @PutMapping("/{id}")
    public ApplicationDto update(@PathVariable long id, @Valid @RequestBody ApplicationForm form, BindingResult bindingResult, Principal principal) {
        service.find(id);
        form.setId(id);
        return dto(service.save(validated(form, bindingResult), principal.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Principal principal) {
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
