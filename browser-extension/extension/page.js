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
 */
(() => {
  if (window.__recommend4mePage) {
    window.__recommend4mePage.load();
    return;
  }

  const api = async (method, path, body) => {
    const answer = await browser.runtime.sendMessage({ type: "api", method, path, body });
    if (!answer || !answer.ok) throw new Error((answer && answer.error) || "Приложение не ответило");
    return answer.data;
  };
  const enc = encodeURIComponent;

  /** How long a shown work is not read again for every new send of a growing page. */
  const RELOAD_AFTER = 15 * 1000;

  /** What the application said of this page; null until it answers. */
  let view = null;
  let server = "";
  let message = null;
  let cardsTimer = null;
  let busy = false;
  let loadedAt = 0;
  /** The floating panel — a page without a place for it, the reader — starts folded, and stays as the user leaves it. */
  let folded = true;

  // --- Elements ---

  function el(tag, className, text) {
    const e = document.createElement(tag);
    e.setAttribute("data-r4m", "");
    if (className) e.className = className;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function button(text, title, onClick, className = "r4m-button") {
    const b = el("button", className, text);
    b.type = "button";
    if (title) b.title = title;
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      act(onClick);
    });
    return b;
  }

  /** Runs a change, then shows the page again as the application now knows it. */
  async function act(change) {
    if (busy) return;
    busy = true;
    try {
      await change();
      message = null;
    } catch (e) {
      message = e.message;
    }
    busy = false;
    await load();
  }

  /** A score of 0..10 as a class of colour, red to green. */
  function scoreClass(score) {
    return `r4m-s${Math.max(0, Math.min(10, Math.round(score)))}`;
  }

  const lower = (text) => (text || "").trim().toLowerCase();

  /** The file an address names: what a preview and its original share. */
  function fileOf(url) {
    try {
      const path = new URL(url, location.href).pathname;
      return decodeURIComponent(path.substring(path.lastIndexOf("/") + 1)).toLowerCase();
    } catch (e) {
      return "";
    }
  }

  // --- Loading ---

  async function load() {
    loadedAt = Date.now();
    try {
      server = await browser.runtime.sendMessage({ type: "server" });
      view = await api("GET", `/api/pages?url=${enc(location.href)}`);
    } catch (e) {
      view = null;
      message = e.message;
    }
    render();
  }

  function clear() {
    document.querySelectorAll("[data-r4m]:not(r4m-badge)").forEach((e) => e.remove());
    document.querySelectorAll(".r4m-removed, .r4m-doubted, .r4m-marked").forEach((e) =>
      e.classList.remove("r4m-removed", "r4m-doubted", "r4m-marked"),
    );
  }

  function render() {
    clear();
    if (!view) return;
    cards(true);
    const item = view.item;
    if (!item && !view.itemId) return;
    const placed = new Set();
    if (item) {
      (view.decor.facets || []).forEach((decor) => {
        if (facetInPlace(item, decor)) placed.add(decor.facet);
      });
      reviews(item);
      pictures(item);
    }
    panel(item, placed);
  }

  // --- The panel ---

  function panel(item, placed) {
    const root = el("r4m-panel");
    const head = el("r4m-head");
    head.appendChild(el("strong", null, "recommend4me"));
    if (item && item.summary.prediction) {
      head.appendChild(el("r4m-score", scoreClass(item.summary.prediction.score), item.summary.prediction.score.toFixed(1)));
    }
    if (item && item.summary.grade) head.appendChild(el("r4m-grade-mark", null, `★${item.summary.grade}`));
    head.appendChild(el("span", "r4m-spacer"));
    if (item) {
      const open = el("a", "r4m-link", "открыть в приложении");
      open.href = `${server}/#item=${enc(item.summary.source)}/${item.summary.item}`;
      open.target = "_blank";
      open.rel = "noopener";
      head.appendChild(open);
    }
    root.appendChild(head);
    if (message) root.appendChild(el("r4m-error", null, message));
    if (!item) {
      root.appendChild(el("r4m-line", "r4m-muted", "Работа ещё не сохранена: она появится, когда страница будет отправлена приложению."));
      place(root);
      return;
    }
    const s = item.summary;

    const score = el("r4m-line");
    if (s.prediction) {
      score.appendChild(el("span", null, "Прогноз "));
      score.appendChild(el("r4m-score", scoreClass(s.prediction.score), s.prediction.score.toFixed(1)));
      score.appendChild(el("span", "r4m-muted", " из 10"));
    } else {
      score.appendChild(el("span", "r4m-muted", "Прогноза пока нет: модель ещё не обучена или работа ещё не разобрана"));
    }
    root.appendChild(score);

    root.appendChild(grades(item));

    if (item.explanation && item.explanation.length) {
      const why = el("r4m-why");
      why.appendChild(el("span", "r4m-muted", "Почему: "));
      item.explanation.slice(0, 6).forEach((c, i) => {
        if (i) why.appendChild(el("span", "r4m-muted", " · "));
        const sign = c.contribution >= 0 ? "+" : "−";
        why.appendChild(el("span", c.contribution >= 0 ? "r4m-up" : "r4m-down", `${sign}${Math.abs(c.contribution).toFixed(1)} ${c.label}`));
      });
      root.appendChild(why);
    }

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
      if (!out.some((f) => f.facet.facet === x.facet)) out.push({ facet: { facet: x.facet, label: x.label, values: [] }, suggestions: x });
    });
    return out;
  }

  /** Puts the panel after the element the source names, or folded in a corner. */
  function place(root) {
    const anchor = view.decor.panelAfter && document.querySelector(view.decor.panelAfter);
    if (anchor) {
      anchor.insertAdjacentElement("afterend", root);
      return;
    }
    root.classList.add("r4m-floating");
    if (folded) root.classList.add("r4m-folded");
    const head = root.querySelector("r4m-head");
    const toggle = el("button", "r4m-button", folded ? "▸" : "▾");
    toggle.type = "button";
    toggle.title = "Свернуть или развернуть";
    toggle.addEventListener("click", (e) => {
      e.preventDefault();
      folded = !folded;
      root.classList.toggle("r4m-folded", folded);
      toggle.textContent = folded ? "▸" : "▾";
    });
    head.insertBefore(toggle, head.firstChild);
    document.body.appendChild(root);
  }

  function grades(item) {
    const s = item.summary;
    const line = el("r4m-line", "r4m-grades");
    line.appendChild(el("span", null, "Оценка: "));
    item.grades.forEach((label, i) => {
      const grade = i + 1;
      const current = s.grade === grade;
      const b = button(
        `${grade}`,
        current ? `${label} — нажмите ещё раз, чтобы снять` : label,
        () => current
          ? api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/rating`)
          : api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/rating`, { grade }),
        `r4m-grade${current ? " r4m-current" : ""}`,
      );
      line.appendChild(b);
    });
    if (s.grade) line.appendChild(el("span", "r4m-muted", ` ${item.grades[s.grade - 1]}`));
    else if (s.previousGrade) {
      line.appendChild(el("span", "r4m-muted", ` прошлая версия (${s.previousGradeVersion}): ${s.previousGrade}`));
    }
    return line;
  }

  // --- Facets ---

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
   * What goes beside a value: the model's chance in brackets while the user has not answered —
   * a "?" when the work more likely has it not — and the user's ✓ (it has it) and ✕ (it has it
   * not); a value answered shows the answer, pressed again it is taken back.
   */
  function answers(item, facet, v) {
    const out = [];
    if (v.inferred) out.push(Object.assign(el("r4m-inferred", null, "≈"), { title: "Вычислено по описанию, главам и другим тегам" }));
    if (v.corrected === "REMOVED") {
      out.push(button("↺", "Вернуть: вы сказали, что этого у работы нет", () => uncorrect(item, facet, v.key), "r4m-yes"));
      return out;
    }
    if (v.corrected === "ADDED") {
      out.push(button("✕", "Убрать: добавлено вами", () => uncorrect(item, facet, v.key), "r4m-no"));
      return out;
    }
    if (v.corrected === "CONFIRMED") {
      out.push(button("✓", "Вы подтвердили; нажмите, чтобы снять подтверждение", () => uncorrect(item, facet, v.key), "r4m-yes r4m-current"));
    } else {
      if (v.chance !== undefined && v.chance !== null) {
        const unlikely = v.chance < 0.5;
        out.push(Object.assign(el("r4m-chance", unlikely ? "r4m-unlikely" : null, `(${percent(v.chance)})`), {
          title: unlikely ? "Модель считает, что скорее этого у работы нет" : "Уверенность модели",
        }));
      }
      out.push(button("✓", "Да, это у работы есть", () => correct(item, facet, { key: v.key }, true), "r4m-yes"));
    }
    out.push(button("✕", "Нет, этого у работы нет", () => correct(item, facet, { key: v.key }, false), "r4m-no"));
    return out;
  }

  /** A value as a chip: its name and [answers]. */
  function valueChip(item, facet, v) {
    const chip = el("r4m-chip", { ADDED: "r4m-added", CONFIRMED: "r4m-confirmed", REMOVED: "r4m-removed-chip" }[v.corrected] || null);
    if (v.inferred) chip.classList.add("r4m-inferred-chip");
    chip.appendChild(el("span", null, v.name));
    answers(item, facet, v).forEach((e) => chip.appendChild(e));
    return chip;
  }

  /** A suggested value: a dashed chip with its chance, ✓ to add it and ✕ to say the work has it not. */
  function suggestedChip(item, facet, v) {
    const chip = el("r4m-chip", "r4m-suggested");
    chip.title = "Подсказка";
    chip.appendChild(el("span", null, v.name));
    chip.appendChild(el("r4m-chance", null, `(${percent(v.chance)})`));
    chip.appendChild(button("✓", "Да, это у работы есть", () => correct(item, facet, { key: v.key }, true), "r4m-yes"));
    chip.appendChild(button("✕", "Нет, этого у работы нет", () => correct(item, facet, { key: v.key }, false), "r4m-no"));
    return chip;
  }

  /** A field to add a value by its name, with the facet's values as hints. */
  function addField(item, facet, label) {
    const form = el("form", "r4m-add");
    const list = el("datalist");
    list.id = `r4m-values-${facet}`;
    const input = el("input");
    input.type = "text";
    input.placeholder = `+ ${lower(label)}`;
    input.setAttribute("list", list.id);
    let timer = null;
    input.addEventListener("input", () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const s = item.summary;
        try {
          const values = await api("GET", `/api/sources/${enc(s.source)}/facets/${enc(facet)}/values?query=${enc(input.value)}&limit=20`);
          list.textContent = "";
          values.forEach((v) => {
            const option = el("option");
            option.value = v.name;
            option.textContent = `${v.items}`;
            list.appendChild(option);
          });
        } catch (e) {
          // No hints: the value is added by its name all the same
        }
      }, 250);
    });
    // The site's own keys must not see the typing
    ["keydown", "keyup", "keypress"].forEach((t) => input.addEventListener(t, (e) => e.stopPropagation()));
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const name = input.value.trim();
      if (name) act(() => correct(item, facet, { name }, true));
    });
    form.appendChild(input);
    form.appendChild(list);
    return form;
  }

  /**
   * A facet as a block: its label, the site's own line of it when the panel shows it, its values
   * with the user's answers, the suggestions, the field to add one.
   */
  function facetBlock(item, facet, suggestions, inPanel) {
    const block = el("r4m-facet");
    block.appendChild(el("r4m-label", null, facet.label));
    if (inPanel && facet.original) block.appendChild(el("r4m-original", null, `На сайте: ${facet.original}`));
    const line = el("r4m-chips");
    (facet.values || []).forEach((v) => line.appendChild(valueChip(item, facet.facet, v)));
    ((suggestions && suggestions.suggested) || []).forEach((v) => line.appendChild(suggestedChip(item, facet.facet, v)));
    line.appendChild(addField(item, facet.facet, facet.label));
    block.appendChild(line);
    return block;
  }

  /**
   * The facet on the page: on the site's own elements of its values ([decor.values]) — the
   * answers beside each, the user's values and the suggestions after the last — or as a block of
   * its own after the element [decor.after]. False when the page has neither.
   */
  function facetInPlace(item, decor) {
    const facet = (item.facets || []).find((f) => f.facet === decor.facet) || { facet: decor.facet, label: decor.facet, values: [] };
    const suggestions = (item.suggestions || []).find((x) => x.facet === decor.facet);
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
      const v = values.find((x) => lower(x.name) === label || lower(x.key) === label);
      if (!v || v.corrected === "ADDED") return;
      if (v.corrected === "REMOVED") e.classList.add("r4m-removed");
      else if (v.corrected !== "CONFIRMED" && v.chance !== undefined && v.chance !== null && v.chance < 0.5) e.classList.add("r4m-doubted");
      const tools = el("r4m-tools");
      answers(item, decor.facet, v).forEach((a) => tools.appendChild(a));
      e.insertAdjacentElement("afterend", tools);
    });
    const after = el("r4m-chips");
    values.filter((v) => v.corrected === "ADDED" || (v.inferred && v.corrected !== "REMOVED")).forEach((v) => after.appendChild(valueChip(item, decor.facet, v)));
    ((suggestions && suggestions.suggested) || []).forEach((v) => after.appendChild(suggestedChip(item, decor.facet, v)));
    after.appendChild(addField(item, decor.facet, facet.label));
    const last = elements[elements.length - 1];
    (last.nextElementSibling && last.nextElementSibling.tagName === "R4M-TOOLS" ? last.nextElementSibling : last)
      .insertAdjacentElement("afterend", after);
    return true;
  }

  // --- Reviews and pictures ---

  function marks(current, put, remove, what) {
    const bar = el("r4m-marks");
    bar.appendChild(button("+", `${what}: повод выбрать работу`, () => (current === 1 ? remove() : put(1)), `r4m-mark${current === 1 ? " r4m-current" : ""}`));
    bar.appendChild(button("−", `${what}: повод отказаться от неё`, () => (current === -1 ? remove() : put(-1)), `r4m-mark${current === -1 ? " r4m-current" : ""}`));
    return bar;
  }

  function reviews(item) {
    const decor = view.decor.reviews;
    if (!decor) return;
    const s = item.summary;
    const base = `/api/items/${enc(s.source)}/${enc(s.item)}/reviews`;
    document.querySelectorAll(decor.selector).forEach((e) => {
      const id = (e.getAttribute(decor.idAttribute) || "").replace(decor.idPrefix, "");
      if (!id) return;
      const current = item.reviewMarks[id];
      const bar = marks(
        current,
        (mark) => api("PUT", `${base}/${enc(id)}/mark`, { mark }),
        () => api("DELETE", `${base}/${enc(id)}/mark`),
        "Отзыв",
      );
      if (current) e.classList.add("r4m-marked");
      e.appendChild(bar);
    });
  }

  function pictures(item) {
    const selector = view.decor.pictures;
    if (!selector || !item.pictures.length) return;
    const s = item.summary;
    const byFile = new Map(item.pictures.map((p) => [fileOf(p.url), p]));
    const done = new Set();
    document.querySelectorAll(selector).forEach((img) => {
      const link = img.closest("a");
      const candidates = [img.getAttribute("src"), img.getAttribute("data-src"), img.getAttribute("data-url"), link && link.getAttribute("href")];
      const picture = candidates.map((u) => u && byFile.get(fileOf(u))).find((p) => p);
      if (!picture || done.has(picture.position)) return;
      done.add(picture.position);
      const base = `/api/items/${enc(s.source)}/${enc(s.item)}/pictures/${picture.position}/mark`;
      const bar = marks(picture.mark, (mark) => api("PUT", base, { mark }), () => api("DELETE", base), picture.position === 0 ? "Обложка" : "Картинка");
      bar.classList.add("r4m-picture-marks");
      (link || img).insertAdjacentElement("afterend", bar);
    });
  }

  // --- Cards of a list ---

  /** The prediction and the grade on every card not yet marked; [again] — all of them anew. */
  async function cards(again) {
    const decor = view && view.decor.cards;
    if (!decor) return;
    if (again) document.querySelectorAll("r4m-badge").forEach((b) => b.remove());
    const wanted = [];
    document.querySelectorAll(decor.selector).forEach((card) => {
      if (card.querySelector("r4m-badge") || card.closest("[data-r4m]")) return;
      const link = card.querySelector(decor.link);
      if (link && link.getAttribute("href")) wanted.push({ card, link });
    });
    if (!wanted.length) return;
    let known = [];
    try {
      known = await api("POST", "/api/pages/cards", { url: location.href, links: wanted.map((w) => w.link.getAttribute("href")) });
    } catch (e) {
      return;
    }
    const byLink = new Map(known.map((k) => [k.link, k]));
    wanted.forEach(({ card, link }) => {
      const k = byLink.get(link.getAttribute("href"));
      if (!k || card.querySelector("r4m-badge")) return;
      const badge = el("r4m-badge");
      if (k.prediction !== undefined && k.prediction !== null) {
        badge.appendChild(el("r4m-score", scoreClass(k.prediction), k.prediction.toFixed(1)));
      }
      if (k.grade) badge.appendChild(el("r4m-grade-mark", null, `★${k.grade}`));
      if (!badge.childNodes.length) return;
      badge.title = "recommend4me: прогноз из 10" + (k.grade ? ", ★ — ваша оценка" : "");
      link.insertAdjacentElement("beforebegin", badge);
    });
  }

  // Lists that load more cards as the user scrolls
  new MutationObserver((mutations) => {
    if (!view || !view.decor.cards) return;
    const theirs = mutations.some((m) => [...m.addedNodes].some((n) => n.nodeType === 1 && !n.hasAttribute("data-r4m")));
    if (!theirs) return;
    clearTimeout(cardsTimer);
    cardsTimer = setTimeout(() => cards(false), 800);
  }).observe(document.body, { childList: true, subtree: true });

  // A page sent again as it grows: the work is read anew once it is first saved, and then seldom
  browser.runtime.onMessage.addListener((m) => {
    if (m.type !== "captured" || m.url !== location.href) return;
    if (!view || !view.item || Date.now() - loadedAt > RELOAD_AFTER) load();
  });

  window.__recommend4mePage = { load };
  load();
})();
