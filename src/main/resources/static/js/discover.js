// Find Tomcat instances: select all / count of the suggestions, and a busy state while the server is scanned.
(function () {
    "use strict";
    document.addEventListener("DOMContentLoaded", () => {
        document.querySelectorAll("[data-scan-button]").forEach((button) => button.closest("form").addEventListener("submit", () => {
            button.disabled = true;
            button.replaceChildren(Object.assign(document.createElement("span"), { className: "spinner-border spinner-border-sm me-1" }), "Scanning…");
        }));

        const form = document.querySelector("form[data-discovery]");
        if (!form) return;
        const boxes = () => [...form.querySelectorAll("input[type=checkbox][data-row-select]")];
        const all = form.querySelector("[data-select-all]");
        const count = form.querySelector("[data-selected-count]");
        const add = form.querySelector("[data-add-selected]");
        const sync = () => {
            const chosen = boxes().filter((b) => b.checked).length;
            all.checked = chosen > 0 && chosen === boxes().length;
            all.indeterminate = chosen > 0 && !all.checked;
            count.textContent = chosen ? chosen + " selected" : "Nothing selected";
            add.disabled = chosen === 0;
        };
        all.addEventListener("change", () => { boxes().forEach((b) => { b.checked = all.checked; }); sync(); });
        boxes().forEach((b) => b.addEventListener("change", sync));
        sync();
    });
})();

// "Set all…" environment: fills the environment of every row (each row can still be changed).
document.addEventListener("DOMContentLoaded", () => {
    const all = document.querySelector("[data-environment-all]");
    if (!all) return;
    all.addEventListener("change", () => {
        if (!all.value) return;
        document.querySelectorAll("select[data-row-environment]").forEach((s) => { s.value = all.value; });
    });
});
