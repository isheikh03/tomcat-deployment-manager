// Application form: release version settings. Shows the pattern only for automatic versions, fills it from the
// ready-made patterns and shows an example of the versions it produces. The server validates the pattern again.
(function () {
    "use strict";
    const root = document.querySelector("[data-version-settings]");
    if (!root) return;
    const modes = [...root.querySelectorAll("input[data-version-mode]")];
    const group = root.querySelector("[data-version-pattern-group]");
    const input = root.querySelector("input[data-version-pattern]");
    const example = root.querySelector("[data-version-example]");

    const TOKEN = /\{([A-Za-z]+)(?::(\d))?\}/g;
    const KNOWN = ["n", "yyyy", "yy", "MM", "dd", "HH", "mm", "ss", "file", "manifest"];
    const two = (v) => String(v).padStart(2, "0");

    /** The pattern filled in like the server does, with a sample file/manifest version (2.4.1). */
    function render(pattern, n, now) {
        return pattern.replace(TOKEN, (whole, name, width) => {
            switch (name) {
                case "n": return String(n).padStart(width ? Number(width) : 0, "0");
                case "yyyy": return String(now.getFullYear());
                case "yy": return String(now.getFullYear()).slice(-2);
                case "MM": return two(now.getMonth() + 1);
                case "dd": return two(now.getDate());
                case "HH": return two(now.getHours());
                case "mm": return two(now.getMinutes());
                case "ss": return two(now.getSeconds());
                case "file": case "manifest": return "2.4.1";
                default: return whole;
            }
        });
    }

    /** A short reason why the pattern cannot be used, or "" (the server gives the exact message on save). */
    function problem(pattern) {
        if (!pattern) return "Enter a pattern, or pick one below.";
        const names = [...pattern.matchAll(TOKEN)].map((m) => m[1]);
        const unknown = names.find((n) => !KNOWN.includes(n));
        if (unknown) return "Unknown placeholder {" + unknown + "}.";
        if (!names.length) return "Add a placeholder such as {n}.";
        if (!/^[A-Za-z0-9][A-Za-z0-9._+-]*$/.test(render(pattern, 1, new Date()))) return "Use only letters, digits and . _ + - outside placeholders, and start with a letter or digit.";
        return "";
    }

    function update() {
        const auto = modes.some((m) => m.checked && m.value === "AUTO");
        group.hidden = !auto;
        input.required = auto;
        if (!auto) return;
        const pattern = input.value.trim();
        const why = problem(pattern);
        example.replaceChildren();
        if (why) {
            example.className = "small mt-2 text-danger";
            example.textContent = why;
            return;
        }
        example.className = "small mt-2 text-secondary";
        const now = new Date();
        const first = render(pattern, 1, now), second = render(pattern, 2, now);
        const code = (text) => { const c = document.createElement("code"); c.textContent = text; return c; };
        example.append("Example: ", code(first));
        if (second !== first) example.append(", then ", code(second));
        if (/\{(file|manifest)\}/.test(pattern)) example.append(" (with 2.4.1 as the WAR's version)");
    }

    root.querySelectorAll("[data-version-preset]").forEach((button) => button.addEventListener("click", () => {
        input.value = button.dataset.versionPreset;
        input.classList.remove("is-invalid");
        update();
        input.focus();
    }));
    modes.forEach((m) => m.addEventListener("change", update));
    input.addEventListener("input", () => { input.classList.remove("is-invalid"); update(); });
    update();
})();
