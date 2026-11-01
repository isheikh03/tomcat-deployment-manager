// Connection-test panel for host and instance detail pages.
// All remote text is inserted with textContent, never innerHTML.
(function () {
    "use strict";

    const STATUS = {
        SUCCESS: { label: "Success", cls: "text-bg-success", icon: "✓" },
        WARNING: { label: "Warning", cls: "text-bg-warning", icon: "!" },
        FAILED: { label: "Failed", cls: "text-bg-danger", icon: "✕" },
        NOT_APPLICABLE: { label: "Not applicable", cls: "text-bg-light border", icon: "–" },
        SKIPPED: { label: "Skipped", cls: "text-bg-secondary", icon: "–" }
    };

    function el(tag, attrs, ...children) {
        const node = document.createElement(tag);
        Object.entries(attrs || {}).forEach(([k, v]) => {
            if (k === "class") node.className = v; else if (k === "text") node.textContent = v; else node.setAttribute(k, v);
        });
        children.flat().forEach((c) => { if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c))); });
        return node;
    }

    function badge(status) {
        const s = STATUS[status] || { label: status, cls: "text-bg-secondary" };
        return el("span", { class: "badge " + s.cls, text: s.label });
    }

    function formatTime(iso) {
        if (!iso) return "—";
        return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "medium" });
    }

    /** Renders the outcome line and the per-check table. Checks that do not apply can be hidden. */
    function renderChecks(container, result, options = {}) {
        const header = el("div", { class: "d-flex flex-wrap align-items-center gap-2 mb-2" },
            el("strong", { text: options.title || "Overall:" }), badge(result.outcome),
            el("span", { class: "small text-secondary", text: formatTime(result.finishedAt) + " · " + result.durationMs + " ms · by " + result.initiatedBy }));
        const meta = el("div", { class: "small text-secondary mb-2" },
            el("span", { text: "Correlation ID " }), el("code", { text: result.correlationId }),
            result.remoteServerInfo ? el("span", { text: " · " + result.remoteServerInfo }) : null);
        const checks = options.hideNotApplicable ? result.checks.filter((c) => c.status !== "NOT_APPLICABLE") : result.checks;
        const rows = checks.map((c) => el("tr", {},
            el("td", { class: "text-nowrap", text: c.label }),
            el("td", {}, badge(c.status)),
            el("td", { class: "text-break", text: c.detail || "" })));
        const table = el("div", { class: "table-responsive" },
            el("table", { class: "table table-sm align-middle mb-0 check-table" },
                el("thead", {}, el("tr", {}, el("th", { text: "Check" }), el("th", { text: "Status" }), el("th", { text: "Details" }))),
                el("tbody", {}, rows)));
        container.append(header, meta, table);
    }

    window.ConnectivityUI = { el, badge, formatTime, renderChecks };

    function renderResult(panel, result) {
        const container = panel.querySelector("[data-result]");
        container.replaceChildren();
        if (!result) {
            container.append(el("div", { class: "empty-state py-4" },
                el("div", { class: "empty-icon" }, el("i", { class: "bx bx-transfer-alt" })),
                el("div", { class: "h6", text: "No connection test yet" }),
                el("p", { class: "small text-secondary mb-0", text: "Run a test to verify this target." })));
            return;
        }
        renderChecks(container, result);

        if (result.hostKeyVerification === "UNKNOWN" && result.presentedHostKey) {
            const box = el("div", { class: "alert alert-warning mt-3 mb-0 small" },
                el("strong", { text: "Unknown host key. " }),
                el("span", { text: "The server presented " }), el("code", { text: result.presentedHostKey }),
                el("span", { text: ". Compare it with the output of ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub on the server console before trusting it." }));
            if (panel.querySelector("[data-can-trust]") && panel.dataset.pinned !== "true") {
                const button = el("button", { class: "btn btn-sm btn-warning ms-2", type: "button", text: "Trust this host key…" });
                button.addEventListener("click", () => trustHostKey(panel, result.presentedHostKey));
                box.append(el("div", { class: "mt-2" }, button));
            }
            container.append(box);
        } else if (result.hostKeyVerification === "MISMATCH") {
            container.append(el("div", { class: "alert alert-danger mt-3 mb-0 small" },
                el("strong", { text: "Host key mismatch. " }),
                el("span", { text: "Do not proceed until the change is explained. If the server was legitimately rebuilt, an administrator must verify the new key out-of-band and update the host configuration." })));
        }
    }

    function renderHistory(panel, page) {
        const container = panel.querySelector("[data-history]");
        container.replaceChildren();
        const count = panel.querySelector("[data-history-count]");
        if (count) {
            count.hidden = !page || page.totalElements === 0;
            count.textContent = page ? String(page.totalElements) : "";
        }
        if (!page || page.content.length === 0) {
            container.append(el("div", { class: "text-secondary", text: "No previous tests." }));
            return;
        }
        const rows = page.content.map((r) => {
            const row = el("tr", { class: "history-row", tabindex: "0" },
                el("td", { text: formatTime(r.finishedAt) }), el("td", {}, badge(r.outcome)),
                el("td", { text: r.durationMs + " ms" }), el("td", { text: r.initiatedBy }));
            const show = () => {
                renderResult(panel, r);
                // In the tabbed layout the checks are on the Result tab.
                const resultTab = panel.querySelector("[data-result-tab]");
                if (resultTab && window.bootstrap) bootstrap.Tab.getOrCreateInstance(resultTab).show();
            };
            row.addEventListener("click", show);
            row.addEventListener("keydown", (e) => { if (e.key === "Enter") show(); });
            return row;
        });
        container.append(el("div", { class: "table-responsive" },
            el("table", { class: "table table-sm table-hover mb-1" },
                el("thead", {}, el("tr", {}, el("th", { text: "Finished" }), el("th", { text: "Outcome" }), el("th", { text: "Duration" }), el("th", { text: "By" }))),
                el("tbody", {}, rows))),
            el("div", { class: "text-secondary", text: "Showing the latest " + page.content.length + " of " + page.totalElements + ". Select a row to view its checks." }));
    }

    async function loadHistory(panel, renderLatest) {
        try {
            const page = await apiFetch(panel.dataset.historyUrl + "?size=10");
            renderHistory(panel, page);
            if (renderLatest) renderResult(panel, page.content[0]);
        } catch (e) {
            panel.querySelector("[data-result]").replaceChildren(el("div", { class: "alert alert-danger small", text: e.message }));
        }
    }

    async function runTest(panel, button) {
        const original = button.textContent;
        button.disabled = true;
        button.replaceChildren(el("span", { class: "spinner-border spinner-border-sm me-1", role: "status", "aria-hidden": "true" }), "Testing…");
        try {
            const result = await apiFetch(panel.dataset.testUrl, { method: "POST" });
            renderResult(panel, result);
            const resultTab = panel.querySelector("[data-result-tab]");
            if (resultTab && window.bootstrap) bootstrap.Tab.getOrCreateInstance(resultTab).show();
            loadHistory(panel, false);
        } catch (e) {
            Swal.fire({ icon: "error", title: "Connection test not run", text: e.message });
        } finally {
            button.disabled = false;
            button.textContent = original;
        }
    }

    async function trustHostKey(panel, fingerprint) {
        const { isConfirmed } = await Swal.fire({
            icon: "warning",
            title: "Trust this SSH host key?",
            html: "<p class=\"small\">Only continue if you have verified this fingerprint on the server itself, through a channel you trust.</p>" +
                  "<p class=\"small mb-1\">Type or paste the fingerprint to confirm:</p>",
            input: "text",
            inputPlaceholder: "SHA256:…",
            footer: "Presented: " + fingerprint.replace(/[<>&"]/g, ""),
            showCancelButton: true,
            confirmButtonText: "Trust and pin",
            focusCancel: true,
            preConfirm: (value) => {
                if ((value || "").trim() !== fingerprint) { Swal.showValidationMessage("The fingerprint does not match exactly."); return false; }
                return value;
            }
        });
        if (!isConfirmed) return;
        try {
            await apiFetch(panel.dataset.trustUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fingerprint }) });
            await Swal.fire({ icon: "success", title: "Host key pinned", text: "Run the connection test again to authenticate." });
            window.location.reload();
        } catch (e) {
            Swal.fire({ icon: "error", title: "Host key not pinned", text: e.message });
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.querySelectorAll("[data-connectivity]").forEach((panel) => {
            const button = panel.querySelector("[data-run-test]");
            if (button) button.addEventListener("click", () => runTest(panel, button));
            loadHistory(panel, true);
        });
    });
})();
