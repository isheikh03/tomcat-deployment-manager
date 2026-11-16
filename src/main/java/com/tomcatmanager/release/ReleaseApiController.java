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
 * Release library API. Upload: users who can deploy to an instance of the application's team (operators only while
 * {@code app.release.operator-upload-enabled=true}); reading: releases of the applications the user can see.
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseApiController {
    private final ReleaseService service;

    private final com.tomcatmanager.infrastructure.TomcatInstanceRepository instances;
    private final com.tomcatmanager.application.ApplicationService applications;
    private final com.tomcatmanager.access.Access access;

    public ReleaseApiController(ReleaseService service, com.tomcatmanager.infrastructure.TomcatInstanceRepository instances,
                                com.tomcatmanager.application.ApplicationService applications, com.tomcatmanager.access.Access access) {
        this.service = service; this.instances = instances; this.applications = applications; this.access = access;
    }

    /** Releases of an application are uploaded by users who can deploy to one of its team's instances. */
    public static void requireUpload(com.tomcatmanager.application.ManagedApplication app, com.tomcatmanager.access.AccessScope s) {
        if (!s.canUploadTo(app.getTeamId()))
            throw new com.tomcatmanager.access.NotPermittedException("You need deploy access to one of this application's team instances to upload its releases.");
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ReleaseDto> upload(@RequestParam("file") MultipartFile file, @RequestParam("applicationId") long applicationId,
                                             @RequestParam(value = "versionLabel", required = false) String versionLabel, // empty for automatic versions
                                             @RequestParam(value = "releaseNotes", required = false) String releaseNotes,
                                             Principal principal) throws IOException, InvalidWarException {
        requireUpload(applications.findVisible(applicationId, access.current()), access.current());
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
        return PageResponse.of(service.search(applicationId, q, includeArchived, page, size, access.current()).map(ReleaseDto::from));
    }

    @GetMapping("/{id}")
    public ReleaseDto get(@PathVariable long id) { return ReleaseDto.from(service.findVisible(id, access.current())); }

    public record CompatibilityDto(Compatibility.Requirements requirements, java.util.List<InstanceCompatibility> instances) { }
    public record InstanceCompatibility(Long instanceId, String instanceName, String hostName, String tomcatVersion, Integer javaVersion,
                                        Compatibility.Status status, java.util.List<String> reasons) { }

    /** Compatibility of this release with every configured Tomcat instance (input for deployment previews). */
    @GetMapping("/{id}/compatibility")
    public CompatibilityDto compatibility(@PathVariable long id) {
        var s = access.current();
        Release release = service.findVisible(id, s);
        Compatibility.Requirements requirements = Compatibility.requirements(release.getApplication(), release.getArtifact());
        return new CompatibilityDto(requirements, instances.findAllByOrderByNameAsc().stream().filter(i -> s.canViewInstance(i.getId())).map(i -> {
            Compatibility.Result r = Compatibility.evaluate(requirements, i.getTomcatVersion(), i.getJavaVersion());
            return new InstanceCompatibility(i.getId(), i.getName(), i.getHost().getName(), i.getTomcatVersion(), i.getJavaVersion(), r.status(), r.reasons());
        }).toList());
    }

    public record VerificationDto(boolean ok, String message, Instant verifiedAt) { }

    @PostMapping("/{id}/verify")
    public VerificationDto verify(@PathVariable long id, Principal principal) {
        service.findVisible(id, access.current());
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
