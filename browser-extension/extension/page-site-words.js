/*
 * WHAT EVERY SITE OF THE WORK SAYS OF A FIELD, right above the place of answering on it: a row of
 * words per site, the fields reading the same words sharing their rows once; a press on a word
 * searching for it in the field.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { nameOf, labelOf, el, OTHER_CHARACTERS, pressWord, fieldOf } = r4m;

  /** The two names of a site's word of a pair: `Юто/Агеха`, `Holmes & Watson`. */
  const PAIR_WORD = /\s*\/\s*|\s+&\s+/;

  /**
   * The fields whose sources stand once, over the first of them that is in the same place on the page,
   * and the fields a press on a word searches in; a field of characters takes the name pressed of a
   * word of a pair, not the whole word.
   */
  const GROUPS = [
    { facets: ["kind", "universe"], press: ["universe"] },
    { facets: ["characters", "secondary-characters", "pairings"], press: ["characters", "secondary-characters", "pairings"] },
  ];

  /** The [fields] of a group as one field's sources: a word on several of them is one, reaching whatever it reached in any of them. */
  function saidTogether(fields) {
    const rows = new Map();
    fields.flatMap((f) => f.said || []).forEach((row) => {
      const id = `${row.source}/${row.item}`;
      const known = rows.get(id);
      if (!known) {
        rows.set(id, row);
        return;
      }
      const words = known.words.map((w) => {
        const same = row.words.find((x) => x.key === w.key);
        if (!same) return w;
        const told = [w.reaches, same.reaches].filter(Array.isArray);
        return { ...w, searchable: w.searchable || same.searchable, reaches: told.length ? [...new Set(told.flat())] : undefined };
      });
      rows.set(id, { ...known, words: [...words, ...row.words.filter((x) => !known.words.some((w) => w.key === x.key))] });
    });
    return { ...fields[0], said: [...rows.values()], values: fields.flatMap((f) => f.values || []) };
  }

  /** The search of a field of the work with a site's word typed, the field found when pressed: it may be drawn after the word. */
  const pressInto = (item, facet) => (name) => pressWord(fieldOf(item, facet), name);

  /** A press on a word: to its one field, or of several a line under the row asking which. */
  function pressed(row, targets, name, word) {
    if (targets.length === 1) return targets[0].go(targets[0].names ? name : word);
    const asked = row.parentElement.querySelector(":scope > r4m-said-choice");
    if (asked) asked.remove();
    const choice = el("r4m-said-choice", null, `${name} →`);
    targets.forEach((x) => {
      const b = el("button", "r4m-button", x.label);
      b.type = "button";
      b.addEventListener("click", () => {
        choice.remove();
        x.go(x.names ? name : word);
      });
      choice.appendChild(b);
    });
    row.insertAdjacentElement("afterend", choice);
  }

  /** A word of a site: as it came to the field, a press looking for it in the [targets]; a word of a pair in the fields of characters a press for each of its names. */
  function siteWord(word, facet, targets) {
    const box = el("r4m-word");
    const known = Array.isArray(word.reaches);
    const reached = (facet.values || []).filter((v) => known && word.reaches.includes(v.key));
    const nothing = known && word.reaches.length === 0;
    const rejected = reached.length > 0 && reached.every((v) => v.corrected === "REMOVED");
    if (nothing) box.classList.add("r4m-word-nothing");
    if (rejected) box.classList.add("r4m-word-rejected");
    box.title = nothing ? t("facet.reachedNothing") : reached.length ? t("facet.reached", { values: reached.map(nameOf).join(", ") }) : "";
    const names = targets.some((x) => x.names) ? word.name.split(PAIR_WORD).filter(Boolean) : [word.name];
    names.forEach((name, i) => {
      if (i > 0) box.appendChild(document.createTextNode("/"));
      if (!targets.length || !word.searchable) {
        box.appendChild(document.createTextNode(name));
        return;
      }
      const b = el("button", "r4m-word-press", name);
      b.type = "button";
      b.title = t(targets.length > 1 ? "facet.findWhere" : "facet.find", { name });
      b.addEventListener("click", () => pressed(box.closest("r4m-said-row"), targets, name, word.name));
      box.appendChild(b);
    });
    return box;
  }

  /**
   * The sources of the [facet] as they stand: of a group the fields of it in the same place together,
   * over the first of them, none over the others; null over those. A press goes to the group's open
   * fields, or to the [field] of a facet alone.
   */
  function sourcesOf(item, facet, inPanel, field) {
    const group = GROUPS.find((g) => g.facets.includes(facet.facet));
    if (!group) return { facet, targets: field ? [{ label: labelOf(facet), go: (name) => pressWord(field, name) }] : [] };
    const here = group.facets.map((key) => (item.facets || []).find((f) => f.facet === key)).filter((f) => f && r4m.onPage.has(f.facet) !== inPanel);
    if (here[0] !== facet) return null;
    const open = group.press.map((key) => (item.facets || []).find((f) => f.facet === key)).filter((f) => f && !f.complete);
    return {
      facet: saidTogether(here),
      targets: open.map((f) => ({ label: labelOf(f), names: !!OTHER_CHARACTERS[f.facet], go: pressInto(item, f.facet) })),
    };
  }

  /**
   * THE SOURCES OF A FIELD, right above the place of answering on it: a row for every site of the work
   * with a word on it, those with none on one line, an item never read told apart. A facet placed on
   * the site's own page, the page showing its own, has those of the other sites alone. Null when there
   * are none, and over a field of a group but the first: they stand once ([GROUPS]).
   */
  function saidBlock(item, facet, inPanel, field) {
    const s = item.summary;
    const sources = sourcesOf(item, facet, inPanel, field);
    if (!sources) return null;
    const { facet: shown, targets } = sources;
    const said = (shown.said || []).filter((x) => inPanel || x.source !== s.source || x.item !== s.item);
    if (!said.length) return null;
    const box = el("r4m-said");
    said.filter((x) => x.words.length).forEach((x) => {
      const row = el("r4m-said-row");
      const site = el("a", "r4m-said-site", x.source);
      site.href = x.url;
      site.target = "_blank";
      site.rel = "noreferrer";
      row.appendChild(site);
      x.words.forEach((w) => {
        row.appendChild(siteWord(w, shown, targets));
      });
      box.appendChild(row);
    });
    const silent = said.filter((x) => !x.words.length && x.read);
    if (silent.length) box.appendChild(el("r4m-said-silent", null, t("facet.silent", { sites: silent.map((x) => x.source).join(", ") })));
    said.filter((x) => !x.words.length && !x.read).forEach((x) => {
      box.appendChild(el("r4m-said-silent", null, t("facet.unread", { site: x.source })));
    });
    return box;
  }

  Object.assign(r4m, { saidBlock });
})();
