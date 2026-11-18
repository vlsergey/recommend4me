# The list of works and its index: requirements and where they come from

Every requirement says its source: **the owner** (with the date and the words, translated) or **ours** — a choice
made while building, not agreed with the owner. A choice of ours stays open until the owner agrees, changes or drops
it. A new requirement is added here when it is given.

## The owner's

| # | Requirement | Source |
|---|---|---|
| 1 | In the list a work is one row: the normalized work, shown by one of its items. | AGENTS.md, "A work is a normalized work" |
| 2 | The index of the list (Lucene) is a table of the sites' items, not normalized: a document an item, each with what is worked out of its work — tags, pairings and everything else — copied to every item of it. Reading a work of the databases would still read its worked-out values of the index: nothing gained. | 2026-11-18: "if we tried to read a work of the database, we would still have to read its computed parameters of the index… we keep the duplication in the index: the index holds a denormalized table of the sites' works with the results of computing tags, pairings and everything else, a copy for every site" |
| 3 | What reads every work — the search of duplicates first — reads it of the index, in one pass, as arrays: not of H2. | 2026-11-18: "I hope you take all of it from Lucene… maybe even by reading the file directly"; "put it in an array and read from the array" |
| 4 | The order of a facet's values (`position`) matters neither to the index nor to what reads it. | 2026-11-18: "for the index and the search of duplicates the position of a facet does not matter at all" |

## Ours, not agreed

| # | Choice | Where |
|---|---|---|
| a | The rows of a work are made one at the query: the documents of one work collapsed, the one of the best `rank` shown. | `ListQuery`, `ListWorks` |
