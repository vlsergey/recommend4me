/*
 * A FACET WHOSE VALUES ARE A SCALE — the age rating — answered on as one.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { nameOf, el, ownButton, withFailures, about, shownValue, answeredShown, unansweredShown, correct, uncorrect, percent } = r4m;

  /**
   * A FACET WHOSE VALUES ARE A SCALE — the age rating — as the scale: every step in its order, the
   * work's one chosen, and where it is from — the user's answer, the site's word, the model's chance
   * by the tags; under every other step the model's chance of it; a step the site lets not faded, to
   * choose all the same where the site is wrong. Sites that disagree choose nothing: every step held
   * is marked with its sites, and the user decides. A step pressed is the work's — the user's answer —
   * the user's own pressed again taken back.
   */
  function scaleBlock(item, facet) {
    const values = (facet.values || []).filter((v) => v.corrected !== "REMOVED" && !v.offered);
    const answered = values.find((v) => v.corrected === "ADDED" || v.corrected === "CONFIRMED");
    // Every step a site names, with the sites naming it: one site naming two is no disagreement
    const said = facet.said || [];
    const named = new Map(facet.steps.map((s) => [s.key, said.filter((x) => x.words.some((w) => w.key === s.key)).map((x) => x.source)]));
    const naming = said.filter((x) => x.words.some((w) => named.has(w.key)));
    const disagree = !answered && (values.length > 1 || (naming.length > 1 && [...named.values()].filter((sites) => sites.length).length > 1));
    const current = answered || (disagree ? undefined : values[0]);
    const mine = !!answered;
    const box = el("r4m-scale");
    const steps = el("r4m-steps");
    const abouts = [];
    facet.steps.forEach((s) => {
      const chosen = !!current && s.key === current.key;
      const name = nameOf(s);
      const on = about(facet.facet, s.key);
      abouts.push(on);
      const step = el("r4m-step");
      const b = chosen && mine
        ? ownButton(name, t("scale.takeBack"), () => uncorrect(item, facet.facet, s.key), "r4m-button r4m-chosen", unansweredShown(facet.facet, s.key), on)
        : ownButton(name, t(s.allowed ? "scale.choose" : "scale.chooseAgainstSite", { name }), () => correct(item, facet.facet, { key: s.key }, true),
          `r4m-button${chosen ? " r4m-chosen" : s.allowed ? "" : " r4m-disallowed"}`, chosenShown(facet.facet, s), on);
      b.setAttribute("aria-pressed", String(chosen));
      step.appendChild(b);
      const of = disagree ? named.get(s.key) : [];
      step.appendChild(el("r4m-chance", null, of.length ? of.join(", ") : s.chance !== undefined && s.chance !== null ? percent(s.chance) : " "));
      steps.appendChild(step);
    });
    box.appendChild(steps);
    const origin = disagree ? t("scale.disagree")
      : !current ? t("scale.none")
      : mine ? t("scale.mine")
      : current.fromSite ? t("scale.site")
      : t("scale.model", { chance: current.chance !== undefined && current.chance !== null ? percent(current.chance) : "—" });
    box.appendChild(el("r4m-origin", null, origin));
    return withFailures(box, abouts);
  }

  /** A step of a scale chosen, as shown: the user's other answer on it goes, the step is the user's. */
  function chosenShown(facet, step) {
    return (item) => {
      const f = (item.facets || []).find((x) => x.facet === facet);
      if (f) f.values = (f.values || []).filter((v) => !(v.corrected === "ADDED" && v.key !== step.key)).map((v) => (v.key !== step.key && v.corrected === "CONFIRMED" ? { ...v, corrected: null } : v));
      answeredShown(facet, step, true)(item);
      shownValue(item, facet, step.key, step.name, step.nameKey).offered = false;
    };
  }

  Object.assign(r4m, { scaleBlock });
})();
