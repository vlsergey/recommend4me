# The search of duplicates: its requirements and where they come from

Every requirement says its source: **the owner** (with the date and the words, translated) or **ours** — a choice
made while building, not agreed with the owner. A choice of ours stays open until the owner agrees, changes or drops
it; then it moves to the owner's list with the date. A new requirement is added here when it is given.

## The owner's

| # | Requirement | Source |
|---|---|---|
| 1 | What a pair is compared by: the titles, the descriptions, the authors, the sizes, and the normalized values — tags, universes, characters, pairings, the age rating — with the facets of the sites. | 2026-11-18: "where are the other things that must also be compared — computed tags, universes, characters, pairings, ratings?" |
| 2 | Titles and authors' names are compared by their letters (trigrams) alone; no vectors of their meaning. | 2026-11-18: "remove the semantic vectors of authors and titles from the search of duplicates; leave only the trigram (lexical) ones" |
| 3 | Every number on the card in half precision (fp16). | 2026-11-18: "all in fp16" |
| 4 | No zeroings and no planes of a facet on the card. | 2026-11-18: "remove the zeroings and the planes" |
| 5 | No search of every pair (N²): a graph index of near works. | 2026-11-18: "can the algorithm be changed to one that works well on the card without N² operations?"; "then the graph index, I like it" |
| 6 | Of every work, one likeliest pair not answered yet is enough. | 2026-11-18: "why do you need 10–15 nearest if one nearest unanswered is enough?" |
| 7 | Any projection or compression of the data is learned, never set by hand. | 2026-11-18: "any projections and compressions must be learned" |
| 8 | What the search computes and in which order is decided by the search itself from the models and the data at every run; nothing is read once, concluded and fixed in the code. | 2026-11-18: "never read once, make a conclusion and hardcode an order" |
| 9 | What the search does and how long each step takes is told to the log, to watch it before changing it. | 2026-11-18: "first add messages to the log… think in advance how you will monitor it" |
| 10 | The models of the pairs are fitted on the answered pairs alone — the owner's and the sites'. Unanswered pairs are not taken for "different". | 2026-11-18: "why do you learn on different works not marked as different? Unmarked pairs come by themselves when you bring me pairs that are different and I mark them" |
| 11 | "One work" is not a "no" to "is it one series". | 2026-11-18: "'one work' must not count as the answer 'no' to whether it is one series" |
| 12 | The answers are taken as they are: no calibration, no reweighting. | 2026-11-18: "no need to calibrate or weigh, these are honest answers" |
| 13 | Whether the search finds what it should is measured against the results of the old search of every pair (a backup of them), not against what the new search found. | 2026-11-18: "compare with the old model's results: run it, take a backup and compare with it — some duplicates are hard to find" |
| 14 | The search reads its data of the index of the list (Lucene) as arrays, in one pass: not of H2, not by new indexes of the database — every value, every text of a work's own (its fields, not its chapters) as the user corrected it, the site's fandoms, the vectors of the title and the description (a byte a number), the counts of the texts the sizes are weighed by (`ListTexts`, `ListDocuments`); the stage of the vectors writes anew the documents holding other vectors than their sources keep (`ListVectors`). Of ficbook's 1.4 million works the texts and the fandoms read of H2, a seek of the disk a row, took 12 minutes of a search (2026-11-22). | 2026-11-18: "I hope you take all of it from Lucene… maybe even by reading the file directly"; "why indexes? you process every work anyway — put it in an array and read from the array"; 2026-11-22: "everything the search of the duplicates needs taken from the index"; "every text — the fields, not the texts of the chapters — in the index of Lucene" |
| 15 | The order of a facet's values (`position`) matters neither to the index nor to the search: authors, tags, pairings, anything. | 2026-11-18: "for the index and the search of duplicates the position of a facet does not matter at all" |
| 16 | A guide, not a hard requirement: the whole search within about 10 s — the speed after which it is "fine". | 2026-11-18: "rather the goal after which one can say 'well, a normal speed'; a guide, though not a hard requirement" |
| 17 | A pair is kept only when some model gives it 1% or more (`PairSearch.least`): a constant, no setting. | 2026-11-18: "yes, take it as an agreed constant; it could go to the settings later, but there is little sense" |
| 18 | The candidates of a search, the last search and how rare every value is are kept in a DuckDB file of the type of their own (`types/<type>/duplicates.duckdb`, `DuckDatabase`, docs/database/duplicates.md): appended whole in the order of their key by every search, no index of them kept — a page is a pass of the columns of its filter and its sort, a pair found by the blocks of rows of its first work. In H2 the rows of 2.6 million candidates took 46 s of a search and their ten indexes 123 s; in DuckDB the rows 11 s, a page 10–20 ms, a count 3 ms (2026-11-22, of synthetic rows). | 2026-11-22: "let us move to DuckDB" |

## Ours, not agreed

| # | Choice | Where |
|---|---|---|
| a | The graph is built on the titles' trigram rows alone — not asked for by the owner (2026-11-18: "you made that up yourself, I said nothing of the kind"). | `GraphSearch` |
| b | 16 neighbours a work; scored: the neighbours, their neighbours, and the extra list — the number left to us (2026-11-18: "at your discretion, though maybe too few — it depends on the timings"). | `GraphSearch.K`, `graph.cu` |
| c | The extra candidates of a work: every work of one of its authors, and up to 256 works of the very same title. | `GraphSearch`, `Titles.MOST` |
| d | The graph's first neighbours: of the same author and of the same title. | `Seeds` |
| e | The pairs answered "one work" are left out of the fitting of the series model altogether (of requirement 11). | `LogisticPairModel` |
| f | The test passes with half of the pairs of the exhaustive search found. | `GraphSearchTest.LEAST_RECALL` |
