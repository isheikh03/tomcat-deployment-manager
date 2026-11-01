// Health monitoring: manual "Check now" and optional auto-refresh of the overview.
(function () {
    "use strict";
    const root = document.getElementById("health");
    if (!root) return;
    const base = root.dataset.checkBase;
    let refreshTimer = null;

    async function check(button) {
        const label = button.textContent;
        button.disabled = true;
        button.textContent = "Checking…";
        try {
            const r = await apiFetch(base + button.dataset.check + "/check", { method: "POST" });
            await Swal.fire({ icon: r.result.healthy ? "success" : "error", title: r.result.healthy ? "Healthy" : "Health check failed",
                text: r.result.detail + " (" + r.result.elapsedMs + " ms)", timer: r.result.healthy ? 1800 : undefined, showConfirmButton: !r.result.healthy });
            window.location.reload();
        } catch (e) {
            Swal.fire({ icon: "error", text: e.message });
            button.disabled = false;
            button.textContent = label;
        }
    }

    root.addEventListener("click", (event) => {
        const b = event.target.closest("[data-check]");
        if (b) { event.stopPropagation(); event.preventDefault(); check(b); }
    }, true);

    const auto = root.querySelector("[data-auto-refresh]");
    if (auto) {
        try { auto.checked = localStorage.getItem("health.autoRefresh") !== "off"; } catch (e) { /* storage unavailable */ }
        const schedule = () => {
            clearTimeout(refreshTimer);
            if (auto.checked) refreshTimer = setTimeout(() => window.location.reload(), 60000);
        };
        auto.addEventListener("change", () => {
            try { localStorage.setItem("health.autoRefresh", auto.checked ? "on" : "off"); } catch (e) { /* ignore */ }
            schedule();
        });
        schedule();
    }
})();
