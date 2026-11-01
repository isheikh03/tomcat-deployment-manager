package com.tomcatmanager.release;

import com.tomcatmanager.common.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;

/**
 * Release library API. Upload requires ADMIN (or DEPLOYMENT_OPERATOR when
 * {@code app.release.operator-upload-enabled=true}); reading requires any signed-in user.
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseApiController {
    private final ReleaseService service;

    private final com.tomcatmanager.infrastructure.TomcatInstanceRepository instances;

    public ReleaseApiController(ReleaseService service, com.tomcatmanager.infrastructure.TomcatInstanceRepository instances) {
        this.service = service; this.instances = instances;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ReleaseDto> upload(@RequestParam("file") MultipartFile file, @RequestParam("applicationId") long applicationId,
                                             @RequestParam("versionLabel") String versionLabel,
                                             @RequestParam(value = "releaseNotes", required = false) String releaseNotes,
                                             Principal principal) throws IOException, InvalidWarException {
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a .war file to upload.");
        if (releaseNotes != null && releaseNotes.length() > 20_000) throw new IllegalArgumentException("Release notes are limited to 20,000 characters.");
        Release release;
        try (InputStream in = file.getInputStream()) {
            release = service.upload(new ReleaseService.UploadCommand(applicationId, versionLabel, releaseNotes, file.getOriginalFilename()), in, principal.getName());
        }
        return ResponseEntity.created(URI.create("/api/releases/" + release.getId())).body(ReleaseDto.from(release));
    }

    @GetMapping
    public PageResponse<ReleaseDto> search(@RequestParam(required = false) Long applicationId, @RequestParam(defaultValue = "") String q,
                                           @RequestParam(defaultValue = "false") boolean includeArchived,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.search(applicationId, q, includeArchived, page, size).map(ReleaseDto::from));
    }

    @GetMapping("/{id}")
    public ReleaseDto get(@PathVariable long id) { return ReleaseDto.from(service.find(id)); }

    public record CompatibilityDto(Compatibility.Requirements requirements, java.util.List<InstanceCompatibility> instances) { }
    public record InstanceCompatibility(Long instanceId, String instanceName, String hostName, String tomcatVersion, Integer javaVersion,
                                        Compatibility.Status status, java.util.List<String> reasons) { }

    /** Compatibility of this release with every configured Tomcat instance (input for deployment previews). */
    @GetMapping("/{id}/compatibility")
    public CompatibilityDto compatibility(@PathVariable long id) {
        Release release = service.find(id);
        Compatibility.Requirements requirements = Compatibility.requirements(release.getApplication(), release.getArtifact());
        return new CompatibilityDto(requirements, instances.findAllByOrderByNameAsc().stream().map(i -> {
            Compatibility.Result r = Compatibility.evaluate(requirements, i.getTomcatVersion(), i.getJavaVersion());
            return new InstanceCompatibility(i.getId(), i.getName(), i.getHost().getName(), i.getTomcatVersion(), i.getJavaVersion(), r.status(), r.reasons());
        }).toList());
    }

    public record VerificationDto(boolean ok, String message, Instant verifiedAt) { }

    @PostMapping("/{id}/verify")
    public VerificationDto verify(@PathVariable long id, Principal principal) {
        ArtifactStorage.Verification result = service.verify(id, principal.getName());
        return new VerificationDto(result.ok(), result.message(), Instant.now());
    }

    @ExceptionHandler(InvalidWarException.class)
    ResponseEntity<com.tomcatmanager.common.ApiExceptionHandler.ApiError> invalidWar(InvalidWarException e) {
        return com.tomcatmanager.common.ApiExceptionHandler.error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), null);
    }

    @ExceptionHandler(DuplicateReleaseException.class)
    ResponseEntity<com.tomcatmanager.common.ApiExceptionHandler.ApiError> duplicate(DuplicateReleaseException e) {
        return com.tomcatmanager.common.ApiExceptionHandler.error(HttpStatus.CONFLICT, e.getMessage(), "/releases/" + e.getExistingReleaseId());
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ResponseEntity<com.tomcatmanager.common.ApiExceptionHandler.ApiError> tooLarge(UploadTooLargeException e) {
        return com.tomcatmanager.common.ApiExceptionHandler.error(HttpStatus.CONTENT_TOO_LARGE, e.getMessage(), null);
    }
}
