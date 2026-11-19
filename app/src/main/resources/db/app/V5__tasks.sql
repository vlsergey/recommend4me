-- The queue's works are its tasks: "work" is the work of the sites' items, one of several sites (owner, 2026-11-19:
-- "let it be Work"); the queue's tables and their keys renamed so — a primary key of a column renamed made anew, of the
-- column's new name.
ALTER TABLE queued_work RENAME TO queued_task;
ALTER TABLE queued_task DROP PRIMARY KEY;
ALTER TABLE queued_task ALTER COLUMN work_key RENAME TO task_key;
ALTER TABLE queued_task ADD PRIMARY KEY (task_key);
ALTER TABLE work_run RENAME TO task_run;
ALTER INDEX work_run_kind RENAME TO task_run_kind;
ALTER TABLE queued_key DROP PRIMARY KEY;
ALTER TABLE queued_key ALTER COLUMN work_key RENAME TO task_key;
ALTER TABLE queued_key ADD PRIMARY KEY (task_key, item_key);
