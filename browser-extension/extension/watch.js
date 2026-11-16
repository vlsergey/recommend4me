/*
 * WHAT A WANTED PAGE SENDS: shared by the Firefox extension (its background script puts the
 * function into the page) and the userscript for Safari (which runs it in the page itself).
 */

/**
 * The page as it is sent: without what page.js put into it (every element marked data-r4m, its
 * classes on the page's own elements), and without the page's scripts and the tokens of the user's
 * session on the site — a CSRF meta, the hidden token of a form. The parsers read none of these, and
 * a sent page is kept for good, in every backup too.
 */
function pageHtml() {
  const copy = document.documentElement.cloneNode(true);
  copy.querySelectorAll("[data-r4m], [data-r4m-style]").forEach((e) => {
    e.remove();
  });
  copy
    .querySelectorAll(".r4m-removed, .r4m-doubted, .r4m-marked")
    .forEach((e) => {
      e.classList.remove("r4m-removed", "r4m-doubted", "r4m-marked");
    });
  copy
    .querySelectorAll('script, meta[name*="csrf" i], meta[name*="token" i], input[name*="csrf" i], input[name*="token" i]')
    .forEach((e) => {
      e.remove();
    });
  return copy.outerHTML;
}

/**
 * Sends the page's DOM ([pageHtml]) now and whenever it grows, for a minute: browser.runtime.sendMessage with
 * {type: "capture"} — the background script of the extension, or the userscript's stand-in for it.
 * What page.js puts into the page is not counted as growth either. A block of [late] — the selectors
 * of the blocks the site fills after the page (its source's lateBlocks) — filled sends it again
 * however little it grew.
 */
// biome-ignore lint/correctness/noUnusedVariables: called by background.js (put into the page after this file) and by userscript.js
function watchPage(late = []) {
  if (window.__recommend4meWatching) return;
  window.__recommend4meWatching = true;
  const QUIET = 2500;
  const LIMIT = 60 * 1000;
  const started = Date.now();
  let lastLength = 0;
  let timer = null;
  let filled = 0;
  const ours = (node) => node.nodeType === 1 && (node.hasAttribute("data-r4m") || node.closest("[data-r4m]"));
  const lateFilled = () =>
    late.filter((selector) => {
      const block = document.querySelector(selector);
      return block && [...block.children].some((child) => !child.hasAttribute("data-r4m"));
    }).length;
  const send = () => {
    const html = pageHtml();
    const nowFilled = lateFilled();
    // Grown by less than a hundredth and no late block filled: a counter ticked, nothing new to read
    if (nowFilled <= filled && Math.abs(html.length - lastLength) < lastLength / 100) return;
    filled = nowFilled;
    lastLength = html.length;
    browser.runtime.sendMessage({ type: "capture", url: location.href, html });
  };
  send();
  const observer = new MutationObserver((mutations) => {
    const theirs = mutations.some((m) => {
      const target = m.target.nodeType === 1 ? m.target : m.target.parentElement;
      if (target && ours(target)) return false;
      if (m.type !== "childList") return true;
      return [...m.addedNodes, ...m.removedNodes].some((n) => !(n.nodeType === 1 && n.hasAttribute("data-r4m")));
    });
    if (!theirs) return;
    if (Date.now() - started > LIMIT) {
      observer.disconnect();
      return;
    }
    clearTimeout(timer);
    timer = setTimeout(send, QUIET);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
}
