-- The vectors of the texts read whole, no windows (owner, 2026-11-27: a description is one vector): the table they are
-- copied into by V44, of no columns of the windows, and how far the copy got; V45 puts it in place.
CREATE TABLE text_vector_whole(
    item_id BIGINT NOT NULL,
    text_key CHARACTER VARYING(100) NOT NULL,
    encoder CHARACTER VARYING(100) NOT NULL,
    text_hash CHARACTER VARYING(64) NOT NULL,
    vec BINARY VARYING NOT NULL,
    pieces CHARACTER VARYING(100),
    full_text CHARACTER VARYING(100)
);
CREATE TABLE text_vector_copied(id INTEGER PRIMARY KEY, upto BIGINT NOT NULL);
