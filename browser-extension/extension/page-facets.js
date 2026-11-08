/*
 * A FACET OF THE WORK AS SHOWN: a block of its own — in the panel or after the site's element the
 * source names — or on the site's own elements of its values; "no other …" of a facet whose values
 * may all be named.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, nameOf, labelOf, el, saving, failed, ownButton, withFailure } = r4m;
  const { UNIVERSE, about, answeredShown, correct, answers, stableOrder, laterOffer, valueChip, suggestedChip } = r4m;
  const { moreOf, shownKeys, scaleBlock, lower, addField, saidBlock, byTextBlock } = r4m;

  /** The facets the user may say are all named, with the keys of how the button and its hints name them. */
  const ALL_NAMED = {
    [UNIVERSE]: { others: "allNamed.universeOthers", what: "allNamed.universeWhat" },
    characters: { others: "allNamed.charactersOthers", what: "allNamed.charactersWhat" },
    "secondary-characters": { others: "allNamed.secondaryOthers", what: "allNamed.secondaryWhat" },
    pairings: { others: "allNamed.pairingsOthers", what: "allNamed.pairingsWhat" },
  };

  /**
   * "No other universes", "no other pairings": the user's word that the work's values of the
   * facet are all named — the others are not offered and count as rejected for the model; pressed
   * again, taken back. Shown with no value too, as in the application: "none" is an answer as well.
   */
  function completeButton(item, facet) {
    // Any other facet the user may say is complete — the languages of a game — by the words of any value
    const keys = ALL_NAMED[facet.facet] || (facet.completable ? { others: "allNamed.valuesOthers", what: "allNamed.valuesWhat" } : null);
    if (!keys || facet.editable === false) return null;
    const names = { others: t(keys.others), what: t(keys.what) };
    const s = item.summary;
    const complete = facet.complete === true;
    const about = `complete|${facet.facet}`;
    return withFailure(ownButton(
      complete ? `✓ ${names.others}` : names.others,
      t(complete ? "allNamed.doneTitle" : "allNamed.title", { what: names.what }),
      () => api(complete ? "DELETE" : "PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/corrections/complete?facet=${enc(facet.facet)}`),
      `r4m-button r4m-complete${complete ? " r4m-current" : ""}`,
      (shown) => {
        const f = (shown.facets || []).find((x) => x.facet === facet.facet);
        if (!f) return;
        f.complete = !complete;
        // Said complete: nothing more is offered
        if (!complete) {
          f.values = (f.values || []).filter((v) => !v.offered);
          const s2 = (shown.suggestions || []).find((x) => x.facet === facet.facet);
          if (s2) s2.suggested = [];
        }
      },
      about,
    ), about);
  }

  /** A facet of one value that has one — the language of a book, the kind of its universe ([FacetDef.single]). */
  const held = (facet) => facet.single === true && (facet.values || []).some((v) => !v.offered && v.corrected !== "REMOVED");

  /**
   * A facet as a block: its label, what the sites say of it, its values with the user's answers, the
   * suggestions, the field to add one.
   */
  function facetBlock(item, facet, suggestions, inPanel) {
    const block = el("r4m-facet");
    const label = el("r4m-label", null, labelOf(facet));
    block.appendChild(label);
    const line = el("r4m-chips");
    // A facet of the site is its word: shown, never answered on
    const editable = facet.editable !== false;
    const values = facet.values || [];
    // The work's values the user has not answered on — not those only offered, nor those it has by a tag under them
    const open = values.filter((v) => !v.corrected && !v.offered && !v.implied);
    if (editable && open.length > 1) {
      const all = `all|${facet.facet}`;
      label.appendChild(withFailure(ownButton(t("facet.confirmAll", { count: open.length }), t("facet.confirmAllTitle"), async () => {
        // The last answer kept says what all of them made of the facet
        let last;
        for (const v of open) last = await correct(item, facet.facet, { key: v.key }, true);
        return last;
      }, "r4m-button r4m-all", (shown, last) => {
        open.forEach((v) => {
          answeredShown(facet.facet, v, true)(shown, last);
        });
      }, all), all));
    }
    // A scale — the age rating — answered on as one
    if (editable && facet.steps?.length) {
      const words = saidBlock(item, facet, inPanel);
      if (words) block.appendChild(words);
      block.appendChild(scaleBlock(item, facet));
      return block;
    }
    const field = editable ? addField(item, facet.facet, labelOf(facet), held(facet)) : null;
    const words = saidBlock(item, facet, inPanel, field);
    if (words) block.appendChild(words);
    const allNamed = completeButton(item, facet);
    if (allNamed) label.appendChild(allNamed);
    // The work's values, the suggested ones and the candidates "…" showed, in the order first shown, whichever they were then
    const chips = values.map((v) => ({ key: v.key, value: v, chip: () => valueChip(item, facet.facet, v, editable) }));
    let more = null;
    if (editable) {
      (suggestions?.suggested || []).forEach((v) => {
        chips.push({ key: v.key, chip: () => suggestedChip(item, facet.facet, v) });
      });
      // A facet of one value that has one offers no other: one chosen in its place replaces it
      if (!facet.complete && !held(facet)) {
        more = moreOf(item, facet.facet, shownKeys(facet, suggestions));
        chips.push(...more.chips);
      }
    }
    const ordered = stableOrder(item, facet.facet, chips);
    const later = laterOffer(item, facet.facet, ordered.filter((c) => c.value).map((c) => c.value));
    ordered.forEach((c) => {
      if (c.value && c.value === later) line.appendChild(Object.assign(el("r4m-later"), { title: t("facet.laterOffers") }));
      line.appendChild(c.chip());
    });
    if (more?.button) line.appendChild(more.button);
    if (editable) line.appendChild(field);
    block.appendChild(line);
    const text = byTextBlock(item, facet);
    if (text) block.appendChild(text);
    return block;
  }

  /**
   * The facet on the page: on the site's own elements of its values ([decor.values]) — the
   * answers beside each, the user's values and the suggestions after the last — or as a block of
   * its own after the element [decor.after]. False when the page has neither.
   */
  function facetInPlace(item, decor) {
    const suggestions = (item.suggestions || []).find((x) => x.facet === decor.facet);
    const facet = (item.facets || []).find((f) => f.facet === decor.facet) || { facet: decor.facet, labelKey: suggestions?.labelKey, values: [] };
    if (decor.after) {
      const anchor = document.querySelector(decor.after);
      if (!anchor) return false;
      // After the blocks of the facets put there before it, in the order the source lists them
      let at = anchor;
      while (at.nextElementSibling && at.nextElementSibling.tagName === "R4M-FACET") at = at.nextElementSibling;
      at.insertAdjacentElement("afterend", facetBlock(item, facet, suggestions, false));
      return true;
    }
    const elements = [...document.querySelectorAll(decor.values)].filter((e) => !e.closest("[data-r4m]"));
    if (!elements.length) return false;
    const values = facet.values || [];
    elements.forEach((e) => {
      const label = lower(e.getAttribute("title") || e.textContent);
      // The site writes a value as the data has it; a value of the application's own, maybe as the words say it
      const v = values.find((x) => lower(x.name) === label || lower(x.key) === label || lower(nameOf(x)) === label);
      if (!v || v.corrected === "ADDED") return;
      if (v.corrected === "REMOVED") e.classList.add("r4m-removed");
      else if (v.corrected !== "CONFIRMED" && v.chance !== undefined && v.chance !== null && v.chance < 0.5) e.classList.add("r4m-doubted");
      const tools = el("r4m-tools", saving.has(about(decor.facet, v.key)) ? "r4m-saving" : null);
      answers(item, decor.facet, v).forEach((a) => {
        tools.appendChild(a);
      });
      const error = failed.get(about(decor.facet, v.key));
      if (error) tools.appendChild(Object.assign(el("r4m-failure", null, `⚠ ${t("common.failed", { error })}`), { role: "alert" }));
      e.insertAdjacentElement("afterend", tools);
    });
    const after = el("r4m-chips");
    // The user's values and the model's, the suggested ones and the candidates "…" showed, in the order first shown, whichever they were then
    const chips = values
      .filter((v) => v.corrected === "ADDED" || (v.inferred && v.corrected !== "REMOVED"))
      .map((v) => ({ key: v.key, chip: () => valueChip(item, decor.facet, v) }));
    (suggestions?.suggested || []).forEach((v) => {
      chips.push({ key: v.key, chip: () => suggestedChip(item, decor.facet, v) });
    });
    const more = !facet.complete && !held(facet) ? moreOf(item, decor.facet, shownKeys(facet, suggestions)) : null;
    if (more) chips.push(...more.chips);
    stableOrder(item, decor.facet, chips).forEach((c) => {
      after.appendChild(c.chip());
    });
    if (more?.button) after.appendChild(more.button);
    const field = addField(item, decor.facet, labelOf(facet), held(facet));
    after.appendChild(field);
    const allNamed = completeButton(item, facet);
    if (allNamed) after.appendChild(allNamed);
    const last = elements[elements.length - 1];
    (last.nextElementSibling && last.nextElementSibling.tagName === "R4M-TOOLS" ? last.nextElementSibling : last)
      .insertAdjacentElement("afterend", after);
    // The page shows the site's own words: over the values, the other sites'
    const words = saidBlock(item, facet, false, field);
    if (words) after.insertAdjacentElement("beforebegin", words);
    const text = byTextBlock(item, facet);
    if (text) after.insertAdjacentElement("afterend", text);
    return true;
  }

  Object.assign(r4m, { facetBlock, facetInPlace });
})();
