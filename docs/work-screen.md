# The screen of a work: the design

The first design started from the layout ("left to read, right to answer") and the fields were placed into it. The
owner sent it back: the source data of a field must stand beside the place of marking it, and a form is designed
from its scenarios of use. This one starts from them, and was walked on the real works (443 items, 38 works on
several sites) before it was built.

## 1. How a form is designed

1. **Who answers, when, with what in their head:** the scenarios, from what was really done, not imagined.
2. **What a person does to answer one question:** understand it, find the answer, decide, place the answer
   (Tourangeau, Rips, Rasinski; Jarrett for forms). Every field serves all four **at one place**. Most of a form's
   trouble is at "find": the answer is somewhere else on the screen.
3. **The kindred forms:** this is the reconciliation of records. Several sources say something of one work and one
   person settles what is true, field by field, seeing every source of that field and the result (a data steward's
   merge screen of master data management). It is not a questionnaire and not a reading page.
4. **An inventory of the fields:** for each its sources, what decides it, what it depends on, how often it is
   answered, what "done" means.
5. **Layout last:** one column of fields, in the order their answers depend on each other; grouped by topic; the
   rare at the end, folded.
6. **Walk every scenario on real works** before building, and again after.

## 2. The scenarios

Taken from the marks kept in the database (backup of 2026-10-30), languages left out as made by migrations.

| # | Scenario | Evidence | What it needs |
|---|---|---|---|
| A | **Marking a work fully**, most often after reading it: the head knows the truth, the form is checked field by field | 154 of 357 works have 10+ answers, and they hold 86% of all answers. The usual set is kind, universe, characters, pairings and tags together | Every field in one pass, a field's sources over it, a mark that it is finished |
| B | **Coming back** to a work: the marks of one work span over 2 h on 58 of those 154 works, 15+ min on 36 more | Times of the answers | At a look: what is done, what is left |
| C | **Grade, then mark**: of 182 graded works with answers, 79 got all of them after the grade, 68 on both sides of it, 35 before it | Times of grades and answers | The form stays after a grade |
| D | **One fix on a work seen in a list**, usually the universe or the kind: 110 works have 1–2 answers; 38 have only the universe | Answers per work; 40 works a day on 2026-10-24 and 25 | The field found at once; what the sites say at it; a site's word bound once for all its works (152 fandoms are bound so) |
| E | **Sites disagree**: a work on several sites (38 of the first 200 graded). "Паутина Света - 1": R on ficbook, NC-17 and NC-21 on author.today, G on fanfics.me; fandom on two, tags only on the third | Real works | Every site at every field at once, none by switching |
| F | **A site's word that reached nothing**: "Ринко" on the site, "Ринко Кудзаки" in the dictionary | Real works: names written by authors as they please | The word beside the field, shown as not reached; from it to the search of that field |
| G | **No site says**: author.today has no fandom and no characters | Schemas | Silence told as silence; then what the work's own texts name is the source |
| H | **Deciding whether to read**: interest, from a list | Not a marking: head and left page | The form does not get in the way; a phone opens on the work |
| I | **Correcting a title, a text, a number** | **Never done**: 0 corrections in the database | Kept, folded at the end, by the same pattern |
| J | **"The work is this and no more"** | "No other": universe 180, characters 71, pairings 72 | The closing of a field beside its sources |
| K | **"Is this really the same work?"** A site's item among the others | Authors differ on 17 of 38 works, some only by transliteration | Sites side by side, whole; the way to take one out |

## 3. The inventory of the fields

The order is the order of dependency: the kind decides whether there is a universe, the universe gives the
characters to choose from, the characters give the pairs.

