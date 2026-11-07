/*
 * THE APPLICATION AS THE PAGE TALKS TO IT: its calls, made by the background script — the page's
 * own origin may not call it — and the words of the server's keys its answers carry.
 *
 * A part of the site as the application's interface: see page.js for how the parts share one namespace.
 */
(() => {
  window.__recommend4mePage = window.__recommend4mePage || {};
  const r4m = window.__recommend4mePage;
  if (r4m.started) return;

  const api = async (method, path, body) => {
    const answer = await browser.runtime.sendMessage({ type: "api", method, path, body });
    if (answer?.ok) {
      // Every key of the server's words the answer has can be said at once
      await need(keysIn(answer.data));
      return answer.data;
    }
    throw new Error(await problemText(answer));
  };
  /** A call as [api], whose status tells more than its data: {status, data}. */
  const apiAnswer = async (method, path, body) => {
    const answer = await browser.runtime.sendMessage({ type: "api", method, path, body });
    if (answer?.ok) {
      await need(keysIn(answer.data));
      return { status: answer.status, data: answer.data };
    }
    throw new Error(await problemText(answer));
  };
  const enc = encodeURIComponent;

  /**
   * The words of the server's keys ("<namespace>:<key>") by namespace — "standard" and the plugins'
   * — read once for the page's life: static files of the application (/i18n/<namespace>/<language>.json).
   * A namespace being read is its promise; one that could not be read is read again when next needed.
   */
  const serverWords = new Map();

  function readNamespace(namespace) {
    if (!serverWords.has(namespace)) {
      // Kept only while still wanted: the words of a language no longer spoken are dropped
      const done = (words) => {
        if (serverWords.get(namespace) !== reading) return;
        if (words) serverWords.set(namespace, words);
        else serverWords.delete(namespace);
      };
      const reading = browser.runtime.sendMessage({ type: "api", method: "GET", path: `/i18n/${enc(namespace)}/${r4mLanguage}.json` }).then(
        (answer) => done(answer?.ok && answer.data),
        () => done(null),
      );
      serverWords.set(namespace, reading);
    }
    return serverWords.get(namespace);
  }

  /** The namespace of a key, "<namespace>:<key>": what comes before its ":"; null when it is no key, or a language's ([LANGUAGE]). */
  function namespaceOf(key) {
    const match = typeof key === "string" && /^([\w-]+):[^\s:]+$/.exec(key);
    return match && match[1] !== LANGUAGE ? match[1] : null;
  }

  /** The namespace of a language by its ISO 639 code, "language:ru": named by the browser, no word file holds them. */
  const LANGUAGE = "language";

  /** A language named in the language spoken — "русский", "Russian" — its code when the browser knows it not. */
  function languageName(code) {
    try {
      const name = new Intl.DisplayNames([r4mLanguage || navigator.language], { type: "language" }).of(code);
      return name ? name.charAt(0).toLocaleUpperCase() + name.slice(1) : code;
    } catch {
      return code;
    }
  }

  /** The words of a namespace when they are read; else undefined. */
  function wordsOf(namespace) {
    const words = serverWords.get(namespace);
    return words && !(words instanceof Promise) ? words : undefined;
  }

  /** Reads the namespaces of [keys] not read yet; "standard" always. */
  async function need(keys) {
    const namespaces = new Set(["standard", ...keys.map(namespaceOf).filter(Boolean)]);
    await Promise.all([...namespaces].filter((n) => !wordsOf(n)).map(readNamespace));
  }

  /** The fields of the API that hold keys of the server's words. */
  const KEY_FIELDS = ["labelKey", "nameKey", "valueKey", "code"];

  /** Every key of the server's words in an answer of the API, however deep. */
  function keysIn(data, out = []) {
    if (Array.isArray(data)) {
      data.forEach((x) => {
        keysIn(x, out);
      });
    } else if (data && typeof data === "object") {
      Object.entries(data).forEach(([field, value]) => {
        if (KEY_FIELDS.includes(field) && typeof value === "string") out.push(value);
        else keysIn(value, out);
      });
    }
    return out;
  }

  /** The words of the server's [key] in the language spoken, filled from [params]; a key whose words are not read is shown as it is. */
  function said(key, params) {
    if (typeof key === "string" && key.startsWith(`${LANGUAGE}:`)) return languageName(key.substring(LANGUAGE.length + 1));
    const words = wordsOf(namespaceOf(key));
    const text = words ? words[key.substring(key.indexOf(":") + 1)] : undefined;
    return text !== undefined ? fill(text, params) : key;
  }

  /** The name of a value as shown: of the application's own value, by its key; of any other, as the data has it. */
  const nameOf = (v) => (v.nameKey ? said(v.nameKey) : v.name);

  /** The label of a facet as shown: by its key; a facet the application gave no label is shown by its own key. */
  const labelOf = (facet) => (facet.labelKey ? said(facet.labelKey) : facet.facet);

  /**
   * Why a call failed, worded: a refusal by the key of its words and its params — a param that is
   * itself a key of the server's words (the label of a field) said too —, the application silent,
   * or else what the log has.
   */
  async function problemText(answer) {
    if (!answer) return t("app.noAnswer");
    if (answer.unreachable) return t("app.silent");
    const problem = answer.problem;
    if (!problem?.code) return answer.error || t("app.noAnswer");
    const params = problem.params || {};
    await need([problem.code, ...Object.values(params)]);
    const worded = {};
    Object.entries(params).forEach(([name, value]) => {
      worded[name] = namespaceOf(value) ? said(value) : value;
    });
    return said(problem.code, worded);
  }

  /** The language the server's words kept are of. */
  let wordsLanguage = null;

  /**
   * Speaks the language chosen in the extension's options, asked anew every time (i18n.js given to
   * the page again starts from the browser's): another language chosen meanwhile, the server's words
   * are read in it.
   */
  async function speakChosen() {
    setLanguage(await browser.runtime.sendMessage({ type: "language" }));
    if (wordsLanguage !== r4mLanguage) {
      serverWords.clear();
      wordsLanguage = r4mLanguage;
    }
  }

  Object.assign(r4m, { api, apiAnswer, enc, said, nameOf, labelOf, speakChosen });
})();
