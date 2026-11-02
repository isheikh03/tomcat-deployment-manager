// Host (WinSCP-style session) and Tomcat instance forms: key files, method switching and
// "test before saving". The server re-validates everything; nothing here is trusted.
(function () {
    "use strict";
    const MAX_KEY_BYTES = 16384;
    const { el } = window.ConnectivityUI;

    function value(form, name) {
        const field = form.elements.namedItem(name);
        if (!field) return null;
        if (field instanceof RadioNodeList) return field.value || null;
        return field.value === "" ? null : field.value;
    }

    function setBusy(button, busy, label) {
        if (busy) {
            button.dataset.label = button.textContent;
            button.disabled = true;
            button.replaceChildren(el("span", { class: "spinner-border spinner-border-sm me-1", "aria-hidden": "true" }), label);
        } else {
            button.disabled = false;
            button.textContent = button.dataset.label;
        }
    }

    function showResult(container, result) {
        container.hidden = false;
        container.replaceChildren();
        window.ConnectivityUI.renderChecks(container, result, { title: "Test result:", hideNotApplicable: true });
        container.append(el("div", { class: "small text-secondary mt-2", text: "This test used the values in the form. Nothing is saved until you press Save." }));
        container.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    function showError(container, message) {
        container.hidden = false;
        container.replaceChildren(el("div", { class: "alert alert-danger small mb-0", text: message }));
        container.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    // ---------- Host form ----------

    function initHostForm(form) {
        const radios = form.querySelectorAll("[data-auth-radio]");
        const syncAuth = () => {
            const method = value(form, "sshAuthMethod");
            form.querySelectorAll("[data-auth]").forEach((section) => { section.hidden = section.dataset.auth !== method; });
        };
        radios.forEach((r) => r.addEventListener("change", syncAuth));
        syncAuth();

        const keyText = form.querySelector("[data-key-text]");
        const keyStatus = form.querySelector("[data-key-status]");
        if (keyText.value || keyText.classList.contains("is-invalid")) keyText.hidden = false;
        form.querySelector("[data-toggle-paste]").addEventListener("click", () => { keyText.hidden = !keyText.hidden; if (!keyText.hidden) keyText.focus(); });

        form.querySelector("[data-key-file]").addEventListener("change", (event) => {
            const file = event.target.files[0];
            if (!file) return;
            if (file.size > MAX_KEY_BYTES) { keyStatus.textContent = "That file is too large to be a private key."; event.target.value = ""; return; }
            const reader = new FileReader();
            reader.onload = () => {
                const text = String(reader.result);
                const kind = text.startsWith("PuTTY-User-Key-File-") ? "PuTTY key"
                    : text.includes("OPENSSH PRIVATE KEY") ? "OpenSSH key"
                    : /BEGIN [A-Z ]*PRIVATE KEY/.test(text) ? "PEM key" : null;
                if (!kind) {
                    keyStatus.textContent = file.name + " does not look like a private key (did you choose the .pub file?).";
                    keyStatus.classList.add("text-danger");
                    return;
                }
                keyText.value = text;
                keyStatus.classList.remove("text-danger");
                keyStatus.textContent = "Loaded " + file.name + " (" + kind + "). It is encrypted when you save.";
                if (/Encryption: (?!none)/.test(text) || text.includes("ENCRYPTED")) keyStatus.textContent += " This key is passphrase-protected.";
            };
            reader.readAsText(file);
        });

        const button = form.querySelector("[data-draft-test]");
        const result = form.querySelector("[data-draft-result]");
        button.addEventListener("click", () => runHostTest(form, button, result));
    }

    function hostPayload(form) {
        return {
            hostId: value(form, "id") ? Number(value(form, "id")) : null,
            hostname: (value(form, "hostname") || "").trim(),
            port: Number(value(form, "sshPort")),
            username: (value(form, "sshUsername") || "").trim(),
            authMethod: value(form, "sshAuthMethod"),
            password: value(form, "sshPassword"),
            privateKey: value(form, "sshPrivateKey"),
            passphrase: value(form, "sshKeyPassphrase"),
            fingerprint: value(form, "sshHostKeyFingerprint")
        };
    }

    async function runHostTest(form, button, container) {
        const payload = hostPayload(form);
        if (!payload.hostname || !payload.username) { showError(container, "Enter the host name and user name first."); return; }
        setBusy(button, true, "Connecting…");
        let result;
        try {
            result = await apiFetch(form.dataset.draftUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) });
        } catch (e) {
            showError(container, e.message);
            return;
        } finally {
            setBusy(button, false);
        }
        showResult(container, result);

        if (result.hostKeyVerification === "UNKNOWN" && result.presentedHostKey) {
            // WinSCP-style prompt for a server that is not yet known.
            const { isConfirmed } = await Swal.fire({
                icon: "warning",
                title: "Continue connecting to an unknown server?",
                html: "<p class=\"small text-start\">The server's host key was not found in this site's settings. You have no guarantee that the server is the computer you think it is.</p>" +
                      "<p class=\"small text-start mb-1\">Server's key fingerprint:</p>",
                input: "text",
                inputValue: result.presentedHostKey,
                inputAttributes: { readonly: "readonly", "aria-label": "Server host key fingerprint" },
                footer: "Compare it with ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub run on the server itself.",
                showCancelButton: true,
                confirmButtonText: "Accept and test again",
                cancelButtonText: "Cancel",
                focusCancel: true
            });
            if (!isConfirmed) return;
            form.elements.namedItem("sshHostKeyFingerprint").value = result.presentedHostKey;
            form.querySelector("[data-advanced]").open = true;
            runHostTest(form, button, container);
            return;
        }
        if (result.hostKeyVerification === "MISMATCH") {
            Swal.fire({ icon: "error", title: "Host key does not match!",
                text: "The server presented " + result.presentedHostKey + ", which differs from the fingerprint in this site. " +
                      "This may be an attack or a reinstalled server. Investigate before changing the fingerprint." });
            return;
        }
        if (result.outcome === "SUCCESS") {
            Swal.fire({ toast: true, position: "top-end", icon: "success", title: "Connected. Press Save to keep this site.", showConfirmButton: false, timer: 4000 });
        }
    }

    // ---------- Tomcat instance form ----------

    function initInstanceForm(form) {
        const hostSelect = form.querySelector("[data-host-select]");
        const selectedHost = () => hostSelect.selectedOptions[0];

        const syncMethod = () => {
            const method = value(form, "deploymentMethod");
            form.querySelectorAll("[data-method]").forEach((p) => { p.hidden = p.dataset.method !== method; });
            form.querySelectorAll(".method-card").forEach((card) => card.classList.toggle("selected", card.querySelector("input").checked));
        };
        form.querySelectorAll("[data-method-radio]").forEach((r) => r.addEventListener("change", syncMethod));
        syncMethod();

        const syncSsh = () => {
            const option = selectedHost();
            const summary = form.querySelector("[data-ssh-summary]");
            summary.textContent = option && option.value ? option.dataset.ssh : "Choose a host";
            form.querySelector("[data-unpinned]").hidden = !(option && option.value && option.dataset.pinned !== "true");
        };
        hostSelect.addEventListener("change", syncSsh);
        syncSsh();

        const syncDir = () => {
            const base = (value(form, "catalinaBase") || "").replace(/\/+$/, "");
            const app = value(form, "appBase") || "";
            form.querySelector("[data-dir-preview]").textContent = !base && !app.startsWith("/") ? "—" : app.startsWith("/") ? app : base + "/" + app;
        };
        form.querySelectorAll("[data-dir-part]").forEach((i) => i.addEventListener("input", syncDir));
        syncDir();

        // Mirrors DeploymentFilename on the server: target file and Tomcat context path.
        const filenameInput = form.querySelector("[data-deploy-filename]");
        const syncFilename = () => {
            const name = (filenameInput.value || "").trim();
            const dir = form.querySelector("[data-dir-preview]").textContent;
            const valid = /^[A-Za-z0-9][A-Za-z0-9._#-]*\.war$/i.test(name) && !/[\\/]/.test(name);
            form.querySelector("[data-deploy-path]").textContent = valid ? (dir === "—" ? name : dir + "/" + name) : "—";
            let base = valid ? name.slice(0, -4) : "";
            const versionAt = base.indexOf("##");
            const version = versionAt >= 0 ? base.slice(versionAt + 2) : "";
            if (versionAt >= 0) base = base.slice(0, versionAt);
            form.querySelector("[data-deploy-context]").textContent = !valid ? "—" : base === "ROOT" ? "/" : "/" + base.replace(/#/g, "/");
            form.querySelector("[data-deploy-version]").textContent = version ? "(version " + version + ")" : "";
        };
        filenameInput.addEventListener("input", syncFilename);
        form.querySelectorAll("[data-dir-part]").forEach((i) => i.addEventListener("input", syncFilename));
        syncFilename();

        form.querySelector("[data-fill-manager-url]").addEventListener("click", () => {
            const option = selectedHost();
            if (!option || !option.value) { Swal.fire({ icon: "info", text: "Choose the Linux host first." }); return; }
            const host = option.dataset.hostname.includes(":") ? "[" + option.dataset.hostname + "]" : option.dataset.hostname;
            form.querySelector("[data-manager-url]").value = "https://" + host + ":8443/manager/text";
        });

        const button = form.querySelector("[data-draft-manager-test]");
        const container = form.querySelector("[data-method='TOMCAT_MANAGER'] [data-draft-result]");
        button.addEventListener("click", async () => {
            const payload = {
                instanceId: value(form, "id") ? Number(value(form, "id")) : null,
                managerUrl: (value(form, "managerUrl") || "").trim(),
                username: (value(form, "managerUsername") || "").trim(),
                password: value(form, "managerSecret"),
                timeoutSeconds: Number(value(form, "timeoutSeconds")) || null
            };
            if (!payload.managerUrl || !payload.username) { showError(container, "Enter the Manager URL and user name first."); return; }
            setBusy(button, true, "Testing…");
            try {
                const result = await apiFetch(form.dataset.draftManagerUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) });
                showResult(container, result);
            } catch (e) {
                showError(container, e.message);
            } finally {
                setBusy(button, false);
            }
        });
    }

    document.addEventListener("DOMContentLoaded", () => {
        const hostForm = document.getElementById("host-form");
        if (hostForm) initHostForm(hostForm);
        document.querySelectorAll("form[data-draft-manager-url]").forEach(initInstanceForm);
    });
})();

