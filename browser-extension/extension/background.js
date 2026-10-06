/*
 * THE BROWSER TRACKING MODE: every page the user opens whose address one of the application's
 * sources wants is sent to the application — the DOM as the user sees it, after the scripts of the
 * page have filled it (a reader loads the text of a chapter after the page itself).
 *
 * The application says which addresses it wants (GET /api/capture/patterns); nothing else leaves
 * the browser. A page is sent when it has loaded, and again when its content grows while the user
 * is on it, at most once every few seconds and not after a minute.
 */

/** The wanted addresses, as regular expressions, and when they were asked for. */
let patterns = [];
let patternsAt = 0;
const PATTERNS_TTL = 10 * 60 * 1000;

/** What happened to the last pages, for the popup: newest first. */
const recent = [];
const RECENT = 20;

async function wanted(url) {
  const { server, enabled } = await loadSettings();
  if (!enabled) return false;
  if (Date.now() - patternsAt > PATTERNS_TTL) {
    try {
      const response = await fetch(`${server}/api/capture/patterns`);
      if (response.ok) {
        patterns = (await response.json()).flatMap((p) => p.patterns.map((re) => new RegExp(`^(?:${re})$`)));
        patternsAt = Date.now();
      }
    } catch (e) {
      // The application is not running: nothing is wanted until it is
      patterns = [];
      patternsAt = Date.now() - PATTERNS_TTL + 30 * 1000;
    }
  }
  return patterns.some((re) => re.test(url));
}

/** The script put into a wanted page: sends its DOM now and whenever it grows, for a minute. */
function watchPage() {
  if (window.__recommend4meWatching) return;
  window.__recommend4meWatching = true;
  const QUIET = 2500;
  const LIMIT = 60 * 1000;
  const started = Date.now();
  let lastLength = 0;
  let timer = null;
  const send = () => {
    const html = document.documentElement.outerHTML;
    // Grown by less than a hundredth: a counter ticked, nothing new to read
    if (Math.abs(html.length - lastLength) < lastLength / 100) return;
    lastLength = html.length;
    browser.runtime.sendMessage({ type: "capture", url: location.href, html });
  };
  send();
  const observer = new MutationObserver(() => {
    if (Date.now() - started > LIMIT) {
      observer.disconnect();
      return;
    }
    clearTimeout(timer);
    timer = setTimeout(send, QUIET);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
}

async function inject(tabId, url) {
  if (!(await wanted(url))) return;
  try {
    await browser.scripting.executeScript({ target: { tabId }, func: watchPage });
  } catch (e) {
    console.warn("recommend4me: cannot watch", url, e);
  }
}

browser.webNavigation.onCompleted.addListener((details) => {
  if (details.frameId === 0) inject(details.tabId, details.url);
});

// A site that changes its address without loading a page (a reader turning to the next chapter)
browser.webNavigation.onHistoryStateUpdated.addListener(async (details) => {
  if (details.frameId !== 0) return;
  try {
    await browser.scripting.executeScript({
      target: { tabId: details.tabId },
      func: () => {
        window.__recommend4meWatching = false;
      },
    });
  } catch (e) {
    // The page is gone
  }
  inject(details.tabId, details.url);
});

async function capture(url, html, tabId) {
  const { server } = await loadSettings();
  const entry = { url, at: Date.now(), items: [], error: null };
  try {
    const response = await fetch(`${server}/api/capture`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, html }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const result = await response.json();
    entry.items = result.items;
    entry.source = result.source;
    if (tabId !== undefined) {
      await browser.action.setBadgeBackgroundColor({ color: "#2e7d32", tabId });
      await browser.action.setBadgeText({ text: result.items.length ? String(result.items.length) : "✓", tabId });
    }
  } catch (e) {
    entry.error = String(e.message || e);
    if (tabId !== undefined) {
      await browser.action.setBadgeBackgroundColor({ color: "#c62828", tabId });
      await browser.action.setBadgeText({ text: "!", tabId });
    }
  }
  recent.unshift(entry);
  recent.length = Math.min(recent.length, RECENT);
  return entry;
}

browser.runtime.onMessage.addListener((message, sender) => {
  if (message.type === "capture") return capture(message.url, message.html, sender.tab && sender.tab.id);
  if (message.type === "recent") return Promise.resolve(recent);
  if (message.type === "status") return status();
  if (message.type === "send-now") return sendNow(message.tabId);
  if (message.type === "patterns-changed") {
    patternsAt = 0;
    return Promise.resolve(true);
  }
  return undefined;
});

/** Whether the application answers, and what it wants. */
async function status() {
  const { server, enabled } = await loadSettings();
  try {
    const response = await fetch(`${server}/api/capture/patterns`);
    if (!response.ok) return { server, enabled, ok: false, error: `HTTP ${response.status}` };
    const sources = await response.json();
    return { server, enabled, ok: true, sources: sources.map((s) => s.source) };
  } catch (e) {
    return { server, enabled, ok: false, error: String(e.message || e) };
  }
}

/** The page of a tab sent now, by the popup's button, whatever the patterns say. */
async function sendNow(tabId) {
  const [{ result }] = await browser.scripting.executeScript({
    target: { tabId },
    func: () => ({ url: location.href, html: document.documentElement.outerHTML }),
  });
  return capture(result.url, result.html, tabId);
}
