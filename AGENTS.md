# recommend4me — rules of the repository

## The marking of a work exists twice: keep both alike

The user answers on a work's values in two places with the same behaviour:

- the web interface — `frontend/src`;
- the browser extension, on the site's own page — `browser-extension/extension/page.js` and its parts
  `page-*.js`, one per thing: the values and answers in `page-values.js`, "…" in `page-candidates.js`,
  the field to add a value in `page-add-field.js`, the sites' words in `page-site-words.js`, the facet's
  block in `page-facets.js`, the scale in `page-scale.js`, the changes in `page-changes.js`, the panel in
  `page-panel.js`, the tags by text in `page-tags-by-text.js`, reviews and pictures in `page-marks.js`,
  the cards of a list in `page-cards.js` (plain JavaScript, its words in `i18n.js`; how the parts share
  one namespace is said at the top of `page.js`).

**Every change of how answering looks or behaves is made in both, in the same commit**, and the
commit message says so. A change only one of them can have (the extension has no screen of tags,
the web interface no site's page to mark up) is named as such in the commit message. The screen of
a work (`docs/work-screen.md`) has what a site's page has no room for — its pages, a chip's menu, a
finger's 44 px, the line of fields, the kind's four buttons, a fandom bound from a field — and the
extension stays compact by the owner's choice. Before
finishing such a change, go through the table below and check each row in both places.

**After a change of the extension, bump `version` in `browser-extension/extension/manifest.json`**
and tell the user to update the extension.

| What | Web interface | Extension (`page-*.js`) |
|---|---|---|
| What every site of the work says of a field (`said`), right above the place of answering on it: a row of words per site, the silent on one line, an item never read told apart; a word by what it reached (plain, crossed out, ◌ none); a press on it opens the field's search with the name typed, each name of a pair apart in the fields of characters, added as a new name only once the user changes it. Fields reading the same words (kind and universe; main, secondary characters and pairs) share their rows once, over the group: a word reaches what it reached in any of them, a press goes to the one open field or a line under the row asks which. A facet placed on the site's own page: the other sites' rows alone | `correction/SiteWords.tsx` (`saidTogether`), `universe/FanficSection.tsx` `FieldGroup`, `FacetCorrections.tsx` `FieldBlock` | `saidBlock`, `sourcesOf`, `saidTogether`, `pressed`, `siteWord`, `pressWord` |
| A value as a chip, its look by its state | `correction/FacetCorrections.tsx` `FacetValue` | `valueChip` |
| The order of a facet's values and candidates: the server's (the work's values, then the site's only offered, then the model's offers; likeliest first, a tag above another before it), then the suggested, then those "…" showed — kept while the work is open, a suggestion or a candidate answered on staying where it stood | `facet/valueOrder.ts`, `FacetCorrections.tsx` `ValueLine` | `stableOrder`, `facetBlock` |
| ✓ ✕ ↺ of a value; none of a tag the work has by a tag under it (`implied`) | `correction/FacetAnswers.tsx` | `answers` |
| The mark beside a value: chance, "site" (`fromSite`), "by tag" (`draftedFrom`), ↑ (`implied`) | `facet/FacetValueMarks.tsx` | `answers` |
| "✓ all" — the unanswered values, not offered, not implied | `FacetCorrections.tsx` `FacetLine` | `facetBlock` |
| "…" — more candidates; busy only while its own press is; of the characters and the pairings 2 likeliest and 2 most named by the work's texts (3 times at least, with the count) a press, each once | `FacetCorrections.tsx` `ValueLine` (`AsyncButton`) | `moreOf` |
| A value found by its name and added | `FacetCorrections.tsx` `SearchButton` + `AddFacetValue`; characters `universe/Picker.tsx` `Picker` | `addField` |
| "No other …" — shown with no value too; the unconfirmed go | `universe/AllNamed.tsx` `AllNamed` | `completeButton` |
| An answer: only written; shown once kept, never before, whole — by what the application answers the answers made of the facets it touched (`AnsweredFacet`: a character moved, the other value of a facet of one, a tag above or under); read anew after 2 s of quiet, a read an answer was kept during showing nothing older than the page | `correction/useCorrections.ts` `withAnswers`, `settleLater`; `item/workRead.ts` | `act`, `facetsShown`, `loadLater`, `load` |
| A facet whose values are a scale (the age rating, `steps`): the steps in order, the chosen one with where it is from (the user's / the site's / by the tags with its chance), the model's chance under every other, a step any site lets not faded yet chosen; sites that disagree choose none, each step held marked with its sites; a step pressed is the user's answer, the user's own pressed again taken back | `correction/ScaleChoice.tsx` | `scaleBlock`, `chosenShown` |
| A work whose chances are still to come (`pending`, its texts not encoded yet): a quiet line says so, the work read again every 3 s till they are there | `item/workRead.ts` `whilePending`, `item/WorkScreen.tsx` | `load`, `panel` |
| The suggestions of a facet | `suggestion/Suggestions.tsx` | `suggestedChip` |
| Grades and interest; interest with no mark by what a grade says of it ("by your grade"), a press making it the user's word | `item/useRateItem.ts`, `item/pieces.tsx` | `grades`, `interest` |
| The offers that came after the work was opened stand after a quiet line | `facet/valueOrder.ts` `useLaterOffer`, `correction/FacetCorrections.tsx` `LaterOffers` | `laterOffer` |
| The tags by the work's own text: a button opening a window of its own — under the facet of tags, and in the head of the screen of a work (`item/WorkScreen.tsx`; the extension: in its panel's head) — read again as often as chosen and shown calmly (another list: locked, the rows moved, free after 2 s; a row answered on fades, the rest move up), a row a tag with its paragraph — its name a link to its page, its place a link to the site's chapter — ✓ / not enough / ✕ / not in the work / the red bin of the tag (ignored, as on the screen of the tags) | `tagtext/TagsByText.tsx`, `correction/useCorrections.ts` `answerTagByText` | `byTextBlock`, `byTextRow` |
| Marks of reviews and pictures | `review/Reviews.tsx`, `picture/Pictures.tsx` | `marks`, `reviews`, `pictures` |
| A list's cards: grade, prediction, interest | `item/ItemBoard.tsx`, `item/pieces.tsx` | `cards`, `fillBadge` |

## A form is designed from its scenarios

A form of marking starts from who answers, when and from what, taken from what was really done, then an
inventory of its fields, and the layout last (`docs/work-screen.md`, sections 1–3). **The source data of a
field stands right next to, above, the place of marking it:** every source, in the field, none in another
view. **What several fields read stands once, over them:** the same words are never repeated field after
field. The plan is walked on real works before it is built.

## The core asks no plugin what it is

The core never branches on which plugin, scorer, suggester or model it holds: no `if` on one's id or
kind, and no flag of a capability the core then checks. **Inversion of control: a plugin or a model
says itself what it needs, and the core does the same for every one of them.** A word given as data
goes through one path — `FittedSuggester.model` is whatever a suggester needs to score a work later, the
core keeps those bytes and scores every work by them (`FacetSuggester.scoring`), whatever is in them;
`ModelMeta.averages` tells what the average work is, and `CatalogueReader.gradedWorks` takes it. A computation that serves one engine
alone is planned by that engine: it listens to the application's events and puts its work in the queue
(`Workload.plan`). Branching on data — a null of "every work", an empty list — is not branching on a
plugin.

## A facet is scored by its model kept, with the database refused

Every suggester keeps its model (`FittedSuggester.model`) and the one way to the chances of any work is
`FacetSuggester.scoring` of that model and the work's own features: nothing scores on the task fitted
on, nothing reads the other works of the source to score one. The chain of the facets (`FacetChain`)
reads everything a facet's chances are made of before it — the works of the pass (`WorksRead`), the model,
the dictionary's word, the works' lines — and makes them inside `DatabaseRefused.during`: a connection
asked for there fails at once. A new step of a computation of the facets reads what it needs before it,
never inside it.

## A work is a normalized work; a site's item is what a site says of it

Every item of every site is bound to a normalized work (`work/WorkRepository.kt`), at first one of its
own; "one work" merges two. A translation and a part stay two works, related.

- **Of the normalized work, in the dictionaries:** the grade, the mark of interest, the user's word on
  values and fields. `RatingRepository`, `InterestRepository`, `CorrectionRepository` and
  `UnknownRepository` read and write them by the item, every item of a work seeing them alike: nothing
  is copied between items.
- **Of the model:** a work is one row, made of all its items (`model/WorkFeatures.kt`, `Catalogue`); the
  prediction is the work's, written to each of its items. In the list a work is one row
  (`item/ItemList.kt`); the card is of a finished book's item (`StandardValue.shownFirst`), else of the item that says the most — measured as the item changes and kept
  with its binding to the work (`site_work.fullness`, `Works.measure`), read in that order
  (`WorkClusters.shown`, `fullestFirst`), never worked out at a request.
- **Of the site's item, in its source:** its own data and texts, parts, pictures, reviews and the marks
  on them, the answers on the paragraphs of its text.
- **New data about a work** goes on the normalized work, never on an item, and travels in
  `WorkMembership.merge` and `takeOut`.

## The interface follows its rules: `docs/ui.md`

How the web interface and the extension behave when their data changes and when the user presses is
written in `docs/ui.md`: a list read again shown calmly (`useCalmUpdates`), a locked list's buttons
disabled, a button busy only with its own work, an answer kept and then shown, the choice of how often an
area reads itself again, layout by CSS alone. **Read it before any change of a screen**, and keep it current:
a new rule of the interface the owner gives is written there.

## A request never waits for a computation

A request of the browser never waits for the queue of the computations, nor runs a computation:
`Workload.run` fails on the thread of a request (`workload/Requests.kt`), in the tests as on the
user's machine. A request writes what it was told, plans the work that follows (`Workload.plan`) and
answers with what there is — what the works made and kept, with a mark that more is coming (202,
`pending`), the interface asking again till it is there. A test standing for the background runs it
in `inBackground` (the thread of a Spring test is bound to a request).

## The speed of a computation before its last digits

In the implementation of an algorithm, speed comes before the precision of the last digits:
numbers kept in half precision (fp16) in the memory and on the card, sums taken in another order,
the card's products against the processor's, a multiply and an add fused or not — whatever moves
only the last digits of a result is taken for speed. A test of such an implementation compares
within those digits, never bit by bit. This is of how an algorithm is computed, not of which one:
the model, its loss, what it reads and the rule it chooses by are chosen for what they give.

## The form and the rules of the code

**Kotlin is formatted by ktfmt**, kotlinlang's style, lines of 120 at most (Spotless): run
`gradlew spotlessApply` before committing Kotlin; `spotlessCheck` fails what is not formatted so.
**Its rules are detekt's default profile as it is**: a class of 600 lines at most, methods short and
simple, numbers named, no exception caught but the one meant. Both run before every module's tests.
No baseline, no rule turned off: a finding is fixed. The one exception is a boundary that must
outlive whatever fails — a background loop, a step of the start, a call of a plugin: there
`@Suppress("TooGenericExceptionCaught")`, with a comment saying why it is a boundary.

**The scripts — the web interface and the extension — are linted by Biome**, its recommended rules
(`biome.jsonc`; `npm run lint` in `frontend`, `gradlew :app:lintScripts`): a file of 600 lines at most.
Biome lints alone, it formats nothing.

**A class or a file too long is split by meaning, never by its length.** Each new one is about one thing
— an entity, a step, a purpose the reader names ("the dictionaries of the facets", "the choice of what
a work is shown with"), named after it and holding all of it: never "part 2", never a cut where the
lines ran out, never a bag of helpers. If the honest name of a piece is a kind of code rather than a
thing it is about, the cut is wrong.
