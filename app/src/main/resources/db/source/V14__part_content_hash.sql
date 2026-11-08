-- The hash of a part's text (SHA-256 of its UTF-8, in hex), written with the text: what the search's fingerprint of a
-- work is made of, the text not read. None of a part kept before: hashed the first time it is asked for
ALTER TABLE part ADD COLUMN content_hash VARCHAR(64);