| Field | Sources shown right above it | Other evidence | Depends on | Closed means |
|---|---|---|---|---|
| Kind | the fandom of each site; of author.today its tags, its genres being among them | description | | the user chose |
| Universe | the fandom of each site; a site with no fandom, its tags | | kind not of no universe | "No other" |
| Main characters | the characters and pairs of each site, as words | names in texts | universe | "No other" |
| Secondary characters | the same | names in texts | universe | "No other" |
| Pairings | the same, and the direction of each site (`Books.DIRECTION`: ficbook and fanfics.me `direction`, AO3 `category`) | pairs in texts | main characters | "No other" |
| Tags | the tags of each site | By text | | it has no closing: nothing left to answer is its end |
| Age rating | the rating of each site | model by tags | | the user chose a step |
| Languages | the language of each site | | | "No other" |
| Description language | no site can speak of it: no sources | | | the user answered |
| Title, texts, numbers (folded) | each site's own | | | never asked for |

A facet of a site that speaks of no field (status, size, form, engine, OS, censored, voiced, original author, original
language) is no source of a field: it stands with the facts on the left page.

## 4. A field

Every field of the form, the rare ones too, is one pattern:

```
ВСЕЛЕННАЯ                                        ✓ все (2)  Нет других
  ficbook ↗     Omamori Himari
  fanfics.me ↗  Omamori Himari
  author.today: не указывает
  [✓ Омамори Химари ↺]  [Наруто 3% ✓✕] …  🔍
```

- **Label** with the field's state and its tools (✓ all, No other).
- **Sources:** a row for each site that has a word on the field, in the order of `sites`: the site's mark, a link to its
  page, its words as the site writes them. Two sites saying the same stay two rows: that is a fact too (E). The sites
  with no word stand on **one line**: "author.today, fanfics.me: не указывают". A site whose item was never read (it has
  no texts, parts, pictures or reviews, only a card) says so apart: "ficbook: не прочитан", with its refresh: silence must
  not pass for "the site has none". A field no site can speak of has no line at all.
- **The texts' row,** under the sites': what the work's own text names and **no chip of the field holds**, with counts
  and ✓ ✕, and "…" for more. A candidate that is a chip carries its count on the chip. Of the tags, the button By text.
- **The place of the answer:** the chips, every suggestion among them, the search, "…". Nothing stands between the
  sources and the first chip.

The rows are repeated above each of the three fields of characters and pairs: one block over them would leave the
pairings far from their source (600 px on ficbook/1866947).

**A word of a site** is one of:

| State | Look | How it is known |
|---|---|---|
| Reached the field | plain; its title names the value | the same key (a rating, a language); a tag's binding; a fandom's binding; a draft (`store.drafts`, AO3) |
| Its value answered no | crossed out | the above, and the user's answer |
| Reached nothing | dotted underline, ◌ | a binding or a draft could have told it, and there is none |
| Nothing can tell | plain | the names authors write on ficbook and fanfics.me: no binding exists, so no ◌ would mean anything; the eye compares them |

A scale has no search: its words are not pressed, and the same key means a step.

**A press on a word** opens the field's search with the word typed; the words are not retyped (F):

- **a character's word:** the picker of that field. A word of a pair (`Юто/Агеха`, `A & B`) is split at the `/` or `&` and each
  name is its own press in the fields of characters;
- **a pair's word,** in the field of pairs: the picker of pairs with both names typed; no pairs to choose from yet: the
  composing of a pair, opened;
- **a tag's word:** the add line;
- **a fandom's word:** the universes' picker, and under it the catalogue search with the word typed: the dictionary holds
  28 universes and 2701 fandoms are unbound, so the catalogue is what finds it.

A word typed by the press is not the user's typing: "add as a new name" appears only once the user changes the line, or one
press would make a character named "Юто/Агеха".

**A fandom bound once for all its works** (D): when the work has a universe and a fandom's word reached nothing, the word
offers "всем работам фандома: <universe>"; `PUT /api/universes/fandoms`, the write of the screen of the universes. It says the other
works get it when their drafts are made anew, in the background. A bound fandom shows its universe and takes it back there.
Web interface only: the extension has no screen of bindings.

**A chip's "site"** names the sites in its title and on the finger's sheet: the sources of a long field of tags can scroll
out of sight on a small screen (over 500 px on 41 of 400 works at 1366×768).

