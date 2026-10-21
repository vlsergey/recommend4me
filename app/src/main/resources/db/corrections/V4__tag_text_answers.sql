-- The user's word on a tag offered by a paragraph of the work's text: YES — the work has the tag by
-- it (the tag answered on the work beside); ENOUGH_NOT — the paragraph is of the tag, not enough to
-- give it the work: offered so no more, nothing learnt; WRONG — the text and the tag are of
-- different things. With the closeness of the two as it was then: what the model of the closeness
-- learns from
CREATE TABLE tag_text_answer (
    item_id      VARCHAR(200)             NOT NULL,
    tag          BIGINT                   NOT NULL,
    part_id      VARCHAR(100)             NOT NULL,
    paragraph_no INT                      NOT NULL,
    verdict      VARCHAR(16)              NOT NULL,
    closeness    REAL                     NOT NULL,
    said_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, tag, part_id, paragraph_no)
);
