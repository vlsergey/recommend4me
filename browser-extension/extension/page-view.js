/*
 * WHAT THE APPLICATION SAYS OF THIS PAGE, read and drawn on the site's page: the elements put into
 * it, cleared and drawn anew, every part of the page in its place.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, speakChosen } = r4m;

  /** How long a shown work is not read again for every new send of a growing page. */
  const RELOAD_AFTER = 15 * 1000;
  /** How often a work whose chances are still to come (`pending`) is read again. */
  const PENDING_EVERY = 3 * 1000;
  let pendingTimer = null;

  /** What the application said of this page; null until it answers. */
  r4m.view = null;
  r4m.server = "";
  /** Why the work could not be read as the application knows it; gone once it is read. */
  r4m.unread = null;
  /** The facets the page has a place for, as of the last drawing; the others stand in the panel. */
  r4m.onPage = new Set();
  let loadedAt = 0;

  // --- Elements ---

  function el(tag, className, text) {
    const e = document.createElement(tag);
    e.setAttribute("data-r4m", "");
    if (className) e.className = className;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  const keyOf = (item) => `${item.summary.source}/${item.summary.item}`;

  // --- Loading ---

  /**
   * The page read as the application knows it. A read a change of the work was kept during — the
   * page showing it already, a read of seconds — is stale: the work shown stays, and the read after
   * the changes' quiet brings the rest (page-changes.js).
   */
  async function load() {
    loadedAt = Date.now();
    clearTimeout(pendingTimer);
    try {
      await speakChosen();
      r4m.server = await browser.runtime.sendMessage({ type: "server" });
      const before = r4m.kept;
      const view = await api("GET", `/api/pages?url=${enc(location.href)}`);
      const shown = r4m.view?.item;
      if (r4m.kept !== before && shown && view.item && keyOf(view.item) === keyOf(shown)) {
        render();
        return;
      }
      r4m.view = view;
      r4m.unread = null;
      // The work read anew: the candidates the fields offer may be others now
      r4m.staleFields();
      // What moves the prediction is read apart, not waited for: a work seen first gets it at once
      if (r4m.view.item) r4m.explainNew(r4m.view.item);
    } catch (e) {
      // What was shown stays, with why it is not current
      r4m.unread = e.message;
    }
    render();
    // Its chances still to come, its texts not encoded yet: read again till they are there
    if (r4m.view?.item?.pending) pendingTimer = setTimeout(load, PENDING_EVERY);
    // The candidates asked for are read anew too, after the work is shown: an answer changes which
    // the work may yet have, and they wait for its chances to be made
    if (r4m.view?.item && r4m.candidatesAsked()) {
      try {
        await r4m.rereadCandidates(r4m.view.item);
      } catch (e) {
        r4m.unread = e.message;
      }
      render();
    }
  }

  /** A work's page sent again as it grows: read anew once it is first saved, and then seldom. */
  function loadIfStale() {
    if (!r4m.view?.item || Date.now() - loadedAt > RELOAD_AFTER) load();
  }

  function clear() {
    document.querySelectorAll("[data-r4m]:not(r4m-badge)").forEach((e) => {
      // A field's own parts stay in it: the field is put back whole (page-add-field.js). A card's badge
      // stays with what it shows: filled once, it is not asked for again (page-cards.js)
      if (e.parentElement?.closest("form.r4m-add, r4m-badge")) return;
      e.remove();
    });
    document.querySelectorAll(".r4m-removed, .r4m-doubted, .r4m-marked").forEach((e) => {
      e.classList.remove("r4m-removed", "r4m-doubted", "r4m-marked");
    });
  }

  /**
   * Whether the site's page is dark: by its own background, the body's or the root's — not the
   * system's, which a site with no dark skin (AO3) does not follow.
   */
  function pageIsDark() {
    for (const e of [document.body, document.documentElement]) {
      if (!e) continue;
      const m = getComputedStyle(e).backgroundColor.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/);
      // Transparent: the colour is of the one under it
      if (!m || (m[4] !== undefined && Number(m[4]) === 0)) continue;
      return 0.2126 * Number(m[1]) + 0.7152 * Number(m[2]) + 0.0722 * Number(m[3]) < 128;
    }
    return false;
  }

  /** Draws the page anew; the field typed in keeps its text, its list and its focus ([keepFieldsThrough]). */
  function render() {
    r4m.keepFieldsThrough(draw);
  }

  function draw() {
    clear();
    // The site's skin may change while the page is open
    document.documentElement.setAttribute("data-r4m-theme", pageIsDark() ? "dark" : "light");
    const view = r4m.view;
    if (!view) return;
    r4m.cardsAnew();
    r4m.drawPaging();
    const item = view.item;
    if (!item && !view.itemId) return;
    const placed = new Set();
    // Told before anything is drawn: the sources of a group stand over its first field in the same place
    r4m.onPage = new Set((view.decor.facets || []).filter((d) => document.querySelector(d.after || d.values)).map((d) => d.facet));
    try {
      if (item) {
        (view.decor.facets || []).forEach((decor) => {
          if (r4m.facetInPlace(item, decor)) placed.add(decor.facet);
        });
        r4m.reviews(item);
        r4m.pictures(item);
      }
    } catch (e) {
      // A part the page could not take: the panel still shows the work, and why
      console.error("recommend4me", e);
      r4m.message = r4m.message || String(e.message || e);
    }
    r4m.panel(item, placed);
    // The window of the tags by the text, when open: over the page, wherever it was opened from
    if (item) r4m.drawByTextDialogs(item);
  }

  Object.assign(r4m, { el, keyOf, load, loadIfStale, render });
})();
