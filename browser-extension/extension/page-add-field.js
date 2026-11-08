/*
 * A VALUE FOUND BY ITS NAME AND ADDED: the field of a facet with its own list of hints, kept through
 * the drawing of the page anew; a site's word typed into it by a press.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, nameOf, el, keyOf, act, about, answeredShown, correct, percent, mentionsText } = r4m;

  /** The facets that say which values a work may have — its universes, their characters, the pairs of them — hinted by its candidates. */
  const CHOSEN = ["universe", "characters", "secondary-characters", "pairings"];

  /** The other facet of a work's characters: a character is in one of them, never in both. */
  const OTHER_CHARACTERS = { characters: "secondary-characters", "secondary-characters": "characters" };

  /** The original characters: many persons, of either facet of characters at once. */
  const ORIGINAL_CHARACTERS = ["oc:female", "oc:male"];

  /**
   * The facets whose values are entries of the dictionary alone — a universe of the catalogue, a
   * pair of its characters: one is chosen among the hints, never made of a name typed, which would
   * be a value no dictionary knows. A character may be named as the work names it.
   */
  const KEYS_ONLY = ["universe", "pairings"];

  /**
   * THE FIELDS TO ADD A VALUE ARE KEPT THROUGH THE DRAWING OF THE PAGE ANEW, by work and facet
   * ("source/item|facet"): every answer and every reading of the work draws the page again, and a
   * field drawn anew would lose what is typed in it, its focus and its open list. A field the
   * drawing leaves out is dropped.
   */
  const addFields = new Map();

  /** The focus given back to a field after the drawing: not the user's focusing it. */
  let refocusing = false;

  const lower = (text) => (text || "").trim().toLowerCase();

  /** A name as it is matched: any case, "ё" as "е". */
  const folded = (text) => (text || "").toLowerCase().replace(/ё/g, "е");

  /**
   * A field to add a value by its name, with a list of hints of its own under it: of a facet that
   * says which values the work may have, every candidate with the model's chance and how often the
   * work's texts name it, the likeliest first, narrowed to those whose name has every word typed,
   * anywhere in it; of any other facet, the facet's values as they are typed, how many works have
   * each. A hint chosen is added by its key, a name typed and sent by its name.
   *
   * NOT A <datalist>: a universe's candidates are thousands, and Safari on an iPad draws them all
   * at once, slowly, by the value alone — without the chance and the mentions — matching the start
   * of a name only. The list here draws what is scrolled to, a few screens at a time.
   */
  function addField(item, facet, label, changing = false) {
    const id = `${keyOf(item)}|${facet}`;
    const placeholder = changing ? t("facet.change", { what: lower(label) }) : `+ ${lower(label)}`;
    const kept = addFields.get(id);
    if (kept && !kept.isConnected) {
      kept.querySelector("input").placeholder = placeholder;
      return kept;
    }
    const form = el("form", "r4m-add");
    const input = el("input");
    input.type = "text";
    input.placeholder = placeholder;
    input.autocomplete = "off";
    input.setAttribute("role", "combobox");
    input.setAttribute("aria-autocomplete", "list");
    input.setAttribute("aria-expanded", "false");
    const box = el("r4m-options");
    box.setAttribute("role", "listbox");
    box.hidden = true;
    const s = item.summary;
    const chosen = CHOSEN.includes(facet);

    /**
     * Every hint known: { name — as shown, names? — every one it goes by, key, nameKey?, description?, note }; and those the typing leaves, drawn
     * up to [drawn], the one gone to by the keys [active].
     */
    let hints = [];
    let shown = [];
    let drawn = 0;
    let active = -1;

    /**
     * Adds a value: a hint by its key, a name typed ([key] none) by its name; a hint of an entry of a
     * catalogue ([entry]) by it — the tag made of it, shown by the key the answer tells.
     */
    function add(name, key, nameKey, entry) {
      if (!name) return;
      form.r4mStale();
      if (entry) {
        let made = null;
        act(
          async () => {
            made = await correct(item, facet, { entry }, true);
            return made;
          },
          (shownItem, answer) => answeredShown(facet, { key: made.key, name: made.name || name, nameKey, offered: true }, true)(shownItem, answer),
          about(facet, key),
        );
        return;
      }
      act(() => correct(item, facet, key ? { key } : { name }, true), answeredShown(facet, { key: key || name, name, nameKey, offered: true }, true), about(facet, key || name));
    }

    function close() {
      box.hidden = true;
      active = -1;
      input.setAttribute("aria-expanded", "false");
      document.removeEventListener("pointerdown", outside, true);
    }

    /** A press anywhere but the field and its list closes the list; not the field's blur — on an iPad it comes before the tap on a row does. */
    function outside(e) {
      if (!form.contains(e.target)) close();
    }

    /** A row of a hint: its name, and under it what tells it apart; pressed, it is added. */
    function row(h, i) {
      const r = el("r4m-option", i === active ? "r4m-active" : null);
      r.setAttribute("role", "option");
      r.appendChild(el("r4m-option-name", null, h.name));
      // What the catalogue says of it: two of one name told apart
      if (h.description) r.appendChild(el("r4m-option-note", null, h.description));
      if (h.note) r.appendChild(el("r4m-option-note", null, h.note));
      // A mouse's press keeps the focus in the field; a finger's is the start of a scroll as often as of a tap
      r.addEventListener("pointerdown", (e) => {
        if (e.pointerType === "mouse") e.preventDefault();
      });
      // A tap, not a scroll: the click comes only of a touch that did not move
      r.addEventListener("click", () => {
        close();
        input.value = "";
        add(h.name, h.key, h.nameKey, h.entry);
      });
      return r;
    }

    /**
     * The rows drawn on until a screen of the list beyond what is seen: a flick does not reach the
     * end before more are drawn. A list not yet as high as it may be grows a row at a time.
     */
    function drawMore() {
      while (drawn < shown.length && box.scrollHeight <= box.scrollTop + 2 * box.clientHeight) {
        box.appendChild(row(shown[drawn], drawn));
        drawn++;
      }
    }

    function show() {
      box.textContent = "";
      box.scrollTop = 0;
      drawn = 0;
      active = -1;
      if (!shown.length || document.activeElement !== input) return close();
      box.hidden = false;
      input.setAttribute("aria-expanded", "true");
      document.addEventListener("pointerdown", outside, true);
      drawMore();
    }

    /** The hints that have every word typed, each in any of their names ("мадам хуч": "Мадам Трюк", "Роланда Хуч"), in their order. */
    function narrow() {
      const words = folded(input.value).split(/[\s/&]+/).filter(Boolean);
      shown = chosen ? hints.filter((h) => words.every((w) => [h.name, ...(h.names || [])].some((n) => folded(n).includes(w)))) : hints;
      show();
    }

    function go(step) {
      if (box.hidden || !shown.length) return;
      active = Math.max(0, Math.min(shown.length - 1, active + step));
      while (drawn <= active) {
        box.appendChild(row(shown[drawn], drawn));
        drawn++;
      }
      [...box.children].forEach((r, i) => {
        r.classList.toggle("r4m-active", i === active);
      });
      box.children[active].scrollIntoView({ block: "nearest" });
    }

    box.addEventListener("scroll", drawMore);
    form.r4mClose = close;
    form.r4mStale = () => {};
    if (chosen) {
      /** The reading of the candidates, once for the work as last read: read again after it is read anew ([form.r4mStale]). */
      let reading = null;
      const hintsRead = () => {
        if (!reading) {
          reading = api("GET", `/api/items/${enc(s.source)}/${enc(s.item)}/candidates?facet=${enc(facet)}`).then(
            (candidates) => {
              hints = candidates.map((c) => ({
                name: nameOf(c),
                // Every name a character goes by: found by any of them
                names: c.names,
                key: c.key,
                nameKey: c.nameKey,
                description: c.description,
                note: [c.chance !== undefined && c.chance !== null ? percent(c.chance) : "", mentionsText(c.mentions)].filter(Boolean).join(" · "),
              }));
              // A character of the work's other facet of characters — the user's or the model's — is found
              // here too, said so: chosen, it moves here from there
              const other = OTHER_CHARACTERS[facet];
              const work = r4m.view?.item || item;
              const elsewhere = other && (work.facets || []).find((f) => f.facet === other);
              if (elsewhere) {
                const known = new Map(hints.map((h) => [h.key, h]));
                const moving = t(facet === "characters" ? "add.fromSecondary" : "add.fromMain");
                (elsewhere.values || [])
                  .filter((v) => v.corrected !== "REMOVED" && !v.offered && !ORIGINAL_CHARACTERS.includes(v.key))
                  .forEach((v) => {
                    const hint = known.get(v.key);
                    if (hint) hint.note = [hint.note, moving].filter(Boolean).join(" · ");
                    else hints.push({ name: nameOf(v), names: [nameOf(v)], key: v.key, nameKey: v.nameKey, description: v.description, note: moving });
                  });
              }
            },
            () => {
              // No hints: the value is added by its name all the same; asked again on the next typing
              reading = null;
            },
          );
        }
        return reading;
      };
      form.r4mStale = () => {
        reading = null;
      };
      input.addEventListener("focus", async () => {
        // The focus given back after the drawing: the list stays as it was
        if (refocusing) return;
        await hintsRead();
        narrow();
      });
      input.addEventListener("input", async () => {
        await hintsRead();
        narrow();
      });
    } else {
      let timer = null;
      input.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(async () => {
          try {
            const values = await api("GET", `/api/sources/${enc(s.source)}/facets/${enc(facet)}/values?query=${enc(input.value)}&limit=20`);
            // By its key: a value of the application's own is shown by words the name typed would not match.
            // An entry of a catalogue no tag is bound to, of a facet of tags none of the name typed: a new tag, told so
            hints = values.map((v) => ({
              name: nameOf(v),
              key: v.key,
              nameKey: v.nameKey,
              note: v.entry ? [t("facet.newTag"), v.entry.description].filter(Boolean).join(" · ") : t("facet.works", { count: v.items }),
              entry: v.entry && { catalogue: v.entry.catalogue, entry: v.entry.entry },
            }));
            narrow();
          } catch {
            // No hints: the value is added by its name all the same
          }
        }, 250);
      });
    }
    input.addEventListener("keydown", (e) => {
      // The site's own keys must not see the typing
      e.stopPropagation();
      if (e.key === "ArrowDown") {
        e.preventDefault();
        if (box.hidden) narrow();
        else go(1);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        go(-1);
      } else if (e.key === "Escape" || e.key === "Tab") {
        close();
      }
    });
    ["keyup", "keypress"].forEach((type) => {
      input.addEventListener(type, (e) => e.stopPropagation());
    });
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      // The hint gone to by the keys; else a hint of just the name typed; else the name as it is —
      // of a facet of the dictionary's entries alone, none: the list stays open to choose from
      const typed = input.value.trim();
      const h = active >= 0 ? shown[active] : hints.find((x) => folded(x.name) === folded(typed));
      // A site's word typed by a press is not the user's typing: it adds only what it finds
      if (!h && (KEYS_ONLY.includes(facet) || typed === form.r4mWord)) {
        input.setAttribute("aria-invalid", "true");
        note.hidden = false;
        narrow();
        return;
      }
      close();
      input.value = "";
      if (h) add(h.name, h.key, h.nameKey, h.entry);
      else add(typed, null);
    });
    // Said under the field, not in a title: a finger sees no title
    const note = el("r4m-add-note", null, t("facet.chooseHint"));
    note.hidden = true;
    note.setAttribute("role", "alert");
    input.addEventListener("input", () => {
      note.hidden = true;
      input.removeAttribute("aria-invalid");
    });
    form.appendChild(input);
    form.appendChild(box);
    form.appendChild(note);
    if (!kept) addFields.set(id, form);
    return form;
  }

  /** The search of a field with a site's word typed: not the user's typing, so it adds only what it finds. */
  function pressWord(field, name) {
    const input = field.querySelector("input");
    input.value = name;
    field.r4mWord = name;
    input.focus();
    input.dispatchEvent(new Event("input", { bubbles: true }));
  }

  /** The field of the [item]'s [facet] as last drawn. */
  const fieldOf = (item, facet) => addFields.get(`${keyOf(item)}|${facet}`);

  /** The work read anew: the candidates the fields offer may be others now. */
  function staleFields() {
    addFields.forEach((form) => {
      form.r4mStale();
    });
  }

  /**
   * The page drawn anew by [draw]; the field typed in keeps its text, its list and its focus — given
   * back, with the caret where it was.
   */
  function keepFieldsThrough(draw) {
    const active = document.activeElement;
    const focused = active?.closest?.("form.r4m-add") ? active : null;
    const caret = focused ? [focused.selectionStart, focused.selectionEnd] : null;
    draw();
    addFields.forEach((form, id) => {
      if (form.isConnected) return;
      form.r4mClose();
      addFields.delete(id);
    });
    if (focused?.isConnected && document.activeElement !== focused) {
      refocusing = true;
      try {
        focused.focus({ preventScroll: true });
        if (caret[0] !== null) focused.setSelectionRange(caret[0], caret[1]);
      } finally {
        refocusing = false;
      }
    }
  }

  Object.assign(r4m, { OTHER_CHARACTERS, lower, addField, pressWord, fieldOf, staleFields, keepFieldsThrough });
})();
