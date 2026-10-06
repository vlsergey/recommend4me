/*
 * The extension's settings, kept in the browser's storage: where the application listens, and
 * whether the pages are sent at all.
 */
const DEFAULT_SERVER = "http://127.0.0.1:8095";

async function loadSettings() {
  const stored = await browser.storage.local.get({ server: DEFAULT_SERVER, enabled: true });
  return { server: stored.server.replace(/\/+$/, ""), enabled: stored.enabled };
}

async function saveSettings(settings) {
  await browser.storage.local.set(settings);
}
