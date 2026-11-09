// Navbar notification bell and the notifications page: mark one or all as read.
(function () {
    "use strict";

    function el(tag, cls, text) {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    }

    const bell = () => document.getElementById("notification-bell");
    const readUrl = (id) => bell().dataset.notificationsUrl + id + "/read";

    function render(data) {
        const b = bell();
        if (!b || !data) return;
        const badge = b.querySelector("[data-unread]");
        badge.hidden = data.unread === 0;
        badge.textContent = data.unread > 99 ? "99+" : String(data.unread);
        const list = b.querySelector("[data-list]");
        if (!data.recent.length) { list.replaceChildren(el("div", "small text-secondary px-3 py-3", "No notifications yet.")); return; }
        list.replaceChildren(...data.recent.map((n) => {
            const item = el("div", "notification-item d-flex gap-2" + (n.unread ? " unread" : ""));
            const body = el(n.link ? "a" : "div", "flex-grow-1 min-w-0 text-reset text-decoration-none");
            if (n.link) {
                body.href = appUrl(n.link);
                // Opening an unread notification marks it read; keepalive lets the request finish while the page changes.
                if (n.unread) body.addEventListener("click", () => { apiFetch(readUrl(n.id), { method: "POST", keepalive: true }).catch(() => {}); });
            }
            const head = el("div", "d-flex justify-content-between gap-2");
            head.append(el("span", "fw-semibold small", n.title), el("span", "badge " + n.badgeClass, n.level));
            body.append(head, el("div", "small text-secondary text-truncate", n.message),
                el("div", "small text-secondary", new Date(n.createdAt).toLocaleString()));
            item.append(body);
            if (n.unread) {
                const mark = el("button", "btn btn-sm btn-light border align-self-start px-1 py-0");
                mark.type = "button";
                mark.title = "Mark as read";
                mark.setAttribute("aria-label", "Mark “" + n.title + "” as read");
                mark.append(el("i", "bx bx-check"));
                mark.addEventListener("click", async (event) => {
                    event.stopPropagation();
                    mark.disabled = true;
                    try { render(await apiFetch(readUrl(n.id), { method: "POST" })); markPageRow(n.id); } catch (e) { mark.disabled = false; }
                });
                item.append(mark);
            }
            return item;
        }));
    }

    /** Keeps the notifications page in step when an item is marked read from the bell. */
    function markPageRow(id) {
        const button = document.querySelector("#notifications-page [data-mark-one-id='" + id + "']");
        if (button) { button.closest("li").classList.remove("unread"); button.remove(); }
    }

    async function load() {
        try { render(await apiFetch(bell().dataset.api)); } catch (e) { /* keep the last state; retried on the next poll */ }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const b = bell();
        if (b) {
            load();
            setInterval(() => { if (!document.hidden) load(); }, 60000);
            b.querySelector("[data-mark-read]").addEventListener("click", async (event) => {
                event.stopPropagation();
                try { render(await apiFetch(b.dataset.readUrl, { method: "POST" })); } catch (e) { /* ignore */ }
            });
        }
        const page = document.getElementById("notifications-page");
        if (!page) return;
        page.querySelector("[data-mark-page-read]").addEventListener("click", async () => {
            try { await apiFetch(page.dataset.readUrl, { method: "POST" }); window.location.reload(); }
            catch (e) { Swal.fire({ icon: "error", text: e.message }); }
        });
        page.querySelectorAll("[data-mark-one]").forEach((button) => button.addEventListener("click", async () => {
            button.disabled = true;
            try {
                render(await apiFetch(button.dataset.markOne, { method: "POST" }));
                button.closest("li").classList.remove("unread");
                button.remove();
            } catch (e) {
                button.disabled = false;
                Swal.fire({ icon: "error", text: e.message });
            }
        }));
    });
})();
