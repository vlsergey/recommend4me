# The Firefox extension

The browser tracking mode of recommend4me: every page you open whose address one of the
application's sources wants — a work on author.today, a fanfic on ficbook.net, a thread on f95zone
— is sent to the application as you see it, after the page's scripts have filled it in. The
application tells the extension which addresses it wants (`GET /api/capture/patterns`); no other
page leaves the browser.

A page is sent when it has loaded and again when its content grows while you are on it (a reader
loads the text of a chapter after the page itself), at most once every few seconds and not after
a minute. The toolbar badge says how many works the page gave (`✓` — none new, `!` — the
application did not answer). The popup shows whether the application answers, the last pages sent,
a switch, and a button to send the open page whatever the patterns say.

## Installing

A stock Firefox keeps only signed extensions, so there are two ways in:

**A temporary add-on** — on any Firefox. Download `recommend4me-firefox.zip` from the application
(`http://127.0.0.1:8095/extension/recommend4me-firefox.zip`, or the link in its "Слежение в
браузере" dialog), open `about:debugging#/runtime/this-firefox`, press "Load Temporary Add-on…"
and choose the zip (or `extension/manifest.json` of this folder). It lasts until Firefox is closed.

**A permanent install** — on Firefox Developer Edition, Nightly or ESR only: set
`xpinstall.signatures.required` to `false` in `about:config`, then install the zip from
`about:addons` → ⚙ → "Install Add-on From File…".

The address of the application (by default `http://127.0.0.1:8095`) is set on the extension's
options page.

## Development

```bash
npm install
```

```bash
npm run lint
```

`npm run dev` starts a Firefox with the extension loaded and reloads it on every change.
