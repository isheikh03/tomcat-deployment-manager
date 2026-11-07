// Quick deployment: upload WAR → choose instances → connectivity/preflight → deploy.
// The upload creates (or reuses) the release; preflight and submit use the normal deployment API,
// which re-runs every check on the server. All text uses textContent.
(function () {
    "use strict";

    const root = document.getElementById("quick");
    if (!root) return;

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    const FILENAME = /^[A-Za-z0-9][A-Za-z0-9._#-]{0,195}\.war$/;
    const state = { options: null, release: null, environment: null, targets: new Map(), parallel: false, preview: null };

    function el(tag, attrs, ...children) {
        const node = document.createElement(tag);
        Object.entries(attrs || {}).forEach(([k, v]) => {
            if (v === undefined || v === null || v === false) return;
            if (k === "class") node.className = v; else if (k === "text") node.textContent = v; else node.setAttribute(k, v === true ? "" : v);
        });
        children.flat().forEach((c) => { if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c))); });
        return node;
    }
    const $ = (sel) => root.querySelector(sel);
    const plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");
    const humanSize = (bytes) => {
        const units = ["B", "KB", "MB", "GB"];
        let i = 0, n = bytes;
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return (i === 0 ? n : n.toFixed(1)) + " " + units[i];
    };
    const contextOf = (name) => {
        if (!FILENAME.test(name || "")) return null;
        let base = name.slice(0, -4);
        if (base.includes("##")) base = base.slice(0, base.indexOf("##"));
        return base === "ROOT" ? "/" : "/" + base.replace(/#/g, "/");
    };
    const envLabel = (id) => (state.options.environments.find((e) => e.id === id) || {}).label || "None";

    function showStep(n) {
        root.querySelectorAll("[data-step]").forEach((s) => { s.hidden = Number(s.dataset.step) > n; });
        root.querySelectorAll("[data-step-nav]").forEach((li) => {
            const k = Number(li.dataset.stepNav);
            li.classList.toggle("done", k < n);
            li.classList.toggle("active", k === n);
        });
    }

    function invalidatePreview() {
        if (!state.preview) return;
        state.preview = null;
        $("[data-preview]").replaceChildren(el("div", { class: "alert alert-secondary small mb-0", text: "The selection changed. Check connectivity again before deploying." }));
    }

    // ---------- step 1: upload ----------
    function initUpload() {
        const input = $("#q-file");
        const zone = $("[data-drop-zone]");
        input.addEventListener("change", () => { if (input.files.length) upload(input.files[0]); });
        ["dragenter", "dragover"].forEach((t) => zone.addEventListener(t, (e) => { e.preventDefault(); zone.classList.add("dragging"); }));
        ["dragleave", "drop"].forEach((t) => zone.addEventListener(t, (e) => { e.preventDefault(); zone.classList.remove("dragging"); }));
        zone.addEventListener("drop", (e) => {
            const files = e.dataTransfer.files;
            if (files.length !== 1) { showUploadError("Drop one .war file."); return; }
            upload(files[0]);
        });
        zone.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); input.click(); } });
    }

    function showUploadError(text) {
        $("[data-upload-error]").replaceChildren(text ? el("div", { class: "alert alert-danger small mb-0", role: "alert", text }) : "");
    }

    function upload(file) {
        showUploadError(null);
        if (!file.name.toLowerCase().endsWith(".war")) { showUploadError("Only .war files can be deployed."); return; }
        if (file.size > Number(root.dataset.maxBytes)) { showUploadError(file.name + " is " + humanSize(file.size) + ", larger than the " + root.dataset.maxSize + " limit."); return; }

        const data = new FormData();
        data.append("file", file, file.name);
        data.append("applicationName", $("#q-app").value.trim());
        data.append("versionLabel", $("#q-version").value.trim());

        const progress = $("[data-progress]"), bar = $("[data-progress-bar]"), percent = $("[data-progress-percent]"), label = $("[data-progress-label]");
        const cancel = $("[data-cancel-upload]");
        const zone = $("[data-drop-zone]");
        progress.hidden = false;
        zone.hidden = true;
        bar.style.width = "0%"; percent.textContent = "0%";
        bar.classList.add("progress-bar-animated");

        const xhr = new XMLHttpRequest();
        const leaveGuard = (e) => { e.preventDefault(); e.returnValue = ""; };
        window.addEventListener("beforeunload", leaveGuard);
        const abort = () => xhr.abort();
        cancel.addEventListener("click", abort);
        const done = () => {
            window.removeEventListener("beforeunload", leaveGuard);
            cancel.removeEventListener("click", abort);
            progress.hidden = true;
            $("#q-file").value = "";
        };

        xhr.upload.addEventListener("progress", (e) => {
            if (!e.lengthComputable) return;
            const pct = Math.round((e.loaded / e.total) * 100);
            bar.style.width = pct + "%";
            percent.textContent = pct + "%";
            label.textContent = pct < 100 ? "Uploading " + humanSize(e.loaded) + " of " + humanSize(e.total) + "…" : "Verifying checksum and inspecting the WAR…";
        });
        xhr.addEventListener("load", () => {
            done();
            let body = null;
            try { body = JSON.parse(xhr.responseText); } catch (ignored) { /* non-JSON error page */ }
            if (xhr.status === 200 && body) { uploaded(body, file); return; }
            zone.hidden = false;
            const message = body && body.message ? body.message : xhr.status === 403 ? "Your role does not permit uploading releases."
                : xhr.status === 401 ? "Your session has expired. Please sign in again." : "Upload failed (HTTP " + xhr.status + ").";
            showUploadError(message);
        });
        xhr.addEventListener("error", () => { done(); zone.hidden = false; showUploadError("The upload failed. Check the connection and try again."); });
        xhr.addEventListener("abort", () => { done(); zone.hidden = false; showUploadError("Upload cancelled. Nothing was saved."); });

        xhr.open("POST", root.dataset.uploadUrl);
        if (csrfHeader && csrfToken) xhr.setRequestHeader(csrfHeader, csrfToken);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.send(data);
    }

    function uploaded(release, file) {
        state.release = release;
        $("[data-upload-form]").hidden = true;
        const notes = [];
        if (release.applicationCreated) notes.push("new application registered");
        if (release.reusedRelease) notes.push("identical WAR already in the library; that release is reused");
        if (release.detectedJavaVersion) notes.push("Java " + release.detectedJavaVersion + "+");
        if (release.servletApi && release.servletApi !== "UNKNOWN") notes.push(release.servletApi.toLowerCase() + ".servlet");
        const change = el("button", { class: "btn btn-light border btn-sm", type: "button", text: "Use a different file" });
        change.addEventListener("click", reset);
        $("[data-upload-result]").replaceChildren(el("div", { class: "alert alert-success small d-flex flex-wrap align-items-center gap-2 mb-0" },
            el("i", { class: "bx bx-check-circle fs-5" }),
            el("span", { class: "flex-grow-1" },
                el("strong", { text: release.applicationName + " " + release.versionLabel }),
                " · " + file.name + " · " + release.size + " · SHA-256 ", el("code", { text: release.sha256.slice(0, 12) + "…" }),
                notes.length ? el("div", { class: "text-secondary", text: notes.join(" · ") }) : null),
            el("a", { class: "btn btn-light border btn-sm", href: root.dataset.baseUrl + "releases/" + release.releaseId, target: "_blank", rel: "noopener", text: "Release details" }),
            change));
        $("[data-upload-result]").hidden = false;
        // Default file names for this application, keeping any name already edited.
        state.targets.forEach((name, id) => { if (!name) state.targets.set(id, release.filenames[id] || ""); });
        invalidatePreview();
        renderTargets();
        update();
    }

    function reset() {
        state.release = null;
        state.preview = null;
        $("[data-upload-result]").hidden = true;
        $("[data-upload-form]").hidden = false;
        $("[data-drop-zone]").hidden = false;
        $("[data-preview]").replaceChildren(el("p", { class: "small text-secondary mb-0", text: "Tests SSH or Tomcat Manager access, inspects each server and checks compatibility. Nothing is changed yet." }));
        update();
    }

    // ---------- step 2: targets ----------
    function defaultName(instance) {
        return (state.release && state.release.filenames[instance.id]) || "";
    }

    /** Why an instance cannot be chosen here, or null. */
    function unavailable(i) {
        if (!i.enabled) return "Disabled";
        if (!i.environment) return "No environment assigned";
        if (i.environment === "PRODUCTION") return "Production: use the deployment wizard";
        if (state.environment && state.environment !== i.environment) return "Other environment (" + envLabel(state.environment) + " selected)";
        return null;
    }

    function renderTargets() {
        const body = $("[data-targets]");
        const instances = state.options.instances;
        body.replaceChildren(...instances.map((i) => {
            const selected = state.targets.has(i.id);
            const reason = unavailable(i);
            const checkbox = el("input", { type: "checkbox", class: "form-check-input", checked: selected, disabled: !!reason && !selected, "aria-label": "Deploy to " + i.name });
            const name = el("input", { class: "form-control form-control-sm font-monospace", value: selected ? state.targets.get(i.id) : defaultName(i),
                placeholder: "app.war", disabled: !selected, spellcheck: "false" });
            const ctx = el("code", { text: contextOf(name.value) || "—" });
            checkbox.addEventListener("change", () => {
                if (checkbox.checked) { state.targets.set(i.id, name.value.trim()); state.environment = i.environment; }
                else { state.targets.delete(i.id); if (state.targets.size === 0) state.environment = null; }
                invalidatePreview(); renderTargets(); update();
            });
            name.addEventListener("input", () => {
                const v = name.value.trim();
                state.targets.set(i.id, v);
                ctx.textContent = contextOf(v) || "invalid name";
                name.classList.toggle("is-invalid", !contextOf(v));
                invalidatePreview(); update();
            });
            const conn = el("span", { class: "badge " + ({ VERIFIED: "text-bg-success", FAILED: "text-bg-danger", WARNING: "text-bg-warning", STALE: "text-bg-secondary" }[i.connectivityState] || "text-bg-light border"), text: i.connectivity });
            const env = i.environment ? el("span", { class: "env-badge env-" + i.environment.toLowerCase(), text: envLabel(i.environment) }) : el("span", { class: "small text-secondary", text: "—" });
            return el("tr", { class: reason && !selected ? "text-secondary" : "" },
                el("td", {}, checkbox),
                el("td", {}, el("strong", { text: i.name }), el("div", { class: "small text-secondary", text: i.hostName + " · " + i.method + (reason && !selected ? " · " + reason : "") })),
                el("td", {}, env), el("td", {}, conn), el("td", {}, name), el("td", {}, ctx));
        }));
        $("[data-parallel-row]").hidden = state.targets.size < 2;
    }

    // ---------- summary ----------
    function update() {
        const r = state.release;
        $("[data-sum-release]").textContent = r ? r.applicationName + " " + r.versionLabel : "—";
        $("[data-sum-env]").textContent = state.environment ? envLabel(state.environment) : "—";
        $("[data-sum-targets]").textContent = state.targets.size ? plural(state.targets.size, "instance") : "—";
        if (state.targets.size < 2) state.parallel = false;
        $("[data-parallel]").checked = state.parallel;
        $("[data-sum-strategy]").textContent = state.parallel ? "All at the same time" : "One at a time";

        const namesValid = [...state.targets.values()].every((n) => contextOf(n));
        let step = 1, hint = "Upload a WAR to start.";
        if (r) { step = 2; hint = "Select at least one instance."; }
        if (r && state.targets.size && !namesValid) hint = "Fix the invalid WAR file names.";
        if (r && state.targets.size && namesValid) { step = 3; hint = "Ready to check connectivity."; }
        if (state.preview) {
            const blocked = counts(state.preview).blockers > 0;
            step = blocked ? 3 : 4;
            hint = blocked ? "Fix the problems, then check again." : "Review and deploy.";
        }
        showStep(step);
        $("[data-to-check]").disabled = !(r && state.targets.size && namesValid);
        $("[data-sum-hint]").textContent = hint;
        syncDeployButton();
    }

    // ---------- step 3: connectivity and preflight ----------
    function requestBody() {
        const parallel = state.parallel && state.targets.size > 1;
        return {
            releaseId: state.release.releaseId, environment: state.environment, serverGroupId: null,
            targets: [...state.targets.entries()].map(([instanceId, filename]) => ({ instanceId, filename })),
            strategy: parallel ? "PARALLEL" : "SEQUENTIAL", maxParallel: parallel ? Math.min(state.targets.size, state.options.maxParallel) : 1
        };
    }

    async function runCheck() {
        const box = $("[data-preview]");
        root.querySelector("[data-step='3']").hidden = false;
        box.replaceChildren(el("div", { class: "d-flex align-items-center gap-2 small text-secondary" },
            el("span", { class: "spinner-border spinner-border-sm" }), "Testing connections and inspecting " + plural(state.targets.size, "server") + "…"));
        $("[data-to-check]").disabled = true;
        root.querySelector("[data-step='3']").scrollIntoView({ behavior: "smooth", block: "start" });
        try {
            state.preview = await apiFetch(root.dataset.previewUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(requestBody()) });
            renderPreview(state.preview);
        } catch (e) {
            state.preview = null;
            box.replaceChildren(el("div", { class: "alert alert-danger small mb-0", text: e.message }));
        }
        update();
    }

    function list(items, cls) { return items.length ? el("ul", { class: "preflight-list " + cls }, items.map((t) => el("li", { text: t }))) : null; }
    const counts = (p) => ({
        blockers: p.blockers.length + p.rows.reduce((n, r) => n + r.blockers.length, 0),
        warnings: p.warnings.length + p.rows.reduce((n, r) => n + r.warnings.length, 0)
    });

    function renderPreview(p) {
        const { blockers, warnings } = counts(p);
        const banner = blockers ? el("div", { class: "alert alert-danger small" }, el("strong", { text: plural(blockers, "problem") + " must be fixed before deploying. " }), "Fix them, then check again.")
            : warnings ? el("div", { class: "alert alert-warning small" }, el("strong", { text: plural(warnings, "warning") + " to review. " }), "You can deploy after acknowledging them.")
            : el("div", { class: "alert alert-success small" }, el("strong", { text: "All instances are reachable and ready. " }), "Deploy when you are ready.");
        const rows = p.rows.map((r) => el("tr", { class: r.blockers.length ? "row-blocked" : r.warnings.length ? "row-warning" : "" },
            el("td", {}, el("strong", { text: r.instanceName }), el("div", { class: "small text-secondary", text: r.hostName + " · " + r.hostname })),
            el("td", { class: "small" }, el("span", { class: "badge " + (r.connectivityState === "ok" ? "text-bg-success" : "text-bg-danger"), text: r.connectivity })),
            el("td", {}, el("code", { text: r.filename }), el("div", { class: "small text-secondary", text: r.contextPath })),
            el("td", { class: "small" }, el("div", { text: (r.currentRelease || "none") + " → " + p.versionLabel }), el("div", { class: "text-secondary", text: r.remoteState })),
            el("td", { class: "small", text: r.backup }),
            el("td", { class: "small" }, list(r.blockers, "blockers"), list(r.warnings, "warnings"), !r.blockers.length && !r.warnings.length ? el("span", { class: "text-success", text: "✓ ready" }) : null)));
        // list() returns null when empty, which replaceChildren would print as "null"
        $("[data-preview]").replaceChildren(banner, list(p.blockers, "blockers") || "", list(p.warnings, "warnings") || "",
            el("div", { class: "table-responsive" }, el("table", { class: "table table-sm align-middle preview-table mb-0" },
                el("thead", {}, el("tr", {}, ["Target", "Connectivity", "Deploy as", "Current → new", "Backup", "Checks"].map((h) => el("th", { text: h })))),
                el("tbody", {}, rows))));
        $("[data-ack-row]").hidden = warnings === 0;
        $("[data-ack-label]").textContent = "I have reviewed the " + plural(warnings, "warning") + " above and want to continue.";
        $("[data-ack]").checked = false;
    }

    // ---------- step 4: deploy ----------
    function syncDeployButton() {
        const p = state.preview;
        if (!p) { $("[data-deploy]").disabled = true; return; }
        const { blockers, warnings } = counts(p);
        const ackOk = warnings === 0 || $("[data-ack]").checked;
        $("[data-confirm-box]").hidden = blockers > 0;
        $("[data-deploy]").disabled = blockers > 0 || !ackOk;
        $("[data-deploy-hint]").textContent = !ackOk ? "Acknowledge the warnings first." : "Deploys " + p.applicationName + " " + p.versionLabel + " to " + plural(p.rows.length, "instance") + ".";
    }

    async function deploy() {
        const button = $("[data-deploy]");
        button.disabled = true;
        button.textContent = "Starting…";
        try {
            const run = await apiFetch(root.dataset.submitUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({
                requestId: root.dataset.requestId, deployment: requestBody(), acknowledgeWarnings: $("[data-ack]").checked, confirmation: null
            }) });
            window.location.href = root.dataset.baseUrl + "deployments/" + run.id;
        } catch (e) {
            button.replaceChildren(el("i", { class: "bx bx-rocket me-1" }), "Deploy");
            button.disabled = false;
            Swal.fire({ icon: "error", title: "Deployment not started", text: e.message + (e.status === 409 ? " Check connectivity again to see what changed." : "") });
        }
    }

    // ---------- init ----------
    document.addEventListener("DOMContentLoaded", async () => {
        try { state.options = await apiFetch(root.dataset.optionsUrl); }
        catch (e) { root.replaceChildren(el("div", { class: "alert alert-danger", text: "Could not load the Tomcat instances: " + e.message })); return; }
        initUpload();
        renderTargets();
        $("[data-parallel]").addEventListener("change", (e) => { state.parallel = e.target.checked; invalidatePreview(); update(); });
        $("[data-to-check]").addEventListener("click", runCheck);
        $("[data-run-check]").addEventListener("click", runCheck);
        $("[data-ack]").addEventListener("change", syncDeployButton);
        $("[data-deploy]").addEventListener("click", deploy);
        update();
    });
})();
