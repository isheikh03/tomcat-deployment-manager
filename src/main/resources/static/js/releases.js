// Release library: WAR upload with progress, integrity verification, metadata editing.
// The server validates everything again; these checks only give faster feedback.
(function () {
    "use strict";

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    const VERSION_LABEL = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,99}$/;

    function humanSize(bytes) {
        if (bytes < 1024) return bytes + " B";
        const units = ["KB", "MB", "GB", "TB"];
        let value = bytes, unit = -1;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return (value >= 100 ? value.toFixed(0) : value.toFixed(1)) + " " + units[unit];
    }

    /** "customer-portal-2.4.1.war" → "2.4.1"; "app-1.0.0-SNAPSHOT.war" → "1.0.0-SNAPSHOT". */
    function suggestVersion(filename) {
        const base = filename.replace(/\.war$/i, "");
        const match = base.match(/(\d+(?:\.\d+)+(?:[-_.+][A-Za-z0-9]+)*)$/) || base.match(/[-_]v?(\d+[A-Za-z0-9._+-]*)$/);
        return match && VERSION_LABEL.test(match[1]) ? match[1] : "";
    }

    /** Picks the application whose name best matches the file name, e.g. "customer-portal-2.war" → "Customer Portal". */
    function guessApplication(select, filename) {
        const normalize = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
        const file = normalize(filename.replace(/\.war$/i, "").replace(/[-_.]?v?\d.*$/, ""));
        if (!file) return;
        for (const option of select.options) {
            if (option.value && normalize(option.dataset.name || "") === file) { select.value = option.value; return; }
        }
    }

    function initUpload(form) {
        const maxBytes = Number(form.dataset.maxBytes);
        const fileInput = form.querySelector("#file");
        const zone = form.querySelector("[data-drop-zone]");
        const title = form.querySelector("[data-drop-title]");
        const fileError = form.querySelector("[data-file-error]");
        const app = form.querySelector("#applicationId");
        const version = form.querySelector("#versionLabel");
        const hint = form.querySelector("[data-version-hint]");
        const submit = form.querySelector("[data-submit]");
        const errorBox = form.querySelector("[data-upload-error]");
        let file = null, versionTouched = false;

        version.addEventListener("input", () => { versionTouched = true; version.classList.remove("is-invalid"); });
        app.addEventListener("change", () => app.classList.remove("is-invalid"));

        function choose(selected) {
            fileError.textContent = "";
            zone.classList.remove("is-invalid", "has-file");
            file = null;
            if (!selected) { title.textContent = "Drag a .war file here or browse"; return; }
            if (!/\.war$/i.test(selected.name)) { fileError.textContent = "Only .war files can be uploaded."; zone.classList.add("is-invalid"); return; }
            if (selected.size === 0) { fileError.textContent = "The file is empty."; zone.classList.add("is-invalid"); return; }
            if (selected.size > maxBytes) {
                fileError.textContent = selected.name + " is " + humanSize(selected.size) + ", larger than the " + form.dataset.maxSize + " limit.";
                zone.classList.add("is-invalid");
                return;
            }
            file = selected;
            zone.classList.add("has-file");
            title.textContent = selected.name + " · " + humanSize(selected.size);
            if (!app.value) guessApplication(app, selected.name);
            const suggestion = suggestVersion(selected.name);
            if (suggestion && (!versionTouched || !version.value)) {
                version.value = suggestion;
                hint.textContent = "Suggested from the file name. Change it if needed.";
            }
        }

        fileInput.addEventListener("change", () => choose(fileInput.files[0]));
        ["dragenter", "dragover"].forEach((type) => zone.addEventListener(type, (e) => { e.preventDefault(); zone.classList.add("dragging"); }));
        ["dragleave", "drop"].forEach((type) => zone.addEventListener(type, (e) => { e.preventDefault(); zone.classList.remove("dragging"); }));
        zone.addEventListener("drop", (e) => {
            const dropped = e.dataTransfer.files;
            if (dropped.length > 1) { fileError.textContent = "Drop one file at a time."; return; }
            choose(dropped[0]);
        });
        zone.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fileInput.click(); } });

        form.addEventListener("submit", (e) => {
            e.preventDefault();
            errorBox.replaceChildren();
            let valid = true;
            if (!app.value) { app.classList.add("is-invalid"); valid = false; }
            if (!VERSION_LABEL.test(version.value.trim())) { version.classList.add("is-invalid"); valid = false; }
            if (!file) { fileError.textContent = fileError.textContent || "Choose a .war file."; zone.classList.add("is-invalid"); valid = false; }
            if (!valid) { form.querySelector(".is-invalid")?.focus(); return; }
            send(form, file, { app, version, submit, errorBox });
        });
    }

    function send(form, file, ui) {
        const data = new FormData();
        data.append("applicationId", ui.app.value);
        data.append("versionLabel", ui.version.value.trim());
        data.append("releaseNotes", form.querySelector("#releaseNotes").value);
        data.append("file", file, file.name);

        const progress = form.querySelector("[data-progress]");
        const bar = form.querySelector("[data-progress-bar]");
        const percent = form.querySelector("[data-progress-percent]");
        const label = form.querySelector("[data-progress-label]");
        const cancel = form.querySelector("[data-cancel]");
        progress.hidden = false;
        ui.submit.disabled = true;
        form.querySelectorAll("input, select, textarea").forEach((el) => { el.readOnly = true; });

        const xhr = new XMLHttpRequest();
        const leaveGuard = (e) => { e.preventDefault(); e.returnValue = ""; };
        window.addEventListener("beforeunload", leaveGuard);
        const cancelUpload = (e) => { e.preventDefault(); xhr.abort(); };
        cancel.addEventListener("click", cancelUpload);

        const finish = () => {
            window.removeEventListener("beforeunload", leaveGuard);
            cancel.removeEventListener("click", cancelUpload);
            ui.submit.disabled = false;
            form.querySelectorAll("input, select, textarea").forEach((el) => { el.readOnly = false; });
            bar.classList.remove("progress-bar-animated");
        };

        xhr.upload.addEventListener("progress", (e) => {
            if (!e.lengthComputable) return;
            const pct = Math.round((e.loaded / e.total) * 100);
            bar.style.width = pct + "%";
            percent.textContent = pct + "%";
            label.textContent = pct < 100 ? "Uploading " + humanSize(e.loaded) + " of " + humanSize(e.total) + "…" : "Verifying checksum and inspecting the WAR…";
        });
        xhr.addEventListener("load", () => {
            let body = null;
            try { body = JSON.parse(xhr.responseText); } catch (ignored) { /* non-JSON error page */ }
            if (xhr.status === 201 && body) {
                window.removeEventListener("beforeunload", leaveGuard);
                label.textContent = "Release created.";
                bar.classList.add("bg-success");
                window.location.href = body.url + "?uploaded=1";
                return;
            }
            finish();
            progress.hidden = true;
            showError(ui.errorBox, xhr.status, body);
        });
        xhr.addEventListener("error", () => { finish(); progress.hidden = true; showError(ui.errorBox, 0, null); });
        xhr.addEventListener("abort", () => { finish(); progress.hidden = true; ui.errorBox.replaceChildren(alert("info", "Upload cancelled. Nothing was saved.")); });

        xhr.open("POST", form.dataset.uploadUrl);
        if (csrfHeader && csrfToken) xhr.setRequestHeader(csrfHeader, csrfToken);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.send(data);
        cancel.textContent = "Cancel upload";
    }

    function alert(kind, text, link) {
        const box = document.createElement("div");
        box.className = "alert alert-" + kind + " mt-3 mb-0";
        box.setAttribute("role", "alert");
        box.textContent = text;
        if (link) {
            const a = document.createElement("a");
            a.href = link.href; a.textContent = link.text; a.className = "alert-link ms-1";
            box.append(" ", a);
        }
        return box;
    }

    function showError(container, status, body) {
        let message = body && body.message ? body.message : "The upload failed (HTTP " + status + "). Please try again.";
        if (status === 0) message = "The connection was interrupted. Nothing was saved; please try again.";
        if (status === 401) message = "Your session has expired. Sign in again and repeat the upload.";
        if (status === 403) message = "Your role is not allowed to upload releases.";
        const link = body && body.link ? { href: body.link, text: "Open the existing release" } : null;
        container.replaceChildren(alert(status === 409 ? "warning" : "danger", message, link));
    }

    // ---------- Release detail ----------

    function initDetail() {
        document.querySelectorAll("[data-copy]").forEach((button) => button.addEventListener("click", async () => {
            const text = document.querySelector(button.dataset.copy).textContent.trim();
            try { await navigator.clipboard.writeText(text); button.textContent = "Copied ✓"; }
            catch (e) { button.textContent = "Select and copy"; }
            setTimeout(() => { button.textContent = "Copy"; }, 2000);
        }));

        document.querySelectorAll("[data-toggle-edit]").forEach((button) => button.addEventListener("click", () => {
            const edit = document.querySelector("[data-edit]");
            const view = document.querySelector("[data-view]");
            if (!edit) return;
            edit.hidden = !edit.hidden;
            view.hidden = !edit.hidden;
            if (!edit.hidden) edit.querySelector("textarea").focus();
        }));

        document.querySelectorAll("[data-verify-url]").forEach((button) => button.addEventListener("click", async () => {
            const status = document.querySelector("[data-verify-status]");
            button.disabled = true;
            button.textContent = "Verifying…";
            try {
                const result = await apiFetch(button.dataset.verifyUrl, { method: "POST" });
                const badge = document.createElement("span");
                badge.className = "badge " + (result.ok ? "text-bg-success" : "text-bg-danger");
                badge.textContent = (result.ok ? "Verified just now" : "Failed just now");
                status.replaceChildren(badge);
                Swal.fire({ icon: result.ok ? "success" : "error", title: result.ok ? "Integrity verified" : "Integrity check failed", text: result.message });
            } catch (e) {
                Swal.fire({ icon: "error", title: "Verification not run", text: e.message });
            } finally {
                button.disabled = false;
                button.textContent = "Verify now";
            }
        }));

        if (new URLSearchParams(window.location.search).get("uploaded") === "1" && window.Swal) {
            Swal.fire({ toast: true, position: "top-end", icon: "success", title: "Release uploaded and checksummed.", showConfirmButton: false, timer: 4000 });
            history.replaceState(null, "", window.location.pathname);
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const form = document.getElementById("upload-form");
        if (form) initUpload(form);
        initDetail();
    });
})();
