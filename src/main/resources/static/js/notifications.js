// Navbar notification bell and the notifications page "mark all as read".
(function () {
    "use strict";

    function el(tag, cls, text) {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    }

    function render(bell, data) {
        const badge = bell.querySelector("[data-unread]");
        badge.hidden = data.unread === 0;
        badge.textContent = data.unread > 99 ? "99+" : String(data.unread);
        const list = bell.querySelector("[data-list]");
        if (!data.recent.length) { list.replaceChildren(el("div", "small text-secondary px-3 py-3", "No notifications yet.")); return; }
        list.replaceChildren(...data.recent.map((n) => {
            const item = el(n.link ? "a" : "div", "notification-item" + (n.unread ? " unread" : ""));
            if (n.link) item.href = n.link;
            const head = el("div", "d-flex justify-content-between gap-2");
            head.append(el("span", "fw-semibold small", n.title), el("span", "badge " + n.badgeClass, n.level));
            item.append(head, el("div", "small text-secondary text-truncate", n.message),
                el("div", "small text-secondary", new Date(n.createdAt).toLocaleString()));
            return item;
        }));
    }

    async function load(bell) {
        try { render(bell, await apiFetch(bell.dataset.api)); } catch (e) { /* keep the last state; retried on the next poll */ }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const bell = document.getElementById("notification-bell");
        if (bell) {
            load(bell);
            setInterval(() => { if (!document.hidden) load(bell); }, 60000);
            bell.querySelector("[data-mark-read]").addEventListener("click", async (event) => {
                event.stopPropagation();
                try { render(bell, await apiFetch(bell.dataset.readUrl, { method: "POST" })); } catch (e) { /* ignore */ }
            });
        }
        const page = document.getElementById("notifications-page");
        if (page) page.querySelector("[data-mark-page-read]").addEventListener("click", async () => {
            try { await apiFetch(page.dataset.readUrl, { method: "POST" }); window.location.reload(); }
            catch (e) { Swal.fire({ icon: "error", text: e.message }); }
        });
    });
})();
