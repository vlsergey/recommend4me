# The search

How a line typed in the search finds works, and where in a work's text the place it means is. The index of the words
and the search by them are the plugin `plugins/search-lucene` (`SearchProvider`); the search by meaning, the order of
what is found and the place shown are the application's (`app/.../meaning`, `app/.../search`).

## The index of the words

One Lucene index of a content type (`types/<type>/search-index/`, `LuceneIndex`): a document of every item, a field of
every text and value of the item (`SearchDocuments`, `WorkTexts`), its weight in the field's name
(`<marks>.<weight in hundredths>.<key>`). Every text is stemmed once, in its own language (`TextLanguages`,
`Stemmers`), every word at its place — no stop word dropped, a function word may be of a name.

The index keeps the fingerprint of what every item's document was made of and is written again only of the items whose
fingerprint changed (`DocumentPrints`, `IndexKept`, `SourceCatchUp`); the documents are brought in by the stage of the
search of the tasks over the works (`WorkStage.TEXTS`, `indexWorkTexts`). It knows nothing of the models, nor of the
vectors: no passage and no vector is in it (its format 4).

## The full-text vectors of the windows

What a work is found by by meaning (`FullTextVectors`): of every window of every chapter and of every text searched by
meaning (`TextDef.searchByMeaning`) — a window the encoder cut the text into (`TextEncoder.encodeWindows`,
`windowSpans`), a short text one window, its vector the text's — its vector less the type's centre, made of the sign of
every number: a bit a number, 128 bytes a window of bge-m3. Kept in the store of the parts of the source
(`PartStore.fullText`, the column family `fullText`, RocksDB's own files of tens of megabytes, not apart from the keys),
of every text one value: its windows' characters and full-text vectors (`FullTextVectorRepository`); the key of the
centre they were made by kept with the part (`part.full_text`) and with the text's vector (`text_vector.full_text`).

- **Made by the stages that make the windows**, of the windows of the run: the chapters' by the stage of the chapters
  (`PartVectors`, `ChapterEncoding`), the texts' by the stage of the texts (`TextVectors`, `TextWindows`); of a text
  whose windows are made and whose full-text vectors are not, of the vectors kept, no model run.
- **The centre** (`FullTextCentres`, the model database `full_text_centre`): the mean of the windows the encoder makes
  of 300 chapters and 2 000 texts searched by meaning of every source drawn at random — learnt once of an encoder of
  windows, at a start or as the texts of a type are written (`fitFullTextCentre`); learnt anew, every window's full-text
  vector is made anew.
- **Why these vectors, and why out of Lucene** (owner, 2026-11-25): the windows' vectors in Lucene's graph (HNSW) were float
  vectors of 4 KB, read at random on the disk of the data a step of the graph — minutes a search, and the whole of
  ficbook's 85 million windows 350 GB in the index beside the store's own copy. The full-text vectors are read whole, one file after
  another, what the disk reads well — 11 GB of the whole of ficbook, seconds when the system holds them. Measured on 85
  retellings among 129 250 windows: the window of the answer among the first 1 000 79 times, as by the vectors
  themselves; its chapter among the first 300 78 times against 79. Of the signs of numbers not less the centre 71
  times; of the signs of the 256 leading directions (32 bytes) 68; of a product quantization of 64 bytes 67.

## A search

1. **The line** is read in the interface's language unless written in letters of another (`LineLanguages`); its words,
   stemmed so, without the function words (`Stemmers.stopWords`).
2. **By meaning** (`FullTextNearest`, given the plugin by `SearchContext.nearest`): the line's vector (`TextVectors.query`,
   one short line on the thread of the request), moved by the correction the user's judgments of snippets taught
   (`QueryShift`: q·p + (q·a)(b·p)), less the centre, against the full-text vector of every window of the type, read
   whole — every number of the line with the sign of the window's —: the 8 000 windows of the best scores, an item counting with its
   best; its closeness (`MeaningSearch.closeness`, the plugin's) the score on the scale of the line's own nearest.
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
  blob files): of every chapter by its part's id, of every text searched by meaning by `text:<key>`, its pieces' codes
  in order (`PieceRepository`); the key of the projection they were made by kept with the part
  (`part.pieces`) and with the text's vector (`text_vector.pieces`). Where every piece lies in the text is not kept:
  the text is read with them and cut into pieces again (the tokenizer, milliseconds).
- **Made by the stages that make the windows**: the chapters' by the stage of the chapters (`PartVectors`,
  `ChapterEncoding`, `encodeSiteItemChapters`), the texts' searched by meaning by the stage of the texts (`TextVectors`,
  `TextWindows`, `encodeSiteItemTexts`) — a text whose vector is made and whose pieces are not has its pieces made
  alone, nothing the models or the index read told of as changed.
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

What is not done by the pieces: finding the candidates. The full-text vectors of the windows do it (above); the centroids of the
pieces alone were measured to lose half the answers (owner, 2026-11-25: not to be tested further).

## What the space costs

Of the ficbook dump (4.7 million chapters, 38 billion characters, about 11 billion pieces, about 85 million windows):
the pieces 32 bytes a piece, about 350 GB; the windows' vectors 2 KB a window of 127 new pieces, about 177 GB; the
windows' full-text vectors 136 bytes a window with its characters, about 11 GB — what every search reads; the paragraphs' vectors of
the tags by text (`Paragraphs`) as before. The index of the words: what the texts' words are, no vector.
