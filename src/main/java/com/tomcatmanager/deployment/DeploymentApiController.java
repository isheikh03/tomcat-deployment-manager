package com.tomcatmanager.deployment;

import com.tomcatmanager.common.ApiExceptionHandler;
import com.tomcatmanager.common.PageResponse;
import com.tomcatmanager.infrastructure.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deployment API. Preview, submit and cancel: ADMIN or DEPLOYMENT_OPERATOR; inspect and reconcile:
 * ADMIN; reading: any signed-in user. Long-running work happens in backend workers; clients poll status.
 */
@RestController
@RequestMapping("/api/deployments")
public class DeploymentApiController {
    private final DeploymentService service;
    private final DeploymentPreflight preflight;
    private final RollbackPreflight rollbacks;
    private final ReconciliationInspector reconciliation;
    private final DeploymentTargetRepository targets;
    private final DeploymentRunRepository runs;
    private final DeploymentLogRepository logs;
    private final DeploymentBackupRepository backups;

    public DeploymentApiController(DeploymentService service, DeploymentPreflight preflight, RollbackPreflight rollbacks,
                                   ReconciliationInspector reconciliation, DeploymentTargetRepository targets, DeploymentRunRepository runs,
                                   DeploymentLogRepository logs, DeploymentBackupRepository backups) {
        this.service = service; this.preflight = preflight; this.rollbacks = rollbacks; this.reconciliation = reconciliation;
        this.targets = targets; this.runs = runs; this.logs = logs; this.backups = backups;
    }

    @PostMapping("/preview")
    public DeploymentPreflight.Preview preview(@RequestBody DeploymentPreflight.Request request, Principal principal) {
        return preflight.evaluate(request, principal.getName());
    }

    public record SubmitRequest(UUID requestId, DeploymentPreflight.Request deployment, boolean acknowledgeWarnings, String confirmation) { }

    @PostMapping
    public ResponseEntity<RunDto> submit(@RequestBody SubmitRequest body, Principal principal) {
        if (body.deployment() == null) throw new IllegalArgumentException("Missing deployment details.");
        DeploymentRun run = service.submit(new DeploymentService.Submission(body.requestId(), body.deployment(), body.acknowledgeWarnings(), body.confirmation()), principal.getName());
        return ResponseEntity.accepted().location(URI.create("/api/deployments/" + run.getId())).body(dto(service.find(run.getId())));
    }