// "Use these" for runtime versions detected by the last Tomcat Manager test.
document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-use-detected]").forEach((button) => button.addEventListener("click", () => {
        const form = button.closest("form");
        form.querySelector("[data-runtime-tomcat]").value = button.dataset.tomcat || "";
        if (button.dataset.java) form.querySelector("[data-runtime-java]").value = button.dataset.java;
        button.textContent = "Applied ✓";
        button.disabled = true;
    }));
});

// Application health check switch: the health URL is required only while the check is on.
document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-health-switch]").forEach((toggle) => {
        const form = toggle.closest("form");
        const sync = () => {
            const on = toggle.checked;
            form.querySelector("[data-health-on]").hidden = !on;
            form.querySelector("[data-health-off]").hidden = on;
            form.querySelector("[data-health-optional]").hidden = on;
            form.querySelector("[data-health-fields]").classList.toggle("health-fields-off", !on);
            form.querySelector("[data-health-url]").required = on;
        };
        toggle.addEventListener("change", sync);
        sync();
    });
});

// Backup switch: explains what switching it off means.
document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-backup-switch]").forEach((toggle) => {
        const form = toggle.closest("form");
        const sync = () => {
            form.querySelector("[data-backup-on]").hidden = !toggle.checked;
            form.querySelector("[data-backup-off]").hidden = toggle.checked;
        };
        toggle.addEventListener("change", sync);
        sync();
    });
});
