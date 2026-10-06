-- The application's settings, and the settings and job states of every source ("source.<id>.…",
-- "job.<id>.…"). "value" is a keyword in H2 2.x, hence "content"
CREATE TABLE setting (
    name    VARCHAR(300) PRIMARY KEY,
    content CLOB
);