**A scale whose sites disagree:** with several steps held and unanswered, no step is chosen; each held one is marked with its
sites, and the field stays open; so too when two sites name different steps and the work holds one. One site naming two
steps is no disagreement. On 11 of 38 works on several sites the sites' ratings have no step in common. Before, `held[0]`
was shown, another one on another site.

## 5. The form as a whole

The form is **of the work**: it never changes with the site read on the left. Every value, mark, step and "site" of it
is made of all the items of the work, the same whichever item it is read through.

```
┌ Вид ✓ · Вселенная ✓ · Гл. персонажи 3 · Второстеп. · Пары 2 · Теги 12 · Возраст 2 · Языки ✓ ┐ sticky
│ МИР                                                                                         │
│   Вид (field)   Вселенная (field)                                                           │
│ ПЕРСОНАЖИ                                                                                   │
│   Главные (field)   Второстепенные (field)   Пары (field)                                   │
│ СОДЕРЖАНИЕ                                                                                  │
│   Теги (field)   Возраст (field)   Языки (field)                                            │
│ ▸ Название, тексты, числа                                                                   │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **The line of fields** is what B and D need. A field is one of three: **closed** (✓, by the table of section 3),
  **open** (the number left to answer), **nothing left and not closed** (the name alone, muted). A press scrolls to it. One line
  that scrolls sideways on a phone, never three rows of 44 px. It replaces "12 to answer · 3 offers".
- **Kind** is its four kinds as buttons with their chances, in place of a list one must open, over its own rows. The
  universe's field is gone while the kind has none; the kind's rows stay, so "Ориджиналы" is seen when it matters.
- **Dependencies are told in place:** characters with no universe say so; the picker still takes a name.
- **Offers and suggestions** stand in their field. The block "suggested" under all fields is gone.
- **Games and other types** have the groups they have fields for.

The extension's panel is the same pattern. A facet placed on the site's own page has beside it only the rows of **the other
sites**, the page itself showing its own (E, on ficbook).

## 6. The rest of the screen

**Head:** what the work is and the verdict, as before. The ✎ of the title goes: the title is a field, at the end of the form.

**A press on a grade stays on the work** (C). The keys 1..5 go on grading and moving on. In the list of the ungraded the
graded work leaves the list, so the screen holds it: `ItemDialog` keeps the work shown apart from `items[index]`, its grade from the
answer of the press, and on leaving (← →, closing) goes on from the place it left, skipping none. A phone opens on the work if it is
ungraded (H), on the answers if graded.

**Left page: to read.** Work, chapters, reviews, pictures, why, sites, evidence as they were, with these changes:

- The choice of a site moves here, over the views: **it is the choice of what to read.** The form's read stays; the left page
  reads the chosen item apart, only when it is another.
- Work: the facts of the site's own facets that are no field's source; texts and numbers to read, the ✎ gone to the form.
- Sites: **"is this really the same work?"** (K): a table of titles, authors, the description, the counts of reviews, pictures,
  parts, ≠ where they differ; open, refresh, not this work; the search of the same work; duplicates; relations; removing the
  site's item.

**Title, texts, numbers** at the end of the form, folded, each a field of the pattern: the rows of each site, then the value, ✎, ↺.
Every text a site's schema declares (notes, dedication) and a value an item lacks can be given here, as `ItemTexts` and
`ItemNumbers` did.

**A short or narrow screen:** one page at a time, as before; the form is a column and stays one. The verdict folds.

## 7. A value's chip

| State | Chip | Controls |
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

A chip's menu (⋯ on hover or touch): evidence; the value's page; ignore the tag; works to confirm by this value; of a
character, move between main and secondary. Chips, as in the extension: the most values in the room. They wrap by CSS;
nothing is measured. A long name wraps, never cuts.

Marking is by the pointer and by touch: no keyboard marking, no walk from value to value. A work takes hours and days
to read; the screen is judged by how clear it is, not by how fast it can be pressed through.

## 8. An answer and what is read

Chips come as the server orders them and do not move while the work is open; offers that come later stand after a quiet
divider. An answer is as `AGENTS.md` says: only written, shown once kept, read anew after 2 s of quiet. Related works
open over the open one, with the way back. The keys stay: ← → walk the works, `1`..`5` grade, `Esc` closes.

The work is read when it opens and 2 s after answers. Why of a graded work waits for its one answer. The window of By
text keeps its `RefreshSelect`. Nothing else reads by itself.

## 9. The left page in detail

- **Work:** the description; notes and spoilers folded; long texts cut until pressed; facts by role; numbers, a share of
  likes among views; signals; series and authors with grades and predictions; of a search, the match and Fits / Does not.
- **Chapters:** their line, title, date, influence, the text in place.
- **Reviews:** stars, author, date; the server's order, its 30 of the total; + −, influence; a likeness, undo in a toast.
- **Pictures:** the grid, the cover named, + −, influence, not analysed or failed, the original on a press.
- **Why:** the parts by what they move, each open to its features; of a graded work by the model that has not seen it. A
  feature that is a value leads to its chip. Under it, the history of grades.
- **Sites:** as in section 6. Taking an item out moves the address to a remaining item when the address named it.
- **Evidence** opens from the right and stays on its value: the passages, and why it is listed. A tag has no passages: it
  says so and offers By text.

**By text** stays a window of its own, as `AGENTS.md` says: its rows are too many and too long for a page.

## 10. The verdict

The grade words stand on the buttons; the prediction beside them as a number, not as a pin on the grade line: a pin on the
control the model learns from would anchor the grade. After a grade: "no estimate yet", then the held-out number. A grade
of an earlier version shows as an outline with "new version". Interest implied by a grade reads "by your grade", and
pressing it makes it the user's word.

## 11. What the server gives

1. `ItemFacet.said`, of a facet of the layer: `{source, item, url, read, words: [{key, name, searchable, reaches: [value]}]}` for every item of the
   work, in the order of `sites`, none where the item's schema has no source for the field. Read in one pass over the members.
2. The sources of a field in the core (`universe/`, by the standard facets, no `if` on a plugin): `originalFacet`, and for the characters
   and pairs the site's `PAIRINGS_FACET`, for the pairs `Books.DIRECTION` too, for a universe with no fandom `kindFrom`. No change of
   `FacetDef`, so of the plugin API. `kindFrom = tag` on author.today.
3. `reaches`: the same key; `store.tags.bindingsOf` of the member's source; `site_fandom`; `store.drafts` of the member. A draft's word is
   kept for every word that gave the value.
4. `ItemCards.ofWork` gives what a site says of the whole work: `fromSite` by each member's own bindings, the steps a site lets, so a
   value's marks are one whichever item is read. A check: the form of each of the 38 works read through each of its items is the same.
5. `FacetValueInfo.sites`: the sites giving a value. `draftedFrom` stays as it is.
6. `WorkSite.texts` in place of `description`: every text of the item, the corrected and the site's own; `read`.
7. The search of a value of a facet (`/api/sources/{source}/facets/{facet}/values`) stays of the source the work is read through; a field's
   search is of the form's item, so it does not change with the left page either.
8. `ItemFacet.original` goes, replaced by `said`; `PagesController` gives `said` to the extension. `SiteTest` and `page.js` follow.

## 12. What `AGENTS.md` gains

Rows of the table: a field's sources; a word's press; the texts' row; the kind's buttons; the line of fields (web only, the panel being
short); a scale whose sites disagree. A fandom's binding is named as web only.

## 13. How it is checked

Each scenario of section 2 on the works it came from, with real presses, in the web interface and in the extension loaded in a
browser, every write undone at once and named: A on "Паутина Света - 1" (three sites), E on its age rating, F on "Ринко" and "Юто/Агеха", G on an
author.today work and a card-only item, D on a fandom found only in the catalogue, C by a grade and a following answer in the list of
the ungraded, H and a game's fields on a phone, K on a work whose authors differ. Reviews are read on a work that has them. By script:
nothing stands between the sources and the first chip of any field; the form is the same through every item of the 38 works; the time of
reading a work, before and after (2.5 s on ficbook's today).
