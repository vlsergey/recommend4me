/*
 * THE TAGS BY THE WORK'S OWN TEXT: a window over the page of the tags the paragraphs of the work's
 * chapters are closest to, each with its paragraph and the answers on the pair, read again as often
 * as the user chose and shown calmly.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, el, render, ownButton, withFailure, answeredShown, correct, percent } = r4m;

  /**
   * The tags by the work's own text, by facet, kept while the page is open: whether the block is
   * open, the list read, its failure, and the timer of the next reading while the chapters'
   * paragraphs are still being encoded.
   */
  const byText = new Map();
  /** How often the list is asked for again while the paragraphs are being encoded: a wait for what was asked, not a refresh. */
  const BY_TEXT_POLL_MS = 3000;
  /** How often the list may be read again by itself, as the web interface's RefreshSelect offers; nought — only when asked. */
  const BY_TEXT_REFRESH = [0, 1000, 2000, 5000, 10000, 30000, 60000];
  /** How often it is read again till the user chooses, kept in the extension's storage as "byTextRefresh". */
  const BY_TEXT_EVERY = 10000;
  /** A list read anew: how long its rows move, and how long the window stays locked after; as the web interface's. */
  const BY_TEXT_MOVE_MS = 1000;
  const BY_TEXT_SETTLE_MS = 1000;
  /** A row answered on: how long it fades, and how long the rows after it move up. */
  const BY_TEXT_LEAVE_MS = 500;
  const BY_TEXT_CLOSE_MS = 500;

  /** The bin of the screen of the tags (lucide's trash-2), drawn in the colour of the text it is in: red on [r4m-bin]. */
  function binIcon() {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("data-r4m", "");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("width", "14");
    svg.setAttribute("height", "14");
    svg.setAttribute("fill", "none");
    svg.setAttribute("stroke", "currentColor");
    svg.setAttribute("stroke-width", "2");
    svg.setAttribute("stroke-linecap", "round");
    svg.setAttribute("stroke-linejoin", "round");
    ["M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11v6", "M14 11v6"].forEach((d) => {
      const path = document.createElementNS(ns, "path");
      path.setAttribute("d", d);
      svg.appendChild(path);
    });
    return svg;
  }

  /**
   * The list of the tags by the text read anew, shown calmly ([takeByText]); read again while the
   * window is open — every few seconds while the paragraphs are still being encoded, else as often
   * as the user chose ([BY_TEXT_EVERY]).
   */
  async function readByText(item, facet) {
    const s = item.summary;
    const state = byText.get(facet);
    clearTimeout(state.timer);
    try {
      const fresh = await api("GET", `/api/items/${enc(s.source)}/${enc(s.item)}/tags/by-text`);
      state.error = null;
      takeByText(state, fresh);
    } catch (e) {
      state.error = e.message;
      render();
    }
    if (!state.open) return;
    const next = state.list?.pending ? BY_TEXT_POLL_MS : state.every;
    if (next) state.timer = setTimeout(() => readByText(item, facet), next);
  }

  /** A row of the list: a tag and its paragraph. */
  const byTextKey = (tag) => `${tag.key}:${tag.part}:${tag.paragraph}`;

  /** The same list: the same rows in the same order, their chances and closeness to the shown digits, the same marks of the list. */
  function sameByText(a, b) {
    if (a.pending !== b.pending || a.fitted !== b.fitted || a.tags.length !== b.tags.length) return false;
    const digits = (tag) => `${byTextKey(tag)}=${tag.chance == null ? "" : Math.round(tag.chance * 100)}/${tag.closeness.toFixed(2)}`;
    return a.tags.every((tag, i) => digits(tag) === digits(b.tags[i]));
  }

  /** Where every row of the window stands, by its key: what a row moves from. */
  function byTextTops() {
    return new Map([...document.querySelectorAll("r4m-bytext-row[data-key]")].map((e) => [e.getAttribute("data-key"), e.getBoundingClientRect().top]));
  }

  /** The rows drawn anew moved from where they stood ([before]) to where they stand, for [ms]; a new one faded in. */
  function moveByTextRows(before, ms) {
    const rows = [...document.querySelectorAll("r4m-bytext-row[data-key]")];
    rows.forEach((e) => {
      const top = before.get(e.getAttribute("data-key"));
      e.style.transition = "none";
      if (top === undefined) e.style.opacity = "0";
      else e.style.transform = `translateY(${top - e.getBoundingClientRect().top}px)`;
    });
    requestAnimationFrame(() =>
      rows.forEach((e) => {
        e.style.transition = `transform ${ms}ms ease, opacity ${ms}ms ease`;
        e.style.transform = "";
        e.style.opacity = "";
      }),
    );
  }

  /**
   * A list read, shown calmly as the web interface's useCalmUpdates (the table of the similar tags
   * alike): the same, nothing moves; the same rows in their places, a number changed, shown at once,
   * nothing locked; another, the window locked — taking no press — its rows moved to the new list for
   * [BY_TEXT_MOVE_MS] and free [BY_TEXT_SETTLE_MS] after; one read while locked waits till then.
   */
  function takeByText(state, fresh) {
    if (state.locked) {
      state.waiting = fresh;
      return;
    }
    if (!state.list) {
      state.list = fresh;
      render();
      return;
    }
    if (sameByText(state.list, fresh)) return;
    // The same rows in the same places, a number of one of them changed: nothing moves, no press goes astray — shown at once
    const inPlace = state.list.tags.length === fresh.tags.length && state.list.tags.every((tag, i) => byTextKey(tag) === byTextKey(fresh.tags[i]));
    if (inPlace) {
      state.list = fresh;
      render();
      return;
    }
    state.locked = true;
    const before = byTextTops();
    state.list = fresh;
    render();
    moveByTextRows(before, BY_TEXT_MOVE_MS);
    setTimeout(() => releaseByText(state), BY_TEXT_MOVE_MS + BY_TEXT_SETTLE_MS);
  }

  /** The rows standing: a list read meanwhile shown now, else the window free. */
  function releaseByText(state) {
    state.locked = false;
    const next = state.waiting;
    state.waiting = null;
    render();
    if (next) takeByText(state, next);
  }

  /**
   * The rows [keys] answered on leave: faded where they stand for [BY_TEXT_LEAVE_MS], the rows after
   * them moved up into their place for [BY_TEXT_CLOSE_MS], the window locked meanwhile. Begun once
   * the page is drawn after the answer kept.
   */
  function removeByText(state, keys) {
    const gone = new Set(keys);
    setTimeout(() => {
      state.locked = true;
      render();
      document.querySelectorAll("r4m-bytext-row[data-key]").forEach((e) => {
        if (!gone.has(e.getAttribute("data-key"))) return;
        e.style.transition = `opacity ${BY_TEXT_LEAVE_MS}ms ease`;
        e.style.opacity = "0";
      });
      setTimeout(() => {
        const before = byTextTops();
        if (state.list) state.list = { ...state.list, tags: state.list.tags.filter((x) => !gone.has(byTextKey(x))) };
        render();
        moveByTextRows(before, BY_TEXT_CLOSE_MS);
        setTimeout(() => releaseByText(state), BY_TEXT_CLOSE_MS);
      }, BY_TEXT_LEAVE_MS);
    }, 0);
  }

  /**
   * THE TAGS BY THE WORK'S OWN TEXT, as the web interface's TagsByTextDialog: of a facet of tags, a
   * button that opens a window over the page — read at the asking — of the tags the paragraphs of
   * the work's chapters are closest to, apart from the model's chances, each with its paragraph and
   * the answers on the pair. Drawn anew with the page, the window keeps where it was scrolled to.
   */
  function byTextBlock(item, facet) {
    if (!facet.tags || facet.editable === false) return null;
    const state = byText.get(facet.facet) || { open: false, list: null, error: null, timer: null, scroll: 0, locked: false, waiting: null, every: BY_TEXT_EVERY };
    byText.set(facet.facet, state);
    const block = el("r4m-bytext");
    const count = state.list?.tags.length ? ` (${state.list.tags.length})` : "";
    const open = el("button", "r4m-button r4m-bytext-toggle", `${t("byText.toggle")}${count}`);
    open.type = "button";
    open.title = t("byText.toggleHint");
    open.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      openByText(item, facet.facet);
    });
    block.appendChild(open);
    return block;
  }

  /** The window of the tags by the text opened — from the facet of tags or from the panel's head — and its list read. */
  async function openByText(item, facet) {
    const state = byText.get(facet) || { open: false, list: null, error: null, timer: null, scroll: 0, locked: false, waiting: null, every: BY_TEXT_EVERY };
    byText.set(facet, state);
    state.open = true;
    render();
    // How often, as the user chose it last; the list read all the same when that cannot be read
    // (a page left from before the extension was updated has no storage any more)
    try {
      const kept = (await browser.storage.local.get({ byTextRefresh: BY_TEXT_EVERY })).byTextRefresh;
      state.every = BY_TEXT_REFRESH.includes(kept) ? kept : BY_TEXT_EVERY;
    } catch (e) {
      console.warn("recommend4me: the choice of how often is not read", e);
    }
    readByText(item, facet);
  }

  /** The window of the tags by the text, over the page: closed by its ✕, by Escape or by a press beside it. */
  function byTextDialog(item, facet, state) {
    const close = () => {
      state.open = false;
      clearTimeout(state.timer);
      render();
    };
    const backdrop = el("r4m-dialog-backdrop");
    backdrop.addEventListener("click", (e) => {
      if (e.target === backdrop) close();
    });
    const dialog = Object.assign(el("r4m-dialog"), { role: "dialog" });
    dialog.setAttribute("aria-label", t("byText.title"));
    dialog.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "Escape") close();
    });
    const head = el("r4m-dialog-head");
    head.appendChild(el("span", "r4m-dialog-title", t("byText.title")));
    // How often the list is read again by itself, as the web interface's RefreshSelect
    const refresh = el("label", "r4m-refresh", t("byText.refresh"));
    const every = el("select");
    BY_TEXT_REFRESH.forEach((ms) => {
      const word = ms === 0 ? t("byText.refreshOff") : ms < 60000 ? t("byText.refreshSeconds", { value: ms / 1000 }) : t("byText.refreshMinutes", { value: ms / 60000 });
      const option = el("option", null, word);
      option.value = String(ms);
      option.selected = ms === state.every;
      every.appendChild(option);
    });
    every.addEventListener("change", () => {
      state.every = Number(every.value);
      browser.storage.local.set({ byTextRefresh: state.every });
      readByText(item, facet);
    });
    refresh.appendChild(every);
    head.appendChild(refresh);
    const x = el("button", "r4m-button", "✕");
    x.type = "button";
    x.title = t("byText.close");
    x.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      close();
    });
    head.appendChild(x);
    dialog.appendChild(head);
    // Locked while its rows move: taking no press, shown busy
    const body = el("r4m-dialog-body", state.locked ? "r4m-locked" : null);
    body.setAttribute("aria-busy", String(state.locked));
    body.addEventListener("scroll", () => (state.scroll = body.scrollTop));
    body.appendChild(el("r4m-note", null, state.locked ? `${t("byText.toggleHint")} ⟳` : t("byText.toggleHint")));
    if (state.error) body.appendChild(Object.assign(el("r4m-note", "r4m-failure", t("byText.failed", { error: state.error })), { role: "alert" }));
    // Not read yet: said so, never an empty window
    else if (!state.list) body.appendChild(el("r4m-note", null, t("byText.loading")));
    const list = state.list;
    if (list) {
      if (list.pending) body.appendChild(el("r4m-note", null, t("byText.pending")));
      if (!list.pending && !list.tags.length) body.appendChild(el("r4m-note", null, t("byText.none")));
      if (list.tags.length && !list.fitted) body.appendChild(el("r4m-note", null, t("byText.notFitted")));
      list.tags.forEach((tag) => {
        body.appendChild(byTextRow(item, facet, tag, state));
      });
    }
    dialog.appendChild(body);
    backdrop.appendChild(dialog);
    // Where it was scrolled to, once it is on the page
    requestAnimationFrame(() => (body.scrollTop = state.scroll));
    return backdrop;
  }

  /**
   * A tag by the text: its name — a link to its page — its chance or closeness, its paragraph six
   * lines till pressed, where it stands — a link to the site's page of it — and the answers: the
   * three on the pair, the tag not in the work, the tag ignored. An answer kept, the row leaves ([removeByText]).
   */
  function byTextRow(item, facet, tag, state) {
    const row = el("r4m-bytext-row");
    row.setAttribute("data-key", byTextKey(tag));
    const head = el("r4m-bytext-head");
    // The name a link to the tag's page in the application
    const name = el("a", "r4m-link r4m-bytext-name", tag.name);
    name.href = `${r4m.server}/tags/${tag.key.split("/").map(enc).join("/")}`;
    name.target = "_blank";
    name.rel = "noopener";
    head.appendChild(name);
    head.appendChild(el("r4m-chance", null, tag.chance !== undefined && tag.chance !== null ? percent(tag.chance) : t("byText.closeness", { value: tag.closeness.toFixed(2) })));
    const s = item.summary;
    const on = `bytext|${tag.key}|${tag.part}|${tag.paragraph}`;
    // Kept, the pair is offered no more and its row leaves; of a yes the tag is the work's, shown as any answer is
    const shown = (verdict) => (work) => {
      if (verdict === "YES") answeredShown(facet, { key: tag.key, name: tag.name, offered: true }, true)(work);
      removeByText(state, [byTextKey(tag)]);
    };
    /** Every row of the tag: what leaves once the tag is said not the work's, or ignored. */
    const ofTag = () => (state.list ? state.list.tags.filter((x) => x.key === tag.key).map(byTextKey) : []);
    const answers = el("r4m-bytext-answers");
    [
      ["YES", "✓ ", "byText.yes", "byText.yesHint"],
      ["ENOUGH_NOT", "− ", "byText.enoughNot", "byText.enoughNotHint"],
      ["WRONG", "✕ ", "byText.wrong", "byText.wrongHint"],
    ].forEach(([verdict, mark, word, hint]) => {
      const change = () => api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/tags/by-text/answer`, { key: tag.key, part: tag.part, paragraph: tag.paragraph, verdict });
      answers.appendChild(ownButton(mark + t(word), t(hint), change, "r4m-button", shown(verdict), on));
    });
    // The work has not the tag: the user's word on the facet, as ✕ of a value of it — every row of the tag leaves
    answers.appendChild(
      ownButton(`⊘ ${t("byText.absent")}`, t("byText.absentHint"), () => correct(item, facet, { key: tag.key }, false), "r4m-button", (work) => {
        answeredShown(facet, { key: tag.key, name: tag.name, offered: true }, false)(work);
        removeByText(state, ofTag());
      }, on),
    );
    // The tag ignored altogether, as the bin of the screen of the tags does: offered nowhere, read by no model — every row of it leaves
    const bin = ownButton("", t("byText.ignoreHint"), () => api("PUT", "/api/tags/ignored", { tag: tag.key, ignored: true }), "r4m-button r4m-bin", () => removeByText(state, ofTag()), on);
    bin.setAttribute("aria-label", t("byText.ignore"));
    bin.appendChild(binIcon());
    answers.appendChild(bin);
    // The window locked while its rows move: its buttons shown disabled, the reason no press is taken
    if (state.locked) {
      answers.querySelectorAll("button").forEach((b) => {
        b.disabled = true;
      });
    }
    head.appendChild(answers);
    row.appendChild(withFailure(head, on));
    // Text to select and copy: unfolded or folded by a press that selects nothing
    const quote = el("div", "r4m-bytext-quote", tag.quote);
    quote.title = t("byText.unfold");
    quote.addEventListener("click", (e) => {
      e.stopPropagation();
      if (window.getSelection().toString()) return;
      quote.title = t(quote.classList.toggle("r4m-whole") ? "byText.fold" : "byText.unfold");
    });
    row.appendChild(quote);
    // Where the paragraph is read on the site: its chapter, scrolled to it
    const where = el("a", "r4m-link r4m-bytext-where", tag.partTitle ? t("byText.partParagraph", { part: tag.partTitle, n: tag.paragraph + 1 }) : t("byText.paragraph", { n: tag.paragraph + 1 }));
    where.href = tag.url;
    where.target = "_blank";
    where.rel = "noopener";
    row.appendChild(where);
    return row;
  }

  /** The windows of the tags by the text that are open, over the page, wherever they were opened from. */
  function drawByTextDialogs(item) {
    byText.forEach((state, facet) => {
      if (state.open) document.body.appendChild(byTextDialog(item, facet, state));
    });
  }

  Object.assign(r4m, { byTextBlock, openByText, drawByTextDialogs });
})();
