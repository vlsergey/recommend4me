-- The model of how a tag's closeness to a paragraph tells that the work has it, learnt from the
-- user's answers on the tags offered by the text (tag_text_answer of every source): the chance is
-- σ(slope · closeness + intercept + the tag's own bias). The row of tag nought holds the slope and
-- the intercept; a tag of no answers has no row, its bias nought
CREATE TABLE tag_text_model (
    tag      BIGINT                   NOT NULL PRIMARY KEY,
    bias     DOUBLE PRECISION         NOT NULL,
    slope    DOUBLE PRECISION,
    answers  INT                      NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
