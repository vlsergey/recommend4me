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
- **Right page:** every value of the work, as rows, each with its chance and who gives it.

Rows, not chips: the chances stand in a line to the eye. They flow by CSS `columns: 15rem`, so a wider
screen has more columns and nothing is measured. A long name wraps, never cuts.

Marking is by the pointer and by touch. There is no keyboard marking and no walk from value to value: a
work takes hours and days to read, and seconds saved on marking it are worth nothing (the owner,
2026-10-30). The screen is judged by how clear it is, not by how fast it can be pressed through.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ✕ ‹ 12/340 ›  ▓▓▓ Bonds of Blood ✎            [AO3] ficbook↗                              ⋯  │
│               ▓▓▓ A. Writer · in progress · 412k words · ≈ 2.7M chars · 34 chapters          │
│ Interest [Yes][No] 72%   Grade [1][2][3][4][5]   ▲ 6.8 predicted                             │
├───────────────────────────────────┬──────────────────────────────────────────────────────────┤
│ Work Chapters 34 Reviews 412      │ 12 to answer · 3 offers                                  │
│ Pictures 3 Why Sites 2            │                                                          │
│                                   │ AGE RATING   AO3: PG-13, R                               │
│ Harry, in his sixth year, learns  │  G 2  [PG-13 31]  ( R  by tags 61 )  NC-17 6             │
│ that the blood wards were never   │                                                          │
│ meant to protect him...           │ CHARACTERS   AO3: Harry/Hermione, Snape    ✓all … + No other │
│                                   │  Harry Potter  site 97 ✓✕ │ ┆Sirius Black┆ 38 ✓✕         │
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

Below 56rem the pages are one at a time, under two labelled buttons (Answers 12 · Work). The verdict folds to one line and opens by a tap, so that a missed tap under the last ✕
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

A row's menu (⋯ on hover or touch): evidence; the value's page; ignore the tag; works
to confirm by this value; of a character, move between main and secondary.

`+` finds by name: every word in any name, "ё" as "е", a Q-number or a pasted Wikidata address finds the
entry exactly; a typed value warns it is new, or that a new tag is made of an entry, with the entry's link.
Under universes it also searches Wikidata and adds a universe to the dictionary from here. Under pairings
it makes a pair of two characters, OMC and OFC among them.

Kind shows its four kinds, each with its chance; choosing one answers no to the others. The universe block
is gone while the kind has no universe.

## 3. An answer

Rows come as the server orders them and do not move while the work is open.

- **What counts:** the heading counts the work's and the site's values, as "✓ all" does, with the offers apart
  ("12 to answer · 3 offers"). The server refills offers (at least three in a facet), so a count of them would
  never fall. Offers that come later stand after a quiet divider.
- **An answer is as `AGENTS.md` says:** only written, shown once kept, read anew after 2 s of quiet.
- **A grade moves on,** as today; an answer never does.
- **Related works** open over the open one, with the way back.

The keys of the window stay as they are today and none is added: ← → walk the works, `1`..`5` grade, `Esc`
closes.

## 4. What is read, and when

As today: the work is read when it opens and 2 s after answers. Waiting for one answer the user needs (Why of a
graded work, 202) is a fixed wait of that view. By text keeps its `RefreshSelect`; its "chapters still being
encoded" waits while the view is open. Nothing else reads by itself. Sites reads `WorkSite` alone, carrying
numbers and counts, rather than a `getItem` per item.

## 5. The left page

- **Work:** the description; notes and spoilers folded; long texts cut until pressed; facts by role; numbers,
  a share of likes among views; signals; a text or a number the source declares and the item lacks can be
  given; series and authors with grades and predictions; of a search, the match and Fits / Does not.
- **Chapters:** their line (dates, how many kept, how many raise or lower), title, date, influence, the text in
  place.
- **Reviews:** stars, author, date; the order the server gives (strongest, or the newest without a model), its
  30 of the total; + −, influence; a likeness with "alike" / "similar, but not in that sense", undo in a toast.
- **Pictures:** the grid, the cover named, + −, influence, not analysed or failed, the original on a press.
- **Why:** the parts by what they move, each open to its features, the absent crossed out; of a graded work
  by the model that has not seen it. A feature that is a value leads to its row. Under it, the history of
  grades of any work with dates and sites, an old grade to take back.
- **Sites:** a table, site by site: title, authors, about, numbers, counts, `≠` by plain comparison skipping an
  absent value; open, refresh, not this work. What each site says stands apart from the user's correction,
  with ↺. Under it the search for the same work on another site, prefilled with the title and run at once; what
  the search of duplicates found for this work, answered; the works said to be related, taken back.
  Taking an item out moves the address to a remaining item when the address named it.
- **Evidence** opens from the right and stays on its value, named in its heading: the passages,
  and "why it is listed": the universe, the classes, leaving out a class or an entry, the Wikidata page. A tag has
  no passages: it says so and offers By text.

A failed section says so in its place; the rest stands. The hints stay as tooltips: what the points mean, what
"No other" does for the model, what taking an item out keeps. The address stays `#item=<source>/<item>`: the
extension writes it. Press outside no longer closes: there is no outside.

**By text** is answering, so it is on the right, in place of the facets, the text to read staying on the left. A row is
a tag, its paragraph, ✓ / not enough / ✕ / not in the work / ignore. The list comes calmly, as `AGENTS.md` says.

## 6. The verdict

The grade words stand on the buttons; on a phone, that of the chosen one. The prediction stands beside them as a
number, **not as a pin on the grade line:** a pin on the very control the model learns from would anchor the
grade, and a work just graded has no held-out estimate. After a grade: "no estimate yet", then the held-out
number, drawn as such. A grade of an earlier version shows as an outline with its version, and "new version".
Interest implied by a grade reads "by your grade", and pressing it makes it the user's word.

## 7. What the server needs

1. Facets with no values among those of a work's window: `empty = true` for the editable facets.
2. `WorkSite` carries the raw values beside the corrected ones, numbers and counts.
3. `ItemDetails.relations`: the word on the work.
4. `GET /items/{source}/{item}/duplicates`.
5. Corrected texts of `removeItem`, `settleSuggestions`, `getSuggestions` in the API: the first removes the item
   alone, the second never waits.

## 8. Open for the owner

1. **The extension.** `AGENTS.md` asks the same behaviour in both. Proposed: its panel takes the rows; chips stay on
   the site's own elements.
2. **By text** leaves "a window of its own", and the table of `AGENTS.md` changes.

## 9. Order of the work

1. What the server needs.
2. The new screen beside the old, behind a choice, the old one kept till everything of sections 2 to 6 stands.
3. The extension, by the decision above.
4. The old window removed.
