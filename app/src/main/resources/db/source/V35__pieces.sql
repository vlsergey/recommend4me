-- The key of the projection the codes of the pieces of a chapter and of a text field were made by (piece.PieceProjections),
-- the codes in the store of the parts; null of none made: the stages of the chapters and of the texts make them
ALTER TABLE part ADD COLUMN pieces VARCHAR(100);
ALTER TABLE text_vector ADD COLUMN pieces VARCHAR(100);
