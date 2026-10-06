/*
 * The extension's settings, kept in the browser's storage: where the application listens, whether
 * the pages are sent at all, and whether the application's panel and marks are shown on them.
 */
const DEFAULT_SERVER = "http://127.0.0.1:8095";

async function loadSettings() {
  const stored = await browser.storage.local.get({ server: DEFAULT_SERVER, enabled: true, decorate: true });
  return { server: stored.server.replace(/\/+$/, ""), enabled: stored.enabled, decorate: stored.decorate };
}

async function saveSettings(settings) {
  await browser.storage.local.set(settings);
}
