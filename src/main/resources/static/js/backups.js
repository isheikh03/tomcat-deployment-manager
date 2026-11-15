// Backup management: integrity verification and deletion (the server enforces roles and protection).
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

    async function remove(button) {
        const rollbackPoint = button.dataset.rollbackPoint === "true";
        const consequence = !rollbackPoint ? "Its deployment can no longer be restored from it."
            : button.dataset.libraryFallback === "true"
                ? "<strong>This is the current rollback point.</strong> Afterwards a rollback can only restore the previous release from the library, not the exact WAR that ran on the server."
                : "<strong>This is the current rollback point.</strong> Afterwards this deployment <strong>cannot be rolled back at all</strong>.";
        const result = await Swal.fire({
            icon: "warning", title: rollbackPoint ? "Delete the current rollback point?" : "Delete this backup?",
            html: "<div class='text-start small'><p>The server backup of <strong data-label></strong> is deleted from disk. " + consequence
                + " This is recorded in the deployment and audit logs.</p>"
                + "<label class='form-label' for='delete-reason'>Reason</label>"
                + "<input id='delete-reason' class='form-control' placeholder='e.g. disk space; superseded by newer releases'>"
                + (rollbackPoint ? "<div class='form-check mt-3'><input class='form-check-input' type='checkbox' id='delete-confirm'>"
                    + "<label class='form-check-label' for='delete-confirm'>I understand that this rollback option is lost.</label></div>" : "")
                + "</div>",
            didOpen: (popup) => { popup.querySelector("[data-label]").textContent = button.dataset.label; },
            showCancelButton: true, confirmButtonText: "Delete backup", confirmButtonColor: "#b02a37", focusCancel: true,
            preConfirm: () => {
                const reason = document.getElementById("delete-reason").value.trim();
                if (reason.length < 5) { Swal.showValidationMessage("Give a reason (at least 5 characters)."); return false; }
                if (rollbackPoint && !document.getElementById("delete-confirm").checked) { Swal.showValidationMessage("Confirm that the rollback option is lost."); return false; }
                return reason;
            }
        });
        if (!result.isConfirmed) return;
        try {
            await apiFetch(api + button.dataset.delete + "/delete", { method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ reason: result.value, confirmRollbackPoint: rollbackPoint }) });
            root.querySelector("[data-backup-row='" + button.dataset.delete + "']").remove();
            // Refresh the "N selected" summary of the bulk delete, which may have counted the removed row.
            const box = root.querySelector("input[name='backupIds']");
            if (box) box.dispatchEvent(new Event("change"));
            Swal.fire({ icon: "success", text: "Backup deleted.", timer: 1600, showConfirmButton: false });
        } catch (e) {
            Swal.fire({ icon: "error", text: e.message });
        }
    }

    root.addEventListener("click", (event) => {
        const v = event.target.closest("[data-verify]");
        if (v) { verify(v); return; }
        const d = event.target.closest("[data-delete]");
        if (d) remove(d);
    });
})();
