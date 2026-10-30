# recommend4me — rules of the repository

## The marking of a work exists twice: keep both alike

The user answers on a work's values in two places with the same behaviour:

- the web interface — `frontend/src`;
- the browser extension, on the site's own page — `browser-extension/extension/page.js` (plain
  JavaScript, its words in `i18n.js`).

**Every change of how answering looks or behaves is made in both, in the same commit**, and the
commit message says so. A change only one of them can have (the extension has no screen of tags,
the web interface no site's page to mark up) is named as such in the commit message. The screen of
a work (`docs/work-screen.md`) has what a site's page has no room for — its pages, a chip's menu, a
finger's 44 px — and the extension stays compact by the owner's choice. Before
finishing such a change, go through the table below and check each row in both places.

**After a change of the extension, bump `version` in `browser-extension/extension/manifest.json`**
and tell the user to update the extension.

| What | Web interface | Extension (`page.js`) |
|---|---|---|
| A value as a chip, its look by its state | `correction/FacetCorrections.tsx` `FacetValue` | `valueChip` |
| The order of a facet's values and candidates: the server's (the work's values, then the site's only offered, then the model's offers; likeliest first, a tag above another before it), kept while the work is open | `facet/valueOrder.ts` | `stableOrder` |
| ✓ ✕ ↺ of a value; none of a tag the work has by a tag under it (`implied`) | `correction/FacetAnswers.tsx` | `answers` |
| The mark beside a value: chance, "site" (`fromSite`), "by tag" (`draftedFrom`), ↑ (`implied`) | `facet/FacetValueMarks.tsx` | `answers` |
| "✓ all" — the unanswered values, not offered, not implied | `FacetCorrections.tsx` `FacetLine` | `facetBlock` |
| "…" — more candidates; busy only while its own press is | `FacetCorrections.tsx` `MoreCandidates` (`AsyncButton`) | `moreOf` |
| A value found by its name and added | `FacetCorrections.tsx` `SearchButton` + `AddFacetValue`; characters `universe/FanficSection.tsx` `Picker` | `addField` |
| "No other …" — shown with no value too; the unconfirmed go | `universe/FanficSection.tsx` `AllNamed` | `completeButton` |
| An answer: only written; shown once kept, never before; read anew after 2 s of quiet, the work once its chances are made | `correction/useCorrections.ts`, `suggestion/useSuggestions.ts` `suggestionsLater` | `act`, `loadLater`, `settledLater` |
| A facet whose values are a scale (the age rating, `steps`): the steps in order, the chosen one with where it is from (the user's / the site's / by the tags with its chance), the model's chance under every other, a step the site lets not faded yet chosen; a step pressed is the user's answer, the user's own pressed again taken back | `correction/ScaleChoice.tsx` | `scaleBlock`, `chosenShown` |
| The suggestions of a facet | `suggestion/Suggestions.tsx` | `suggestedChip` |
| Grades and interest; interest with no mark by what a grade says of it ("by your grade"), a press making it the user's word | `item/useRateItem.ts`, `item/pieces.tsx` | `grades`, `interest` |
| The offers that came after the work was opened stand after a quiet line | `facet/valueOrder.ts` `useLaterOffer`, `correction/FacetCorrections.tsx` `LaterOffers` | `laterOffer` |
| The tags by the work's own text: a button opening a window of its own — under the facet of tags, and in the head of the screen of a work (`item/WorkScreen.tsx`; the extension: in its panel's head) — read again as often as chosen and shown calmly (another list: locked, the rows moved, free after 2 s; a row answered on fades, the rest move up), a row a tag with its paragraph — its name a link to its page, its place a link to the site's chapter — ✓ / not enough / ✕ / not in the work / the red bin of the tag (ignored, as on the screen of the tags) | `tagtext/TagsByText.tsx`, `correction/useCorrections.ts` `answerTagByText` | `byTextBlock`, `byTextRow` |
| Marks of reviews and pictures | `review/Reviews.tsx`, `picture/Pictures.tsx` | `marks`, `reviews`, `pictures` |
| A list's cards: grade, prediction, interest | `item/ItemBoard.tsx`, `item/pieces.tsx` | `cards`, `fillBadge` |

## A work is a normalized work; a site's item is what a site says of it

Every item of every site is bound to a normalized work (`work/WorkRepository.kt`), at first one of its
own; "one work" merges two. A translation and a part stay two works, related.

- **Of the normalized work, in the dictionaries:** the grade, the mark of interest, the user's word on
  values and fields. `RatingRepository`, `InterestRepository`, `CorrectionRepository` and
  `UnknownRepository` read and write them by the item, every item of a work seeing them alike: nothing
  is copied between items.
- **Of the model:** a work is one row, made of all its items (`model/WorkFeatures.kt`, `Catalogue`); the
  prediction is the work's, written to each of its items. In the list a work is one row
  (`item/ItemList.kt`); the card is of the item that says the most (`Works.fullness`).
- **Of the site's item, in its source:** its own data and texts, parts, pictures, reviews and the marks
  on them, the answers on the paragraphs of its text.
- **New data about a work** goes on the normalized work, never on an item, and travels in
  `WorkRepository.merge` and `takeOut`.

## An area that reads itself again lets the user choose how often

Every part of the web interface that reads its data again by itself has, in its heading, the choice
of how often — `components/RefreshSelect.tsx` with `lib/useRefresh.ts` (off, 1 s … 1 min). The
choice is kept in the browser's localStorage under the area's own key (`<area>.refresh`), and the
query reads `refetchInterval: every || false`. A new area that reads itself again gets one too.

## A request never waits for a computation

A request of the browser never waits for the queue of the computations, nor runs a computation:
`Workload.run` fails on the thread of a request (`workload/Requests.kt`), in the tests as on the
user's machine. A request writes what it was told, plans the work that follows (`Workload.plan`) and
answers with what there is — what the works made and kept, with a mark that more is coming (202,
`pending`), the interface asking again till it is there. A test standing for the background runs it
in `inBackground` (the thread of a Spring test is bound to a request).

## A button shows only its own work

A button is busy (spinner, disabled) only while its own press is at work — `AsyncButton` in the web
interface, `act` with its `about` mark in the extension. Never by the state of a query: a list read
anew in the background is not the button's work.
