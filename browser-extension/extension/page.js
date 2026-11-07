/*
 * THE SITE AS THE APPLICATION'S INTERFACE, put into the pages the application's sources want.
 *
 * On a work's page: a panel with the prediction, the grade, what the prediction rests on, the
 * suggested tags and a field to add one; on the site's own tags a button to take each away (and to
 * bring it back), the tags the user added after them, the suggested ones as dashed chips to
 * confirm or reject; a "+ / −" under every review and picture of the work. On a list: the
 * prediction and the grade on every card.
 *
 * What to mark up the source says (GET /api/pages: CSS selectors of its pages); everything is
 * changed through the application's own calls, made by the background script. Every element put
 * here carries data-r4m — the tracking leaves those out of the pages it sends.
 *
 * IT IS MADE OF PARTS, each about one thing, given to the page in this order after i18n.js (by the
 * background script, PAGE_SCRIPTS; in the userscript for Safari, by UserScriptController, inside
 * runPage()) — this file, which starts it, last:
 *
 *   page-application.js  — the calls to the application and the words of the server's keys;
 *   page-view.js         — what the application says of this page, read and drawn;
 *   page-changes.js      — the user's changes, sent one after another, and the buttons running them;
 *   page-values.js       — a facet's values as chips and the answers on them;
 *   page-candidates.js   — "…", more candidates of a facet;
 *   page-scale.js        — a facet whose values are a scale;
 *   page-add-field.js    — a value found by its name and added;
 *   page-site-words.js   — what every site of the work says of a field;
 *   page-tags-by-text.js — the tags by the work's own text;
 *   page-facets.js       — a facet as a block or on the site's own values;
 *   page-panel.js        — the panel: the prediction and what moves it, the grade, the interest;
 *   page-marks.js        — the marks of reviews and pictures;
 *   page-cards.js        — the cards of a list;
 *   page-paging.js       — paging through a list by itself.
 *
 * HOW THE PARTS SHARE: every part is a function run at once, its own names kept inside it, and
 * all of them share one object, window.__recommend4mePage — the one name the script adds to the
 * page's window. A part puts there what the others call; it takes by name, at its start, what the
 * parts before it put there, and calls what a later part puts there through the object when it
 * runs. What changes as the page lives — the work as read (view), the changes on their way
 * (pending)… — stands on the object as a field, read from it every time, never kept by name.
 *
 * The page may be given the script again (a site changing its address without loading a page):
 * every part then finds the object started and does nothing, and this file reads the work anew —
 * the parts' state, their timers and their listeners stay those of the first time.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) {
    r4m.load();
    return;
  }
  r4m.started = true;
  const { load, loadIfStale, cardsAnew, observeCards, pagingGo, markSent, learnSent } = r4m;

  observeCards();

  // A page sent again as it grows. A list: the works it just saved get their badges, the page is
  // not read anew. A work's page: read anew once it is first saved, and then seldom
  browser.runtime.onMessage.addListener((m) => {
    if (m.type !== "captured" || m.url !== location.href) return;
    markSent();
    // Answers on their way: the page is read anew when the last is answered
    if (r4m.pending) {
      pagingGo();
      return;
    }
    const view = r4m.view;
    if (view && !view.item && !view.itemId) {
      cardsAnew();
      return;
    }
    loadIfStale();
  });

  learnSent();
  load();
})();
