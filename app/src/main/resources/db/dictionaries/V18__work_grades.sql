-- The version an item stands at now: a grade is of a work, given through an item at a version, and a
-- new release of that item leaves the work ungraded with its grade before. Kept here so that which
-- grades are current is read with them, the items lying in the files of their sources
ALTER TABLE site_work ADD COLUMN version VARCHAR(500) DEFAULT '' NOT NULL;

-- The grade of a normalized work, 1..5, and the item and version it was given through: of a work one
-- current, the rest grades of releases before
CREATE TABLE work_rating (
    work     BIGINT                   NOT NULL REFERENCES normalized_work (id) ON DELETE CASCADE,
    source   VARCHAR(100)             NOT NULL,
    item     VARCHAR(200)             NOT NULL,
    version  VARCHAR(500)             NOT NULL,
    grade    INT                      NOT NULL,
    rated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (work, source, item, version),
    CONSTRAINT work_rating_grade CHECK (grade BETWEEN 1 AND 5)
);
CREATE INDEX work_rating_item ON work_rating (source, item);

-- The user's word on whether a normalized work is interesting at all
CREATE TABLE work_interest (
    work        BIGINT                   PRIMARY KEY REFERENCES normalized_work (id) ON DELETE CASCADE,
    interesting BOOLEAN                  NOT NULL,
    marked_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
