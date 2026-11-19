-- What a work of the queue is to be done of, kept till it is done: the items, the fandoms its owner names — a key a
-- row. A stop leaves them to the next start: the work planned again does what was asked, not "everything" because
-- what was asked was lost (owner, 2026-11-19: "the queue is kept in the database: nothing is to be run at a start just
-- because it is a start"). "work_key" is the work's key, as of queued_work.
CREATE TABLE queued_key (
    work_key VARCHAR(2000) NOT NULL,
    item_key VARCHAR(1000) NOT NULL,
    PRIMARY KEY (work_key, item_key)
);

-- Of the encoding of the texts the last run left undone, the items it was given were not kept: every text of its
-- source gone through once ("" asks for every one), the items left after a portion kept by their ids.
INSERT INTO queued_key (work_key, item_key) SELECT work_key, '' FROM queued_work WHERE what = 'textVectors';
