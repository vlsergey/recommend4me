# The search

How a line typed in the search finds works, and where in a work's text the place it means is. The index of the words
and the search by them are the plugin `plugins/search-lucene` (`SearchProvider`); the search by meaning, the order of
what is found and the place shown are the application's (`app/.../meaning`, `app/.../search`).

## The index of the words

One Lucene index of a content type (`types/<type>/search-index/`, `LuceneIndex`): a document of every item, a field of
every text and value of the item (`SearchDocuments`, `WorkTexts`), its weight in the field's name
(`<marks>.<weight in hundredths>.<key>`). Every text is stemmed once, in its own language (`TextLanguages`,
`Stemmers`), every word at its place — no stop word dropped, a function word may be of a name. A text's language is the
one its work's values say (`SearchField.language`: the description's language of a text, the work's one language of a
chapter — of the index's documents the site's and the user's word, of a card's the model's too, more likely than not as
the list keeps it); of none said, a long text tells its own of its first 1 000 characters, a short one is the
description's.

The index keeps the fingerprint of what every item's document was made of and is written again only of the items whose
fingerprint changed (`DocumentPrints`, `IndexKept`, `SourceCatchUp`); the documents are brought in by the stage of the
search of the tasks over the works (`WorkStage.TEXTS`, `indexWorkTexts`). It knows nothing of the models, nor of the
vectors: no passage and no vector is in it (its format 4).

## The full-text vectors of the windows

What a work is found by by meaning (`FullTextVectors`): of every window of every chapter and of every text searched by
meaning (`TextDef.searchByMeaning`) — a window the encoder cut the text into (`TextEncoder.encodeWindows`,
`windowSpans`), a short text one window, its vector the text's — made as RaBitQ makes its codes (Gao and Long, SIGMOD
2024; Lucene's and Elasticsearch's BBQ): the vector less the type's centre, turned by the type's random rotation, of
every number whether it is above nought — a bit a number, 128 bytes a window of bge-m3. Kept in the store of the parts of the source
(`PartStore.fullText`, the column family `fullText`, RocksDB's own files of tens of megabytes, not apart from the keys),
of every text one value: the characters of the text and its one full-text vector (`FullTextVectorRepository`); the key of the
centre they were made by kept with the part (`part.full_text`) and with the text's vector (`text_vector.full_text`).

- **Made by the stages that make the windows**, of the windows of the run: the chapters' by the stage of the chapters
  (`PartVectors`, `ChapterEncoding`); a text's — read whole, one window over the whole text, no windows of its own
  (owner, 2026-11-27) — by the stage of the texts (`TextVectors`, `SearchedTexts`), of its vector; of a text whose vector
  is made and whose full-text vector is not, of the vector kept, no model run.
- **The centre and the rotation** (`FullTextCentres`, the model database `full_text_centre`): the mean of the windows
  the encoder makes of 300 chapters and 2 000 texts searched by meaning of every source drawn at random, and a random
  orthogonal matrix (Gram-Schmidt of numbers of a normal law) — learnt once of an encoder of
  windows, at a start or as the texts of a type are written (`fitFullTextCentre`) — and learnt anew as a search reads
  twice the windows the type had as it was learnt (`FullTextCentres.read`): a type begun of a few works, or a site of
  another language added, has its windows told apart by a centre of its own. Learnt anew, every window's full-text
  vector is made anew, of the windows' vectors kept, no model run. (The clusters to come — SPANN — are to replace the
  one centre: a window coded by its cluster's, a cluster grown split and coded anew alone.)
- **Why these vectors, and why out of Lucene** (owner, 2026-11-25): the windows' vectors in Lucene's graph (HNSW) were float
  vectors of 4 KB, read at random on the disk of the data a step of the graph — minutes a search, and the whole of
  ficbook's 85 million windows 350 GB in the index beside the store's own copy. The full-text vectors are read whole, one file after
  another, what the disk reads well — 11 GB of the whole of ficbook, seconds when the system holds them. Measured on 85
  retellings among 129 250 windows: the window of the answer among the first 1 000 79 times, as by the vectors
  themselves; its chapter among the first 100 70 times, among the first 300 81 — of the vectors themselves 67 and 79.
  Not turned 66 and 78; turned by a Hadamard transform of random signs 67 and 80; not less the centre 63 and 77; of the
  256 leading directions (32 bytes) 59 and 73; of a product quantization of 64 bytes 51 and 72. The line in 4 bits a
  number loses nothing against its numbers whole.

## A search

1. **The line** is read in the interface's language unless written in letters of another (`LineLanguages`); its words,
   stemmed so, without the function words (`Stemmers.stopWords`).
2. **By meaning** (`FullTextNearest`, given the plugin by `SearchContext.nearest`): the line's vector (`TextVectors.query`,
   one short line on the thread of the request), moved by the correction the user's judgments of snippets taught
   (`QueryShift`: q·p + (q·a)(b·p)), less the centre, turned, in 4 bits a number — 16 levels evenly from its least to
   its most —, against the full-text vector of every window of the type, read whole: of every bit of the line's levels
   the bits it shares with the window's, counted (`popcount`) — the 8 000 windows of the best scores, an item counting with its
   best; its closeness (`MeaningSearch.closeness`, the plugin's) the score on the scale of the line's own nearest.
3. **By words** (`WordSearch`): the words in any of their forms or as beginnings of words, in every field, weighed by the
   field; the whole line in a naming field (title, author) a bonus.
4. **The reranker** (`SearchReranking`): bge-reranker-v2-m3 reads every work found together with the line — a work
   with windows among the first `SearchReranking.WINDOWS` (1 000) of the 8 000 nearest by meaning by those windows,
   each apart, the work's score the best of them (their texts of the store of the parts and the corrected texts,
   `WindowTexts`, no document made, the chapters of every work of a source read by one MultiGet in the order of the
   store's keys); any other work by its document, its title and passage nearest first. The windows are read a portion
   at a time, the nearest first (`SearchReranking.PORTIONS`: the first 100, to 300, to 1 000), the documents with the
   first: every portion but the last a version of the order — the works read by their scores, then the rest as found.
   About 2.7 ms a window on the card; the chapters a window is cut of are read of the disk of the data, a seek a
   chapter, and they are most of the wait (the log tells the two apart). What was read for a line is kept by what it
   was read of (`SearchScoreRepository`): of a work read by its windows, the fingerprint of its document — the hashes
   of its chapters' texts in it — and where every window is, the line searched again reading nothing anew. Measured on
   85 retellings among the 7.5 million windows of 48 966 works (2026-11-25): the work of the scene first 4 times by the
   windows' vectors and 26 by the first 1 000 windows read so; among the first ten 14 and 38 (300 windows: 33; 2 000:
   40; 5 000: 43); a fusion of the two orders by their ranks is worse than the reranker's alone. Qwen3-Embedding-0.6B
   in place of bge-m3, measured alike on 791 134 windows, put the work among the first ten 28 times against bge-m3's 40.
