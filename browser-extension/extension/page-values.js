/*
 * A FACET'S VALUES AND THE USER'S ANSWERS ON THEM: a value as a chip, its look by its state, the
 * marks and the ✓ ✕ ↺ beside it, a suggested value; the order they are shown in; what an answer
 * looks like once the application has kept it.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, nameOf, el, saving, failed, apart, button, withFailure } = r4m;

  /** The facet the application gives every work of a type with universes: the universes of fan fiction. */
  const UNIVERSE = "universe";

  // --- What a change looks like before the application answers ---

  /** The value [key] of the [facet] as shown, put there if it is not yet; [nameKey] — of a value of the application's own. */
  function shownValue(item, facet, key, name, nameKey) {
    item.facets = item.facets || [];
    let f = item.facets.find((x) => x.facet === facet);
    if (!f) {
      const suggested = (item.suggestions || []).find((x) => x.facet === facet);
      f = { facet, labelKey: suggested?.labelKey, values: [] };
      item.facets.push(f);
    }
    f.values = f.values || [];
    let v = f.values.find((x) => x.key === key);
    if (!v) {
      v = { key, name: name || key, nameKey, offered: true };
      f.values.push(v);
    }
    return v;
  }

  /** Given by the site, the model or a draft before any answer: confirmed by a yes, not added. */
  const given = (v) => (!v.corrected && !v.offered && !v.implied) || (v.chance || 0) > 0.5 || !!v.draftedFrom;

  /**
   * The facets an answer touched as the application says the answers make them ([answered]: its
   * AnsweredFacet), as the page shows them until the work is read anew — the values' chances and the
   * sites' word as shown: a value answered on is the user's — CONFIRMED of one the site, the model
   * or a draft gave, ADDED of any other — or REMOVED, crossed out in its place; one whose answer was
   * taken back is the site's and the model's again, or goes when only the user gave it; one shown no
   * more goes, and so does every other of a facet of one value a value was added to; a tag the work
   * has by a tag under it the user confirmed is marked so — not one the work has of itself — and
   * shown if it was not. [named] — the name of the value answered on, as the page shows it. Answered,
   * a suggestion is no longer one.
   */
  function facetsShown(item, answered, named) {
    answered.forEach((a) => {
      const f = (item.facets || []).find((x) => x.facet === a.facet);
      if (!f) return;
      const answers = new Map(a.answers.map((x) => [x.key, x]));
      const hidden = new Set(a.hidden);
      const implied = new Set(a.implied.map((x) => x.key));
      const replaced = f.single === true && a.answers.some((x) => x.added);
      f.values = (f.values || []).flatMap((v) => {
        const word = answers.get(v.key);
        if (word) {
          if (!word.added) return [{ ...v, corrected: "REMOVED", offered: null, implied: null }];
          const corrected = v.corrected === "CONFIRMED" || v.corrected === "ADDED" ? v.corrected : given(v) ? "CONFIRMED" : "ADDED";
          return [{ ...v, corrected, offered: null, implied: null }];
        }
        if (hidden.has(v.key) || replaced) return [];
        if (v.corrected) return v.corrected === "ADDED" && !given(v) ? [] : [{ ...v, corrected: null }];
        if (implied.has(v.key) && (v.offered || v.implied)) return [{ ...v, implied: true, offered: null }];
        return [v];
      });
      const known = new Set(f.values.map((v) => v.key));
      a.answers.filter((x) => !known.has(x.key)).forEach((x) => {
        const name = named?.key === x.key ? named : null;
        f.values.push({ key: x.key, name: name?.name || x.name || x.key, nameKey: name?.nameKey, corrected: x.added ? "ADDED" : "REMOVED" });
      });
      a.implied.filter((x) => !known.has(x.key) && !answers.has(x.key)).forEach((x) => {
        f.values.push({ key: x.key, name: x.name, nameKey: x.nameKey, implied: true });
      });
      const s = (item.suggestions || []).find((x) => x.facet === a.facet);
      if (s) s.suggested = (s.suggested || []).filter((x) => !answers.has(x.key));
    });
  }

  /** What an answer kept says it made of the facets: the FacetAnswer's, or the list the tags by the text answer with. */
  const answeredOf = (answer) => (Array.isArray(answer) ? answer : answer?.facets);

  /**
   * The user's word on a value as shown once kept: by what the application says the answers make
   * of the facets it touched ([facetsShown]). A suggestion answered on is no longer a suggestion.
   */
  function answeredShown(facet, value, added) {
    return (item, answer) => {
      const answered = answeredOf(answer);
      if (answered) {
        facetsShown(item, answered, value);
        return;
      }
      // Of no word of the application on the facets: the answer alone
      const s = (item.suggestions || []).find((x) => x.facet === facet);
      if (s) s.suggested = (s.suggested || []).filter((x) => x.key !== value.key);
      const v = shownValue(item, facet, value.key, value.name, value.nameKey);
      v.corrected = added ? (given(v) ? "CONFIRMED" : "ADDED") : "REMOVED";
    };
  }

  /** The user's word on a value taken back, as shown once kept: by what the application says the answers make of the facets. */
  function unansweredShown(facet, key) {
    return (item, answer) => {
      const answered = answeredOf(answer);
      if (answered) {
        facetsShown(item, answered);
        return;
      }
      const f = (item.facets || []).find((x) => x.facet === facet);
      const v = f && (f.values || []).find((x) => x.key === key);
      if (!v) return;
      if (v.corrected === "ADDED" && !given(v)) f.values = f.values.filter((x) => x !== v);
      else v.corrected = null;
    };
  }

  const about = (facet, key) => `${facet}|${key}`;

  function correct(item, facet, value, added) {
    const s = item.summary;
    return api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/corrections/facets`, { facet, ...value, added });
  }

  function uncorrect(item, facet, key) {
    const s = item.summary;
    return api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/corrections/facets?facet=${enc(facet)}&key=${enc(key)}`);
  }

  function percent(chance) {
    return `${Math.round(chance * 100)}%`;
  }

  /**
   * What goes beside a value the user has not answered on: "site" by one the site gives, the model's
   * chance in small print — amber when the work more likely has it not — and the user's ✓ (it has it) and ✕ (it has it
   * not). A value the user confirmed or added keeps one button that takes the word back, shown
   * when the chip is pointed at; one the user took away has ↺.
   */
  function answers(item, facet, v) {
    const out = [];
    const on = about(facet, v.key);
    if (v.corrected === "REMOVED") {
      out.push(button("↺", t("value.restore"), () => uncorrect(item, facet, v.key), "r4m-yes", unansweredShown(facet, v.key), on));
      return out;
    }
    if (v.corrected === "ADDED" || v.corrected === "CONFIRMED") {
      out.push(button("✕", t(v.corrected === "ADDED" ? "value.removeAdded" : "value.unanswer"), () => uncorrect(item, facet, v.key), "r4m-undo", unansweredShown(facet, v.key), on));
      return out;
    }
    if (v.implied) {
      // The work has it by a tag under it: no answer of its own, it is the work's while that one is
      out.push(Object.assign(el("r4m-chance", null, "↑"), { title: t("value.implied") }));
      return out;
    }
    // The site gives it: told apart from the model's offers, the chance beside it the model's word
    if (v.fromSite && !v.draftedFrom) out.push(Object.assign(el("r4m-chance", null, t("value.fromSite")), { title: t("value.fromSiteNote") }));
    if (v.draftedFrom) {
      // Drafted from the site's value: a universe of a fandom of the site, anything else through the dictionary — said so,
      // not a chance of the model
      const fandom = facet === UNIVERSE;
      out.push(
        Object.assign(el("r4m-chance", null, t(fandom ? "value.draftedByFandom" : "value.drafted")), {
          title: t(fandom ? "value.draftedByFandomFrom" : "value.draftedFrom", { from: v.draftedFrom }),
        }),
      );
    } else if (v.chance !== undefined && v.chance !== null) {
      const unlikely = v.chance < 0.5;
      out.push(Object.assign(el("r4m-chance", unlikely ? "r4m-unlikely" : null, percent(v.chance)), {
        title: t(unlikely ? "value.unlikely" : "value.confidence"),
      }));
    }
    out.push(button("✓", t("value.yes"), () => correct(item, facet, { key: v.key }, true), "r4m-yes", answeredShown(facet, v, true), on));
    out.push(button("✕", t("value.no"), () => correct(item, facet, { key: v.key }, false), "r4m-no", answeredShown(facet, v, false), on));
    return out;
  }

  /** The order a facet's chips of an item were first shown in, by their values' keys: kept while the page is open. */
  const orders = new Map();
  const firstShown = new Map();

  /**
   * The chips of a facet's line in the order first shown — the work's values in the application's
   * order: by the model's chance, the likeliest first, the user's answers aside, a rejected value
   * too, and a tag above another before it; then the suggested, then the candidates "…" showed —
   * whichever a value was then: an answer changes how a value looks, never where it is — a
   * suggestion or a candidate answered on becomes the work's value where it stood — until the page
   * is opened again. A value that comes later goes after them. [values] — anything with a key.
   */
  function stableOrder(item, facet, values) {
    const id = `${item.summary.source}/${item.summary.item}/${facet}`;
    const order = orders.get(id) || [];
    const known = new Set(order);
    const fresh = values.filter((v) => !known.has(v.key)).map((v) => v.key);
    const all = order.concat(fresh);
    orders.set(id, all);
    if (!firstShown.has(id) && all.length > 0) firstShown.set(id, new Set(all));
    const at = new Map(all.map((k, i) => [k, i]));
    return [...values].sort((a, b) => at.get(a.key) - at.get(b.key));
  }

  /**
   * The first of the offers that came after the values were first shown: the server refills them
   * as they are answered on, and they stand after a quiet line, not among the first.
   */
  function laterOffer(item, facet, values) {
    const first = firstShown.get(`${item.summary.source}/${item.summary.item}/${facet}`);
    return first && values.find((v) => v.offered && !first.has(v.key));
  }

  /**
   * A value as a chip, its state told by its look and a sign, not by colour alone: the user's —
   * filled, a ✓ before the name; not answered on — outlined on white, with [answers]; taken away
   * — faded and crossed out. A universe the work is linked to has ↻ to ask the catalogue for its
   * characters again.
   */
  function valueChip(item, facet, v, editable = true) {
    const mine = v.corrected === "ADDED" || v.corrected === "CONFIRMED";
    const chip = el("r4m-chip", mine ? "r4m-mine" : v.corrected === "REMOVED" ? "r4m-removed-chip" : editable ? (v.offered ? "r4m-open r4m-offered" : "r4m-open") : null);
    if (saving.has(about(facet, v.key))) chip.classList.add("r4m-saving");
    if (mine) chip.appendChild(Object.assign(el("r4m-check", null, "✓"), { title: t("value.mine") }));
    chip.appendChild(el("span", null, nameOf(v)));
    // What the catalogue says of a universe or a character: the name alone may be of several
    if (v.description) chip.title = v.description;
    if (editable) {
      answers(item, facet, v).forEach((e) => {
        chip.appendChild(e);
      });
    }
    let refreshed = null;
    // A universe's entries of the catalogue read again, and their characters when the universe's are read
    if (facet === UNIVERSE && v.corrected !== "REMOVED") {
      const on = `refresh|${v.key}`;
      refreshed = on;
      const b = el("button", `r4m-button${saving.has(on) ? " r4m-saving" : ""}`, "↻");
      b.type = "button";
      b.title = t("universe.refresh");
      b.disabled = saving.has(on);
      b.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        apart(() => api("POST", "/api/universes/refresh", { universe: v.key }), on);
      });
      chip.appendChild(b);
    }
    return withFailure(chip, refreshed && failed.has(refreshed) ? refreshed : about(facet, v.key));
  }

  /** A suggested value: a dashed chip with its chance, ✓ to add it and ✕ to say the work has it not. */
  function suggestedChip(item, facet, v) {
    const chip = el("r4m-chip", "r4m-suggested");
    chip.title = [v.description, t("value.suggested")].filter(Boolean).join("\n");
    chip.appendChild(el("span", null, nameOf(v)));
    chip.appendChild(el("r4m-chance", null, percent(v.chance)));
    const on = about(facet, v.key);
    // Answered, it stops being a suggestion: shown as the work's value the user added or rejected
    const offered = { key: v.key, name: v.name, nameKey: v.nameKey, offered: true };
    chip.appendChild(button("✓", t("value.yes"), () => correct(item, facet, { key: v.key }, true), "r4m-yes", answeredShown(facet, offered, true), on));
    chip.appendChild(button("✕", t("value.no"), () => correct(item, facet, { key: v.key }, false), "r4m-no", answeredShown(facet, offered, false), on));
    return withFailure(chip, on);
  }

  Object.assign(r4m, {
    UNIVERSE,
    about,
    shownValue,
    answeredShown,
    unansweredShown,
    correct,
    uncorrect,
    facetsShown,
    percent,
    answers,
    stableOrder,
    laterOffer,
    valueChip,
    suggestedChip,
  });
})();
