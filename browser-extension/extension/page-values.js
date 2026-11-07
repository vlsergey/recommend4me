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

  /**
   * The user's word on a value as shown: has it — the site's value confirmed, another added; has
   * it not — taken away. A suggestion answered on is no longer a suggestion.
   */
  function answeredShown(facet, value, added) {
    return (item) => {
      const s = (item.suggestions || []).find((x) => x.facet === facet);
      if (s) s.suggested = (s.suggested || []).filter((x) => x.key !== value.key);
      const v = shownValue(item, facet, value.key, value.name, value.nameKey);
      const site = !v.offered && !v.inferred && v.corrected !== "ADDED";
      v.corrected = added ? (site ? "CONFIRMED" : "ADDED") : "REMOVED";
    };
  }

  /** The user's word on a value taken back, as shown: a value only the user gave goes. */
  function unansweredShown(facet, key) {
    return (item) => {
      const f = (item.facets || []).find((x) => x.facet === facet);
      const v = f && (f.values || []).find((x) => x.key === key);
      if (!v) return;
      if (v.corrected === "ADDED" && !v.inferred) f.values = f.values.filter((x) => x !== v);
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
      // Drafted from the site's value through the dictionary: said so, not a chance of the model
      out.push(Object.assign(el("r4m-chance", null, t("value.drafted")), { title: t("value.draftedFrom", { from: v.draftedFrom }) }));
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

  /** The order a facet's values of an item were first shown in, by their keys: kept while the page is open. */
  const orders = new Map();
  const firstShown = new Map();

  /**
   * The values in the order first shown — the application's: by the model's chance, the likeliest
   * first, the user's answers aside, a rejected value too, and a tag above another before it: an
   * answer changes how a value looks, never where it is, until the page is opened again. A value
   * that comes later goes after them.
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
    const at = v.key.indexOf(":");
    let refreshed = null;
    if (facet === UNIVERSE && v.corrected !== "REMOVED" && at > 0) {
      const on = `refresh|${v.key}`;
      refreshed = on;
      const b = el("button", `r4m-button${saving.has(on) ? " r4m-saving" : ""}`, "↻");
      b.type = "button";
      b.title = t("universe.refresh");
      b.disabled = saving.has(on);
      b.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        apart(() => api("POST", "/api/universes", { catalogue: v.key.substring(0, at), universe: v.key.substring(at + 1) }), on);
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
    percent,
    answers,
    stableOrder,
    laterOffer,
    valueChip,
    suggestedChip,
  });
})();
