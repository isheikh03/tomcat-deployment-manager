// Runs in <head>: applies the saved light/dark choice before the page paints (same storage key as the Solic template).
(function () {
    "use strict";
    try {
        if (localStorage.getItem("Solicdarktheme")) {
            const html = document.documentElement;
            html.setAttribute("data-theme-mode", "dark");
            html.setAttribute("data-menu-styles", "dark");
        }
    } catch (e) { /* storage unavailable: stay light */ }
})();