    @GetMapping
    public PageResponse<RunDto> history(@RequestParam(required = false) Long applicationId, @RequestParam(required = false) Environment environment,
                                        @RequestParam(required = false) DeploymentState state, @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.search(applicationId, environment, state, page, size).map(this::dto));
    }

    public record StatusDto(RunDto run, List<TargetDto> targets, List<LogDto> logs, long lastLogId) { }

    /** Status for polling; returns only log entries newer than {@code afterLogId}. */
    @GetMapping("/{id}")
    public StatusDto status(@PathVariable long id, @RequestParam(defaultValue = "0") long afterLogId) {
        DeploymentRun run = service.find(id);
        List<DeploymentTarget> list = targets.findByRunIdOrderBySequenceAsc(id);
        List<Long> ids = list.stream().map(DeploymentTarget::getId).toList();
        Map<Long, DeploymentBackup> backupByTarget = backups.findByTargetIdIn(ids).stream()
                .collect(Collectors.toMap(DeploymentBackup::getTargetId, Function.identity()));
        // newest first, so the first rollback seen per target is the latest one
        Map<Long, DeploymentRun> latestRollback = runs.findByRollbackOfTargetIdInOrderByRequestedAtDesc(ids).stream()
                .collect(Collectors.toMap(DeploymentRun::getRollbackOfTargetId, Function.identity(), (newer, older) -> newer));
        List<LogDto> newLogs = logs.findByRunIdAndIdGreaterThanOrderByIdAsc(id, afterLogId).stream().map(LogDto::from).toList();
        long last = newLogs.isEmpty() ? afterLogId : newLogs.get(newLogs.size() - 1).id();
        return new StatusDto(dto(run), list.stream().map(t -> {
            DeploymentRun rollback = latestRollback.get(t.getId());
            boolean candidate = !run.isRollback() && !run.getState().isActive() && rollbacks.isRollbackCandidate(t)
                    && (rollback == null || rollback.getState().isTerminal());
            return TargetDto.from(t, backupByTarget.get(t.getId()), candidate, rollback);
        }).toList(), newLogs, last);
    }

    @PostMapping("/{id}/cancel")
    public RunDto cancel(@PathVariable long id, Principal principal) {
        service.cancel(id, principal.getName());
        return dto(service.find(id));
    }

    /** {@code outcome} SUCCEEDED, FAILED or ROLLBACK_REQUIRED; the older boolean {@code succeeded} is still accepted. */
    public record ReconcileRequest(Boolean succeeded, TargetState outcome, String note) { }

    @PostMapping("/{id}/targets/{targetId}/reconcile")
    public RunDto reconcile(@PathVariable long id, @PathVariable long targetId, @RequestBody ReconcileRequest body, Principal principal) {
        TargetState outcome = body.outcome() != null ? body.outcome()
                : body.succeeded() == null ? null : body.succeeded() ? TargetState.SUCCEEDED : TargetState.FAILED;
        if (outcome == null) throw new IllegalArgumentException("Choose the outcome you verified.");
        service.reconcile(id, targetId, outcome, body.note(), principal.getName());
        return dto(service.find(id));
    }

    /** Read-only evidence for reconciling an unknown outcome (deployed file checksum, Tomcat state, one health request). */
    @PostMapping("/{id}/targets/{targetId}/inspect")
    public ReconciliationInspector.Evidence inspect(@PathVariable long id, @PathVariable long targetId, Principal principal) {
        return reconciliation.inspect(id, targetId, principal.getName());
    }

    @ExceptionHandler(DeploymentService.PreflightBlockedException.class)
    ResponseEntity<Map<String, Object>> blocked(DeploymentService.PreflightBlockedException e) {
        ApiExceptionHandler.ApiError error = ApiExceptionHandler.error(HttpStatus.CONFLICT, e.getMessage(), null).getBody();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "error", "Conflict", "message", error.message(),
                "timestamp", Instant.now(), "preview", e.getPreview()));
    }

    RunDto dto(DeploymentRun r) {
        Long originalRunId = r.isRollback() ? targets.findById(r.getRollbackOfTargetId()).map(t -> t.getRun().getId()).orElse(null) : null;
        return RunDto.from(r, originalRunId);
    }

    // ---- DTOs ----

    public record RunDto(Long id, DeploymentRun.Kind kind, Long applicationId, String applicationName, Long releaseId, String versionLabel,
                         Environment environment, DeploymentRun.Strategy strategy, int maxParallel, String serverGroupName, DeploymentState state,
                         String stateLabel, String badgeClass, boolean active, boolean cancelRequested, String cancelRequestedBy, String requestedBy,
                         Instant requestedAt, Instant startedAt, Instant finishedAt, String summary,
                         Long rollbackOfTargetId, Long rollbackOfRunId, String restoreDescription) {
        static RunDto from(DeploymentRun r, Long rollbackOfRunId) {
            return new RunDto(r.getId(), r.getKind(), r.getApplication().getId(), r.getApplication().getName(),
                    r.getRelease() == null ? null : r.getRelease().getId(), r.getVersionLabel(),
                    r.getEnvironment(), r.getStrategy(), r.getMaxParallel(), r.getServerGroupName(), r.getState(), r.getState().label(),
                    r.getState().badgeClass(), r.getState().isActive(), r.isCancelRequested(), r.getCancelRequestedBy(), r.getRequestedBy(),
                    r.getRequestedAt(), r.getStartedAt(), r.getFinishedAt(), r.getSummary(),
                    r.getRollbackOfTargetId(), rollbackOfRunId, r.getRestoreDescription());
        }
    }

    public record TargetDto(Long id, Long instanceId, String instanceName, String hostName, String hostname, String method, String filename,
                            String contextPath, TargetState state, String stateLabel, String badgeClass, Instant startedAt, Instant finishedAt,
                            Long bytesTransferred, String message, String backup, String reconciledBy, String reconcileNote, int step,
                            boolean rollbackAvailable, Long rolledBackByRunId, Long latestRollbackRunId, String latestRollbackState) {
        static TargetDto from(DeploymentTarget t, DeploymentBackup b, boolean rollbackAvailable, DeploymentRun latestRollback) {
            String backup = b == null ? null : b.isDeleted() ? "Deleted (" + b.getDeleteReason() + ")" : b.getSource() == DeploymentBackup.Source.REMOTE_WAR
                    ? "Downloaded current WAR (" + RemoteInspector.humanSize(b.getSizeBytes()) + ")" : "Previous library release";
            return new TargetDto(t.getId(), t.getInstanceId(), t.getInstanceName(), t.getHostName(), t.getHostname(),
                    t.getDeploymentMethod().name(), t.getTargetFilename(), t.getContextPath(), t.getState(), t.getState().label(),
                    t.getState().badgeClass(), t.getStartedAt(), t.getFinishedAt(), t.getBytesTransferred(), t.getMessage(), backup,
                    t.getReconciledBy(), t.getReconcileNote(), step(t.getState()), rollbackAvailable, t.getRolledBackByRunId(),
                    latestRollback == null ? null : latestRollback.getId(), latestRollback == null ? null : latestRollback.getState().label());
        }
        /** Progress step 0..5 for the per-target timeline (backup, upload, deploy, health, done). */
        static int step(TargetState s) {
            return switch (s) {
                case PENDING, SKIPPED, PREFLIGHT_FAILED -> 0; case BACKUP_IN_PROGRESS -> 1; case BACKUP_COMPLETED, TRANSFER_IN_PROGRESS -> 2;
                case DEPLOYING -> 3; case HEALTH_CHECKING -> 4; default -> 5;
            };
        }
    }

    public record LogDto(Long id, Long targetId, Instant loggedAt, DeploymentLog.Level level, String event, String message, Long durationMs) {
        static LogDto from(DeploymentLog l) {
            return new LogDto(l.getId(), l.getTargetId(), l.getLoggedAt(), l.getLevel(), l.getEvent(), l.getMessage(), l.getDurationMs());
        }
    }
}
