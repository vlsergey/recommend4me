-- The model of a work's age rating by its tags, learnt from the ratings the sites state or bound
-- (a rating, or from one to another) and the user's answers, of every source of every type
-- (AgeRatingModel): the chance a work's rating is at most the step k of the scale is
-- σ(threshold_k − the sum of its tags' weights). A tag of no such work has no row, its weight nought;
-- the four thresholds between the five ratings are the rows of the tags −1 to −4
CREATE TABLE age_rating_model (
    tag       BIGINT                   NOT NULL PRIMARY KEY,
    weight    DOUBLE PRECISION         NOT NULL,
    works     INT                      NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
