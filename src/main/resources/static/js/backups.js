// Restore points: integrity verification. Backups are deleted in the Storage cleanup tab (a form; the server enforces roles and protection).
(function () {
    "use strict";
    const root = document.getElementById("backups");
    if (!root) return;
    const api = root.dataset.api;

    async function verify(button) {
        const id = button.dataset.verify;
        const cell = root.querySelector("[data-backup-row='" + id + "'] [data-integrity]");
        button.disabled = true;
        button.textContent = "Verifying…";
        try {
            const result = await apiFetch(api + id + "/verify", { method: "POST" });
            const badge = document.createElement("span");
            badge.className = "badge " + (result.ok ? "text-bg-success" : "text-bg-danger");
            badge.textContent = result.ok ? "Verified just now" : "Failed just now";
            const detail = document.createElement("div");
            detail.className = "text-secondary mt-1";
            detail.textContent = result.message;
            cell.replaceChildren(badge, detail);
        } catch (e) {
            Swal.fire({ icon: "error", text: e.message });
        } finally {
            button.disabled = false;
            button.textContent = "Verify";
        }
    }

    root.addEventListener("click", (event) => {
        const v = event.target.closest("[data-verify]");
        if (v) verify(v);
    });
})();
