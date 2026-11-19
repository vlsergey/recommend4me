-- A task of the queue has a priority, the works it is of, and where it goes on from (owner, 2026-11-19: "let a task have
-- a priority"; "the tasks have flags of priority and an optional flag of which works they were scheduled for, and
-- optional fields of the sorting"): "priority" — asked, user, background, markedWorks, otherWorks — replaces "asked";
-- "works" — marked or other — tells two tasks of one kind apart, a part of its key when there is one; "cursor_grade"
-- and "cursor_work" — the expected grade and the work it goes on after, none of the grade the very first.
ALTER TABLE queued_task ADD COLUMN priority VARCHAR(20) DEFAULT 'background' NOT NULL;
UPDATE queued_task SET priority = 'asked' WHERE asked;
ALTER TABLE queued_task ALTER COLUMN priority DROP DEFAULT;
ALTER TABLE queued_task DROP COLUMN asked;
ALTER TABLE queued_task ADD COLUMN works VARCHAR(20);
ALTER TABLE queued_task ADD COLUMN cursor_grade DOUBLE PRECISION;
ALTER TABLE queued_task ADD COLUMN cursor_work VARCHAR(1000);
ALTER TABLE task_run ADD COLUMN works VARCHAR(20);
