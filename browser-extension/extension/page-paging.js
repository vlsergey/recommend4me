/*
 * PAGING THROUGH A LIST BY ITSELF: a button by the site's link to the next page
 * ([decor.nextPage]) has the browser go through every page of the list. On each page it waits
 * until the page is sent to the application, every work of its cards is saved there and no change
 * of the user's is on its way, then opens the next page — not for the predictions, made after. The press is kept in the tab's sessionStorage,
 * so the next page goes on with it; the last page — no link onward — ends it, as a press does.
 * What it waits for stands on the button.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { el, cardsAnew } = r4m;

  const PAGING_KEY = "r4m.paging";
  /** Whether the page has been sent to the application since it was opened. */
  let pageSent = false;
  /** The next page asked for: not asked twice while it loads. */
  let leaving = false;

  function paging() {
    try {
      return sessionStorage.getItem(PAGING_KEY) === "on";
    } catch {
      return false;
    }
  }

  function setPaging(on) {
    try {
      if (on) sessionStorage.setItem(PAGING_KEY, "on");
      else sessionStorage.removeItem(PAGING_KEY);
    } catch {
      // A page that keeps nothing: the paging stops with it
    }
  }

  /** The page told sent to the application: the paging waits for it no more. */
  function markSent() {
    pageSent = true;
  }

  /** The page may have been sent before this script listened: the background script tells what it sent. */
  function learnSent() {
    browser.runtime.sendMessage({ type: "recent" }).then((recent) => {
      const sent = (recent || []).some((e) => e.url === location.href && !e.error && e.at >= performance.timeOrigin);
      if (sent && !pageSent) {
        pageSent = true;
        pagingGo();
      }
    }).catch(() => {});
  }

  /** The site's links to the next page — a list has one over it and one under it. */
  function nextLinks() {
    const selector = r4m.view?.decor.nextPage;
    if (!selector) return [];
    return [...document.querySelectorAll(selector)].filter((a) => !a.closest("[data-r4m]") && a.getAttribute("href"));
  }

  /**
   * How many cards still wait to be known to the application: with no badge yet, or a badge of a work
   * not saved yet. A work saved whose prediction is still worked out does not hold the paging: its
   * badge is filled on the page it is on, or seen on the list in the application.
   */
  function cardsWaiting() {
    const decor = r4m.view?.decor.cards;
    if (!decor) return 0;
    let waiting = 0;
    document.querySelectorAll(decor.selector).forEach((card) => {
      if (card.closest("[data-r4m]")) return;
      const link = card.querySelector(decor.link);
      if (!link?.getAttribute("href")) return;
      const badge = card.querySelector("r4m-badge");
      if (!badge || badge.classList.contains("r4m-unsaved")) waiting++;
    });
    return waiting;
  }

  /** What this page still waits for, in words; null when it is done. */
  function pagingWait() {
    if (!pageSent) return t("paging.sending");
    if (r4m.pending) return t("panel.saving");
    const waiting = cardsWaiting();
    return waiting ? t("paging.cards", { count: waiting }) : null;
  }

  /** The button by every link to the next page, drawn anew with what the page waits for. */
  function drawPaging() {
    document.querySelectorAll("[data-r4m].r4m-paging").forEach((e) => {
      e.remove();
    });
    nextLinks().forEach((a) => {
      a.insertAdjacentElement("afterend", pagingButton());
    });
  }

  function pagingButton() {
    const on = paging();
    const wait = on ? pagingWait() : null;
    const text = !on ? t("paging.start") : wait ? t("paging.stopWaiting", { what: wait }) : t("paging.stop");
    const b = el("button", `r4m-button r4m-paging${on ? " r4m-current" : ""}`, text);
    b.type = "button";
    b.title = t(on ? "paging.stopTitle" : "paging.startTitle");
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      setPaging(!paging());
      if (paging()) {
        // The cards are asked for again, now with no end of patience
        cardsAnew();
      }
      pagingGo();
    });
    return b;
  }

  /** The next page opened once this one is done; told whenever its cards, its sending or a change of it move. */
  function pagingGo() {
    drawPaging();
    if (!paging() || leaving || !r4m.view) return;
    const next = nextLinks()[0];
    if (!next) {
      // The last page
      setPaging(false);
      return;
    }
    if (pagingWait()) return;
    leaving = true;
    location.assign(next.href);
  }

  Object.assign(r4m, { paging, pagingGo, drawPaging, markSent, learnSent });
})();
