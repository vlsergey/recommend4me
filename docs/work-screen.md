# The screen of a work: the design

A design made from nothing, with no look at the window of a work it is to replace, then reviewed on its
own and compared with that window function by function (339 functions: 157 kept, 106 changed, 51 lost,
25 new). This is the result: every blocker of the review and every lost function has an answer below.
Nothing here is built yet.

## 1. The idea

**Left to read, right to answer, head to decide.**

- **Head:** what the work is, and the verdict: interest and grade, the model's prediction beside them.
- **Left page:** one view at a time of what there is to read: the work, chapters, reviews, pictures, why,
  sites. It never covers the answers.
- **Right page:** every value of the work, as rows, each with its chance and who gives it. One cursor
  walks them.

Rows, not chips: the chances stand in a line to the eye, and the reading order is the order of the walk.
They flow by CSS `columns: 15rem`, so a wider screen has more columns and nothing is measured. A long
name wraps, never cuts.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ✕ ‹ 12/340 ›  ▓▓▓ Bonds of Blood ✎            [AO3] ficbook↗                              ⋯  │
│               ▓▓▓ A. Writer · in progress · 412k words · ≈ 2.7M chars · 34 chapters          │
│ Interest [Yes][No] 72%   Grade [1][2][3][4][5]   ▲ 6.8 predicted                             │
├───────────────────────────────────┬──────────────────────────────────────────────────────────┤
│ Work Chapters 34 Reviews 412      │ 12 to answer · 3 offers                     ✓ Angst · Z  │
│ Pictures 3 Why Sites 2            │                                                          │
│                                   │ AGE RATING   AO3: PG-13, R                               │
│ Harry, in his sixth year, learns  │  G 2  [PG-13 31]  ( R  by tags 61 )  NC-17 6             │
│ that the blood wards were never   │                                                          │
│ meant to protect him...           │ CHARACTERS   AO3: Harry/Hermione, Snape    ✓all … + No other │
│                                   │ ▸Harry Potter  site 97 ✓✕ │ ┆Sirius Black┆ 38 ✓✕         │
│ ▸ Author's notes                  │  Hermione G.   site 95 ✓✕ │ ┆Remus Lupin┆  21 ✓✕         │
│ ▸ Spoilers                        │                                                          │
│                                   │ TAGS         AO3: Angst, Slow burn   ✓all … + By text    │
│ FACTS                             │  Angst   site 92 ✓✕ │ Hurt/comfort 73 ✓✕                 │
│ ALSO BY A. WRITER                 │  ~Magic~        ↺   │ Politics ↑                         │
│ The Long Road   5                 │                                                          │
└───────────────────────────────────┴──────────────────────────────────────────────────────────┘
```

The site marks in the head switch **the whole screen** to that site: the left page, the site's line above
every facet, the steps a site allows, its series and authors. The values and answers on the right stay,
being the work's. The mark of the site says the most and comes first; each carries a link to its page.

Below 56rem the pages are one at a time, under two labelled buttons (Answers 12 · Work), the keys acting
on the page shown. The verdict folds to one line and opens by a tap, so that a missed tap under the last ✕
neither grades nor overwrites. A landscape iPad has both pages. A tap on a name opens a sheet: the
evidence, the link to its page, the row's menu. Targets are 44 px. The head folds to two lines on a short
screen, by a container query on height. Below 1366×768 about half of a work's rows are in sight.

## 2. A value's row

| State | Row | Controls |
|---|---|---|
| The work's or the site's, unanswered | Name, "site", chance | ✓ ✕ |
| Model's offer | Dashed, muted | ✓ ✕ |
| The site's, the model doubts | Amber edge, never hidden | ✓ ✕ |
| The site's, the model thinks it absent | Dashed amber | ✓ ✕ |
| Drafted | "by <value of the site>" in place of the chance | ✓ ✕ |
| Implied by a tag under it | Muted, ↑ | none |
| Answered yes | ✓ green | ↺ |
| Answered no | Crossed out | ↺ |
| Press at work | A spinner in place of the pressed control | neighbours stay usable |
| Refused or failed | A warning in place of the control, a toast naming the reason | ✓ ✕ |

A row's menu (Enter on the cursor, ⋯ on hover or touch): evidence; the value's page; ignore the tag; works
to confirm by this value; of a character, move between main and secondary.

`+` finds by name: every word in any name, "ё" as "е", a Q-number or a pasted Wikidata address finds the
entry exactly; a typed value warns it is new, or that a new tag is made of an entry, with the entry's link.
Under universes it also searches Wikidata and adds a universe to the dictionary from here. Under pairings
it makes a pair of two characters, OMC and OFC among them.

Kind shows its four kinds, each with its chance; choosing one answers no to the others. The universe block
is gone while the kind has no universe.

## 3. The walk

Rows come as the server orders them and do not move while the work is open.

- **What counts:** the work's and the site's values, as "✓ all" counts them. The offers **present when the
  work opened** are walked after them. The server refills offers (at least three in a facet): later ones
  stand after a quiet divider, and the walk does not enter them unless asked. So "12 to answer" falls to
  nothing and the work can be finished.
- **The cursor is state** (`data-cursor` on a row, its own look), not DOM focus: a click or a selection on
  the left does not lose it. Keys go to it unless an input or a dialog has the focus, ignored with Ctrl, Alt
  or Meta.
- **An answer says what it changed:** the reply names the rows it implied, negated or took, and the walk
  uses this at once. Otherwise a ✕ on a parent followed by ✓ on its child writes a contradiction.
- **The walk never enters the next work by an answer.** All answered, the heading says so. A grade moves on,
  as today: in the list of the unrated the graded work leaves.
- **The queue:** the ordered keys of the list are taken once when the run starts and kept with it in
  `history.state`. A grade removes a work from the list, so paging by offset would skip one a grade.
- **Related works** open over the open one, with the way back; the run goes on.

## 4. Keys

By `KeyboardEvent.code`, so they stand on any layout. Auto-repeat counts for J, K, ↑, ↓ alone.

| Key | Action |
|---|---|
| `J` `K`, ↓ ↑ | Next, previous row |
| `F` | ✓, then the next counted row |
| `D` | ✕, then the next counted row |
| `U` | ↺ of the row |
| `Z` | Undo the last write of any kind: answer, ✓ all, No other, grade, interest; of another work it names the work |
| `Tab` `Shift+Tab` | Next, previous facet, wrapping |
| `Shift+A` | ✓ all of the facet |
| `Shift+X` | No other |
| `M` | … more candidates |
| `/` | + add by name |
| `Enter` | The row's menu |
| `B` | By text |
| `E` | Evidence of the row |
| `Space` `Shift+Space` | Scroll the left page |
| `` ` `` `Shift+`` ` `` | Next, previous left view |
| ← → | Previous, next work, as today: never an answer |
| `1`..`5` | Grade and move on. On a graded work it does nothing but say so |
| `Shift+1`..`5` | Replace the grade, stay |
| `I` `Shift+I` | Interesting, not; again takes it back |
| `?` | The keys |
| `Esc` | Out of an input; then the view opened from the right; then the screen, never on auto-repeat |