5. **The pieces** (below, `SearchPieces`): of the items found by meaning, the first `SearchPieces.READ` (300) by the
   order so far — the reranker's —, the line's pieces against the pieces of the text of the passage nearest — the best
   span of it, its score the item's feature `pieces`, the span its place (`SearchResult.places`). After the reranker:
   the pieces reorder nothing but by the weights learnt, they tell where in a text the line is said, and the order the
   user waits for comes first.
6. **The order** (`SearchWeights`): a weighed sum of the features — the meaning, the words, the line in the name, the
   pieces, the reranker's score — the weights learnt of the user's judgments, none below half its share; the first of
   every way of finding at the top; of none learnt, the reranker's order.
7. **What is shown** (`Search.matches`): where the words stand together; else the best span of the pieces, from the
   beginning of the word of its first piece to the end of the word of its last, the words of the pieces that matched
   the line's best marked (`PlaceShown`); else the passage nearest by meaning, whole. The documents of the cards are
   read once (`SearchDocuments.shown`), the values the list keeps the model gave them among their values — no model
   run —, of their chapters those of the passage nearest and of the place of the pieces alone, every other named; a
   chapter named is read only where the index holds the line's words of the work in its chapters, eight at a time till
   one has them (`WorkSnippet`, `SearchContext.texts`), and for words apart only of a work found by no passage.
8. **In versions** (`SearchProgress`, `listItemVersions`): the first page of a search is answered as lines of JSON,
   a version of the page each, written on the thread of the request as a step ends — the works found by words and by
   meaning (2), every portion of the reranker's but the last, the reranker's whole (4), and last the page with the
   pieces read and the order learnt —, each but the last with the step at work after it (`SearchRefinement`). The card
   of a work is made once a request, at the first version that shows it, made again only when its place by the pieces
   came (`ItemList.Paging`). The one who asked gone — another line typed —, the next line fails to be sent and the
   search ends. The interface shows each as it comes (docs/ui.md 11).

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
  `SearchedTexts`, `encodeSiteItemTexts`), a text read whole in one pass that makes its vector and its pieces together
  (`PieceEncoder.encodeWhole`) — a text whose vector is made and whose pieces are not has its pieces made alone, nothing
  the models or the index read told of as changed.
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
