/*
 * WHAT A WANTED PAGE SENDS: shared by the Firefox extension (its background script puts the
 * function into the page) and the userscript for Safari (which runs it in the page itself).
 */

/**
 * Sends the page's DOM now and whenever it grows, for a minute: browser.runtime.sendMessage with
 * {type: "capture"} — the background script of the extension, or the userscript's stand-in for it.
 * What page.js puts into the page (every element marked data-r4m) is neither sent nor counted as growth.
 */
function watchPage() {
  if (window.__recommend4meWatching) return;
  window.__recommend4meWatching = true;
  const QUIET = 2500;
  const LIMIT = 60 * 1000;
  const started = Date.now();
  let lastLength = 0;
  let timer = null;
  const ours = (node) => node.nodeType === 1 && (node.hasAttribute("data-r4m") || node.closest("[data-r4m]"));
  const send = () => {
    const copy = document.documentElement.cloneNode(true);
    copy.querySelectorAll("[data-r4m], [data-r4m-style]").forEach((e) => e.remove());
    copy.querySelectorAll(".r4m-removed, .r4m-doubted").forEach((e) => e.classList.remove("r4m-removed", "r4m-doubted"));
    const html = copy.outerHTML;
    // Grown by less than a hundredth: a counter ticked, nothing new to read
    if (Math.abs(html.length - lastLength) < lastLength / 100) return;
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
