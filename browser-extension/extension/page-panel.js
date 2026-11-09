/*
 * THE PANEL OF A WORK: the prediction and what moves it, the grade, the interest, and the facets the
 * site's page has no place for.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, apiAnswer, enc, said, el, keyOf, render, ownButton, withFailures, facetBlock, openByText } = r4m;

  /**
   * What moves the work's prediction ({ of: "source/item", parts }), read apart from the page: of
   * a graded work the application fits a model that had not seen it for the asking, a second or
   * more.
   */
  let explanation = null;
  /** How often a model being fitted is asked for: one answer awaited. */
  const FITTING_POLL_MS = 1000;

  /**
   * What moves the work's prediction; of a graded work, of the model that had not seen it — the one
   * of every grade has seen the user's grade and partly learnt it by heart — with that model's score
   * ([heldOut]): the parts are of that score, not of the prediction shown.
   */
  async function readExplanation(item) {
    const of = keyOf(item);
    const path = `/api/items/${enc(item.summary.source)}/${enc(item.summary.item)}`;
    try {
      if (item.summary.grade) {
        const answer = await apiAnswer("GET", `${path}/held-out`);
        if (answer.status === 202) {
          // Being fitted in the queue of the computations: the request never waits for it, so it is asked again
          setTimeout(() => {
            if (r4m.view?.item && keyOf(r4m.view.item) === of) readExplanation(r4m.view.item);
          }, FITTING_POLL_MS);
          return;
        }
        // No content: too few graded works for a model that had not seen it
        const held = answer.data;
        explanation = { of, parts: held ? held.explanation : [], heldOut: held ? held.score : null };
      } else {
        explanation = { of, parts: await api("GET", `${path}/explanation`), heldOut: null };
      }
    } catch {
      // The panel goes without it
      return;
    }
    if (r4m.view?.item && keyOf(r4m.view.item) === of) render();
  }

  /** What moves the prediction of a work seen first, read at once; of the work shown already, read after the changes. */
  function explainNew(item) {
    if (!explanation || explanation.of !== keyOf(item)) readExplanation(item);
  }

  /** A score of 0..10 as a class of colour, red to green. */
  function scoreClass(score) {
    return `r4m-s${Math.max(0, Math.min(10, Math.round(score)))}`;
  }

  /** A prediction on 0..10, as the application shows it: above the scale "10+", below it 0. */
  function shownScore(score) {
    return score > 10 ? "10+" : Math.max(0, score).toFixed(1);
  }

  function panel(item, placed) {
    const root = el("r4m-panel");
    const head = el("r4m-head");
    head.appendChild(el("strong", null, "recommend4me"));
    if (item?.summary.prediction) {
      head.appendChild(el("r4m-score", scoreClass(item.summary.prediction.score), shownScore(item.summary.prediction.score)));
    }
    if (item?.summary.grade) head.appendChild(el("r4m-grade-mark", null, `★${item.summary.grade}`));
    head.appendChild(el("span", "r4m-spacer"));
    const pending = r4m.pending;
    if (pending) head.appendChild(el("r4m-saving-note", null, pending > 1 ? t("panel.savingCount", { count: pending }) : t("panel.saving")));
    // The tags by the work's own text, beside the way to the application, as in its window of the work: of a source with tags alone
    const tagFacet = item && (item.facets || []).find((f) => f.tags && f.editable !== false);
    if (tagFacet) {
      const byTextButton = el("button", "r4m-button", t("byText.toggle"));
      byTextButton.type = "button";
      byTextButton.title = t("byText.toggleHint");
      byTextButton.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        openByText(item, tagFacet.facet);
      });
      head.appendChild(byTextButton);
    }
    if (item) {
      const open = el("a", "r4m-link", t("panel.open"));
      open.href = `${r4m.server}/#item=${enc(item.summary.source)}/${item.summary.item}`;
      open.target = "_blank";
      open.rel = "noopener";
      head.appendChild(open);
    }
    root.appendChild(head);
    [r4m.unread, r4m.message].filter(Boolean).forEach((m) => {
      root.appendChild(el("r4m-error", null, m));
    });
    if (!item) {
      root.appendChild(el("r4m-line", "r4m-muted", t("panel.unsaved")));
      place(root);
      return;
    }
    const s = item.summary;

    const score = el("r4m-line");
    if (s.prediction) {
      score.appendChild(el("span", null, `${t("panel.prediction")} `));
      score.appendChild(el("r4m-score", scoreClass(s.prediction.score), shownScore(s.prediction.score)));
      score.appendChild(el("span", "r4m-muted", ` ${t("panel.outOf")}`));
    } else {
      score.appendChild(el("span", "r4m-muted", t("panel.noPrediction")));
    }
    root.appendChild(score);
    if (item.pending) root.appendChild(el("r4m-line", "r4m-muted", t("panel.chancesPending")));

    // Right under the prediction, what moves it; of a graded work, first the score of the model that
    // had not seen the grade — the parts are of that one
    const known = explanation && explanation.of === keyOf(item) ? explanation : null;
    if (known && known.heldOut != null) {
      const held = el("r4m-line");
      held.appendChild(el("span", "r4m-muted", `${t("panel.withoutGrade")} `));
      held.appendChild(el("r4m-score", scoreClass(known.heldOut), shownScore(known.heldOut)));
      root.appendChild(held);
    }
    if (known?.parts?.length) {
      const why = el("r4m-why");
      why.appendChild(el("span", "r4m-muted", `${t("panel.why")} `));
      known.parts.slice(0, 6).forEach((c, i) => {
        if (i) why.appendChild(el("span", "r4m-muted", " · "));
        const sign = c.contribution >= 0 ? "+" : "−";
        why.appendChild(el("span", c.contribution >= 0 ? "r4m-up" : "r4m-down", `${sign}${Math.abs(c.contribution).toFixed(1)} ${said(c.labelKey)}`));
      });
      root.appendChild(why);
    }

    root.appendChild(grades(item));
    root.appendChild(interest(item));

    // The facets the page does not show, in the panel
    facetsOf(item).forEach(({ facet, suggestions }) => {
      if (!placed.has(facet.facet)) root.appendChild(facetBlock(item, facet, suggestions, true));
    });
    place(root);
  }

  /** Every facet of the item with its suggestions, those only suggested included. */
  function facetsOf(item) {
    const out = (item.facets || []).map((facet) => ({ facet, suggestions: (item.suggestions || []).find((x) => x.facet === facet.facet) }));
    (item.suggestions || []).forEach((x) => {
      if (!out.some((f) => f.facet.facet === x.facet)) out.push({ facet: { facet: x.facet, labelKey: x.labelKey, values: [] }, suggestions: x });
    });
    return out;
  }

  /**
   * Puts the panel after the element the source names. A page without such a place — the reader
   * of a chapter — gets no panel: a box floating over the text distracts from reading, and the
   * page is sent all the same.
   */
  function place(root) {
    const decor = r4m.view.decor;
    const anchor = decor.panelAfter && document.querySelector(decor.panelAfter);
    if (anchor) anchor.insertAdjacentElement("afterend", root);
  }

  /** The grades 1..5 of a work of the page's content type. */
  const GRADES = [1, 2, 3, 4, 5];

  /** The label of a [grade] of the page's content type, by the key of the standard words. */
  const gradeLabel = (grade) => said(`standard:type.${r4m.view.type}.grade.${grade}`);

  function grades(item) {
    const s = item.summary;
    const line = el("r4m-line", "r4m-grades");
    line.appendChild(el("span", null, `${t("grade.title")} `));
    GRADES.forEach((grade) => {
      const label = gradeLabel(grade);
      const current = s.grade === grade;
      const b = ownButton(
        `${grade}`,
        current ? t("common.pressAgain", { what: label }) : label,
        () => current
          ? api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/rating`)
          : api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/rating`, { grade }),
        `r4m-grade${current ? " r4m-current" : ""}`,
        (shown) => {
          shown.summary.grade = current ? null : grade;
        },
        `grade|${grade}`,
      );
      line.appendChild(b);
    });
    if (s.grade) line.appendChild(el("span", "r4m-muted", ` ${gradeLabel(s.grade)}`));
    else if (s.previousGrade) {
      line.appendChild(el("span", "r4m-muted", ` ${t("grade.previous", { version: s.previousGradeVersion, grade: s.previousGrade })}`));
    }
    return withFailures(line, GRADES.map((grade) => `grade|${grade}`));
  }

  /**
   * Whether the work is interesting to read at all, beside how good it is: the user's mark; without
   * one, what a grade says of it, a button pressed making it the user's word; without that, the
   * chance the model gives it.
   */
  function interest(item) {
    const s = item.summary;
    const line = el("r4m-line", "r4m-interest");
    const grade = s.grade || s.previousGrade;
    const byGrade = s.interest != null || !grade ? null : grade >= 4 ? true : grade === 1 ? false : null;
    line.appendChild(el("span", null, `${t("interest.title")} `));
    // Not thumbs: a thumb says liked or not, and interest is another thing
    [[true, `🤔 ${t("interest.yes")}`], [false, `🥱 ${t("interest.no")}`]].forEach(([value, label]) => {
      const current = s.interest === value;
      line.appendChild(ownButton(
        label,
        current ? t("common.pressAgainAlone") : null,
        () => current
          ? api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/interest`)
          : api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/interest`, { interesting: value }),
        `r4m-mark${current ? " r4m-current" : ""}`,
        (shown) => {
          shown.summary.interest = current ? null : value;
        },
        `interest|${value}`,
      ));
    });
    if (byGrade !== null) line.appendChild(el("span", "r4m-muted", ` ${t(byGrade ? "interest.yes" : "interest.no")}: ${t("interest.byGrade")}`));
    else if (s.interest == null && s.interestChance != null) {
      line.appendChild(el("span", "r4m-muted", ` ${t("interest.byModel", { percent: Math.round(s.interestChance * 100) })}`));
    }
    return withFailures(line, ["interest|true", "interest|false"]);
  }

  Object.assign(r4m, { readExplanation, explainNew, scoreClass, shownScore, panel });
})();