On a scale J and K walk the steps, `F` chooses and moves to the next facet, `D` does nothing, `U` takes back.
In a facet of one value `F` moves to the next facet. The last write stands in the heading ("✓ Angst · Z"):
without it no undo helps, as a slip goes unseen. A grade and a mark of interest also give a toast with undo.
`Z` keeps what was there, so it restores a replaced grade and an earlier answer of a facet of one value.
One work's writes are sent in order.

## 5. What is read, and when

| When | What |
|---|---|
| A work opens, or a site is chosen | **A new marking read:** summary, facets with the empty ones, texts, numbers. About 25 KB against today's 210 to 380 KB and 3 s; the head comes at once from the list's row |
| A left view opens | Its data alone: explanation, related, reviews, parts, pictures |
| After an answer, 2 s of quiet | The marking read again; numbers change in place, no row moves |
| Why is open, after an answer | Held-out read anew |
| Sites opens | `WorkSite` alone, carrying numbers and counts, no `getItem` per item |

While a work opens, answers are ignored; ← → pass and cancel the read. Waiting for one answer the user
needs (Why of a graded work, 202) is a fixed wait of that view, as today. By text keeps its `RefreshSelect`;
its "chapters still being encoded" waits while the view is open. Nothing else reads by itself. No read-ahead:
it would be a cache of stored data, and none is asked for.

