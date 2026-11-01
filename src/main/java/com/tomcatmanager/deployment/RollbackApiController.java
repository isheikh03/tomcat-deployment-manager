package com.tomcatmanager.deployment;

import com.tomcatmanager.common.ApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rollback API. Preview and submit: ADMIN or DEPLOYMENT_OPERATOR; listing: any signed-in user.
 * The rollback runs in the background like a deployment; poll {@code /api/deployments/{id}}.
 */
@RestController
@RequestMapping("/api/rollbacks")
public class RollbackApiController {
    private final RollbackPreflight preflight;
    private final RollbackService service;
    private final DeploymentRunRepository runs;
    private final DeploymentApiController deployments;

    public RollbackApiController(RollbackPreflight preflight, RollbackService service, DeploymentRunRepository runs, DeploymentApiController deployments) {
        this.preflight = preflight; this.service = service; this.runs = runs; this.deployments = deployments;
    }

    public record PreviewRequest(Long targetId) { }

    @PostMapping("/preview")
    public RollbackPreflight.Preview preview(@RequestBody PreviewRequest body, Principal principal) {
        if (body.targetId() == null) throw new IllegalArgumentException("Choose the deployment target to roll back.");
        return preflight.evaluate(body.targetId(), principal.getName());
    }

    public record SubmitRequest(UUID requestId, Long targetId, boolean confirmed, boolean acknowledgeWarnings, String confirmation) { }

    @PostMapping
    public ResponseEntity<DeploymentApiController.RunDto> submit(@RequestBody SubmitRequest body, Principal principal) {
        DeploymentRun run = service.submit(new RollbackService.Submission(body.requestId(), body.targetId(), body.confirmed(),
                body.acknowledgeWarnings(), body.confirmation()), principal.getName());
        return ResponseEntity.accepted().location(URI.create("/api/deployments/" + run.getId())).body(deployments.dto(runs.findById(run.getId()).orElseThrow()));
    }

    @GetMapping
    public List<DeploymentApiController.RunDto> recent() {
        return runs.findTop5ByKindOrderByRequestedAtDesc(DeploymentRun.Kind.ROLLBACK).stream().map(deployments::dto).toList();
    }

    @ExceptionHandler(RollbackService.RollbackBlockedException.class)
    ResponseEntity<Map<String, Object>> blocked(RollbackService.RollbackBlockedException e) {
        ApiExceptionHandler.ApiError error = ApiExceptionHandler.error(HttpStatus.CONFLICT, e.getMessage(), null).getBody();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "error", "Conflict", "message", error.message(),
                "timestamp", Instant.now(), "preview", e.getPreview()));
    }
}
