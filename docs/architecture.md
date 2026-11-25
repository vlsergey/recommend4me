# Architecture

recommend4me is a local recommender: it collects works (games, books…) from sites, the user
grades some of them, and a model trained on those grades puts every other work in order on the
user's own 0..10 scale. The application knows nothing of any particular site: every site, every
neural encoder, every way of turning vectors into scores and the search are plugins.

## Modules

| Module | What it is |
|---|---|
| `plugin-api` | The contract between the application and its plugins: interfaces and data types only. |
| `libs/matrix` | Off-heap float arrays, OpenBLAS products, the Vector API loops. Used by the core and by scorers. |
| `libs/optim` | L-BFGS in ask/tell form, shared by the scorers. |
| `libs/onnx` | ONNX Runtime sessions on the CPU or CUDA, shared by the encoder plugins. |
| `app` | The application: storage, background work, training, the HTTP API, the web interface, the plugin loader. |
| `frontend` | The web interface (React), built into the application's static resources. |
| `plugins/encoder-bge-m3` | Text encoder: bge-m3, the one used when its files are there; with the file of its ColBERT head, the vector of every piece of a text too (`BgeM3Pieces`, docs/search.md). |
| `plugins/encoder-e5` | Text encoder: multilingual-e5-small, without bge-m3's files. |
| `plugins/encoder-siglip2` | Picture encoder: SigLIP2 NaFlex. |
| `plugins/reranker-bge` | Reranker: bge-reranker-v2-m3 reads two texts together: the line searched and every work found — a work by its windows among the first 1 000 nearest by meaning, else by its document (docs/search.md). The works found are put in the order of a weighed sum of what they were found by — the plugin's features (`SearchResult.features`) and the reranker's score — the weights learnt in the background from the user's "fits / doesn't fit" (`SearchLearning`, `SearchWeights`); the reranker's order till a line has both. Learnt of them too, the closeness of the line and a passage by meaning: q·p + (q·a)(b·p), the line's vector moved before it is searched (`QueryShift`). |
| `plugins/scorer-pairwise` | Pairwise logistic ranking (RankNet with a line), C chosen by cross-validation. |
| `plugins/scorer-knn` | k nearest rated neighbours by cosine — a baseline. |
| `plugins/scorer-fm` | Pairwise factorization machine: the line and every two features meeting through vectors of rank 8; C chosen by cross-validation. |
| `plugins/scorer-kernel` | Pairwise kernel ranking with the arc-cosine kernel (no width to choose); C chosen by cross-validation. |
| `plugins/scorer-boosting` | Gradient boosted trees on the pairwise logistic loss, Newton leaves; the number of trees chosen by cross-validation. |
| `plugins/search-lucene` | Search by words over one Lucene index per content type; by meaning the application's, the full-text vectors of every window read whole (`FullTextNearest`), and the texts of the works found read again by their pieces (`SearchPieces`): how a search goes in docs/search.md. |
| `plugins/catalogue-wikidata` | The universes of fan fiction and their characters, from Wikidata. |
| `plugins/pair-logistic` | The works of different sites that may be one work, or one series of works: a logistic model of how alike their layers are, one a kind, fitted on the user's answers on pairs; and the search of the pairs by both at once, on the card when there is one. |
| `plugins/cast-reading` | The ways the sites' lines of characters and pairings — split by each site's habit into names and pairings, a half structure with no guarantee — are given to the models of the characters and the pairings: the line whole as one vector (as from the first), every name a vector and the mean of them, every name and pairing a feature, the names found in the dictionary of the universes as the site's word on its characters, or all at once; one chosen for each such facet on the page of the models. |
| `plugins/suggester-bilinear` | The values a work should have — tags, universes, characters, pairings — by one model of every work and every value. |
| `plugins/suggester-knn` | The values a work should have by its nearest works fitted on, alike by its texts and pictures; the model kept is where every one lies and what it says of the values. |
| `plugins/suggester-bayes` | The values a work should have by naive Bayes over its other values; the model kept is the counts. |
| `plugins/source-aff` | adult-fanfiction.org, browser tracking and a work's chapters on request. |
| `plugins/source-ao3` | archiveofourown.org, browser tracking and a work's page on request. |
| `plugins/source-author-today` | author.today, browser tracking only. |
| `plugins/source-ficbook` | ficbook.net, browser tracking only. |
| `plugins/source-fanfics-me` | fanfics.me: the whole catalogue, updates, browser tracking and a work's page on request. |
| `plugins/source-ffn` | fanfiction.net, browser tracking only: the site answers no request but a browser's. |
| `plugins/source-molwe` | molwe.ru, browser tracking and a work's chapters on request. |
| `browser-extension` | The Firefox extension of the browser tracking mode. |

The f95zone.to source lives in a private repository of its own, `recommend4me-f95zone`, built
against this one as a Gradle composite build.

## Plugins at run time

