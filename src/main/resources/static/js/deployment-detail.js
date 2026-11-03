// Live deployment progress: polls the status API while the deployment (or rollback) is active.
(function () {
    "use strict";
    const root = document.getElementById("deployment");
    if (!root) return;
    const isRollback = root.dataset.rollback === "true";
    const STEPS = isRollback ? ["Prepare", "Upload", "Restore", "Health", "Done"] : ["Backup", "Upload", "Deploy", "Health", "Done"];
    let lastLogId = 0, timer = null, finalFetchDone = false;

    function el(tag, attrs, ...children) {
        const node = document.createElement(tag);
        Object.entries(attrs || {}).forEach(([k, v]) => {
            if (v === undefined || v === null || v === false) return;
            if (k === "class") node.className = v; else if (k === "text") node.textContent = v; else node.setAttribute(k, v === true ? "" : v);
        });
        children.flat().forEach((c) => { if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c))); });
        return node;
    }
    const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    const time = (iso) => iso ? new Date(iso).toLocaleTimeString() : "";
    const size = (b) => b == null ? "" : b < 1048576 ? (b / 1024).toFixed(1) + " KB" : (b / 1048576).toFixed(1) + " MB";
    const uuid = () => (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
        : "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) => (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & 15) >> (c / 4)).toString(16));
    const runLink = (id) => root.dataset.deploymentBase + id;

    function renderRun(run, targets) {
        const badge = root.querySelector("[data-run-state]");
        badge.className = "badge fs-6 " + run.badgeClass;
        badge.textContent = run.stateLabel;
        const cancel = root.querySelector("[data-cancel]");
        const noun = isRollback ? "rollback" : "deployment";
        if (cancel) { cancel.hidden = !run.active; cancel.disabled = run.cancelRequested; cancel.textContent = run.cancelRequested ? "Cancelling…" : "Cancel " + noun; }

        const done = targets.filter((t) => ["SUCCEEDED", "FAILED", "SKIPPED", "PREFLIGHT_FAILED", "ROLLBACK_REQUIRED", "ROLLED_BACK", "UNKNOWN"].includes(t.state)).length;
        const pct = targets.length ? Math.round(done / targets.length * 100) : 0;
        const bar = root.querySelector("[data-progress-bar]");
        bar.style.width = pct + "%";
        bar.className = "progress-bar" + (run.active ? " progress-bar-striped progress-bar-animated" : "") +
            ({ SUCCEEDED: " bg-success", FAILED: " bg-danger", PARTIALLY_SUCCEEDED: " bg-warning", UNKNOWN: " bg-warning", CANCELLED: " bg-secondary" }[run.state] || "");
        root.querySelector("[data-progress-label]").textContent = run.active
            ? (run.cancelRequested ? "Cancelling: finishing the current target safely…" : isRollback ? "Rolling back…" : "Deploying…") : "Finished";
        root.querySelector("[data-progress-count]").textContent = done + " of " + targets.length + " target" + (targets.length === 1 ? "" : "s");

        const summary = root.querySelector("[data-run-summary]");
        if (!run.active && run.summary) {
            summary.hidden = false;
            summary.className = "alert small " + ({ SUCCEEDED: "alert-success", FAILED: "alert-danger", PARTIALLY_SUCCEEDED: "alert-warning", UNKNOWN: "alert-warning", CANCELLED: "alert-secondary" }[run.state] || "alert-info");
            const needsRollback = targets.some((t) => t.state === "ROLLBACK_REQUIRED");
            const failed = targets.filter((t) => ["FAILED", "ROLLBACK_REQUIRED"].includes(t.state) && t.message);
            // Deployments finished before the summary carried the reason: show the first failed target's message.
            const reason = failed.length && !run.summary.includes(failed[0].message) ? failed[0].instanceName + ": " + failed[0].message : null;
            const lines = [
                el("strong", { text: run.stateLabel + ". " }), run.summary,
                reason ? el("div", { class: "mt-1", text: reason }) : null,
                run.state === "UNKNOWN" ? el("div", { class: "mt-1", text: "Some outcomes could not be established. An administrator inspects the server and reconciles each target below. No automatic retry happens." }) : null,
                needsRollback ? el("div", { class: "mt-1", text: "A target needs a rollback: its new version is in place but unhealthy. Use “Roll back…” on that target to restore the previous version." }) : null,
                failed.length && !needsRollback && !isRollback ? el("div", { class: "mt-1", text: "Check the failed target and its log below, fix the cause (for a slow-starting application, raise the instance's startup timeout), then deploy again." }) : null,
                isRollback && run.state === "SUCCEEDED" ? el("div", { class: "mt-1", text: "The previous version is restored and verified. The original deployment is marked as rolled back." }) : null
            ];
            summary.replaceChildren(...lines.filter((c) => c != null)); // replaceChildren would print null as "null"
        }
    }

    function renderTargets(targets) {
        root.querySelectorAll("[data-targets]").forEach((box) => {
            const mayReconcile = box.dataset.canReconcile === "true", mayRollback = box.dataset.canRollback === "true";
            box.replaceChildren(...targets.map((t) => {
                const failedStep = ["FAILED", "ROLLBACK_REQUIRED", "UNKNOWN", "PREFLIGHT_FAILED"].includes(t.state);
                const timeline = el("ol", { class: "timeline" }, STEPS.map((label, i) => {
                    const n = i + 1;
                    const finished = ["SUCCEEDED", "ROLLED_BACK"].includes(t.state);
                    // A step switched off on the instance (no backup / no health check) was passed without doing anything: not green.
                    const off = (t.stepsOff || []).includes(n) && (n < t.step || finished);
                    const cls = t.state === "SKIPPED" ? "skipped" : off ? "off" : n < t.step || (n === 5 && finished) ? "done" : n === t.step ? (failedStep ? "failed" : "current") : "";
                    return el("li", { class: cls, title: off ? label + " is switched off for this instance" : null }, el("span", { text: off ? label + " · off" : label }));
                }));
                const reconcile = mayReconcile && t.state === "UNKNOWN"
                    ? el("button", { type: "button", class: "btn btn-sm btn-warning", text: "Inspect & reconcile…" }) : null;
                if (reconcile) reconcile.addEventListener("click", () => reconcileTarget(t));
                const rollback = mayRollback && t.rollbackAvailable
                    ? el("button", { type: "button", class: "btn btn-sm " + (t.state === "ROLLBACK_REQUIRED" ? "btn-danger" : "btn-outline-danger"), text: "⟲ Roll back…" }) : null;
                if (rollback) rollback.addEventListener("click", () => rollbackTarget(t));
                const links = [];
                if (t.rolledBackByRunId) links.push(el("span", {}, "Rolled back by ", el("a", { href: runLink(t.rolledBackByRunId), text: "rollback #" + t.rolledBackByRunId })));
                else if (t.latestRollbackRunId) links.push(el("span", {}, "Latest rollback: ", el("a", { href: runLink(t.latestRollbackRunId), text: "#" + t.latestRollbackRunId }), " (" + t.latestRollbackState + ")"));
                return el("section", { class: "panel target-card" },
                    el("div", { class: "d-flex flex-wrap justify-content-between align-items-start gap-2" },
                        el("div", { class: "min-w-0" }, el("strong", { text: t.instanceName }),
                            el("div", { class: "small text-secondary", text: t.hostName + " · " + t.hostname + " · " + (t.method === "SSH_SFTP" ? "SSH / SFTP" : "Tomcat Manager") })),
                        el("div", { class: "d-flex flex-wrap align-items-center gap-2" }, rollback, reconcile, el("span", { class: "badge " + t.badgeClass, text: t.stateLabel }))),
                    el("div", { class: "small mt-2" }, (isRollback ? "Restore as " : "Deploy as "), el("code", { text: t.filename }), " → ", el("code", { text: t.contextPath }),
                        t.bytesTransferred ? " · " + size(t.bytesTransferred) + " transferred" : "", t.backup ? " · Backup: " + t.backup : ""),
                    timeline,
                    t.message ? el("div", { class: "small target-message", text: t.message }) : null,
                    links.length ? el("div", { class: "small mt-1" }, links) : null,
                    t.reconciledBy ? el("div", { class: "small text-secondary mt-1", text: "Reconciled by " + t.reconciledBy + ": " + t.reconcileNote }) : null);
            }));
        });
    }

    function appendLogs(logs, targets) {
        if (!logs.length) return;
        const box = root.querySelector("[data-logs]");
        const names = new Map(targets.map((t) => [t.id, t.instanceName]));
        const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
        logs.forEach((l) => box.append(el("div", { class: "log-line level-" + l.level.toLowerCase() },
            el("span", { class: "log-time", text: time(l.loggedAt) }),
            el("span", { class: "log-target", text: l.targetId ? names.get(l.targetId) || "target " + l.targetId : isRollback ? "rollback" : "deployment" }),
            el("span", { class: "log-msg", text: l.message + (l.durationMs != null ? " (" + l.durationMs + " ms)" : "") }))));
        if (atBottom) box.scrollTop = box.scrollHeight;
    }

    async function poll() {
        try {
            const status = await apiFetch(root.dataset.statusUrl + "?afterLogId=" + lastLogId);
            lastLogId = status.lastLogId;
            renderRun(status.run, status.targets);
            renderTargets(status.targets);
            appendLogs(status.logs, status.targets);
            if (status.run.active) { timer = setTimeout(poll, 2000); return; }
            root.querySelector("[data-log-hint]").textContent = (isRollback ? "Rollback" : "Deployment") + " finished.";
            if (!finalFetchDone) { finalFetchDone = true; timer = setTimeout(poll, 1000); }
        } catch (e) {
            root.querySelector("[data-progress-label]").textContent = "Could not refresh: " + e.message + " Retrying…";
            timer = setTimeout(poll, 5000);
        }
    }

    function refresh() { clearTimeout(timer); finalFetchDone = false; poll(); }

    async function cancel() {
        const noun = isRollback ? "rollback" : "deployment";
        const { isConfirmed } = await Swal.fire({ icon: "warning", title: "Cancel this " + noun + "?",
            text: "No further target will be started. A target already in progress finishes safely; nothing is interrupted half-way.",
            showCancelButton: true, confirmButtonText: "Cancel " + noun, cancelButtonText: "Keep running", focusCancel: true });
        if (!isConfirmed) return;
        try { await apiFetch(root.dataset.cancelUrl, { method: "POST" }); refresh(); }
        catch (e) { Swal.fire({ icon: "error", text: e.message }); }
    }

    // ---- reconciliation of unknown outcomes (administrators) ----

    async function reconcileTarget(t) {
        Swal.fire({ title: "Inspecting " + t.instanceName + "…", text: "Reading the deployed file and running one health request. Nothing is changed.",
            allowOutsideClick: false, didOpen: () => Swal.showLoading() });
        let evidence = null, inspectError = null;
        try { evidence = await apiFetch(root.dataset.reconcileUrl + t.id + "/inspect", { method: "POST" }); }
        catch (e) { inspectError = e.message; }

        const suggestion = evidence && evidence.suggestion;
        const option = (value, label, hint, disabled) =>
            "<label class='reconcile-option" + (disabled ? " disabled" : "") + "'><input type='radio' name='outcome' value='" + value + "'" + (suggestion === value ? " checked" : "") + (disabled ? " disabled" : "") + ">"
            + "<span><strong>" + label + "</strong><small>" + hint + "</small></span></label>";
        const findings = evidence ? "<ul class='small mb-2'>" + evidence.findings.map((f) => "<li>" + esc(f) + "</li>").join("") + "</ul>"
            + "<div class='alert alert-" + (suggestion ? "info" : "warning") + " small py-2'>" + esc(evidence.suggestionText) + "</div>"
            : "<div class='alert alert-warning small py-2'>The server could not be inspected automatically: " + esc(inspectError) + " Inspect it yourself.</div>";
        const rollbackPossible = !evidence || evidence.rollbackPossible;
        const result = await Swal.fire({
            icon: "question", title: "Reconcile " + t.instanceName, width: 640,
            html: "<div class='text-start'><p class='small'>The outcome of this target could not be established automatically. Evidence collected now:</p>" + findings
                + "<div class='reconcile-options'>"
                + option("SUCCEEDED", "Succeeded", "The " + (isRollback ? "restored" : "new") + " version is in place and works.", false)
                + option("ROLLBACK_REQUIRED", "Rollback required", "The new version is in place but bad; restore the previous version next.", isRollback || !rollbackPossible)
                + option("FAILED", "Failed", "The new version is not in place, or the result is unusable.", false)
                + "</div><label class='form-label small mt-2' for='reconcile-note'>What did you verify?</label>"
                + "<textarea id='reconcile-note' class='form-control' rows='3' placeholder='e.g. webapps/" + esc(t.filename) + " is the new version and /health returns 200'></textarea></div>",
            showCancelButton: true, confirmButtonText: "Record outcome", focusConfirm: false,
            preConfirm: () => {
                const chosen = document.querySelector("input[name=outcome]:checked");
                const note = document.getElementById("reconcile-note").value.trim();
                if (!chosen) { Swal.showValidationMessage("Choose the outcome you verified."); return false; }
                if (note.length < 5) { Swal.showValidationMessage("Describe what you verified (at least 5 characters)."); return false; }
                return { outcome: chosen.value, note };
            }
        });
        if (!result.isConfirmed) return;
        try {
            await apiFetch(root.dataset.reconcileUrl + t.id + "/reconcile", { method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify(result.value) });
        } catch (e) { Swal.fire({ icon: "error", text: e.message }); return; }
        refresh();
    }

    // ---- manual rollback (operators and administrators) ----

    function checksHtml(p) {
        const row = (label, value) => value == null ? "" : "<dt class='col-4'>" + label + "</dt><dd class='col-8'>" + value + "</dd>";
        const list = (items, cls) => items.length ? "<ul class='small mb-2 " + cls + "'>" + items.map((i) => "<li>" + esc(i) + "</li>").join("") + "</ul>" : "";
        return "<div class='text-start small'><dl class='row mb-2'>"
            + row("Instance", esc(p.instanceName) + " <span class='text-secondary'>(" + esc(p.hostName) + " · " + esc(p.hostname) + ")</span>")
            + row("Environment", "<span class='env-badge env-" + esc(String(p.environment).toLowerCase()) + "'>" + esc(p.environment) + "</span>")
            + row("Replaces", esc(p.replacedVersion))
            + row("Restores", p.restoreDescription ? esc(p.restoreDescription) : "<span class='text-danger'>No restore point</span>")
            + row("As file", "<code>" + esc(p.filename) + "</code> → <code>" + esc(p.contextPath) + "</code>")
            + row("Integrity", esc(p.integrity))
            + row("Compatibility", esc(p.compatibility))
            + row("Connectivity", esc(p.connectivity))
            + row("Health check", p.healthCheck ? "Restored version is verified before the rollback counts as successful" : "<span class='text-warning-emphasis'>Off: not verified</span>")
            + "</dl>"
            + (p.blockers.length ? "<div class='fw-semibold text-danger'>Blocked</div>" + list(p.blockers, "text-danger") : "")
            + (p.warnings.length ? "<div class='fw-semibold text-warning-emphasis'>Warnings</div>" + list(p.warnings, "") : "")
            + "</div>";
    }

    async function rollbackTarget(t) {
        Swal.fire({ title: "Checking rollback…", text: "Verifying the restore point, connectivity and locks.", allowOutsideClick: false, didOpen: () => Swal.showLoading() });
        let p;
        try {
            p = await apiFetch(root.dataset.rollbackPreviewUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ targetId: t.id }) });
        } catch (e) { Swal.fire({ icon: "error", title: "Rollback check failed", text: e.message }); return; }

        if (p.blockers.length) {
            Swal.fire({ icon: "error", title: "Rollback not possible", html: checksHtml(p), width: 680, confirmButtonText: "Close" });
            return;
        }
        const extra = (p.warnings.length ? "<div class='form-check text-start mt-2'><input class='form-check-input' type='checkbox' id='rb-ack'>"
                + "<label class='form-check-label small' for='rb-ack'>I reviewed the warnings above.</label></div>" : "")
            + (p.production ? "<label class='form-label small mt-2 text-start d-block' for='rb-confirm'>Production: type the instance name <strong>" + esc(p.confirmationText) + "</strong> to confirm</label>"
                + "<input id='rb-confirm' class='form-control' autocomplete='off'>" : "");
        const result = await Swal.fire({
            icon: "warning", title: "Roll back " + p.instanceName + "?", width: 680,
            html: checksHtml(p) + extra,
            showCancelButton: true, confirmButtonText: "⟲ Roll back now", confirmButtonColor: "#b02a37", focusCancel: true,
            preConfirm: () => {
                const ack = document.getElementById("rb-ack"), confirm = document.getElementById("rb-confirm");
                if (ack && !ack.checked) { Swal.showValidationMessage("Acknowledge the warnings first."); return false; }
                if (confirm && confirm.value.trim() !== p.confirmationText) { Swal.showValidationMessage("Type " + p.confirmationText + " exactly."); return false; }
                return { acknowledgeWarnings: !!ack, confirmation: confirm ? confirm.value.trim() : null };
            }
        });
        if (!result.isConfirmed) return;
        try {
            const run = await apiFetch(root.dataset.rollbackUrl, { method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ requestId: uuid(), targetId: t.id, confirmed: true, acknowledgeWarnings: result.value.acknowledgeWarnings, confirmation: result.value.confirmation }) });
            window.location.href = runLink(run.id);
        } catch (e) { Swal.fire({ icon: "error", title: "Rollback not started", text: e.message }); }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const c = root.querySelector("[data-cancel]");
        if (c) c.addEventListener("click", cancel);
        poll();
    });
})();
