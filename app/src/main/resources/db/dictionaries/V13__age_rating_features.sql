-- The model of the age rating by named features: a tag ("tag:<id>"), the site a work is of
-- ("source:<id>" — a site's works may all be stronger or milder whatever their tags: every game of
-- F95zone is for adults), and the thresholds between the ratings ("threshold:<k>")
DROP TABLE age_rating_model;

CREATE TABLE age_rating_weight (
    feature   VARCHAR(200)             NOT NULL PRIMARY KEY,
    weight    DOUBLE PRECISION         NOT NULL,
    works     INT                      NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
