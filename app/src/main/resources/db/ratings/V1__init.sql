-- THE USER'S OWN DATA about the items of a source: what no scrape can bring back.

-- The grade of an item at a version, 1..5
CREATE TABLE rating (
    item_id  VARCHAR(200)             NOT NULL,
    version  VARCHAR(500)             NOT NULL,
    grade    INT                      NOT NULL,
    rated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, version),
    CONSTRAINT rating_grade CHECK (grade BETWEEN 1 AND 5)
);

-- A mark on a picture: +1 "I would pick the work for this", -1 "I would drop it for this". Kept with
-- the address of the picture: a picture whose address changed is another one, and the mark is not its
CREATE TABLE picture_mark (
    item_id   VARCHAR(200)             NOT NULL,
    position  INT                      NOT NULL,
    url       VARCHAR(2000)            NOT NULL,
    mark      SMALLINT                 NOT NULL,
    marked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, position),
    CONSTRAINT picture_mark_sign CHECK (ABS(mark) = 1)
);

-- A mark on a review, the same way
CREATE TABLE review_mark (
    item_id   VARCHAR(200)             NOT NULL,
    review_id VARCHAR(100)             NOT NULL,
    mark      SMALLINT                 NOT NULL,
    marked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, review_id),
    CONSTRAINT review_mark_sign CHECK (ABS(mark) = 1)
);

-- What the user said of a match shown for a mark of this source: the match — a picture or a review
-- of an item of any source of the content type — is alike in the sense meant (+1) or in another (-1).
-- The refs are a picture's position or a review's id
CREATE TABLE match_feedback (
    mark_kind    VARCHAR(16)              NOT NULL,
    mark_item    VARCHAR(200)             NOT NULL,
    mark_ref     VARCHAR(100)             NOT NULL,
    match_source VARCHAR(100)             NOT NULL,
    match_item   VARCHAR(200)             NOT NULL,
    match_ref    VARCHAR(100)             NOT NULL,
    verdict      SMALLINT                 NOT NULL,
    given_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (mark_kind, mark_item, mark_ref, match_source, match_item, match_ref),
    CONSTRAINT match_feedback_verdict CHECK (ABS(verdict) = 1)
);

-- The user's own actions as the site's pages show them: liked, on a shelf of the library, read to a
-- chapter, bookmarked
CREATE TABLE site_signal (
    item_id VARCHAR(200)             NOT NULL,
    signal  VARCHAR(100)             NOT NULL,
    content VARCHAR(500)             NOT NULL,
    seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, signal)
);