The application is a distribution, not a fat jar: `lib/` holds the application and its libraries,
`plugins/<id>/` the jar of every plugin with the libraries it needs beyond the application's.
`Launcher` puts every jar of every plugin folder into one class loader above the application's —
one, so that two plugins sharing a library with native code (ONNX Runtime) load it once — and
starts Spring with it. A plugin is a Spring auto-configuration
(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`): its beans
implement the interfaces of `plugin-api`, and the application finds them by type.

Plugin folders: `<install>/plugins`, plus any listed in `recommend4me.plugin-dirs`.

## Content types and sources

A source belongs to a content type (`games`, `books`). Works of one type are graded on one scale
and ranked by one model, whichever site they came from: a book of author.today and a fanfic of
ficbook are compared with each other.

Every source has up to four modes:

- **scrape all** — the whole catalogue, resumable;
- **updates** — what is new since the last time;
- **browser tracking** — the pages the user opens in Firefox are sent by the extension and parsed;
  nothing is requested from the site by the application;
- **page** — one work's page downloaded by the application when the user asks for it (the
  "refresh" button of a work, or a work graded that is known by its card alone); no job, no crawl.

author.today and ficbook are never scraped whole. ficbook's catalogue was filled once, on
2026-11-17, from the dataset IlyaGusev/ficbook of Hugging Face (1.39 million works with the text of
every part, collected up to 2024) by a one-time import, not a part of the application (user,
2026-11-17: one-time work never becomes a part of the application). Of it, the authors and the
fandoms the dataset gave by their names alone are keyed `name:<name>` (`Ficbook.NAME_KEY`), which the
site's page replaces later. The source says so of its authors (`FacetDef.byName`): as soon as a page
gives an author's own key, the normalized author of the key of the same name is merged into it
(`NamedAuthors`, marked `NAME`), every work of the dump naming them one author's; a fandom is bound by
its name to the normalized fandom of the same name anyway, its key of no works let go as a twin
(`UnusedFandoms`).

A MILLION WORKS ARE ENCODED A PORTION AT A TIME, THE LIKELIEST FIRST (user, 2026-11-17; owner, 2026-11-19):
the texts of the works and the chapters are tasks over the works (`TextVectors`: a thousand works a turn,
`PartVectors`: the chapters of 50 works), of the marked and rated works ahead of the others, the
others after every other task, each turn planning the next from where it left, so a grade or a
training goes between them; the works in the order of their expected grade (`NextWorks`), those not
predicted last. A work whose page the user waits on has its texts encoded ahead of everything.
Archive of Our Own has browser tracking and single pages, one request at a time, five seconds
apart, with "bot" in the user agent, as the site asks of anything automated.

fanfics.me asks no visitor to prove itself and publishes no dump of its data; its search of every
work is its catalogue: twenty cards a page, each with everything a work's header says but its text
(the authors, the fandoms, the characters and pairings by the ids of the site's encyclopedia, the
rating, the genres, the warnings and the events, the size, the status, the summary). The whole
scrape reads every page of it, a second apart, then what the genres, the warnings and the events
mean (the site's help, every fandom's encyclopedia) and what the fandoms are of; the updates read it
from the top down to the works known, then what the fandoms not described yet are of — by the
rating of the fandoms when there are many, else each by its own page. A work's own page and its text in the reader — the notes of its author, the chapters,
the comments — are read only as a single page, or by the browser; the text of a work rated NC-17
the site shows only to a reader logged in.

## Storage

The tables of every database, as diagrams: [docs/database](database/README.md).

H2, one file per level of data, under the data folder (`recommend4me.data-dir`: `data` of the
repository when run from it by `run.bat` or `:app:run`, else `%LOCALAPPDATA%\recommend4me` on
Windows, `~/.local/share/recommend4me` elsewhere):

```
app.mv.db                          settings
dictionaries.mv.db                 the user's dictionaries, every content type's: the normalized
                                   tags of every site (merged, named, linked, bound to entries,
                                   ignored) and the universes with their characters
preferences.mv.db                  the user's preferences, every content type's: the names given
                                   to universes and characters, the judgments of the search
sources/<source>/source.mv.db      what the site says: works, facets, numbers, texts, pictures,
                                   reviews, text parts, captured pages — and their vectors, a
                                   compressed form of the same scrape
sources/<source>/parts/            the texts of the parts, the vectors of their windows and
                                   paragraphs, the codes of the pieces and the full-text
                                   vectors of the windows of their texts and of the items' texts searched by
                                   meaning (docs/search.md), outside the database
                                   (RocksDB, the texts compressed
                                   by zstd with a dictionary of their own, the vectors in blob
                                   files shared by thousands of parts, apart from their keys):
                                   tens of gigabytes of chapters, which an H2 file holds badly
sources/<source>/corrections.mv.db the user's corrections: tags added and removed, fields
                                   overridden, duplicates merged within the source and with
                                   the other sources of its type (kept by the smaller key)
sources/<source>/ratings.mv.db     the user's own data: grades, marks on pictures and reviews,
                                   words on matches, the user's own actions seen on the site
sources/<source>/images/           picture files
types/<type>/model.mv.db           the trained model, the predictions, the likeness to the marks,
                                   the directions of the set embeddings, the suggested tags — all
                                   recomputable
types/<type>/work_duplicates.duckdb
                                   the store of the search of the works' duplicates (DuckDB): its
                                   candidates, the models of the answers, how rare every value is
                                   — all recomputable, made anew empty as its tables change
types/<type>/authors_duplicates.duckdb
                                   the store of the search of the authors' duplicates (DuckDB):
                                   its pairs of authors — recomputable, made anew empty as its
                                   tables change
types/<type>/search-index/         the index of the search by words
types/<type>/list.duckdb           the index of the list of works (DuckDB): what the list is
                                   filtered, sorted, counted and paged by — all recomputable,
                                   made anew empty as its tables change
models/                            ONNX graphs of the encoders
backups/                           the settings, the dictionaries, the preferences, corrections
                                   and ratings, zipped on every start
```

Every file has its own connection pool and its own Flyway history. H2 does not join across
files, so whatever reads several of them reads each file once, in batch, only the columns it
needs, and puts the answer together in memory for that one operation.

THE LIST OF WORKS IS ASKED OF ITS INDEX ALONE (user, 2026-11-17): of a million works, the list put
together of every file at every request was minutes. A DuckDB file of every content type
(`worklist`: `ListIndex`, made by `worklist/store.sql`; of Lucene till 2026-11-22, owner: "work")
holds a row of every item (`item`) — the work it is of, a work of several items one row grouped
by it; its dates; and what changes of its work without it — graded, the prediction, the chance of
interest, their product — set in place, many rows by one `UPDATE … FROM` of a table of what is
pending — and, in the tables beside, keyed by the item, the values of every filter facet as the
user sees them (the site's, the models' kept, the user's word on top: `item_value`), the values the
models give it — kept here alone (`item_model`, `ListModelValues`) —, every number
(`item_number`), every text held whole and every vector of a text. The filters, the orders, the
count and the page are one SQL query of it; only the cards of the page are read of the databases,
by their keys. It is written through one connection whose transaction stays open till a commit,
and read by the requests as last committed. The
databases stay the truth: the index is written in the queue of the computations (`ListIndexing`)
a second after something it holds changed — an item's data, an answer, the values the model gives, a
grade, a mark of interest, a regrouping of the works — a consumer of the changes as the stages are:
of every source the items changed after where its writing stands, in the order of the dates the
databases keep with the rows (`item.changed_at`, `normalized_work.changed_at`), ten thousand a turn,
where it stands kept with the index's commit (`ListCursors`, the rows of `list_mark`): nothing is named in the queue. Every row holds the date
every kind of data of its work last changed (`WorkDatum`), and the stages of the tasks over the works take
the works by them, each by the kinds it reads, up to its own mark (`StageSweeps`, docs/computations.md, c).
The predictions of the works are kept in the index alone (`ListPredictions`), written in place by what
makes them where they change as the index keeps them, to three digits. The index has no format: one
current as of nought takes every item from the first, a row of what it holds anew alone written
with its stages as they were.

## Works, items and their data

An *item* is a work as one source knows it, keyed by the source's own id. What the model and the
interface read of it is generic:

- **facets** — categorical values: tags, engine, status, developer, fandom, genre, language…
  (`facet`, `value_key`), with display names in `facet_value`. The source declares its facets:
  label, whether they filter the list, whether the model reads them.
- **numbers** — likes, views, votes, chapters…, declared with how the model reads them.
- **texts** — overview, annotation, changelog…, declared with their search weight, whether the
  model reads their vector and under which block key (two book sources share `text:annotation`).
- **pictures** — position 0 the cover, then screenshots; each analysed by the picture encoder.
- **reviews** — other readers' opinions: f95zone reviews, author.today рецензии, ficbook and
  Archive of Our Own comments; each encoded whole.
- **parts** — the text of a book's chapters, cut into windows; a vector for every window.
- **series** — a site's cycle of works: the work's facet of the role SERIES names it (one a site:
  the first the site lists of the work), its own page — or a list of an author's series — tells its
  title, description, status, dates and works (`ItemSeriesStore.keepSeries`, kept in `series`), and
  a work's number in it is kept in `series_place`. Seeing a series' page makes the series and puts
  every work listed on it in it.

A source may keep tables of its own in its source database (its own Flyway history).

A **normalized series** (`series/TypeSeries`) is made on reading, never kept: the series of the
sites and the groups of works the user said to be one series (`series_group`) are one series with
every other one sharing a normalized work with them — a work is of one series — named by the
smallest key of a site's series ("ficbook/28510"). Its works go in the order every site's numbers
give by pairs (`SeriesOrder`). The answer "one series" on a pair of works puts both in one group;
"different" or the word taken back takes the user's works out, a site's series keeps its own. The
model reads every site's series and the normalized one as features.

## Corrections

The interface and the model read *effective* data: the source's, with the user's corrections on
top — tags added and removed, fields overridden. Merged items form a cluster: one card, one
grade; the cluster's members never fall into different folds of cross-validation and are never
paired with each other.

## The model

For every content type:

1. the training reads the works it learns of alone — the graded and those marked of interest, each
   with all its items (`EveryWork.training`): the vectors of their texts, pictures and sets, their
   facets, numbers and the grade of an earlier version — and what the average work of the type
   is, as the last sweep of the scores counted it (below);
2. the active scorer (`recommend4me.scorer.<type>` setting, the first available by default) is
   measured on 5-fold cross-validation over works for each of its candidate parameters and the
   one-standard-error choice is made;
3. the grades are placed on 0..10 from the out-of-fold scores (the ladder);
4. every item is scored by the sweep of the scores that the training begins; the explanation
   switches feature groups off one at a time.

THE TRAINING AND THE SCORING ARE TWO WORKS OF THE QUEUE (user, 2026-11-17): of the books' 1.4
million works one training that scored every work held the queue for an hour and a half. The
training (`Recommendations.retrain`, the work `train`, asked when the user's action asked) reads
the graded works alone and takes seconds; the average work it centres the vectors and the numbers
by is kept in the model database (`average_work`, `AverageWorkRepository`) — counted by a pass over
every work only when none is kept, or the encoders or the directions of the sets changed since
(`AverageWork.basis`). Its end begins the pass of the expectations (`WorkPredictions`, the tasks
`predictWorks` of the marked and rated works and of the others, never asked): every work done before
the model is to be done again, a thousand at a time, the highest predicted first, from where the turn
before left; scored by the model kept of the fields the index of the list keeps, the predictions that
changed as the index keeps them written into it in place (`ListPredictions`), and the average work counted along — every
vector of an item as the scoring reads it, every work's features (`AverageCount`). Where the counting
stands — the model, the sums counted so far — is kept in the model database (`score_sweep`), so a
restart of the application goes on with it. At the end of the pass of the other works the
average work counted is kept for the next
training.

Scorers implement `Scorer`; anything that maps a row of features to a number fits.

### The manner of a text

How a work is written, apart from what it is about, is read by the frequencies of its words
(`manner`): of every content type the 1000 words most frequent in the texts of its works — every
text weighing alike, a word mostly written with a capital (a name) left out — with the mean and
the spread of each one's share over the texts (`MannerWords`, kept in the model database). The
manner of an item is the share of every one of them in the text of its chapters, standardised: a
vector of a number a word, kept with the hash of the texts it was made of (`item_manner`). The
words are fitted again whenever the number of the items with a text changes, every manner then
made again; else the manner of an item whose chapters changed is made again by the words kept —
in the queue, after the chapters read go quiet (`Manners`). A word is a run of letters and no
more: no forms of a word, no grammar.

The manner of every normalized author — the mean of the manners of all their works that have a
text, a work's own the mean of its items' — and of every normalized series is kept too
(`group_manner`, `MannerGroups`), with the hash of what it was made of: the words, the works and
the texts of their items. It is made again in the same computation when that changes — a text
read, an item written (its authors, its series), the user's word that puts works, series or
authors together or apart (`WorkGroupChangeEvent`, told by the repositories of the dictionaries).

The model reads three blocks of it (`TypeManners`): the work's own (`manner:work`), its normalized
authors' kept ones (`manner:author`, of several the mean) and its normalized series' kept one
(`manner:series`). A work of no text of its own has its authors' and its series' all the same.

## Tags worked out

Authors tag their works carelessly or not at all, and write their pairings and characters as
they please. For every facet a source marks `suggest` (the tags of author.today and ficbook) or
`infer` (ficbook's pairings and main characters) the model gives the chance of every value a
work has, and of every value it lacks that it more likely has than not:

- every value of the site is shown — none is hidden — with the chance in brackets, marked when
  the work more likely has it not;
- of a `suggest` facet, a value the work lacks and more likely has is a suggestion to confirm;
- of an `infer` facet, such a value is the work's at once (marked as worked out): the site's
  line of the values (`FacetDef.original`, kept as a text) is shown above, and is what the model
  learns from beside the description, the chapters and the other tags.

The user says of any value that the work has it (✓) or has it not (✕): a correction, applied over
any chance, seen at once by the model, the filters and the search.

The work is the `FacetSuggester` plugin's (`suggester-bilinear`). It is handed every item of the
source — every input the model of the grades reads, each a block of its own (the title, the
description, the notes, the site's lines, the reviews and the windows of the chapters as sets,
the pictures), its values with the user's corrections, the values of its other facets, the site's
own among them — and every value with the vector of its name as a query. One model scores every
pair of a work and a value: σ((xA)·(zB + e) + b + w·p) — the work's inputs and the value's name
and groups carried to a few coordinates where they meet, the value's own coordinates and bias,
and the pair's own features (the site gives the value as an example, the common bias). How often
the work's texts name a character or two of them in one paragraph is NO FEATURE: no model reads the
search index, which the ranking of the works never waits for. The texts are counted of one work
when its candidates are asked for — "…" of the characters and the pairings offers the two likeliest
and the two the texts name most, three times at least. IT LEARNS OF THE USER'S WORD ALONE (owner,
2026-11-19: "the site's markup is the input, the user's markup the output"): of the works the user
answered on of the facet, by the weighted logistic loss over their pairs — a value the user said
the work has weighs 1, one the user said it has not 1, of a work said all named every other value
1, labelled nought; a value the user said nothing of nought. What the site gives a work, of the
facet or as its examples, and the drafts of the dictionary are inputs of its pairs, never labels:
the model learns of the user's answers how far the site is right. Every work is judged by a model
that did not learn from it (five folds by the work's id), so an answer on a work does not move
its own chances. The penalty is chosen by the held-out loss — the strongest
penalty within one standard error of the least; gone on from the state fitted last, the penalty
chosen then, the two beside it and the strongest, whose model is all but the values' base rates —
a choice gone where every model holds out worse than that walks back from it. No fitting holds
more than a portion of ten thousand works. A facet of no more works than a portion holds is
fitted over them whole (`bilinear-2`, the one version of the model since 2026-11-19): every
model's minimum searched by L-BFGS (`WholeSearch`, every penalty's folds in one product a step);
of author.today's tags a held-out loss of 0.0007 where the descent over portions held 0.002 at
its best, in a tenth of the time. A facet of more descends a portion at a time (Adam, every penalty's every fold in one product), walking the portions a few times — from
scratch once to see them (the input blocks are the portions' own: the value side has no work and
tells none), three to six to descend, once to score them by the model kept; gone on from the
state fitted last, twice and once, unless the works have a block its layout does not know. The
descent overshoots at the weak penalties (of the same tags 0.85 at λ = 1e-5), and the held-out
loss chooses a stronger one.

An input block holds the rows of the works that have it alone (`InputBlock`: the vectors and the
work of each row), as the products read them — of the 75 630 works of fanfics.me the notes are of
46, the cover of 10 217, and no row of zeros is multiplied. A reading of a portion of works (of a
catalogue, of a refit) puts its inputs on the card once, for every facet that reads them, and frees
them once the portion is done (`WorksRead`); there the suggester makes both products — the works'
coordinates and their scores — and chooses what every work is shown with
(`SuggestionScores.shown`, `features.cu`), bringing back the few chosen, never a row of every value.
Without a card the same is made in the main memory.

The facets the model works out — the kinds, the universes, the main and the secondary characters,
the pairings, the tags, the languages — are worked out IN A CHAIN, in the order of the schema: each
reads as its context the values the site and the user give of every other facet, and the values
the model gives of the facets before it — made in the same reading, of a pass over every work by
the models just fitted before it (`FacetChain.before`). A work's characters are those of
the universes given before them, its pairings pairs of the characters given before them. The
fitting and the reading follow the same chain, so a facet is judged on what it was fitted on.

THE MODEL OF EACH FACET IS KEPT, in the model database: its fitted state, the values it chooses
from, and the model the works are scored by. THE CHANCES OF THE WORKS ARE NOT KEPT (user,
2026-10-15): whoever reads works — a page, a card, the extension, the search index, the training of
the grades — gets them made in memory at the reading, of those works alone, from their vectors and
values by the models kept, through the chain. Nothing waits for a refit.

THE CHANCES ARE CALIBRATED BY THE USER'S ANSWERS, AND THE CUTOFF OF THE LIKELY IS LEARNT (user,
2026-11-17), whatever suggester scored them (`Calibration`): every fitting's folds hand over the score
each gave the values the user answered on of the items it held out (`FittedSuggester.heldOut`).
Of them the facet's curve — the score against yes or no made monotone (`FacetCurve`) — and every
value's own slope and shift over it on the scale of the log-odds, drawn to the facet's curve by a
spread learnt of all the values (`ValueShifts`, DerSimonian and Laird): a value of no answer is the
facet's curve, of a few between, of many its own. The cutoff is the highest chance at which the
answers wrongly shown are not fewer than those wrongly hidden — rather shown wrongly than hidden.
A chance made is the calibrated one, and whether it is likely is told with it (`Chance.likely`): the
suggester's choosing takes every value's cutoff of its score (`SuggestionScores.shown`), the values
given, the filters, the interface and the extension read that mark, no number of their own. A facet
learnt of no answer keeps the suggester's chances, likely above one half.

THE VALUES A WORK IS GIVEN ARE KEPT FOR THE FILTERS (user, 2026-11-17) — IN THE INDEX OF THE LIST
ALONE, NEVER IN A DATABASE (user, 2026-11-18): a filter of the list runs no model. THE MODELS AND THE
VALUES GO IN STAGES, BY TASKS OVER THE WORKS (owner, 2026-11-19, `docs/computations.md`): the pass of
a source's facets fits their models alone (and a calibrated facet's cutoff) and tells it
(`FieldModelFitEvent`); then three tasks over the works, each of the marked and rated works ahead of
the others' after every other task, a thousand works a turn, the highest predicted first, from where
the turn before left (`NextWorks`, `TaskCursor`): the fields the models give (`WorkFields`, the task
`fillWorkFields`) — made (`WorkModelValues`), compared with those the documents keep, the documents
whose values changed made anew —, after the vectors of the site's text fields (`TextVectors`,
`encodeSiteItemTexts`; of a work the user waits on, at once), the expected grade and interest (`WorkPredictions`, `predictWorks`)
and the documents of the search (`WorkTexts`, `indexWorkTexts`), each planning the next of the works
it did (`WorkFieldFillEvent`, `WorkPredictionFillEvent`). When each stage was last done of an item is
kept in its document (`WorkStages`); a stage is done again of the documents done before what it
depends on changed. A document made anew of a changed work keeps its fields of before, every stage
of it to be done again, the vectors first (`WorkDocumentWriteEvent`). The fields of the marked and rated works all computed, the models of the grade
and of interest are made of them (`RatedWorkValueFillEvent`), their values read of the index of the
list, no chain run (`CatalogueReader.gradedWorks`).
THE INDEX OF THE SEARCH KNOWS NOTHING OF THE MODELS (user, 2026-11-18: "the refitting of the models and
the updating of the computed values must not lead to an update of the full-text search"): its
documents hold the texts and the values the site and the user give alone (`SearchDocuments`, of no
`models`); what a reader of a few documents reads — the reranker — is made with the models' values.

EVERY SUGGESTER KEEPS ITS MODEL, AND A WORK IS SCORED BY IT ALONE (user, 2026-11-12):
`FittedSuggester.model` is made of the task fitted on and kept; `FacetSuggester.scoring` of it and
of the works' own features is the one way to the chances of any work — of every work after a refit,
of one work on its page. Nothing scores on the task fitted on, and nothing reads the other works of
the source to score one: the bilinear model keeps the coordinates of the values; the nearest works
(`suggester-knn`) keep where every work fitted on lies (256 numbers in half precision) and what it
says of the values, a work asked scored by its nearest among them — the works chosen by their ids in
one walk, which sees every input block the portions have (the value side tells none), and placed in
a second, each projected of every block, nought of one it lacks; naive Bayes (`suggester-bayes`)
keeps its counts and what every work added to them. A work the model was fitted on is known by its
id and is not its own witness. The universes of author.today on the nearest works had a page of one
work read every work of the site — its inputs, then every facet after them — six times a page.

THE CHAIN READS, THEN COMPUTES WITH THE DATABASE REFUSED (user, 2026-11-12): the works of a pass are
read as it begins (`WorksRead`: their values, answers, drafts, the tags under others, their
inputs); of every facet its model, the dictionary's word on the values each work may have and the
works' lines of characters are read before it — what they are read of is the chances of the facets
before it — and its chances are made inside `DatabaseRefused.during`, where a connection asked for
fails at once (`InlineBlobs.acquire`), in the tests as on the user's machine. So are the chances
of every work after a refit. The age rating is a field of the chain as any other (owner,
2026-11-19): learnt of the user's answers, of the whole description of the work and the fields
before it, a work offered the steps its site lets it have alone (`AgeRatingVocabulary`).
The models of the facets of a type's sources are fitted again in the background, in the queue of the
computations, whenever anything they learn from changes — at once of the user's word; of no word of
the user's (a chapter, the texts' vectors, the names of a cast of a work the user answered on or
rated, a site's page of it written again) once such changes, counted beside the models
(`ModelAutoChanges`, `model_auto_changes`), come to a hundredth of the works they learnt of, and so
the models of the grades of the works' pictures, sets and manners (`WorkFeatureCounts`; owner,
2026-11-24: a chapter of a rated work read fitted every model again, and every work's fields and
expectation were made anew from the first): one work of the queue of the type, the
sources changed kept waiting in it, whole, NOTHING OF IT HOLDING MORE
THAN TEN THOUSAND WORKS (user, 2026-11-17: of ficbook's 1.39 million works a pass holding every work
was more than the memory of 12 GB). The works of the source are read a portion at a time in the
order of their ids (`WorkPasses.forEachPortion`, `TaskPortions.MOST`), each portion read anew of
the database at every walk and let go before the next: no reading of more works at once is let
through (`TooManyWorks`). A facet after another down the chain (`FacetFitting`, `FacetPass`), each walking
the works:
- once, of their values alone (no vector read): what its value side is made of (`ValueCount`) — the
  values the works have or were answered of, the keys of their context and how many works have each
  (a key of one work alone no column), the pairs of characters the works may have, the texts of
  their lines read, the fingerprints of the works, streamed in the order of their ids whatever the
  portions (`WorksPrint`). The value side (`ValueSide`): those values and every one the dictionary
  knows, their names, groups and texts, the columns of the context, the basis of the facet — of the
  values, never of the works;
- as often as its suggester's fitting needs, of the same value side (`FacetPortions`), unless the
  model kept was fitted on the same basis; the calibration of what the folds said of the works the
  user answered on, by their ids;
- of a calibrated facet, once to make the chances of the works rated and answered on by the model
  kept (`FacetScoring`): the chances of the values open to the model counted into a bounded
  histogram, of which the facet's cutoff is worked out for the next pass (`OpenChances`).
The context of a facet is read of the values the facets before it gave, as the same pass kept them:
the chain is not made again for every portion of every walk. Fitted anew from scratch at the user's
asking, a facet reads them as the last pass kept them.

Of a work's chances those shown are made — of the values it has, rejected, given as examples, more
likely than not, and the few likeliest others offered — not every character of a work's universes.
The user choosing among every value a work may have (the candidates) gets every one at the asking.

## The normalized tags

The application's layer of tags ("Теги") is of normalized tags, one dictionary of every site of
every content type — a tag of a site of games and one of a site of books may be one — kept in the
dictionaries: a normalized tag is an id and the name the user gave it, if any (`normalized_tag`);
every tag of every site — its source and the site's own key of it — is bound to exactly one
(`site_tag`, keyed by the two). A work's values of the layer are the ids, written out. A site's tag
seen first is bound to the normalized tag the same site's tags of its name are bound to, any case,
when there is exactly one such, else to a new normalized tag of its own (`TagNormalization`, as the
works are captured). The tags of different sites mean different things till the user merges them:
"Angst" of one site and of another are two tags, offered to be merged by their likeness.

A normalized tag the user says keeps its works out of the library (`normalized_tag.keeps_out`, the
ban button on its page, asked to be confirmed with how many works go) keeps out every work any site
tags with it or with a tag under it, of every site by one path: the core's store of a source's writes
(`KeptOutItems`) removes the work the moment its site's tags are written and one of them is bound to
such a tag, and keeps none of its writes after; the tag said so, the works of it kept already are
removed in the background (`KeptOutWorks`), with everything of them. Of the sites' own tags alone: a
tag the user or the model gave a work keeps nothing out.

Tags that mean one thing under other names — "Angst" of Archive of Our Own and "Ангст" of ficbook —
are merged by the user on the screen of the tags; the application only offers the tags alike by
the meaning of their names (the sites' tags' names encoded as the search encodes short texts, a
tag the mean of its sites' tags' directions, alike by the cosine), of one site or of another, and
the user may say two tags are different (`normalized_tag_distinct`). Merging is binding: the
sites' tags of one normalized tag are bound to the other, the user's answers on it become answers
on the other — the newest standing of a work answered on both — its links go with it, and it is
gone. A site's tag taken out is bound to a new normalized tag of its own; the answers stay with the
tag they were given on.

The graph is of normalized tags, in a table of its own (`normalized_tag_parent`), and a tag may
have several parents: a work with the child has every parent — shown on its card as given by the
child — and a work the user said has not a parent has not its children either, which are then not
shown; the "has not" of a tag above wins over a "has" of one under it. The model of the grades
reads the works' tags so, and the suggester of the tags learns from the answers so
(`TagHierarchy`). No tag is put under one of its own descendants. On a tag's page every alike tag,
and every tag the user said something of to it, has four words to press and press again: the same
tag (merged), a tag under it, a tag above it, a different one; every site's tag bound to it is a
row of its own, merged, unpressed to take it out.

A tag is shown by the name the user gave it; else by the label of the entries of the catalogues the
user bound it to, in the user's first language one of them has it in, of the one bound first; else
by its sites' tags' name most used by the works the user liked (graded above the middle of the
scale), by the works graded, by all its works; else by the first by the alphabet.

The user binds a tag to entries of a catalogue of tags (`TagCatalogue`; `catalogue-wikidata`) —
"Quidditch" to Q305561 — found by a name, a Q-id or the entry's page; the tag's names are offered to
search by, and a tag bound to none is searched for by its name as it opens — what is found is only
offered. The tag is named in the user's language, and the entries' labels and aliases of every
language find it. A tag may be bound to several entries and an entry to several tags. The binding
is the user's word, kept with the merges in the dictionaries (`normalized_tag_entry`); the entries' texts as the catalogue gave them when bound (`tag_entry`). A
tag merged into another gives it its entries.

What a tag means is what its sites' tags' own pages say (`ItemStore.describeFacetValues`, kept in
the source's `facet_value_description`: ficbook's page of a tag), then what the descriptions of its
entries say — each shown with who says it.

What an entry is a kind of (`TagCatalogue.above`: Wikidata's P279 "subclass of" and P1074
"fictional analog of", never P31) is read up from every entry bound, five levels, when it is bound
and once on start for a binding made before; kept in `tag_entry_above`, the entries met on the way
in `tag_entry` with their names. A tag's card offers another tag above it when one of its entries
is a kind of the other's, and under it the other way round, with the way up — "минет → половой
акт" — unless the two are related already; only the user puts one under another. "Update from the
catalogue" on the card reads its entries and every level above them again, what was kept shown
meanwhile.

Under the list of tags the screen shows the offers of the tags' graph (`HierarchyOffers`),
read apart and again by its own button: a tag under another as above; two tags bound to one entry
merged; a new tag of an entry no tag is bound to, above the tags that meet there first — of the
entries both of two tags are a kind of, the nearest to the farther of the two; where a tag is bound
there already, that tag is offered instead. Each says how many graded works it changes — of a link
the child's graded works without the parent, which get it, and the ones said to have not the parent,
which have not the child either; of a merge, the smaller tag's; of a new tag, all of its tags'. The
nearest in the catalogue come first — "oral sex" right above two tags before "process" three levels
up — and of those alike near, the ones changing most.

The names are worked out again a few seconds after the works or
their grades change and kept with the tags' statistics in the dictionaries (`tag_stat.calculated_name`):
made of the rest, not the user's word. Every source reads them from there, as the universes' names
are read from their dictionary, never from a copy of its own. The names the user gives the universes
and the characters are the user's preferences (`preferences.mv.db`, `catalogue_entity_rename`).

## Universes

A content type may have universes (`ContentType.universes`: books) — works may be fan fiction of
someone else's world. A UNIVERSE IS A NORMALIZED FANDOM, as a normalized tag is a tag
(`universe/FandomRepository`, the dictionaries' `fandom`): every fandom of every site — the site's
own key of it, ficbook's "books/rouling_dzhoan___garri_potter", fanfics.me's "2", AO3's tag — is
bound to exactly one universe (`fandom_site`). A site's fandom seen first is bound to the universe
the sites' fandoms of its name are bound to, any case, when there is exactly one such, else to a
new universe of its own (`FandomNormalization`, as the works are captured and on start). From then
on the user binds them: merges two universes that are one (their sites' fandoms, entries, links and
the user's answers on the works go to the one kept, the newest answer standing of a work answered on
both), takes a site's fandom out to a universe of its own — or puts it down, to a universe of its own
under the one it leaves (a part of a franchise) — puts one under another — a work of the
child is of the parent too — tells two apart, names one. The screen of the universes offers the
universes alike one by the meaning of their sites' fandoms' names (the names encoded as the search
encodes short texts, kept by their sources, made in the background: `FandomVectors`) and those bound
to the same entry; it merges nothing by itself. The likeness of two names is learnt of the user's
word on the universes (`FandomLikeness`, `fandom_likeness`): a weight of every dimension of the
names' vectors, so that two names of one universe — the sites' fandoms the user bound together —
and of a universe and one under or above it are nearer than a name of another, the names of two
universes said different the farthest; learnt again in the queue after every word, and told how
right it is on the universes held out of it beside the encoder's own. What changes it by no word of
the user's — the names of the sites' fandoms encoded, a synonym or a parent a site tells of its
fandom — is counted beside it (`fandom_likeness.auto_changes`), and learnt again once it comes to a
hundredth of the universes it was learnt of. The page of the models shows it as a model of the type
that keeps the universes (`FandomLikenessStates`): learnt again when the user asks, and the ways a
site's fandom is read compared when the user asks (`FandomWayComparisons`, `FandomRetrieval`: the
name alone, the description a synonym, the description added to the name — how well each finds the
universes as the user made them, a minute and more of the card), the universes alike one found by
the way the user chooses there (`FandomWays`). A site's description of its
fandom is a synonym of the name: one more name of the universe. The names' and the descriptions'
vectors are read as they are kept — half precision, into one block off the heap (`FandomRows`) —,
the pairs laid out flat by their anchor (`NamePairs`), and the learning runs on the card by kernels
of its own (`FandomLikenessOnCard`, `likeness.cu`): sent once, every step there, the weights alone
brought back — 2.3 million pairs, 100 steps, in a tenth of a second; on the processor where there is
no card (`FandomLikenessDescent`), alike within the last digits. Every site's fandom on it, on the card and among the
alike, stands with what its site says it is of — the source's `facet_value_description` of the
fandom: ficbook's heading of a fandom's page, "Rowling Joanne «Harry Potter» / Серия книг, 1997 -
2007"; fanfics.me's line under a fandom's name, on its pages and in the rating of the fandoms. A
fandom not read of yet is asked of the browser when its universe is opened (`Source.describing`), as
a tag is; AO3 says nothing of its fandoms but their names.

A universe is shown by the name the user gave it; else by the name of the first entry of a catalogue
it is bound to; else by the name of its sites' fandom of the most works (`fandom.calculated_name`,
worked out again as the fandoms are bound and the user's words change them).

A universe may be bound to entries of a catalogue (`UniverseCatalogue`; `catalogue-wikidata`;
`fandom_entry`): a Wikidata item — a franchise, a series, a work — kept as the catalogue gave it
(`universe`), with every name and alias in the user's languages (`recommend4me.languages`). The
characters of its entries — whatever the catalogue says is in it or in its parts, the way the fans
write them among them — are read and given to its works only when the user said so of the universe
(`fandom.characters`, "read the characters"): on for the 28 universes bound to their entries before
the fandoms were normalized, off for every universe made since of a site's fandom. An entry made a
universe by the search of the catalogue has its characters read. The catalogue is asked only when
the user asks — a search by the button with the variants shown, an entry bound, a universe's
characters turned on or read again from the universes screen or a site's page — and what it
answered is kept. An entry bound to no universe any more is gone with its characters.

A work of a site with fandoms has the universe of every fandom its site gives it, and every universe
above it, as a draft (`LayerDrafting`): a value the work has, nothing to confirm; the user's answer on
the work — the site's fandom wrong, one it forgot — stands over it. A work its site says is of no
universe (the fandom "originals") has none of its fandoms. A source without fandoms (author.today)
has its works' universes offered by the model, to be confirmed, as before.

Every work of the type has five facets of the application's own beside the source's
(`UniverseFacets`), their values kept as the user's corrections and worked out by the model as
any facet's:

- `kind` — fan fiction, realism (our world as it is), an alternative history or an original
  work, the last three of no universe: the site's when it says (ficbook and fanfics.me by the
  fandom "originals" — `SourceSchema.kindFrom`, shown above; author.today by its genre "Фанфик", one of
  the site's tags), suggested otherwise;
- `universe` — the universes the work is fan fiction of, one or several: of its sites' fandoms
  (drafts, above), suggested from its tags and texts to a work not known to be original, to be
  confirmed;
- `characters` — its main characters: of the characters of its universes — confirmed, or given
  by the model more likely than not and not rejected, none but the confirmed once the user said
  there are no others — and the original characters (ОЖП, ОМП), every name of a character held against the line the site writes
  them in (`SourceSchema.universeLine`, shown above), the tags, the description and the chapters;
- `secondary-characters` — its secondary characters, worked out and learnt as the main ones;
- `pairings` — pairs of the work's main characters, one whatever their order.

A character is a work's main one or its secondary one, never both (`UniverseFacet.leftOut`): what
the work has of one facet is not given, offered or shown — even crossed out — in the other. The
user's word comes first: a character said to be secondary leaves the main ones, and one said to
be either is taken from the other answer. Of the model's word alone the main characters, worked
out first, come first. The original characters are many persons and may be in both.

The characters and the pairings are given to the work at once when more likely than not; the
site's own strings stay its facets as they are, for the model and as what the model reads. The
universes, the characters of either kind and the pairings can each be said to be all named: the
work keeps its own and is offered no other.

### Statements of the catalogue and drafts

A source plugin names the catalogue's statements it wants of the universes and their characters
(`Source.catalogueProperties`: Archive of Our Own asks Wikidata for P8419, the entity's tag on
AO3). They are asked with every universe added or refreshed — of the universe, of the works,
series and franchises within it, and of every character — and kept in the dictionary
(`universe_property`, `universe_character_property`); a universe asked for other statements than
the plugins want now is asked again on start.

From them a source drafts values of the layer (`Source.draft`): AO3 matches its fandoms to
universes and the names of its relationship tags ("A/B", "A & B") to characters by the tag, a
romantic pair of two matched characters to a pairing — an entry's statement finds the universes bound
to the entry; the application adds the universe of every drafted character the dictionary has in one
universe whose characters are read alone (`LayerDrafting`). A name matching
nothing, or several entries, drafts nothing. Every matched character is a main one: the user moves
it to the secondary ones.

The drafts are derived data, neither the site's nor the user's, and kept nowhere (2026-11-23): made
of the work's values on its sites and the dictionary where the work is read — its card, a portion of
the models' pass, the writing of its document of the list — the dictionary read by the keys the works
have and the statements the source asks, never whole (`WorkDrafts`, `DraftDictionary`); the index of
the list holds what they made. A write that changes what a universe or a tag means to its works — a
binding, a merge, a universe put under another, an entry read anew — or binds a site's value anew
dates it (`dictionary_change`, `DictionaryChanges`), naming no work; the writing of the index takes the
changes after the last it took and writes anew the documents holding one of the values — found in the
index itself — those whose drafts made otherwise dated so for the stages after it (`ListDictionary`).
A draft is a value the work has (`Chance.draftedFrom`): shown with
the site's value it came from instead of a chance, found by the search, read by the model as a
value of the work. The user's answers come first — a draft taken away stays taken away, whatever
it is made of again. The model learns from a draft the user has not confirmed with less weight
(`FacetData.DRAFT_WEIGHT`, `SuggestionTask.draftWeight`): it is likely, but no one's word.

## Languages of the interface

The web interface speaks English, Russian and Serbian (Latin script), through i18next and
react-i18next (`frontend/src/i18n`). The language is the one the user chose in the menu (kept in
the browser's local storage), else the first of the browser's languages the interface has, else
English. Numbers, dates and durations are formatted in it (`i18n/format.ts`).

The words are split by the section of the application they belong to: every folder of the
frontend with words of its own keeps them beside its code, `<section>-i18n.<language>.json` —
`tag/tag-i18n.en.json`, `universe/universe-i18n.ru.json` — one i18next namespace a section; the
words every section shares are the namespace `common`, in `i18n/`. Keys are flat,
`"Component.purpose"`; a word of a count has a key of each plural form of its language
(`works_one`, `works_few`, `works_many`, `works_other`) and is asked with `{ count }`.

The keys are typed: the English files are the shape `t` is checked against (`i18n/i18next.d.ts`),
so a key that does not exist does not compile; and `i18n/resources.ts` checks every other language
against the English — the same keys, no more and no fewer, every word of a count in each plural
form of that language — so a translation missing or left over does not compile either. A new
section's files are registered there.

### The server sends keys, never words

The server words nothing for the user. Where the API has something to show that is not data, it
sends the key of its words — `labelKey`, `nameKey`, `messageKey`, a `Problem`'s `code` with its
`params` — and the interface translates it. A key is `"<namespace>:<key>"`, the key within the
namespace flat and without ":" (`I18nKeys` of the plugin API builds them):

- `standard` — the application's own words, every content type's alike. The web interface keeps
  them (`frontend/src/standard/standard-i18n.<language>.json`, typed as every section's) and lays
  them out for the browser extension too (`/i18n/standard/<language>.json`);
- a plugin's id — a source's (`ficbook`), a scorer's (`pairwise`), a catalogue's (`wikidata`) —
  the words of that plugin alone. The plugin carries them among its resources,
  `i18n/<id>.<language>.json`, and the application lays them out as they are,
  `/i18n/<id>/<language>.json`; the interfaces read the namespaces of the plugins they show. A
  plugin's test checks its files with the plugin API's fixture `Translations`: every language,
  exactly the same keys, and of a source every key it needs (`I18nKeys.requiredKeys`).

The keys of a plugin's namespace: `title`; of a facet, number, text, signal of its own (not a
standard one) `facet.<key>`, `number.<key>`, `text.<key>`, `signal.<key>`, a named value of a
signal `signal.<key>.value.<value>`; `facet.<key>.none` of a facet whose works without a value are a
line of the filter; `setting.<key>` and `setting.<key>.hint`; `reviews` and `parts` — what it calls
the reviews and the parts of a text it keeps; `job.<key>` — a message its jobs leave.

The keys of the standard namespace the server sends:

- `type.<type>.title`, `type.<type>.verb`, `type.<type>.grade.<1..5>` — of `books`, `games`;
- `facet.<key>` of every standard facet and of the application's layer — `author`, `status`,
  `tag`, `fandom`, `pairing`, `tags`, `kind`, `universe`, `characters`, `secondary-characters`,
  `pairings` — and `facet.<key>.value.<value>` of a standard value: `status` — `finished`,
  `in-progress`, `frozen`; `kind` — `fanfiction`, `realism`, `alternative-history`, `original`;
- `number.<key>` of every standard number — `parts`, `words`, `likes`, `views`;
- `signal.<key>` and `signal.<key>.value.<value>` of every standard signal — `read` and `liked`
  of the value `yes`; `shelf` of `reading`, `saved`, `finished`, `disliked`, `purchased`;
- `character.oc-female`, `character.oc-male` — the original characters;
- `text.title` — the title among the texts the search and the model read; `part.untitled` — a
  chapter without a title, `{{n}}` its number;
- `model.previous` (the grade of the previous version), `model.source`, `model.pictures`,
  `model.reviews`, `model.cover`, `model.screens`, `model.screensOneByOne`,
  `model.reviewsOneByOne`, `model.textFragments`, `model.likePictures`, `model.likeReviews` — the
  parts and blocks of the model's input;
- `error.<code>` — a request refused, its `params` named in the words: `noField` (field),
  `siteField` (field — the key of its label), `noValue`, `noSuchWork`, `joinTypes`, `joinSelf`,
  `badRange` (range), `noDownloads`, `siteSilent` (error), `refreshFailed`, `badGrade` (min, max),
  `badMark`, `badAnswer`, `noUniverses` (type), `notInCatalogue` (universe), `catalogueSilent`
  (error), `noSuchUniverse`, `noSuchType`, `noSiteTags`, `tagSelf`, `tagAbove`, `tagBelow`,
  `noSetting` (setting), `noScorer` (scorer), `accessRefused`, `noExtensionFolder`.

What the model and the search read keeps one language, Russian, whatever the user reads — the
names of the standard values kept with the works (`StandardValue.modelName`), the original
characters — their texts are encoded once; the user is shown them by their keys. The data itself —
the works' texts, the sites' tags, the catalogue's names — is shown as it is.

A number every site of a content type tells alike is a standard number (`StandardNumber`): the
chapters of a book (`Books.PARTS`); of any type the likes — likes, kudos —, the views — views,
hits — and the words (`StandardNumbers`). A source declares its own number as the standard one
under its own key; the sources' numbers of one standard are one filter, one sorting and one
feature of the model.

The list of works is sorted by one choice of every order alike: the prediction, the date of the
update, the date the application first saw the work, the surprise, and every number the list can
be sorted by — the standard ones first.

## Requests answered slowly

Every request of the browser is timed from its arrival to its answer (`request/SlowRequests`, a
filter after the guard of access); one that took `recommend4me.slow-request` (500 ms) or longer is
written to `slow-requests.log` in the data folder, and to `app.log`: its method, its address with
the query, the status, the milliseconds, and how many other requests were being served when it
came — those it may have waited behind.

## The site as the interface

On the pages the sources want, the extension also shows the work as the application knows it,
so the site itself is an interface of the application (`GET /api/pages?url=`): a panel with the
prediction, the grade buttons, what the prediction rests on and the tags; on the site's own tag
elements the model's chance and the user's ✓ and ✕, the user's and the suggested tags after
them; the worked out pairings and characters in lines of their own under the site's; "+ / −" on
every review and picture of the work; the prediction and the grade on every card of a list
(`POST /api/pages/cards`). A source says where these go by `PageDecor` — CSS selectors of its
pages — and which work a page or a card's link is of by `Source.itemIdOf`. Everything is changed
through the same calls as the application's own interface.

## Browser tracking

The extension asks the application which addresses it wants (`GET /api/capture/patterns`) and
sends every such page the user opens — its rendered DOM, without what the extension itself put
into it — to `POST /api/capture`. The application
keeps the page (`captured_page`), hands it to the source whose patterns match, and re-parses kept
pages when a parser's version grows.
