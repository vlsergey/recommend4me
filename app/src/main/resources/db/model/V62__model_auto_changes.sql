-- The changes of no word of the user's a model learns from counted beside it — the models of the grades ("grades"),
-- the models of the fields of a source ("fields:<source>") —, with of how many works it learnt: fitted again once they
-- come to a hundredth of those works, the count let go by the fitting that read them (owner, 2026-11-24). A model of no
-- row, or of none learnt told, is fitted at the first change
CREATE TABLE model_auto_changes (
    model    VARCHAR(200) PRIMARY KEY,
    learnt   INT,
    changes  INT          DEFAULT 0 NOT NULL
);
