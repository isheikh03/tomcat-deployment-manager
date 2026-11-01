// Shared UI behaviours. Validation and authorization are enforced on the server; these are conveniences.
(function () {
    "use strict";

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    /** fetch() wrapper that sends the CSRF token and parses JSON errors into Error objects. */
    window.apiFetch = async function (url, options = {}) {
        const headers = Object.assign({ "Accept": "application/json" }, options.headers || {});
        if (options.method && options.method !== "GET" && csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
        const response = await fetch(url, Object.assign({ credentials: "same-origin" }, options, { headers }));
        const isJson = (response.headers.get("Content-Type") || "").includes("application/json");
        const body = isJson ? await response.json() : null;
        if (!response.ok) {
            let message = body && body.message ? body.message : "Request failed (HTTP " + response.status + ").";
            if (response.status === 401) message = "Your session has expired. Please sign in again.";
            if (response.status === 403) message = "Your role does not permit this action.";
            const error = new Error(message);
            error.status = response.status;
            throw error;
        }
        return body;
    };

    window.confirmAction = async function (text, confirmText) {
        if (!window.Swal) return window.confirm(text);
        const result = await Swal.fire({ icon: "warning", text, showCancelButton: true, confirmButtonText: confirmText || "Confirm", focusCancel: true });
        return result.isConfirmed;
    };

    document.addEventListener("DOMContentLoaded", () => {
        document.querySelectorAll("form[data-confirm]").forEach((form) => {
            form.addEventListener("submit", async (event) => {
                if (form.dataset.confirmed === "true") return;
                event.preventDefault();
                if (await confirmAction(form.dataset.confirm, "Yes, continue")) {
                    form.dataset.confirmed = "true";
                    form.submit();
                }
            });
        });

        // Whole table rows act as links, without hijacking clicks on inner links or buttons.
        document.querySelectorAll("tr.row-link[data-href]").forEach((row) => {
            row.addEventListener("click", (event) => {
                if (event.target.closest("a, button, input, select, form")) return;
                window.location.href = row.dataset.href;
            });
        });

        // Filters that apply immediately when changed.
        document.querySelectorAll("[data-autosubmit]").forEach((input) => input.addEventListener("change", () => input.form.submit()));

        // Show only the credential fields for the selected SSH authentication method.
        document.querySelectorAll("[data-auth-switch]").forEach((select) => {
            const sync = () => document.querySelectorAll("[data-auth]").forEach((el) => { el.hidden = el.dataset.auth !== select.value; });
            select.addEventListener("change", sync);
            sync();
        });

        // Show/hide a password field (sign-in page).
        document.querySelectorAll("[data-toggle-password]").forEach((button) => button.addEventListener("click", () => {
            const input = document.getElementById(button.dataset.togglePassword);
            const show = input.type === "password";
            input.type = show ? "text" : "password";
            button.textContent = show ? "Hide" : "Show";
            button.setAttribute("aria-label", show ? "Hide password" : "Show password");
        }));

        // Show Tomcat Manager settings only for the Manager deployment method.
        document.querySelectorAll("[data-method-switch]").forEach((select) => {
            const sync = () => document.querySelectorAll("[data-method]").forEach((el) => { el.hidden = el.dataset.method !== select.value; });
            select.addEventListener("change", sync);
            sync();
        });
    });
})();