## 6. The left page

- **Work:** the description; notes and spoilers folded; long texts cut until pressed; facts by role; numbers,
  a share of likes among views; signals; a text or a number the source declares and the item lacks can be
  given; series and authors with grades and predictions; of a search, the match and Fits / Does not.
- **Chapters:** their line (dates, how many kept, how many raise or lower), title, date, influence, the text in
  place.
- **Reviews:** stars, author, date; the order the server gives (strongest, or the newest without a model), its
  30 of the total; + −, influence; a likeness with "alike" / "similar, but not in that sense", undo in a toast.
- **Pictures:** the grid, the cover named, + −, influence, not analysed or failed, the original on a press.
- **Why:** the parts by what they move, each open to its features, the absent crossed out; of a graded work
  by the model that has not seen it. A feature that is a value moves the cursor there. Under it, the history of
  grades of any work with dates and sites, an old grade to take back.
- **Sites:** a table, site by site: title, authors, about, numbers, counts, `≠` by plain comparison skipping an
  absent value; open, refresh, not this work. What each site says stands apart from the user's correction,
  with ↺. Under it the search for the same work on another site, prefilled with the title and run at once; what
  the search of duplicates found for this work, answered; the works said to be related, taken back.
  Taking an item out moves the address to a remaining item when the address named it.
- **Evidence** opens from the right and stays on its value, named in its heading, until `E` again: the passages,
  and "why it is listed": the universe, the classes, leaving out a class or an entry, the Wikidata page. A tag has
  no passages: it says so and offers By text.

A failed section says so in its place; the rest stands.

**By text** is answering, so it is on the right: a mode with a banner saying the keys act on it. A row is a tag, its
paragraph, ✓ / not enough / ✕ / not in the work / ignore (`F` `G` `D` `N` `Delete`). The list comes calmly, as
`AGENTS.md` says.

## 7. The verdict

The grade words stand on the buttons; on a phone, that of the chosen one. The prediction stands beside them as a
number, **not as a pin on the grade line:** a pin on the very control the model learns from would anchor the
grade, and a work just graded has no held-out estimate. After a grade: "no estimate yet", then the held-out
number, drawn as such. A grade of an earlier version shows as an outline with its version, and "new version".
Interest implied by a grade reads "by your grade", and pressing it makes it the user's word.

## 8. What the server needs

1. The marking read, with `empty = true` for the editable facets.
2. `FacetAnswer` names what the answer implied, negated and took.
3. `WorkSite` carries the raw values beside the corrected ones, numbers and counts.
4. The ordered keys of a list, for the queue.
5. `ItemDetails.relations`: the word on the work.
6. `GET /items/{source}/{item}/duplicates`.
7. Corrected texts of `removeItem`, `settleSuggestions`, `getSuggestions` in the API: the first removes the item
   alone, the second never waits.
8. `Works.fullness` chooses the opened site, so nothing else may depend on which it is.

## 9. Open for the owner

1. **The extension.** `AGENTS.md` asks the same behaviour in both. Proposed: its panel takes the rows and the same
   keys while the panel has the focus, never globally on a site's page; chips stay on the site's own elements.
2. **By text** leaves "a window of its own", and the table of `AGENTS.md` changes.
3. **The read 2 s after an answer** stays, as `AGENTS.md` says: it brings the new chances. It is not a read of a list.

## 10. Order of the work

1. The marking read, measured: the whole rhythm hangs on it.
2. The new screen beside the old, behind a choice, the old one kept till everything of section 2 to 6 stands.
3. The keys and the walk.
4. The extension, by the decision above.
5. The old window removed.
