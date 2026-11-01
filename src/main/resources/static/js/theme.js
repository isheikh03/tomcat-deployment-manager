// Template behaviours the layout uses: light/dark toggle, full screen, scroll-to-top, button waves,
// scrollable menu and the coloured page band sized to the page header.
(function () {
    "use strict";
    const html = document.documentElement;

    function toggleTheme() {
        const dark = html.getAttribute("data-theme-mode") !== "dark";
        html.setAttribute("data-theme-mode", dark ? "dark" : "light");
        html.setAttribute("data-menu-styles", dark ? "dark" : "light");
        try {
            if (dark) localStorage.setItem("Solicdarktheme", "true"); else localStorage.removeItem("Solicdarktheme");
        } catch (e) { /* not remembered */ }
    }

    function toggleFullscreen(link) {
        const open = link.querySelector(".full-screen-open");
        const close = link.querySelector(".full-screen-close");
        if (!document.fullscreenElement) {
            html.requestFullscreen?.();
            open.classList.add("d-none"); close.classList.remove("d-none");
        } else {
            document.exitFullscreen?.();
            close.classList.add("d-none"); open.classList.remove("d-none");
        }
    }

    // The template paints a fixed-height band behind the page title; grow it so the page header always sits on it.
    function sizeBand() {
        const content = document.querySelector(".app-content > .container-fluid");
        if (!content) return;
        const intro = content.querySelector(":scope > .page-intro") || content.querySelector(":scope > nav[aria-label='breadcrumb']");
        if (!intro) return;
        const bottom = intro.getBoundingClientRect().bottom + window.scrollY;
        html.style.setProperty("--page-band-height", Math.max(190, Math.round(bottom + 70)) + "px");
    }

    document.addEventListener("DOMContentLoaded", () => {
        document.querySelectorAll("[data-theme-toggle]").forEach((el) => el.addEventListener("click", toggleTheme));
        document.querySelectorAll("[data-fullscreen]").forEach((el) => el.addEventListener("click", () => toggleFullscreen(el)));

        const scrollToTop = document.querySelector(".scrollToTop");
        if (scrollToTop) {
            window.addEventListener("scroll", () => { scrollToTop.style.display = window.scrollY > 100 ? "flex" : "none"; });
            scrollToTop.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));
        }

        if (window.Waves) { Waves.attach(".btn-wave", ["waves-light"]); Waves.init(); }
        const menu = document.getElementById("sidebar-scroll");
        if (menu && window.SimpleBar) new SimpleBar(menu, { autoHide: true });
        document.querySelectorAll("[data-bs-toggle='tooltip']").forEach((el) => new bootstrap.Tooltip(el));

        sizeBand();
        window.addEventListener("resize", sizeBand);
        if (document.fonts) document.fonts.ready.then(sizeBand);
    });
})();
