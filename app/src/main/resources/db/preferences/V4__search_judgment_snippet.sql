-- The user's word is on the piece of a work's text a search showed for the line — its snippet — not on the work
-- (user, 2026-11-13): "this is it" of one passage of a work and "this is not it" of another. The snippet as it was
-- shown: the field of the work's document it is of, its text, and the text's hash its key is made of. The words on
-- works said before are gone: the user judges anew
DROP TABLE search_judgment;
CREATE TABLE search_judgment (
    query        VARCHAR(500)             NOT NULL,
    source       VARCHAR(100)             NOT NULL,
    item_id      VARCHAR(200)             NOT NULL,
    field        VARCHAR(200)             NOT NULL,
    snippet_hash BIGINT                   NOT NULL,
    snippet      VARCHAR(100000)          NOT NULL,
    fit          VARCHAR(10)              NOT NULL,
    judged_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (query, source, item_id, field, snippet_hash)
);
