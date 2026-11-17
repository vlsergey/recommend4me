-- THE AVERAGE WORK AS LAST COUNTED (AverageWorkRepository): by the last sweep of the scores over every work, or by the
-- full pass of a training that found none — what a training reads instead of every work. One row; of another
-- format or another basis of the vectors (the encoders, the directions of the sets) it is none
CREATE TABLE average_work (
    id         INT                      PRIMARY KEY,
    format     INT                      NOT NULL,
    basis      VARCHAR(1000)            NOT NULL,
    counted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    average    BLOB                     NOT NULL
);

-- WHERE THE SWEEP OF THE SCORES OVER EVERY WORK STANDS (ScoreSweepRepository): the model it scores by, the next
-- portion of the works (the source, and the last item id done in it; none of a sweep just begun), how many items it
-- scored and changed so far, and the sums of the average work counted so far. One row while a sweep goes on
CREATE TABLE score_sweep (
    id               INT                      PRIMARY KEY,
    model_trained_at TIMESTAMP WITH TIME ZONE NOT NULL,
    basis            VARCHAR(1000)            NOT NULL,
    source           VARCHAR(100),
    after_id         VARCHAR(200),
    began_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    scored           INT                      NOT NULL,
    changed          INT                      NOT NULL,
    counted          BLOB
);
