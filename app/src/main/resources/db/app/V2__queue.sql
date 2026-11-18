-- The works of the queue of the computations not done yet — planned, waiting their turn or running — a row a work, kept
-- till it ends: a stop or a crash leaves them for the next start, whose owners plan them again. "plan_no" tells a plan
-- from a newer one of the same work, made while it ran: its end removes its own row alone. The work's key: what, type,
-- source, facet and item, tab-separated.
CREATE TABLE queued_work (
    work_key  VARCHAR(2000) PRIMARY KEY,
    what      VARCHAR(100) NOT NULL,
    type_id   VARCHAR(100),
    source_id VARCHAR(100),
    facet     VARCHAR(300),
    item      VARCHAR(300),
    plan_no   BIGINT NOT NULL,
    due       TIMESTAMP WITH TIME ZONE NOT NULL,
    since     TIMESTAMP WITH TIME ZONE NOT NULL,
    reasons   VARCHAR(4000) NOT NULL,
    asked     BOOLEAN NOT NULL
);

-- The last runs of every kind of work — a work of any item alike, "kind_key" its key without the item: when each began
-- and ended, why, whether it failed or was given up; what a work to come is expected to take, and what the interface
-- shows of the works done.
CREATE TABLE work_run (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind_key  VARCHAR(2000) NOT NULL,
    what      VARCHAR(100) NOT NULL,
    type_id   VARCHAR(100),
    source_id VARCHAR(100),
    facet     VARCHAR(300),
    item      VARCHAR(300),
    began     TIMESTAMP WITH TIME ZONE NOT NULL,
    ended     TIMESTAMP WITH TIME ZONE NOT NULL,
    reasons   VARCHAR(4000) NOT NULL,
    failed    BOOLEAN NOT NULL,
    given_up  BOOLEAN NOT NULL
);
CREATE INDEX work_run_kind ON work_run (kind_key, ended);
