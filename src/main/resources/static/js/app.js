// Shared UI behaviours. Validation and authorization are enforced on the server; these are conveniences.
(function () {
    "use strict";

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    /**
     * Turns an application path from the server (for example "/deployments/5") into a link that works under the
     * context path the app is deployed at. Full URLs and relative links are returned unchanged.
     */
    const appBase = document.querySelector('meta[name="app-base"]')?.content || "/";
    window.appUrl = function (path) {
        if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//")) return path;
        if (appBase !== "/" && (path + "/").startsWith(appBase)) return path;   // already includes the context path
        return (appBase.endsWith("/") ? appBase.slice(0, -1) : appBase) + path;
    };

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

        // Filters that apply immediately when changed (requestSubmit also runs the submit handlers below).
        document.querySelectorAll("[data-autosubmit]").forEach((input) => input.addEventListener("change", () => {
            if (input.form.requestSubmit) input.form.requestSubmit(); else input.form.submit();
        }));

        // List filters: leave empty fields out of the address, so it stays short and readable.
        document.querySelectorAll("form.list-toolbar").forEach((form) => form.addEventListener("submit", () => {
            form.querySelectorAll("input[name], select[name]").forEach((field) => { if (field.value === "") field.disabled = true; });
        }));
        // Coming back with the browser's Back button: re-enable them.
        window.addEventListener("pageshow", () => document.querySelectorAll("form.list-toolbar [name]:disabled").forEach((f) => { f.disabled = false; }));

        // "/" jumps to the list search, unless the user is already typing somewhere.
        const search = document.querySelector("[data-search-shortcut]");
        if (search) document.addEventListener("keydown", (event) => {
            const typing = event.target.closest("input, textarea, select, [contenteditable='true']");
            if (event.key === "/" && !typing && !event.ctrlKey && !event.metaKey && !event.altKey) { event.preventDefault(); search.focus(); search.select(); }
        });

        // Sub-menu headers are toggled by the template's menu script; keep "#" out of the address.
        document.querySelectorAll("a[data-submenu-toggle]").forEach((link) => link.addEventListener("click", (event) => {
            event.preventDefault();
            const item = link.closest(".slide.has-sub");
            const menu = item && item.querySelector(".slide-menu");
            if (menu) setTimeout(() => link.setAttribute("aria-expanded", String(getComputedStyle(menu).display !== "none")), 350);
        }));

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
