# The search

How a line typed in the search finds works, and where in a work's text the place it means is. The index and the
search by words and by meaning are the plugin `plugins/search-lucene` (`SearchProvider`); the order of what is found
and the place shown are the application's (`app/.../search`).

## The index

One Lucene index of a content type (`types/<type>/search-index/`, `LuceneIndex`):

- **a document of every item**: a field of every text and value of the item (`SearchDocuments`, `WorkTexts`), its weight
  in the field's name (`<marks>.<weight in hundredths>.<key>`). Every text is stemmed once, in its own language
  (`TextLanguages`, `Stemmers`), every word at its place — no stop word dropped, a function word may be of a name;
- **a document of every passage of the item's texts** searched by meaning: a window of a text the encoder cut it into
  (`TextEncoder.encodeWindows`, `windowSpans`) — of a long text field, and of every chapter — its vector in the one
  field `VECTOR` (HNSW). A short text is one passage, its vector the text's.

The index keeps the fingerprint of what every item's document was made of and is written again only of the items whose
fingerprint changed (`DocumentPrints`, `IndexKept`, `SourceCatchUp`); the documents are brought in by the stage of the
search of the tasks over the works (`WorkStage.TEXTS`, `indexWorkTexts`). It knows nothing of the models.

## A search

1. **The line** is read in the interface's language unless written in letters of another (`LineLanguages`); its words,
   stemmed so, without the function words (`Stemmers.stopWords`).
2. **By meaning** (`MeaningSearch`): the line's vector (`TextVectors.query`, one short line on the thread of the request),
   moved by the correction the user's judgments of snippets taught (`QueryShift`: q·p + (q·a)(b·p)), against every
   passage's vector — the 8 000 nearest passages, an item counting with its nearest; its closeness
   (`MeaningSearch.closeness`) the cosine on the scale of the line's own nearest.
3. **By words** (`WordSearch`): the words in any of their forms or as beginnings of words, in every field, weighed by the
   field; the whole line in a naming field (title, author) a bonus.
4. **The pieces** (below, `SearchPieces`): of the items found by meaning, the first `SearchPieces.READ` (300) by the
   order so far, the line's pieces against the pieces of the text of the passage nearest — the best span of it, its
   score the item's feature `pieces`, the span its place (`SearchResult.places`).
5. **The order** (`SearchWeights`): a weighed sum of the features — the meaning, the words, the line in the name, the
   pieces, the reranker's score (`SearchReranking`, the reranker reading the title and the passage nearest) — the
   weights learnt of the user's judgments, none below half its share; the first of every way of finding at the top.
6. **What is shown** (`Search.matches`): where the words stand together; else the best span of the pieces, from the
   beginning of the word of its first piece to the end of the word of its last, the words of the pieces that matched
   the line's best marked (`PlaceShown`); else the passage nearest by meaning, whole.

## The pieces

A vector of every piece (token) of a text, so a meaning is found where it is said — a phrase in a long chapter — rather
than by one vector a window is squeezed into (owner, 2026-11-25). Measured on 85 retellings of a work's paragraphs
among 3 002 chapters (128 200 passages): the passage of the answer first 22 times of 85 by the windows' vectors, 29 by
the windows' nearest 300 passages read again by their pieces; among the first ten 40 and 49.

- **Made with the windows**, by the same run of the encoder (`TextEncoder.pieces`): of every window the vector of its
  first piece (the window's, as before) and of every piece bge-m3's ColBERT head (`colbert_linear`, 1024 → 1024, its
  file beside the model's) projected to 128 numbers (`PieceProjection`), in the graph on the card — a piece of a window
  taken of the window where it stands farthest from an edge, so it sees its text on both sides.
- **Kept as codes** (`PieceCodebook`): of every number of the 128 its bucket of four, 2 bits — 32 bytes a piece — by
  cuts learnt with the projection, the bucket's mean read back; nothing of a centroid, so nothing kept changes when the
  index of the pieces does. In the store of the parts of the source (`PartStore.pieces`, the column family `pieces`,
  blob files): of every chapter by its part's id, of every text field of an item and its title by `text:<key>`, its
  pieces' codes in order (`PieceRepository`); the key of the projection they were made by kept with the part
  (`part.pieces`) and with the text's vector (`text_vector.pieces`). Where every piece lies in the text is not kept:
  the text is read with them and cut into pieces again (the tokenizer, milliseconds).
- **Made by the stages that make the windows**: the chapters' by the stage of the chapters (`PartVectors`,
  `encodeSiteItemChapters`), the text fields' by the stage of the texts (`TextVectors`, `TextWindows`,
  `encodeSiteItemTexts`) — a text whose vector is made and whose pieces are not has its pieces made alone, nothing the
  models or the index read told of as changed.
- **The projection and the cuts** (`PieceProjection`, the model database `piece_projection`) are learnt once of a sample
  of the type's chapters — the leading 128 directions of the head's vectors of 100 000 pieces, then the quartiles of
  every number — when the encoder has none (`fitPieceProjection`): learnt of other works they lose 0.015 of the MRR
  against the work's own. Of every source with chapters an equal share, 600 of its chapters drawn at most; nothing is
  learnt of fewer than 10 000 pieces. Learnt anew (`PieceProjectionFitEvent`), every text's pieces are made anew: the
  stages of the chapters and of the texts are set at nought, the key of the projection being a part of what they tell
  they were made by.
- **A search reads the pieces of a few texts**: of the first `SearchPieces.READ` items found by meaning by their order
  before, the text of the passage nearest — the store read a text a read, about 10 ms on the disk of the data, 3 s at
  most; the line's pieces (exact, not coded) against every piece of the text: of every span of 8 × the line's pieces
  (`PieceSpans.SPAN`), the mean over the line's pieces of the best cosine of a piece of the span (MaxSim), the best span
  the text's. The work's feature `pieces` is that span's score; the span is what is shown, the line's pieces' best
  matches marked.

What is not done by the pieces: finding the candidates. The nearest windows' vectors do it (above); the centroids of the
pieces alone were measured to lose half the answers (owner, 2026-11-25: not to be tested further).

## What the space costs

Of the ficbook dump (4.7 million chapters, 38 billion characters, about 11 billion pieces): the pieces 32 bytes a piece,
about 350 GB; the windows' vectors 2 KB a window of 127 new pieces, about 177 GB; the paragraphs' vectors of the tags
by text (`Paragraphs`) as before.
