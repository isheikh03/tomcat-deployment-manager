// Deployment wizard: release → environment → targets → strategy → preflight preview → confirm.
// The server re-runs the full preflight on submit; nothing here is trusted. All text uses textContent.
(function () {
    "use strict";

    const root = document.getElementById("wizard");
    if (!root) return;

    const state = { options: null, app: null, release: null, environment: null, group: "", targets: new Map(), strategy: "SEQUENTIAL", maxParallel: 2, preview: null };
    const FILENAME = /^[A-Za-z0-9][A-Za-z0-9._#-]{0,195}\.war$/;

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
    const contextOf = (name) => {
        if (!FILENAME.test(name || "")) return null;
        let base = name.slice(0, -4);
        if (base.includes("##")) base = base.slice(0, base.indexOf("##"));
        return base === "ROOT" ? "/" : "/" + base.replace(/#/g, "/");
    };
    const date = (iso) => new Date(iso).toLocaleDateString(undefined, { dateStyle: "medium" });

    function showStep(n) {
        root.querySelectorAll("[data-step]").forEach((s) => { if (Number(s.dataset.step) <= n) s.hidden = false; });
        root.querySelectorAll("[data-step-nav]").forEach((li) => {
            const k = Number(li.dataset.stepNav);
            li.classList.toggle("done", k < n);
            li.classList.toggle("active", k === n);
        });
    }

    function invalidatePreview() {
        if (!state.preview) return;
        state.preview = null;
        $("[data-confirm-box]").hidden = true;
        $("[data-preview]").replaceChildren(el("div", { class: "alert alert-secondary small mb-0", text: "The selection changed. Run the preflight again before deploying." }));
    }

    // ---------- step 1: release ----------
    function renderApplications() {
        const select = $("#w-app");
        select.replaceChildren(el("option", { value: "", text: "Choose application…" }),
            ...state.options.applications.map((a) => el("option", { value: a.id, text: a.name + " (" + a.releases.length + " release" + (a.releases.length === 1 ? "" : "s") + ")" })));
        select.addEventListener("change", () => { state.app = state.options.applications.find((a) => String(a.id) === select.value) || null; state.release = null; renderReleases(); update(); });
        const pre = root.dataset.preselectedRelease;
        if (pre) {
            const app = state.options.applications.find((a) => a.releases.some((r) => String(r.id) === pre));
            if (app) { select.value = app.id; state.app = app; state.release = app.releases.find((r) => String(r.id) === pre); }
        }
        renderReleases();
    }

    function renderReleases() {
        const box = $("[data-releases]");
        if (!state.app) { box.replaceChildren(el("p", { class: "small text-secondary mb-0", text: "Choose an application first." })); return; }
        if (state.app.releases.length === 0) { box.replaceChildren(el("p", { class: "small text-secondary mb-0", text: "This application has no available releases. Upload one in the release library." })); return; }
        box.replaceChildren(...state.app.releases.map((r, i) => {
            const input = el("input", { type: "radio", name: "release", value: r.id, checked: state.release ? state.release.id === r.id : false });
            input.addEventListener("change", () => { state.release = r; invalidatePreview(); update(); });
            return el("label", { class: "choice-item" }, input,
                el("span", {}, el("strong", { text: r.versionLabel }), i === 0 ? el("span", { class: "badge text-bg-light border ms-2", text: "latest" }) : null,
                    el("small", { text: "Uploaded " + date(r.uploadedAt) + " · SHA-256 " + r.sha256.slice(0, 10) + "…" + (r.hasNotes ? "" : " · no release notes") })));
        }));
    }

    // ---------- step 2: environment ----------
    function renderEnvironments() {
        renderUnassigned();
        $("[data-environments]").replaceChildren(...state.options.environments.map((e) => {
            const input = el("input", { type: "radio", name: "environment", value: e.id, disabled: e.instanceCount === 0, checked: state.environment === e.id });
            input.addEventListener("change", () => { state.environment = e.id; state.targets.clear(); state.group = ""; $("#w-group").value = ""; invalidatePreview(); renderTargets(); update(); });
            return el("label", { class: "env-card" + (e.production ? " production" : "") + (e.instanceCount === 0 ? " empty" : ""),
                    title: e.instanceCount === 0 ? "No Tomcat instance is assigned to " + e.label + " yet" : null }, input,
                el("strong", { text: e.label }),
                el("small", { text: e.instanceCount === 0 ? "No instances assigned" : e.instanceCount + " instance" + (e.instanceCount === 1 ? "" : "s") }),
                e.production ? el("small", { class: "text-danger", text: "Sequential · notes · typed confirmation" }) : null);
        }));
    }

    /** Instances without an environment cannot be deployed to; say so and link straight to fixing it. */
    function renderUnassigned() {
        const box = $("[data-unassigned]");
        const unassigned = state.options.instances.filter((i) => !i.environment);
        if (unassigned.length === 0) { box.replaceChildren(); return; }
        const none = state.options.environments.every((e) => e.instanceCount === 0);
        const isAdmin = !!root.querySelector("[data-is-admin]");
        const items = unassigned.map((i) => el("li", {}, isAdmin
            ? el("a", { href: "/infrastructure/instances/" + i.id + "/edit#environment1", text: i.name + " (" + i.hostName + ")" })
            : el("span", { text: i.name + " (" + i.hostName + ")" })));
        box.replaceChildren(el("div", { class: "alert " + (none ? "alert-warning" : "alert-secondary") + " small" },
            el("strong", { text: none ? "No Tomcat instance has an environment yet, so there is nothing to deploy to. "
                                      : unassigned.length + " instance" + (unassigned.length === 1 ? " has" : "s have") + " no environment and cannot be selected. " }),
            isAdmin ? "Open the instance, choose Development, Testing, Staging or Production under Environment, and save:"
                    : "Ask an administrator to assign an environment to:",
            el("ul", { class: "mb-0 mt-1" }, items),
            isAdmin ? el("div", { class: "mt-1 text-secondary", text: "Then reload this page." }) : null));
    }

    // ---------- step 3: targets ----------
    function instancesInEnv() { return state.options.instances.filter((i) => i.environment === state.environment); }

    function renderGroups() {
        const select = $("#w-group");
        select.append(...state.options.groups.map((g) => el("option", { value: g.id, text: g.name })));
        select.addEventListener("change", () => {
            state.group = select.value;
            state.targets.clear();
            if (state.group) instancesInEnv().filter((i) => i.enabled && i.groupIds.includes(Number(state.group)))
                .forEach((i) => state.targets.set(i.id, i.filename || ""));
            invalidatePreview(); renderTargets(); update();
        });
    }

    function renderTargets() {
        const body = $("[data-targets]");
        const list = instancesInEnv();
        if (list.length === 0) { body.replaceChildren(el("tr", {}, el("td", { colspan: 6, class: "text-center text-secondary py-4", text: "No Tomcat instances in this environment." }))); return; }
        body.replaceChildren(...list.map((i) => {
            const selected = state.targets.has(i.id);
            const checkbox = el("input", { type: "checkbox", class: "form-check-input", checked: selected, disabled: !i.enabled, "aria-label": "Deploy to " + i.name });
            const name = el("input", { class: "form-control form-control-sm font-monospace", value: selected ? state.targets.get(i.id) : (i.filename || ""), placeholder: "app.war", disabled: !selected, spellcheck: "false" });
            const ctx = el("code", { text: contextOf(name.value) || "—" });
            const groupBlocked = state.group && !i.groupIds.includes(Number(state.group));
            checkbox.disabled = !i.enabled || groupBlocked;
            checkbox.addEventListener("change", () => {
                if (checkbox.checked) state.targets.set(i.id, name.value); else state.targets.delete(i.id);
                name.disabled = !checkbox.checked;
                invalidatePreview(); update();
            });
            name.addEventListener("input", () => {
                state.targets.set(i.id, name.value.trim());
                ctx.textContent = contextOf(name.value.trim()) || "invalid name";
                name.classList.toggle("is-invalid", !contextOf(name.value.trim()));
                invalidatePreview(); update();
            });
            const conn = el("span", { class: "badge " + ({ VERIFIED: "text-bg-success", FAILED: "text-bg-danger", WARNING: "text-bg-warning", STALE: "text-bg-secondary" }[i.connectivityState] || "text-bg-light border"), text: i.connectivity });
            return el("tr", { class: i.enabled ? "" : "text-secondary" },
                el("td", {}, checkbox),
                el("td", {}, el("strong", { text: i.name }), el("div", { class: "small text-secondary", text: i.hostName + " · " + i.hostname + (i.enabled ? "" : " · disabled") })),
                el("td", { class: "small", text: i.method }), el("td", {}, conn), el("td", {}, name), el("td", {}, ctx));
        }));
        const all = $("[data-select-all]");
        all.checked = list.filter((i) => i.enabled).every((i) => state.targets.has(i.id));
    }

    // ---------- step 4: strategy ----------
    function renderStrategy() {
        const select = $("[data-max-parallel]");
        for (let n = 2; n <= state.options.maxParallel; n++) select.append(el("option", { value: n, text: n }));
        select.addEventListener("change", () => { state.maxParallel = Number(select.value); invalidatePreview(); update(); });
        root.querySelectorAll("input[name=strategy]").forEach((r) => r.addEventListener("change", () => { state.strategy = r.value; invalidatePreview(); update(); }));
    }

    // ---------- summary ----------
    function update() {
        const production = state.environment === "PRODUCTION";
        const parallel = root.querySelector("[data-parallel-choice] input");
        parallel.disabled = production;
        if (production && state.strategy === "PARALLEL") { state.strategy = "SEQUENTIAL"; root.querySelector("input[value=SEQUENTIAL]").checked = true; }
        $("[data-parallel-choice]").classList.toggle("disabled", production);

        $("[data-sum-release]").textContent = state.release ? state.app.name + " " + state.release.versionLabel : "—";
        const env = state.options.environments.find((e) => e.id === state.environment);
        $("[data-sum-env]").textContent = env ? env.label : "—";
        $("[data-sum-targets]").textContent = state.targets.size ? state.targets.size + " instance" + (state.targets.size === 1 ? "" : "s")
            + (state.group ? " (group " + $("#w-group").selectedOptions[0].textContent + ")" : "") : "—";
        $("[data-sum-strategy]").textContent = state.strategy === "SEQUENTIAL" ? "One at a time" : "Parallel, up to " + state.maxParallel;

        let step = 1, hint = "Choose a release to start.";
        if (state.release) { step = 2; hint = "Choose the environment."; }
        if (state.release && state.environment) { step = 3; hint = "Select at least one instance."; }
        const namesValid = [...state.targets.values()].every((n) => contextOf(n));
        if (step === 3 && state.targets.size > 0 && namesValid) { step = 4; hint = "Ready for preflight."; }
        if (step === 3 && state.targets.size > 0 && !namesValid) hint = "Fix the invalid WAR file names.";
        if (state.preview) step = 5;
        showStep(step);
        $("[data-to-preview]").disabled = step < 4;
        $("[data-sum-hint]").textContent = hint;
    }

    // ---------- step 5: preview ----------
    function requestBody() {
        return {
            releaseId: state.release.id, environment: state.environment, serverGroupId: state.group ? Number(state.group) : null,
            targets: [...state.targets.entries()].map(([instanceId, filename]) => ({ instanceId, filename })),
            strategy: state.strategy, maxParallel: state.strategy === "PARALLEL" ? state.maxParallel : 1
        };
    }

    async function runPreview() {
        showStep(5);
        const box = $("[data-preview]");
        $("[data-confirm-box]").hidden = true;
        box.replaceChildren(el("div", { class: "d-flex align-items-center gap-2 small text-secondary" },
            el("span", { class: "spinner-border spinner-border-sm" }), "Running preflight: testing connections and inspecting " + state.targets.size + " server(s)…"));
        root.querySelector("[data-step='5']").scrollIntoView({ behavior: "smooth", block: "start" });
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

    function renderPreview(p) {
        const blockers = p.blockers.length + p.rows.reduce((n, r) => n + r.blockers.length, 0);
        const warnings = p.warnings.length + p.rows.reduce((n, r) => n + r.warnings.length, 0);
        const banner = blockers ? el("div", { class: "alert alert-danger small" }, el("strong", { text: blockers + " blocker" + (blockers === 1 ? "" : "s") + " — this deployment cannot start. " }), "Fix them and run the preflight again.")
            : warnings ? el("div", { class: "alert alert-warning small" }, el("strong", { text: warnings + " warning" + (warnings === 1 ? "" : "s") + " to review. " }), "You can deploy after acknowledging them.")
            : el("div", { class: "alert alert-success small" }, el("strong", { text: "All checks passed. " }), "Review the exact targets below and deploy.");
        const header = el("div", { class: "preview-head small" },
            el("span", {}, el("strong", { text: p.applicationName + " " + p.versionLabel }), " · SHA-256 ", el("code", { text: p.sha256.slice(0, 16) + "…" })),
            el("span", { text: p.environment.charAt(0) + p.environment.slice(1).toLowerCase() + " · " + (p.strategy === "SEQUENTIAL" ? "one at a time" : "parallel ×" + p.maxParallel) + (p.serverGroupName ? " · group " + p.serverGroupName : "") }));
        const rows = p.rows.map((r) => el("tr", { class: r.blockers.length ? "row-blocked" : r.warnings.length ? "row-warning" : "" },
            el("td", {}, el("strong", { text: r.instanceName }), el("div", { class: "small text-secondary", text: r.hostName + " · " + r.hostname })),
            el("td", { class: "small", text: r.method === "SSH_SFTP" ? "SSH / SFTP" : "Tomcat Manager" }),
            el("td", {}, el("code", { text: r.filename }), el("div", { class: "small text-secondary", text: r.contextPath })),
            el("td", { class: "small" }, el("div", { text: (r.currentRelease || "none") + " → " + p.versionLabel }), el("div", { class: "text-secondary", text: r.remoteState })),
            el("td", { class: "small" }, el("span", { class: "badge " + (r.connectivityState === "ok" ? "text-bg-success" : "text-bg-danger"), text: r.connectivity })),
            el("td", { class: "small" }, el("span", { class: "badge " + ({ COMPATIBLE: "text-bg-success", UNKNOWN: "text-bg-warning", INCOMPATIBLE: "text-bg-danger" }[r.compatibility]), text: r.compatibility.toLowerCase() })),
            el("td", { class: "small", text: r.backup }),
            el("td", { class: "small" }, list(r.blockers, "blockers"), list(r.warnings, "warnings"), !r.blockers.length && !r.warnings.length ? el("span", { class: "text-success", text: "✓ ready" }) : null)));
        // list() returns null when empty, which replaceChildren would print as "null"
        $("[data-preview]").replaceChildren(banner, header, list(p.blockers, "blockers") || "", list(p.warnings, "warnings") || "",
            el("div", { class: "table-responsive" }, el("table", { class: "table table-sm align-middle preview-table mb-0" },
                el("thead", {}, el("tr", {}, ["Target", "Method", "Deploy as", "Current → new", "Connectivity", "Compatibility", "Backup", "Checks"].map((h) => el("th", { text: h })))),
                el("tbody", {}, rows))));

        const canDeploy = blockers === 0;
        $("[data-confirm-box]").hidden = !canDeploy;
        $("[data-ack-row]").hidden = warnings === 0;
        $("[data-ack-label]").textContent = "I have reviewed the " + warnings + " warning" + (warnings === 1 ? "" : "s") + " above and want to continue.";
        $("[data-ack]").checked = false;
        $("[data-confirm-row]").hidden = !p.production;
        $("[data-confirm-text]").textContent = p.confirmationText;
        $("[data-confirm-input]").value = "";
        syncDeployButton();
    }

    function syncDeployButton() {
        const p = state.preview;
        if (!p) return;
        const warnings = p.warnings.length + p.rows.reduce((n, r) => n + r.warnings.length, 0);
        const ackOk = warnings === 0 || $("[data-ack]").checked;
        const confirmOk = !p.production || $("[data-confirm-input]").value.trim() === p.confirmationText;
        $("[data-deploy]").disabled = !(ackOk && confirmOk);
        $("[data-deploy-hint]").textContent = !ackOk ? "Acknowledge the warnings first." : !confirmOk ? "Type the version to confirm." :
            "Deploys to " + p.rows.length + " target" + (p.rows.length === 1 ? "" : "s") + ".";
    }

    async function deploy() {
        const button = $("[data-deploy]");
        button.disabled = true;
        button.textContent = "Starting…";
        try {
            const run = await apiFetch(root.dataset.submitUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({
                requestId: root.dataset.requestId, deployment: requestBody(), acknowledgeWarnings: $("[data-ack]").checked, confirmation: $("[data-confirm-input]").value.trim()
            }) });
            window.location.href = "/deployments/" + run.id;
        } catch (e) {
            button.textContent = "▶ Deploy";
            button.disabled = false;
            Swal.fire({ icon: "error", title: "Deployment not started", text: e.message + (e.status === 409 ? " Run the preflight again to see the current blockers." : "") });
        }
    }

    // ---------- init ----------
    document.addEventListener("DOMContentLoaded", async () => {
        try { state.options = await apiFetch(root.dataset.optionsUrl); }
        catch (e) { root.replaceChildren(el("div", { class: "alert alert-danger", text: "Could not load deployment options: " + e.message })); return; }
        renderApplications(); renderEnvironments(); renderGroups(); renderStrategy();
        $("[data-select-all]").addEventListener("change", (e) => {
            instancesInEnv().filter((i) => i.enabled && (!state.group || i.groupIds.includes(Number(state.group))))
                .forEach((i) => { if (e.target.checked) state.targets.set(i.id, state.targets.get(i.id) ?? (i.filename || "")); else state.targets.delete(i.id); });
            invalidatePreview(); renderTargets(); update();
        });
        $("[data-to-preview]").addEventListener("click", runPreview);
        $("[data-run-preview]").addEventListener("click", runPreview);
        $("[data-ack]").addEventListener("change", syncDeployButton);
        $("[data-confirm-input]").addEventListener("input", syncDeployButton);
        $("[data-deploy]").addEventListener("click", deploy);
        update();
    });
})();
