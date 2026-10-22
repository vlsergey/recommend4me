-- The requests of the site a source wants the browser to make for it (Source.describing): asked
-- once; fetched_at set when the browser sent back what the site answered
CREATE TABLE wanted_page (
    url        VARCHAR(2000)            PRIMARY KEY,
    form       VARCHAR(4000),
    wanted_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX wanted_page_awaited ON wanted_page (fetched_at, wanted_at);
