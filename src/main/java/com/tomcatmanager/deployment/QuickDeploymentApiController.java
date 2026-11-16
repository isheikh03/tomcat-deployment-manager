package com.tomcatmanager.deployment;

import com.tomcatmanager.common.ApiExceptionHandler;
import com.tomcatmanager.release.InvalidWarException;
import com.tomcatmanager.release.UploadTooLargeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Quick Deployment upload. Access follows the release upload policy (administrators, and operators
 * while operator upload is allowed); only administrators may create an application on the fly.
 * Preflight and submit then use the normal deployment API.
 */
@RestController
@RequestMapping("/api/quick-deployments")
public class QuickDeploymentApiController {
    private final QuickDeploymentService service;

    private final com.tomcatmanager.access.Access access;
    public QuickDeploymentApiController(QuickDeploymentService service, com.tomcatmanager.access.Access access) { this.service = service; this.access = access; }

    @PostMapping(value = "/release", consumes = "multipart/form-data")
    public QuickDeploymentService.Prepared upload(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(value = "applicationName", required = false) String applicationName,
                                                  @RequestParam(value = "versionLabel", required = false) String versionLabel,
                                                  Authentication authentication) throws IOException, InvalidWarException {
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a .war file to upload.");
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        try (InputStream in = file.getInputStream()) {
            return service.prepare(applicationName, versionLabel, file.getOriginalFilename(), in, authentication.getName(), access.current());
        }
    }

    @ExceptionHandler(InvalidWarException.class)
    ResponseEntity<ApiExceptionHandler.ApiError> invalidWar(InvalidWarException e) {
        return ApiExceptionHandler.error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), null);
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ResponseEntity<ApiExceptionHandler.ApiError> tooLarge(UploadTooLargeException e) {
        return ApiExceptionHandler.error(HttpStatus.CONTENT_TOO_LARGE, e.getMessage(), null);
    }
}
