/*
 * THE CARDS OF A LIST: the prediction, the grade and the marks of interest on every card of a work
 * the application knows, filled in place and asked for again while a prediction is worked out.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, el, saving, failed, act, scoreClass, shownScore } = r4m;

  let cardsTimer = null;

  /**
   * How often a list asks again for the works whose prediction is still worked out — a work first
   * seen is read by the text model and scored seconds after it is saved — and for how long: the
   * patience of a page, not meaning.
   */
  const PENDING_EVERY = 4 * 1000;
  const PENDING_FOR = 2 * 60 * 1000;
  let pendingSince = 0;
  let pendingTimer = null;

  /** The cards filled ([fillCards]), and the next page opened when the list is paged through and this one is done. */
  async function cards() {
    await fillCards();
    r4m.pagingGo();
  }

  /** The cards asked for anew, with the patience of a page from now. */
  function cardsAnew() {
    pendingSince = Date.now();
    cards();
  }

  /**
   * The prediction and the grade on every card of a work the application knows, all the cards in
   * one call. A badge is filled in place, never taken off: a work whose prediction is still worked
   * out shows "…" and is asked for again every few seconds — for the patience of a page, or for as
   * long as the list is paged through, which waits for its work to be saved — the others are not
   * asked again.
   */
  async function fillCards() {
    const decor = r4m.view?.decor.cards;
    if (!decor) return;
    const wanted = [];
    document.querySelectorAll(decor.selector).forEach((card) => {
      if (card.closest("[data-r4m]")) return;
      const badge = card.querySelector("r4m-badge");
      if (badge && !badge.classList.contains("r4m-pending")) return;
      const link = card.querySelector(decor.link);
      if (link?.getAttribute("href")) wanted.push({ card, link });
    });
    if (!wanted.length) return;
    let known = [];
    try {
      known = await api("POST", "/api/pages/cards", { url: location.href, links: wanted.map((w) => w.link.getAttribute("href")) });
    } catch {
      return;
    }
    const byLink = new Map(known.map((k) => [k.link, k]));
    let pending = false;
    wanted.forEach(({ card, link }) => {
      let badge = card.querySelector("r4m-badge");
      if (!badge) {
        badge = el("r4m-badge");
        link.insertAdjacentElement("beforebegin", badge);
      }
      // A work the application does not know yet waits, marked, until the page that shows it is saved
      const k = byLink.get(link.getAttribute("href"));
      const waiting = k ? fillBadge(badge, k) : unsaved(badge);
      pending = pending || waiting;
    });
    clearTimeout(pendingTimer);
    if (pending && (r4m.paging() || Date.now() - pendingSince < PENDING_FOR)) pendingTimer = setTimeout(cards, PENDING_EVERY);
  }

  /** The badge of a card of a work the application does not know yet: "…" until the page is saved. */
  function unsaved(badge) {
    badge.textContent = "";
    const score = el("r4m-score", "r4m-pending-score", "…");
    score.title = t("card.saving");
    badge.appendChild(score);
    badge.classList.add("r4m-pending", "r4m-unsaved");
    return true;
  }

  /** The badge of a card filled with what the application knows of the work [k]; true while its prediction is still worked out. */
  function fillBadge(badge, k) {
    badge.textContent = "";
    const scored = k.prediction !== undefined && k.prediction !== null;
    const score = scored ? el("r4m-score", scoreClass(k.prediction), shownScore(k.prediction)) : el("r4m-score", "r4m-pending-score", "…");
    const waiting = !scored && !k.grade;
    score.title = t(waiting ? "card.pending" : "card.prediction");
    badge.appendChild(score);
    if (k.grade) {
      const grade = el("r4m-grade-mark", null, `★${k.grade}`);
      grade.title = t("card.grade");
      badge.appendChild(grade);
    }
    badge.appendChild(cardInterest(badge, k, true, "🤔"));
    badge.appendChild(cardInterest(badge, k, false, "🥱"));
    // A mark that could not be saved: why, in the badge's title
    const error = [true, false].map((value) => failed.get(`card|${k.source}/${k.item}|${value}`)).find(Boolean);
    badge.title = error ? t("card.notSaved", { error }) : "";
    badge.classList.toggle("r4m-pending", waiting);
    badge.classList.remove("r4m-unsaved");
    return waiting;
  }

  /**
   * A mark of interest on a card of a list — quicker to give than a grade, so given right there. A
   * change as any other ([act]): the button busy while its press is at work, the mark shown once the
   * application has kept it, the error in the badge's title.
   */
  function cardInterest(badge, k, value, icon) {
    const current = k.interest === value;
    const word = t(value ? "interest.yesTitle" : "interest.noTitle");
    const on = `card|${k.source}/${k.item}|${value}`;
    const busy = saving.has(on);
    const b = el("button", `r4m-interest-mark${current ? " r4m-current" : ""}${busy ? " r4m-saving" : ""}`, icon);
    b.type = "button";
    b.disabled = busy;
    const chance = k.interest == null && k.interestChance != null
      ? ` ${t("card.interestChance", { percent: Math.round(k.interestChance * 100) })}`
      : "";
    b.title = current ? t("common.pressAgain", { what: word }) : `${word}${chance}`;
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      const path = `/api/items/${enc(k.source)}/${enc(k.item)}/interest`;
      act(async () => {
        const s = current ? await api("DELETE", path) : await api("PUT", path, { interesting: value });
        Object.assign(k, { interest: s.interest, interestChance: s.interestChance });
      }, null, on).then(() => fillBadge(badge, k));
      fillBadge(badge, k);
    });
    return b;
  }

  /** Lists that load more cards as the user scrolls: the new cards filled once the list is still. */
  function observeCards() {
    new MutationObserver((mutations) => {
      if (!r4m.view?.decor.cards) return;
      const theirs = mutations.some((m) => [...m.addedNodes].some((n) => n.nodeType === 1 && !n.hasAttribute("data-r4m")));
      if (!theirs) return;
      clearTimeout(cardsTimer);
      cardsTimer = setTimeout(cards, 800);
    }).observe(document.body, { childList: true, subtree: true });
  }

  Object.assign(r4m, { cardsAnew, observeCards });
})();
