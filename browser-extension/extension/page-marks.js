/*
 * MARKS OF REVIEWS AND PICTURES: a "+ / −" under every review and picture of the work on the site's
 * page.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;
  const { api, enc, el, saving, button } = r4m;

  /** The file an address names: what a preview and its original share. */
  function fileOf(url) {
    try {
      const path = new URL(url, location.href).pathname;
      return decodeURIComponent(path.substring(path.lastIndexOf("/") + 1)).toLowerCase();
    } catch {
      return "";
    }
  }

  /** "+ / −" of a review or a picture; [set] shows the new mark (or none) before the application answers. */
  function marks(current, put, remove, what, set, on) {
    const bar = el("r4m-marks", saving.has(on) ? "r4m-saving" : null);
    const to = (mark) => (current === mark ? null : mark);
    bar.appendChild(button("+", t("mark.plus", { what }), () => (current === 1 ? remove() : put(1)), `r4m-mark${current === 1 ? " r4m-current" : ""}`, (shown) => set(shown, to(1)), on));
    bar.appendChild(button("−", t("mark.minus", { what }), () => (current === -1 ? remove() : put(-1)), `r4m-mark${current === -1 ? " r4m-current" : ""}`, (shown) => set(shown, to(-1)), on));
    return bar;
  }

  function reviews(item) {
    const decor = r4m.view.decor.reviews;
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
        t("mark.review"),
        (shown, mark) => {
          if (mark) shown.reviewMarks[id] = mark;
          else delete shown.reviewMarks[id];
        },
        `review|${id}`,
      );
      if (current) e.classList.add("r4m-marked");
      e.appendChild(bar);
    });
  }

  function pictures(item) {
    const selector = r4m.view.decor.pictures;
    if (!selector || !item.pictures.length) return;
    const s = item.summary;
    const byFile = new Map(item.pictures.map((p) => [fileOf(p.url), p]));
    const done = new Set();
    document.querySelectorAll(selector).forEach((img) => {
      const link = img.closest("a");
      const candidates = [img.getAttribute("src"), img.getAttribute("data-src"), img.getAttribute("data-url"), link?.getAttribute("href")];
      const picture = candidates.map((u) => u && byFile.get(fileOf(u))).find((p) => p);
      if (!picture || done.has(picture.position)) return;
      done.add(picture.position);
      const base = `/api/items/${enc(s.source)}/${enc(s.item)}/pictures/${picture.position}/mark`;
      const bar = marks(
        picture.mark, (mark) => api("PUT", base, { mark }), () => api("DELETE", base), t(picture.position === 0 ? "mark.cover" : "mark.picture"),
        (shown, mark) => {
          const p = shown.pictures.find((x) => x.position === picture.position);
          if (p) p.mark = mark;
        },
        `picture|${picture.position}`,
      );
      bar.classList.add("r4m-picture-marks");
      (link || img).insertAdjacentElement("afterend", bar);
    });
  }

  Object.assign(r4m, { reviews, pictures });
})();
