/*
 * THE USER'S CHANGES: sent one after another in the order made, the value a change is about marked
 * while it is at work, its effect shown once the application has kept it, its failure told by it,
 * and the work read anew once the changes are quiet; the buttons that run them.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { el, load, render } = r4m;

  /** Why the last change not about a value failed; kept until the next change. */
  r4m.message = null;

  /** How many changes are sent and not yet answered, and the values they are about ("facet|key"). */
  r4m.pending = 0;
  const saving = new Set();
  /** Why a change about a value failed, by what it was about: told by the value, not far off in the panel. */
  const failed = new Map();
  /** The changes, sent one after another in the order made. */
  let queue = Promise.resolve();
  /** How many changes the application has kept: a read of the work begun before one is stale (page-view.js load). */
  r4m.kept = 0;

  /**
   * What moves the work's prediction (page-panel.js) is read apart from the page: of a graded work
   * the application fits a model that had not seen it for the asking, a second or more. After a
   * change it is read anew only once the changes have settled for [SETTLE_MS].
   */
  let explanationTimer = null;
  const SETTLE_MS = 2000;

  /** [element] with the failure of the last change of any of [abouts], when one failed. */
  function withFailures(element, abouts) {
    return withFailure(element, abouts.find((a) => failed.has(a)) || abouts[0]);
  }

  /** [element] with the failure of the last change [about] it after it, when that failed: the element alone else. */
  function withFailure(element, about) {
    const error = failed.get(about);
    if (!error) return element;
    const both = el("r4m-failed");
    both.appendChild(element);
    both.appendChild(Object.assign(el("r4m-failure", null, `⚠ ${t("common.failed", { error })}`), { role: "alert" }));
    return both;
  }

  /** A button running [onClick] as a change ([act]); [shown] and [about] as there. */
  function button(text, title, onClick, className = "r4m-button", shown = null, about = null) {
    const b = el("button", className, text);
    b.type = "button";
    if (title) b.title = title;
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      act(onClick, shown, about);
    });
    return b;
  }

  /**
   * A button whose change is [about] it alone: busy — marked and not pressed again — while its own
   * press is at work, as the web interface's AsyncButton; never by another button's.
   */
  function ownButton(text, title, onClick, className, shown, about) {
    const b = button(text, title, onClick, className, shown, about);
    if (saving.has(about)) {
      b.classList.add("r4m-saving");
      b.disabled = true;
    }
    return b;
  }

  /**
   * A change run APART FROM THE QUEUE of the answers ([act]): a long one — the catalogue asked for
   * a universe's characters, seconds — does not hold the answers given after it. Marked by its
   * [about] while at work, its failure told there; the work read anew after it, once the answers are quiet.
   */
  function apart(change, about) {
    saving.add(about);
    failed.delete(about);
    render();
    change()
      .catch((e) => failed.set(about, e.message))
      .finally(() => {
        saving.delete(about);
        render();
        if (r4m.pending === 0) loadLater();
      });
  }

  /**
   * Runs a change. The value it is [about] is marked as being saved until the application answers
   * — a click must be seen to have landed — and ITS EFFECT IS SHOWN ONCE THE APPLICATION HAS KEPT
   * IT, never before: [shown] changes the work as shown the way the application keeps it, by what
   * the change answered with. Changes go one after another in the order made, none is dropped, and
   * nothing is read between them: the application only writes them. Once no change has come for
   * [SETTLE_MS] the page is read as the application now knows it, its chances made of the answers
   * as it is read ([loadLater]); a read a change was kept during shows nothing older than the page.
   */
  function act(change, shown, about) {
    if (r4m.pending === 0) r4m.message = null;
    r4m.pending++;
    // A new change puts off reading the work and what moves the prediction
    clearTimeout(explanationTimer);
    clearTimeout(loadTimer);
    if (about) {
      saving.add(about);
      failed.delete(about);
    }
    render();
    queue = queue.then(async () => {
      let kept = false;
      let answer;
      try {
        answer = await change();
        kept = true;
        r4m.kept++;
      } catch (e) {
        if (about) failed.set(about, e.message);
        else r4m.message = e.message;
      }
      if (kept && shown && r4m.view?.item) {
        try {
          shown(r4m.view.item, answer);
        } catch {
          // The work read after the quiet shows it all the same
        }
      }
      if (about) saving.delete(about);
      r4m.pending--;
      render();
      if (r4m.pending === 0) loadLater();
    });
    return queue;
  }

  let loadTimer = null;

  /** The work read as the application now knows it once no change has come for [SETTLE_MS]: its chances made of the answers as it is read. */
  function loadLater() {
    clearTimeout(loadTimer);
    loadTimer = setTimeout(async () => {
      if (r4m.pending !== 0) return;
      await load();
      explanationLater();
    }, SETTLE_MS);
  }

  /** Reads what moves the prediction of the work once no change has come for [SETTLE_MS]. */
  function explanationLater() {
    clearTimeout(explanationTimer);
    explanationTimer = setTimeout(() => {
      if (r4m.view?.item && r4m.pending === 0) r4m.readExplanation(r4m.view.item);
    }, SETTLE_MS);
  }

  Object.assign(r4m, { saving, failed, act, apart, button, ownButton, withFailure, withFailures });
})();
