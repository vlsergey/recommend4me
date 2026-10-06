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
| `libs/onnx` | ONNX Runtime sessions on the CPU or CUDA, shared by the encoder plugins. |
| `app` | The application: storage, background work, training, the HTTP API, the web interface, the plugin loader. |
| `frontend` | The web interface (React), built into the application's static resources. |
| `plugins/encoder-e5` | Text encoder: multilingual-e5-small. |
| `plugins/encoder-siglip2` | Picture encoder: SigLIP2 NaFlex. |
| `plugins/scorer-pairwise` | Pairwise logistic ranking (RankNet with a line), C chosen by cross-validation. |
| `plugins/scorer-knn` | k nearest rated neighbours by cosine — a baseline. |
| `plugins/search-lucene` | Search by words and by meaning over one Lucene index per content type. |
| `plugins/suggester-tags` | The tags a work should have, from its text, its other tags and every other work. |
| `plugins/source-author-today` | author.today, browser tracking only. |
| `plugins/source-ficbook` | ficbook.net, browser tracking only. |
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

Every source has up to three modes:

- **scrape all** — the whole catalogue, resumable;
- **updates** — what is new since the last time;
- **browser tracking** — the pages the user opens in Firefox are sent by the extension and parsed;
  nothing is requested from the site by the application.

author.today and ficbook have only the third: their catalogues are never scraped whole.

## Storage

H2, one file per level of data, under the data folder (`%LOCALAPPDATA%
ecommend4me` on
Windows, `~/.local/share/recommend4me` elsewhere; `recommend4me.data-dir`):

```
app.mv.db                          settings
sources/<source>/source.mv.db      what the site says: works, facets, numbers, texts, pictures,
                                   reviews, text parts, captured pages — and their vectors, a
                                   compressed form of the same scrape
sources/<source>/corrections.mv.db the user's corrections: tags added and removed, fields
                                   overridden, duplicates merged within the source
sources/<source>/ratings.mv.db     the user's own data: grades, marks on pictures and reviews,
                                   words on matches, the user's own actions seen on the site
sources/<source>/images/           picture files
types/<type>/corrections.mv.db     duplicates merged across the sources of a type
types/<type>/model.mv.db           the trained model, the predictions, the likeness to the marks,
                                   the directions of the set embeddings, the suggested tags — all
                                   recomputable
types/<type>/search-index/         the search index
models/                            ONNX graphs of the encoders
backups/                           corrections and ratings, zipped on every start
```

Every file has its own connection pool and its own Flyway history. H2 does not join across
files, so whatever reads several of them — the list of works, filtered by corrected tags, sorted
by prediction, without the rated — reads each file once, in batch, only the columns it needs,
and puts the answer together in memory for that one request. Nothing is kept between requests.

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
- **reviews** — other readers' opinions: f95zone reviews, author.today рецензии, ficbook
  comments; each encoded whole.
- **parts** — the text of a book's chapters, cut into windows; a vector for every window.

A source may keep tables of its own in its source database (its own Flyway history).

## Corrections

The interface and the model read *effective* data: the source's, with the user's corrections on
top — tags added and removed, fields overridden. Merged items form a cluster: one card, one
grade; the cluster's members never fall into different folds of cross-validation and are never
paired with each other.

## The model

For every content type:

1. one pass over every source of the type builds the input of every item: the vectors of its
   texts, pictures and sets, its facets, numbers, the user's site signals and the grade of an
   earlier version;
2. the active scorer (`recommend4me.scorer.<type>` setting, the first available by default) is
   measured on 5-fold cross-validation over works for each of its candidate parameters and the
   one-standard-error choice is made;
3. the grades are placed on 0..10 from the out-of-fold scores (the ladder);
4. every item is scored; the explanation switches feature groups off one at a time.

Scorers implement `Scorer`; anything that maps a row of features to a number fits.

## Suggested tags

Authors tag their books carelessly or not at all. For every facet a source marks `suggest`
(the tags of author.today and ficbook) the application works out the values a work should have
and shows them to confirm or reject; it also marks the site's values that do not fit the work.
The work is the `FacetSuggester` plugin's (`suggester-tags`): it is handed every item of the
source — the mean direction of its texts' vectors, its values with the user's corrections, the
values the user confirmed and rejected — and every value with the vector of its name as a query.
`suggester-tags` weighs three witnesses by a logistic regression learnt from the catalogue: the
share of the nearest texts having the value, how much nearer the text is to the value's name than
texts usually are, and how much more often the value goes with the work's other values than by
chance. Every count leaves the work itself out, so a site's value nothing else supports comes out
unlikely.

A confirmation is a correction adding the value, a rejection one taking it away: the model, the
filters and the search see them at once. The suggestions are kept in the model database with
what each was made of (a fingerprint of the work's values, the user's answers and its texts),
made again a few seconds after the work changes, at once for a work that never had any; the
suggester is fitted again when the user's answers have grown by ten or a tenth, and twice a day.

## The site as the interface

On the pages the sources want, the extension also shows the work as the application knows it,
so the site itself is an interface of the application (`GET /api/pages?url=`): a panel with the
prediction, the grade buttons, what the prediction rests on and the tags; on the site's own tag
elements a button to take each away and the user's and the suggested tags after them; "+ / −" on
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
