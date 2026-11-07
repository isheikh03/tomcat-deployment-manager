// Only the browser knows the address the user really opened (a proxy may terminate HTTPS before the server).
(function () {
    "use strict";
    const warning = document.getElementById("insecure-cookie-warning");
    if (!warning) return;
    const local = ["localhost", "127.0.0.1", "[::1]"].includes(window.location.hostname);
    if (window.location.protocol === "http:" && !local) warning.classList.remove("d-none");
})();
