/*
 * MORE CANDIDATES OF A FACET, "…": the values the model offers beyond those the line shows, read as
 * many as asked for and shown as suggestions; of the characters and the pairings, those the work's
 * texts name most besides.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, el, render, suggestedChip } = r4m;

  /**
   * How many times the user pressed "…" of a facet, by facet — kept while the page is open — and
   * the candidates read for them, the likeliest first, with how many were asked of the server
   * ({ limit, named }): so many as are shown, not every one — of the tags, some 8 600, 2.4 MB, read
   * again after every answer, more than an iPad takes.
   */
  const more = new Map();
  const candidatesOf = new Map();
  const candidatesRead = new Map();

  /** How many more a press of "…" shows. */
  const MORE_STEP = 5;
  /**
   * Of the characters and the pairings, which the work's texts name: how many more of the likeliest
   * a press of "…" shows, and how many more of those the texts name most (3 times at least). The
   * texts are counted for this alone: no model reads them.
   */
  const MORE_LIKELIEST = 2;
  const MORE_NAMED = 2;
  const BY_TEXT = new Set(["characters", "secondary-characters", "pairings"]);

  /** The likeliest [limit] candidates of the facet and the [named] most named by the work's texts besides, read and kept. */
  async function readCandidates(item, facet, limit, named) {
    const s = item.summary;
    candidatesRead.set(facet, { limit, named });
    try {
      const besides = named ? `&named=${named}` : "";
      candidatesOf.set(facet, await api("GET", `/api/items/${enc(s.source)}/${enc(s.item)}/candidates?facet=${enc(facet)}&limit=${limit}${besides}`));
    } catch {
      // A facet the model does not work on has none to show
      candidatesOf.set(facet, []);
    }
  }

  /** Whether the user has asked for more candidates of any facet. */
  const candidatesAsked = () => more.size > 0;

  /** The candidates asked for read anew, as many as were: an answer changes which the work may yet have. */
  async function rereadCandidates(item) {
    for (const facet of more.keys()) {
      const asked = candidatesRead.get(facet);
      await readCandidates(item, facet, asked.limit, asked.named);
    }
  }

  /**
   * The candidates the user asked to see more of — those the line does not show yet ([shown]
   * keys), as many as asked, as suggestions — and "…" to see [MORE_STEP] more, while there are. Of
   * the characters and the pairings ([BY_TEXT]) a press shows [MORE_LIKELIEST] more of the likeliest
   * and [MORE_NAMED] more the work's texts name most — 3 times at least, with how many — each once.
   */
  function moreOf(item, facet, line, shown) {
    const byText = BY_TEXT.has(facet);
    const presses = more.get(facet) || 0;
    const likeliestStep = byText ? MORE_LIKELIEST : MORE_STEP;
    const namedStep = byText ? MORE_NAMED : 0;
    const all = candidatesOf.get(facet);
    const read = candidatesRead.get(facet);
    const likeliest = (all || []).filter((c) => c.named !== true);
    const named = (all || []).filter((c) => c.named === true);
    const rest = likeliest.filter((c) => !shown.has(c.key));
    const restNamed = named.filter((c) => !shown.has(c.key));
    const show = (c, byCount) => {
      const chip = suggestedChip(item, facet, c);
      const count = mentionsText(c.mentions);
      chip.title = [c.description, count ? t("candidate.title", { mentions: count }) : t("value.suggested")].filter(Boolean).join("\n");
      // The most named: with how many times, after the chance
      if (byCount && c.mentions) chip.insertBefore(el("r4m-mentions", null, `${c.mentions}×`), chip.querySelector(".r4m-yes"));
      line.appendChild(chip);
    };
    rest.slice(0, likeliestStep * presses).forEach((c) => {
      show(c, false);
    });
    restNamed.slice(0, namedStep * presses).forEach((c) => {
      show(c, true);
    });
    // Fewer read than asked for: there are no more
    const done = all && read && likeliest.length < read.limit && rest.length <= likeliestStep * presses &&
      (!byText || (named.length < read.named && restNamed.length <= namedStep * presses));
    if (done) return;
    const b = el("button", "r4m-button r4m-more", "…");
    b.type = "button";
    // One key, not a word of every plural form: the counts are always the same, the words written for them
    b.title = byText ? t("candidate.moreByText", { likeliest: MORE_LIKELIEST, named: MORE_NAMED }) : t("candidate.more", { count: MORE_STEP });
    b.addEventListener("click", async (e) => {
      e.preventDefault();
      e.stopPropagation();
      const next = presses + 1;
      // The line's values may be among the candidates: so many more read
      b.classList.add("r4m-saving");
      await readCandidates(item, facet, shown.size + likeliestStep * next, byText ? shown.size + namedStep * next : undefined);
      more.set(facet, next);
      render();
    });
    line.appendChild(b);
  }

  /** The keys of the values a facet's line shows: the work's, the user's, the suggested. */
  function shownKeys(facet, suggestions) {
    return new Set([...(facet.values || []), ...(suggestions?.suggested || [])].map((v) => v.key));
  }

  /** "mentioned 3 times" — how often the work's texts name a candidate; empty when they were not counted. */
  function mentionsText(n) {
    if (n === undefined || n === null) return "";
    if (n === 0) return t("candidate.notMentioned");
    return t("candidate.mentions", { count: n });
  }

  Object.assign(r4m, { candidatesAsked, rereadCandidates, moreOf, shownKeys, mentionsText });
})();
