/* The options page: the address of the application, the switch. */

(async () => {
  const settings = await loadSettings();
  document.getElementById("server").value = settings.server;
  document.getElementById("enabled").checked = settings.enabled;
})();

document.getElementById("save").addEventListener("click", async () => {
  const server = document.getElementById("server").value.trim() || DEFAULT_SERVER;
  await saveSettings({ server, enabled: document.getElementById("enabled").checked });
  await browser.runtime.sendMessage({ type: "patterns-changed" });
  document.getElementById("saved").textContent = "Сохранено";
});
